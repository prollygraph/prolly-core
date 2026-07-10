---
tags:
  - format
  - storage
---
# The on-disk format

*Two nested layers — the Flatbuffers `Node` and the field-packed `Tuple` — and
why every byte is load-bearing.*

> **What you'll learn** — what a serialized prolly-tree node actually looks
> like on disk: the `ProllyTreeNode` Flatbuffers layout, the `Tuple` byte
> format that node keys and values use, the `Encoding` type tags, and why the
> format is byte-precise — the node hash depends on it exactly.
>
> _Reading time: ~10 minutes._

## Why it matters

[The prolly tree](the-prolly-tree.md) says a node's address *is* the hash of
its bytes. That makes the byte layout load-bearing: two implementations only
agree on a tree's root hash if they serialize every node **identically, to the
bit**. Byte-for-byte parity with Dolt is an *optional* non-goal (decided 2026-05-29)
— the port keeps its own format — but the byte layout is still load-bearing
*internally*: the port's own writer and reader must serialize every node
identically, to the bit, or its self-consistent root hashes diverge.

The format has two nested layers. The outer layer is the **node**: a
Flatbuffers record. The inner layer is the **tuple**: how an individual key or
value is packed into bytes. Understand both and you can read a node by hand.

## The idea

### Layer 1 — the node (Flatbuffers)

Every node is a [Flatbuffers](https://flatbuffers.dev/) table called
`ProllyTreeNode`. The schema, `prolly.fbs`, is *Dolt's own schema file* —
copied in, `Copyright 2021 Dolthub, Inc.` — and `flatc` generates the Java
reader/writer classes from it into the `serial` package. The table's fields:

| Field | Meaning |
|---|---|
| `key_items` | All key bytes, concatenated, in sorted order. |
| `key_offsets` | `uint16` offsets splitting `key_items` into individual keys. |
| `value_items` / `value_offsets` | The same, for values (leaf nodes only). |
| `key_type` / `value_type` | An `ItemType` tag — `TupleFormatAlpha` today. |
| `address_array` | Chunk addresses — child subtree hashes (internal nodes). |
| `subtree_counts` | Varint-encoded per-child item counts. |
| `tree_count` | Total items in the whole subtree (`uint64`). |
| `tree_level` | Tree height of this node — **`0` means a leaf**. |

A **leaf** (`tree_level == 0`) carries real `value_items`; an **internal node**
(`tree_level > 0`) carries child hashes in `address_array` instead. That single
`tree_level` byte is how the reader tells them apart.

> **Gotcha — node framing.** The port's own writer prepends a 5-byte
> `[PNOD][version]` header (ADR-0072) before the FlatBuffer. Dolt instead
> wraps the FlatBuffer in a 4-byte *serial message* prefix —
> `[1-byte NomsKind][3-byte big-endian size][FlatBuffer]` — which pushes the
> `"TUPM"` Flatbuffers file identifier from offset 4 to offset 8.
> `Node.fromBytes` accepts either a `PNOD`-versioned node or a
> Dolt-serial-framed `TUPM`, and **fails closed** on anything else — a bare
> (unframed) FlatBuffer is deliberately *not* accepted.

### Layer 2 — the tuple

A key or a value is itself a vector of typed fields, packed by the **`Tuple`**
format into one contiguous `MemorySegment`:

```
┌─────────┬─────────┬─────┬─────────┬──────────┬─────┬──────────┬───────┐
│ Value 0 │ Value 1 │ ... │ Value K │ Offset 1 │ ... │ Offset K │ Count │
└─────────┴─────────┴─────┴─────────┴──────────┴─────┴──────────┴───────┘
  ◄────────── field data ──────────►◄──── uint16 LE metadata ─────────►
```

The field values come first, back to back; then a `uint16` little-endian offset
per field; then a `uint16` count. To read field *i* you look at offsets *i* and
*i+1* and slice the segment between them — **zero-copy**, no allocation.

> **Gotcha** — a field is `NULL` when its start and end offsets are *equal*
> (zero length). There is no separate null bitmap; an empty span *is* the null.

What the bytes of a field *mean* is given by the **`Encoding`** enum — 31 type
tags, from `Int8`/`Uint8` through `Float64`, `String`, `Decimal`, `Datetime`,
up to `IRI` (tag 22, used by upstream layers) and the out-of-line `*Addr` types.
A **`TupleDescriptor`** pairs a tuple with its list of `Encoding`s: it is the
"interpreter" that knows field 0 is an `Int64`, field 1 a `String`, and so on,
and it drives ordered comparison — delegating numeric types to `TypeCodec`.

> **Key idea** — keys are compared as **bytes**, in order, and the data is laid
> out so that byte order *is* sort order. `TupleDescriptor`'s `binaryParity`
> mode does a pure `memcmp`; numeric fields are bit-flipped at build time so
> their two's-complement representation sorts lexicographically. The tree's
> ordering invariant is therefore a property of the *encoding*, not of a
> comparator run at query time.

## The key types

In `dolthub-java-port`:

| Type | Responsibility |
|---|---|
| `serial.ProllyTreeNode` | `flatc`-generated reader/writer for the node table. |
| `Node` | The runtime node view; `Node.fromBytes` parses either framing. |
| `FlatbufferNodeSerializer` | Writes nodes in the Flatbuffers layout — Dolt's `prolly.fbs` schema, with the port's 5-byte `[PNOD][version]` header prepended (no Dolt serial-message prefix). |
| `Tuple` | The field-packed byte format; zero-copy field slicing. |
| `TupleBuilder` | Assembles a `Tuple` field by field. |
| `TupleDescriptor` | The schema + comparator for a tuple's fields. |
| `Encoding` | The 31 field type tags. |
| `TypeCodec` | Encodes/decodes and compares typed field values. |

## Rules & gotchas

- > **Gotcha** — the byte layout is **load-bearing**. Changing field order,
  > offset width, or endianness changes every node hash — rehashing every
  > already-stored tree (and widening the already-optional divergence from
  > Dolt). Evolve it deliberately, not casually.
- > **Gotcha** — tuple metadata is `uint16` little-endian; node Flatbuffers
  > fields follow Flatbuffers' own little-endian layout. Don't mix up the two
  > when reading raw bytes.
- > **Trade-off** — Flatbuffers gives random-access reads with no parse step,
  > at the cost of a slightly larger encoding than a hand-rolled format. The
  > project takes that trade for zero-copy reads and a schema Dolt already
  > defined.
- Full byte-parity with Dolt is an *optional* non-goal (decided 2026-05-29):
  the port keeps its own deterministic format, and its divergence from Dolt
  v2.0.3 is multi-layer + experiment-confirmed. `CrossLanguageFixtureTest` pins
  the layers that *do* match (0–2) against a Go-built fixture as
  characterization — not a contract, and not proof pending toward full parity.
  See [the-go-port](the-go-port.md).

## Format versioning

The port's node is **self-describing and versioned** (ADR-0072). Every node that
`FlatbufferNodeSerializer` writes carries a 5-byte header *before* the flatbuffer:
a 4-byte magic `PNOD` (`Node.NODE_MAGIC`) plus a one-byte `CORE_FORMAT_VERSION`
(currently `1`, defined in `FormatVersion`). `Node.fromBytes` checks the magic and
the version byte **before it reads a single flatbuffer field**, so a wrong, foreign,
or future-incompatible node fails closed with `UnsupportedFormatException` instead of
being silently mis-parsed by flatbuffer's additive field tolerance.

> **Gotcha** — the 5-byte header is *not* stripped from the node's bytes. The
> flatbuffer is parsed at offset `NODE_HEADER_SZ`, but the `Node` keeps the whole
> blob, so `hash(node.bytes())` still equals the node's content-address — the
> invariant the garbage-collector reachability walk relies on. The magics are
> distinct across the port's own records: `PNOD` (node), `PCMT` (commit), `PRMT`
> (root-meta-tree), and the inner flatbuffer's `TUPM` file-identifier.

Commit identity is versioned and parent-aware too (ADR-0071). A commit's id is
`hash(metaTreeHash ‖ parent-ids ‖ author ‖ message)` — it hashes its parents'
*already-computed ids* (a Merkle directed-acyclic-graph node, like git) and
deliberately **excludes the wall-clock timestamp**, so the *same logical commit*
produced independently on two peers gets the *same id* — which is what lets sync
converge by construction. See [B4-a-commit](../anatomy/B4-a-commit.md).

## Why this is optimized

The tuple/node format is also tuned for the read hot path — where, on the Java virtual machine,
allocation is the first thing that bites.

- **Zero-copy key reads (`Node.getKeySegment`).** Returns a `MemorySegment` *view*
  (`asSlice`) of the backing bytes instead of copying a `byte[]` per key. Reading
  keys during a scan was the single top allocator before this; the view removes the
  copy. (`getKey` still copies for API callers — the subclass lets the hot path opt
  out.)
- **Slice-free field offsets (`Tuple.fieldRange`).** Returns a field's span as a
  packed `(start << 32) | end` long, decoded in place by the comparator — instead of
  the older `getFieldSegment` → `MemorySegment.asSlice` that allocated a segment
  wrapper *per field, per comparison*. The `VarHandle` for the little-endian uint16
  offset read is hoisted to a `static final` so it isn't re-resolved per call.
- **Reused builder + varint counts (`FlatbufferNodeSerializer`).** One
  `FlatBufferBuilder` is cleared per chunk rather than allocated fresh; internal
  nodes store *per-child* subtree counts as varints — compact; the reader returns
  their prefix sum (a read-time semantic, not stored), which the integrity checker
  relies on.

> **Measure-first honesty.** The `fieldRange` slice-elimination cut *allocation* but
> moved triangle wall-time within noise — it was kept for the allocation win, not a
> latency claim (an A/B settled it; see
> contributing/finding-bottlenecks). The
> packing reserves 32 bits per offset (ample headroom — the tuple format's `uint16`
> offsets cap a tuple at ~64 KB) — and offsets are cached at parse time, so a caller
> must keep the parent segment alive while reading.

## Takeaways

- A node is a `ProllyTreeNode` **Flatbuffers** table; `tree_level == 0`
  distinguishes a leaf (holds values) from an internal node (holds child
  hashes).
- Dolt frames the FlatBuffer with a 4-byte serial prefix; the port prepends
  its own `[PNOD][version]` header; `Node.fromBytes` reads either (and rejects
  an unframed FlatBuffer).
- A key or value is a **`Tuple`** — fields, then `uint16` LE offsets, then a
  count; a null field is a zero-length span.
- `Encoding` tags a field's type; `TupleDescriptor` interprets and compares.
  Byte order is engineered to equal sort order.
- The whole format is load-bearing because the node hash depends on it exactly
  — it is the port's *own* format (evolved freely pre-1.0), not frozen for Dolt
  parity (an optional non-goal).

> **See it live** — the playground's REAL-mode chunk inspector renders any stored
> node as a field-annotated hex grid: every byte painted by the role the engine's own
> parse assigned it (envelope, keys, values/addresses, subtree-count varints, honest
> flatbuffer scaffolding), regions tiling the array exactly. The annotation is computed
> server-side (`/api/nodes/{hash}/layout`) — the page never re-parses the format.

## Where this lives

- `dolthub-java-port/src/main/fbs/prolly.fbs` — Dolt's Flatbuffers schema
- `dolthub-java-port/src/main/java/com/dolthub/prolly/Node.java`,
  `FlatbufferNodeSerializer.java` — node read/write
- `dolthub-java-port/src/main/java/com/dolthub/prolly/FormatVersion.java` — `CORE_FORMAT_VERSION` (ADR-0072)
- `dolthub-java-port/src/main/java/com/dolthub/prolly/Tuple.java`,
  `TupleBuilder.java`, `TupleDescriptor.java` — the tuple format
- `dolthub-java-port/src/main/java/com/dolthub/prolly/Encoding.java`,
  `TypeCodec.java` — field types
- Builds on: [the-prolly-tree](the-prolly-tree.md),
  the-memory-model
- Continues in: [the-go-port](the-go-port.md) — how parity with Dolt is
  verified.
