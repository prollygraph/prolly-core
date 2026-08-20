# On-disk format specification

**Status:** normative for `0.2.0-BETA` (`CORE_FORMAT_VERSION = 1`). Pre-1.0: this
format evolves freely between versions with no backwards compatibility; readers
fail closed on unknown versions rather than migrate. Every constant below is
cited to the code that defines it in the [verification map](#verification-map);
none is aspirational.

For the narrative companion (why the format looks like this), see
[the on-disk format](../foundations/the-on-disk-format.md). This document states
*what the bytes are*.

## 1. Content addressing

Every stored object — tree node, commit record, root-meta-tree record — is
addressed by a **20-byte truncated digest of its exact serialized bytes**.

- Default algorithm: **SHA-512 truncated to 20 bytes** (`SHA512_20`, Dolt
  lineage). All hashing funnels through one site (`HashUtils`).
- The algorithm is **agile**: selected per-process at class-init
  (`-Dprolly.hash.algorithm`, default `SHA512_20`), and each RocksDB store
  stamps the algorithm's one-byte id into its format marker and **fails closed
  on mismatch** — a store written under one algorithm is never silently read
  under another.

| Algorithm | on-disk id | digest | notes |
|---|---|---|---|
| `SHA512_20` | `1` | SHA-512/20 | **default** |
| `SHA256_20` | `2` | SHA-256/20 | proposed default at the next format break ([ADR-0075](../adr/0075-adopt-sha256-content-addresses.md), status: Proposed) |
| `BLAKE2B_160` | `3` | BLAKE2b-160 | requires BouncyCastle |
| `BLAKE3_20` | `4` | BLAKE3/20 | requires BouncyCastle; test-scope |

An address is always the hash of the **entire stored blob including its
header** — headers are not stripped before hashing (see §2), which is the
invariant the garbage-collector's reachability walk relies on.

## 2. Node envelope

A serialized tree node is:

```
[ 'P' 'N' 'O' 'D' ]  [ version: u8 = 1 ]  [ FlatBuffers payload, file_identifier "TUPM" ]
└──── NODE_MAGIC ───┘└─ CORE_FORMAT_VERSION ┘
```

- Header size is 5 bytes (`NODE_HEADER_SZ`); the FlatBuffer is parsed at offset
  5, but the node's identity hash covers **all** bytes including the header.
- The reader (`Node.fromBytes`) verifies magic and version **before reading any
  FlatBuffers field**, so a wrong, foreign, or future-version blob fails closed
  with `UnsupportedFormatException` instead of being additive-misparsed.
- A second framing is accepted **for cross-language import only**: a Dolt
  serial message — a 1-byte NomsKind, a 3-byte big-endian size, then the
  FlatBuffer (its `TUPM` identifier then sits at offset 8 of the blob). A
  **bare** (unframed) FlatBuffer is deliberately rejected, including the port's
  own pre-versioning format: pre-1.0, an old store is re-ingested, never
  silently parsed.
- Magic namespace: `PNOD` (node), `PCMT` (commit record, §6), `PRMT`
  (root-meta-tree record), plus the inner FlatBuffers `TUPM` file identifier.

## 3. The node table (`ProllyTreeNode`)

The payload is a FlatBuffers table, schema
[`prolly.fbs`](../../dolthub-java-port/src/main/fbs/prolly.fbs) (Dolt's schema,
`Copyright 2021 Dolthub, Inc.`, namespace `serial`, root type `ProllyTreeNode`,
file identifier `TUPM`):

| field | type | meaning |
|---|---|---|
| `key_items` | `[ubyte]` required | all key bytes, concatenated, sorted order |
| `key_offsets` | `[uint16]` required | splits `key_items`; first offset 0, last = len |
| `key_type` | `ItemType` | `TupleFormatAlpha = 1` today |
| `value_items` / `value_offsets` / `value_type` | as above | leaf payload (leaves only) |
| `value_address_offsets` | `[uint16]` | offsets of out-of-line addresses inside values |
| `address_array` | `[ubyte]` | child subtree hashes (internal) / value addresses (AddressMap leaves) |
| `subtree_counts` | `[ubyte]` | per-child item counts, varint-encoded |
| `tree_count` | `uint64` | total items in the subtree |
| `tree_level` | `uint8` | height; **0 = leaf** |

`tree_level == 0` is the leaf/internal discriminator: a leaf carries
`value_items`, an internal node carries child hashes in `address_array`.

## 4. The tuple

Keys and values are **`Tuple`s** — fields packed into one contiguous segment:

```
┌─────────┬─────────┬─────┬─────────┬──────────┬─────┬──────────┬───────┐
│ value 0 │ value 1 │ ... │ value K │ offset 1 │ ... │ offset K │ count │
└─────────┴─────────┴─────┴─────────┴──────────┴─────┴──────────┴───────┘
  ◄────────── field data ──────────►◄──── uint16 little-endian ────────►
```

- Field *i* is the byte span between offsets *i* and *i+1* — zero-copy sliced.
- **NULL is a zero-length span** (equal start/end offsets). There is no null
  bitmap.
- The `uint16` offsets cap a tuple at ~64 KiB. (This is what makes literals
  larger than 64 KiB a documented architectural gap in the RDF ring.)
- **Byte order is sort order**: numeric fields are bit-flipped at encode time
  so two's-complement values sort lexicographically; ordered comparison in
  `binaryParity` mode is a pure `memcmp`. The tree's ordering invariant is a
  property of the encoding, not a query-time comparator.

### Field encodings

31 type tags (`Encoding`, tags 0–30):

```
Null=0  Int8=1  Uint8=2  Int16=3  Uint16=4  Int32=5  Uint32=6  Int64=7
Uint64=8  Float32=9  Float64=10  String=11  Bytes=12  JSON=13  Decimal=14
Year=15  Date=16  Time=17  Datetime=18  Enum=19  Set=20  Geometry=21
IRI=22  Hash128=23  Bit64=24  BytesAddr=25  CommitAddr=26  StringAddr=27
JSONAddr=28  GeomAddr=29  ExtendedAddr=30
```

A `TupleDescriptor` pairs a tuple with its ordered `Encoding` list and drives
comparison. `*Addr` types hold 20-byte out-of-line content addresses.

## 5. Chunk boundaries

Nodes are content-defined chunks. The boundary function is the port's **own
deterministic rule** (not Dolt's — cross-language chunk parity is a non-goal):

- Rolling hash: **BuzHash over a 67-byte window** (`WINDOW_SIZE = 67`).
- Bounds: **minimum 512 B** (`1 << 9`), **hard cap 16 KiB** (`1 << 14`) — at
  the cap a boundary is forced unconditionally.
- Hashing starts at offset `MIN_CHUNK_SIZE − WINDOW_SIZE = 445`: bytes before
  that would have rolled out of the window by the first boundary check.
- Every byte is salted before hashing: `input = byte XOR salt(level)`, where
  the per-level salt derives from SHA-512 of the level byte. This prevents
  boundaries aligning vertically across tree levels, which would defeat
  structural sharing at higher levels.
- The boundary test at offset *o* (in `[512, 16384)`):

  ```
  mask(o)  =  (1 << (15 − (o >> 10))) − 1        // the "staircase": loosens every KiB
  boundary ⇔ (buzhash32 & mask(o)) == mask(o)
  ```

  The staircase makes a boundary progressively more likely as the chunk grows,
  bounding the size distribution's tail deterministically. Measured (study,
  4,096 B target): p99 = 7,360 B, max = 8,640 B — see
  [boundary-function-performance](../foundations/boundary-function-performance.md).
  If you are checking for the chunk-imbalance pathology of naive
  content-defined chunking: this is the mitigation, and it is measured.

Determinism: the same bytes always split the same way; two independently built
trees over the same content share chunks. This is load-bearing for structural
sharing and sync dedup.

## 6. The commit record (`PCMT`)

A commit **record** (storage layer) is a versioned binary blob:
`[ 'P' 'C' 'M' 'T' ][ version: u8 ]` then the fields — a fixed 20-byte root
hash (all-zero = the empty-tree sentinel, which cannot collide with a real
SHA-512/20 digest), parent hashes, author, message, and a wall-clock timestamp.
The reader verifies magic + version before reading any field.

## 7. Commit *identity* (cross-ring, informative)

The RDF ring computes commit **ids** content-addressed over a domain-separated
preimage ([ADR-0071](../adr/0071-commit-identity-includes-parents.md); the
implementation lives in the RDF ring's `CommitObject`):

```
preimage = len32("prolly-commit-id-v1") ‖ "prolly-commit-id-v1"
         ‖ len32(metaTreeHash) ‖ metaTreeHash
         ‖ u32(parentCount) ‖ ( len32(parentId) ‖ parentId )…
         ‖ len32(utf8(author)) ‖ utf8(author)
         ‖ len32(utf8(message)) ‖ utf8(message)
id = HashUtils.hash(preimage)          // 20 bytes; all u32 big-endian
```

The **timestamp is deliberately excluded** from identity (it is recorded
alongside, not hashed): the same logical commit produced independently on two
peers gets the same id, which is what lets sync converge by construction.
Parent order is significant. Length prefixes make the encoding injective.

## 8. Out of scope

- **Dolt byte-parity.** The lowest layers (hash function, tuple value
  encodings) are pinned byte-identical to upstream Go Dolt by golden vectors
  (`cross-lang/fixtures`, characterization tests); node framing, tuple offset
  layout, and chunk boundaries deliberately diverge. This port **cannot read
  or write Dolt databases**.
- The pack/sync wire format — see the
  [sync protocol specification](sync-protocol.md).

## Verification map

| claim | source |
|---|---|
| `NODE_MAGIC = 'PNOD'`, 5-byte header, fail-closed order | `dolthub-java-port/src/main/java/com/dolthub/prolly/Node.java` (`NODE_MAGIC`, `NODE_HEADER_SZ`, `fromBytes`) |
| `CORE_FORMAT_VERSION = 1` | `.../prolly/FormatVersion.java` |
| Dolt serial-frame acceptance; bare-TUPM rejection | `Node.fromBytes` branch comments and logic |
| node table fields, `TUPM`, `TupleFormatAlpha = 1` | `dolthub-java-port/src/main/fbs/prolly.fbs` |
| tuple layout, null-as-empty-span, uint16 LE, ~64 KiB cap | `.../prolly/Tuple.java`, `TupleBuilder.java`, `TupleDescriptor.java` |
| 31 encodings, tags 0–30 | `.../prolly/Encoding.java` |
| SHA-512/20 default; algorithm ids 1–4; store marker fail-closed | `.../prolly/HashUtils.java`, `HashAlgorithm.java`; ADR-0075 (Proposed) |
| window 67, min 512, cap 16 KiB, `HASH_FROM = 445`, salt, staircase | `.../prolly/RollingHashSplitter.java` (constants; `rollingHashPattern`; `hashByte`) |
| tail measurements | `docs/foundations/boundary-function-performance.md` |
| `PCMT` magic, empty-root sentinel, timestamp in record | `.../prolly/Commit.java` |
| commit-id preimage and tag `prolly-commit-id-v1` | prolly-rdf: `prolly-rdf4j/.../sail/CommitObject.java` (`TAG`, `serialize`, `writeInt`) |
| golden-vector scope (layers 0–2 pinned; above diverge) | `docs/foundations/the-on-disk-format.md`; `cross-lang/` |
