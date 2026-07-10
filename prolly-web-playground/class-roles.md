# dolthub-java-port — class roles

A one-stop map of every class in the module and the role it plays. The module is the
Java port of Dolt's prolly tree and the foundation of the repo: a **content-addressed,
probabilistically-balanced B-tree** whose chunk boundaries are decided by a rolling
hash over the content itself. That single property gives structural sharing between
similar trees, O(changed) diffs, subtree-skipping three-way merge, and Merkle-graph
sync — every upstream module (starting with `prolly-storage`, here) consumes this one.

Each role line below is distilled from the class's own Javadoc (the summaries were
read, not recalled); the Javadoc remains the authoritative, deeper description —
this doc is the index, not the contract.

## Packages

- **`com.dolthub.prolly`** — the 40 hand-written classes. Files ported from Dolt keep
  the Dolt copyright header (provenance-verified in `build/dolt-provenance-ledger.md`);
  Earasoft-original additions carry the Earasoft header.
- **`serial`** — two FlatBuffers-**generated** classes (`ProllyTreeNode`, `ItemType`),
  the on-disk node schema. Never edited by hand.

## Chunking — the "prolly" part

| Class | Role |
|---|---|
| `BuzHash` | Cyclic-polynomial rolling hash over a sliding byte window — the boundary signal for content-defined chunking. |
| `BuzHashTable` | The fixed random byte→hash substitution table BuzHash mixes with. |
| `RollingHashSplitter` | Decides node boundaries by content, so structurally similar trees share most of their chunks. Carries a hard `MAX_CHUNK_SIZE` byte cap against pathological input. |

## Tree structure + persistence seam

| Class | Role |
|---|---|
| `Node` | One tree node — a leaf (level 0, data tuples) or an internal node (child hashes + subtree counts) — parsed as a thin view over its content-addressed bytes. |
| `FlatbufferNodeSerializer` | The **production** node serializer: `[NODE_MAGIC][CORE_FORMAT_VERSION]` header + a `ProllyTreeNode` flatbuffer. The only node wire format (the divergent test-only `SimpleNodeSerializer` was deleted 2026-07-01). |
| `NodeStore` | The content-addressed blob-store interface everything sits on: write bytes → get their hash; read by hash. Nothing more. Production persistence (`RocksNodeStore`) lives in `prolly-storage`. |
| `InMemoryNodeStore` | Heap-backed `NodeStore` (hex-hash → bytes in a `ConcurrentHashMap`); the test/embedded impl, contract-tested against the production store via the parity registry. |
| `NodeCache` | Bounded, lock-free cache of **parsed** `Node`s keyed by content hash, bounded by a byte budget (not entry count). Caffeine-backed (ADR-0040); production-wired through `prolly.rdf4j.node-cache-bytes`. |
| `Manifest` | The named-reference store — `(repoId, refName)` → root hash. The **only mutable part** of the architecture: the single place "current tip of branch X" changes. |
| `Commit` | A permanent, content-addressed snapshot in the history — one node of the commit graph, pointing at a data-tree root + parent commits. Its parser is fuzz-hardened (the untrusted-byte boundary). |
| `FormatVersion` | The single on-disk/wire format-version constant for the core serialized types; bumped once, coordinated, across a format change. |

## Key/value encoding

| Class | Role |
|---|---|
| `Tuple` | A vector of fields in one contiguous `MemorySegment` — the unit every key and value is made of. Dolt's layout: values, then `uint16` offsets + count at the tail. |
| `TupleBuilder` | Assembles a `Tuple`: stage fields by index, `build()` copies into one pool-borrowed segment. |
| `TupleDescriptor` | Schema + comparator for tuples: an ordered list of `Type`s and the field-by-field compare — the bridge between raw bytes and logical types. |
| `Type` | One field's schema: its wire `Encoding` + nullability. |
| `Encoding` | The wire-format tag for one tuple field — how its bytes are interpreted and compared. |
| `TypeCodec` | Encodes primitives into **lexicographically comparable** byte forms and back — raw-byte compare orders the same as value compare. |
| `Varints` | Unsigned varint (Go `encoding/binary`-compatible) encode/decode — the wire form of the subtree-count vector in internal nodes. |
| `ByteUtils` | Unsigned lexicographic byte comparison + prefix helpers — the raw ordering primitive under every non-type-aware key comparison. |

## Reading / navigation

| Class | Role |
|---|---|
| `Cursor` | The recursive tree cursor — the positioned descent stack under every read and mutation. |
| `MapIterator` | The ordered-iteration contract: bidirectional stepping, key seek, zero-copy access to the current entry. |
| `TreeIter` | The production `MapIterator`: drives a `Cursor` and adds an optional stop predicate (how prefix scans end without knowing their last key up front). |
| `StaticMap` | Immutable read-only view of a tree at one root — point lookups + ordered iteration; the committed/snapshot form. |
| `MutableMap` | A buffered mutable overlay on a base `StaticMap`: stage puts/deletes, then `flush` to a new immutable tree. |

## The three engines

| Class | Role |
|---|---|
| `TreeMutator` | Builds a new root by merging a **sorted edit stream** into an existing tree, re-chunking only the touched path (`applyMutations`). |
| `DiffEngine` | Key-level differences between two trees — additions, modifications, deletions — skipping identical subtrees by hash. |
| `MergeEngine` | Three-way structural merge against a common ancestor — the data-level engine behind a branch merge. |

## Reachability, memory, hashing

| Class | Role |
|---|---|
| `ReachabilityWalker` | Collects every node hash reachable from a root — the closure walk under garbage collection and sync. |
| `BufferPool` | The allocator seam for tuple/node serialization scratch: borrow a `MemorySegment`, optionally release, scope a transaction's allocations. |
| `HeapBufferPool` | The **production** `BufferPool`: fresh `byte[]`-backed segment per borrow, reclaimed by the garbage collector — nothing to leak. (Its zero-copy sibling `DirectBufferPool` lives in `prolly-storage` behind the promotion gate.) |
| `SpillableSortedBuffer` | Sorted key→value staging that spills to disk to bound heap — the no-out-of-memory write path for larger-than-RAM ingests. |
| `SpillQuotaExceededException` | Fail-closed guard when a transaction's spill would exceed the process-global disk quota (`prolly.spill.max-disk-bytes`). |
| `HashUtils` | The one place content-address hashes are computed and rendered: 20-byte truncated SHA-512 + lowercase hex. |
| `HashAlgorithm` | The hash algorithm behind one enum — name + truncation length in one place, so a future hash change is an enum value, not a scavenger hunt. |
| `BootstrapHashes` | Cross-module bit-compatibility pins — known-good hashes that anchor the format against silent drift. |

## Failure taxonomy

| Class | Role |
|---|---|
| `ProllyException` | Root of the typed operational-failure hierarchy — lets callers branch retry vs alert-and-restore vs shed-load. |
| `ProllyCorruptionException` | Stored bytes fail their content-address re-hash on read (ADR-0064 verify-below-the-cache): the data is *wrong*. |
| `ProllyIoException` | Transient storage-layer input/output failure: the data is not wrong; a retry may succeed. |
| `UnsupportedFormatException` | A blob/store declares a format version this engine does not support — possibly fine bytes, wrong version. |

## How a write composes

`TupleBuilder` + `TypeCodec` encode the key/value → `TreeMutator` descends via
`Cursor`, applies the sorted edits at the leaves, and re-runs `RollingHashSplitter`
over the affected span so boundaries stay content-defined → each rebuilt node is
serialized by `FlatbufferNodeSerializer`, hashed by `HashUtils`, written through
`NodeStore` → the new root hash is the tree's identity, recorded in a `Commit`
whose branch pointer moves in the `Manifest` (the only mutation in the whole story).
Reads run the same path in reverse, `Cursor`-driven, with `NodeCache` absorbing
re-parses.

## Module constraints worth knowing

- **Leaf dependencies only**: FlatBuffers, Caffeine, jspecify. No RocksDB here.
- **NullAway-gated**: `@NonNull` default, `@Nullable` the marked
  evidenced exception — the canonical write-up is this package's `package-info.java`.
- **Hardened net**: jqwik property tests (`**/*Property.java` Surefire include),
  Jazzer fuzzing on the untrusted-byte parsers, mutation testing (`pitest`) gated at
  threshold 90.
- **The port's own format is the contract** — Dolt byte-compatibility is optional and
  deferred (2026-05-29); `CrossLanguageFixtureTest` pins the Layer 0–2 parity that
  holds as characterization, not a contract.

## Where this lives

- `dolthub-java-port/src/main/java/com/dolthub/prolly/` — all classes above.
- `dolthub-java-port/src/main/java/serial/` — the generated flatbuffer schema classes.
- `dolthub-java-port/src/main/java/com/dolthub/prolly/package-info.java` — the
  null-safety discipline + package overview.
- [`docs/foundations/`](../docs/foundations) — the narrative explainers (the prolly tree,
  engines).
- `build/dolt-provenance-ledger.md` — per-file Dolt/Earasoft attribution.
