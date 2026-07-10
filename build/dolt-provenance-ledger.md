# Dolt-credit provenance ledger (license-and-cve-gates Step 2)

Auditable record of the per-file provenance judgment that decides which source files carry the
`Copyright 2021 Dolthub, Inc.` upstream credit. Drives the `license-maven-plugin` `licenseSet`
includes (D-2). Re-runnable: the method is content comparison against the Dolt source checked out
at `/home/eriver6/git/dolt` (**v2.0.3**, commit `564f050`).

## Method + principle

Each candidate's **code** was compared to Dolt's Go source (`go/store/**`, `go/libraries/**`).

> **A file is a Dolt port iff its *code translates* specific Dolt Go code — not merely if it
> implements, extends, abstracts, tests, or shares a name with a ported interface/concept.**

Name matching alone is unreliable in **both** directions — verified the hard way this session:
`ZOrder` (no `z_order.go`, but `z_encoding.go` exists → port), `BufferPool` (matches
`buffer_pool.go` by name, but Dolt's is a trivial `[]byte` pool while Java's is a Panama
`MemorySegment` abstraction → Earasoft), `Table` (exact-matches `table.go`, but is upstream-engine
indexing → Earasoft). Every call below is content-grounded.

## DOLT (carry `Copyright 2021 Dolthub, Inc.`) — 28 files

Prolly-tree core + the diff/merge/varint algorithms + the versioning/GC model.

| File | Dolt counterpart | Note |
|---|---|---|
| Commit, Node, NodeCache, NodeStore, ItemAccess, Manifest | `commit_closure.go`, `node.go`, `node_cache.go`, `node_store.go`, `item_access.go`, `manifest.go` | core tree mechanics |
| MutableMap, StaticMap, MapIterator, Cursor, TreeIter, TreeMutator | `mutable_map.go`, `map.go`, `node_cursor.go`, `mutator.go` | map/cursor/mutation |
| Tuple, TupleBuilder, TupleDescriptor, Type, TypeCodec, Encoding | `tuple*.go`, `codec.go` (`go/store/val`) | tuple/value codec |
| RollingHashSplitter, FlatbufferNodeSerializer | `node_splitter.go`, `message/serialize.go` | chunking + node framing |
| **DiffEngine, MergeEngine, Varints** | `tree/diff.go` (`Differ`), `tree/merge.go` (`ThreeWayMerge`), `message/varint.go` | **ADDED** — were uncredited, code translates Dolt |
| Database† | `doltdb` versioning model | commit/branch/merge/cherryPick/revert over a content-addressed store |
| GarbageCollector†, ReachabilityWalker† | `garbage_collection.go`, ref-walk | mark-sweep reachability GC (Noms/Dolt model) |
| ZOrder†, HashUtils† | `val/z_encoding.go`, SHA-512/20 scheme | Z-order/Morton; Dolt's content-address hash choice |

† **Design-adapted credit** (owner decision 2026-06-25) — derived from Dolt's *design/model* but the
code is Java-adapted (upstream-engine roots, Java idiom, standard algorithm), not a line-by-line translation. These
five carry a distinct **4th header template** (`build/license-header-dolt-adapted.txt`): Earasoft +
Dolthub + the line *"Derived from Dolt's design, adapted for Java by Earasoft."* — a precise credit that
distinguishes them from the plain code-translated ports (the 23 above). So the Dolt-credited 28 split
**23 plain port + 5 design-adapted**.

## KCH42 (external algorithm, Earasoft Java reimplementation) — 2 files

`BuzHash`, `BuzHashTable` — the buzhash rolling-hash Dolt *uses* is the external
`github.com/kch42/buzhash` (MIT), **not** Dolt's own code (`BuzHashTable`'s own comment:
"extracted directly from the Go reference implementation"). Per owner decision (2026-06-25):
credit **kch42** as the algorithm origin, marked an Earasoft Java reimplementation — **not** Dolt.

## EARASOFT (credit removed — were mis-credited to Dolt) — 12 files

Earasoft-original code that implements/extends a ported concept or shares a name, but whose code is
not a Dolt translation:

| File | Why Earasoft |
|---|---|
| RocksNodeStore, RocksManifest | RocksDB backends — Dolt uses `nbs`, has no RocksDB store |
| BufferPool | Panama `MemorySegment` allocation abstraction; Dolt's `buffer_pool.go` is a trivial `[]byte` pool |
| SimpleNodeSerializer | "lightweight *alternative*… TLV instead of Flatbuffers" (its Javadoc) |
| LeapfrogJoin | worst-case-optimal join — Earasoft query-engine work, absent from Dolt |
| Table, IndexSchema | upstream secondary-index orchestration (coincidental match to Dolt's SQL `table.go`/`index.go`) |
| ParallelReachabilityWalker | Earasoft parallel variant of the GC walk |
| IntegrityVerifyingNodeStore, ErrorInjectingNodeStore, TreeIntegrityChecker | Earasoft robustness/test wrappers |
| BinaryParityTest, SimpleBench | Earasoft test/benchmark |

(`BufferPool` was uncredited — listed here as "not added"; the other 11 were credited and lose it.)
