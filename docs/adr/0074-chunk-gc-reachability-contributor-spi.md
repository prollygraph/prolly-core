# ADR-0074: Chunk garbage collection — a reachability-contributor SPI closes the aux-root gap

## Status

Accepted, 2026-07-16. **AMENDED 2026-09-04 by [ADR-prolly-core-0076](0076-chunk-sets-are-keyed-by-bytes-not-hex.md)** (id `ADR-20260904-27f3`): the SPI method below is now `ChunkSet reachable(NodeStore)`, not `Set<String> reachableHexes(NodeStore)`. The decision this record makes — that co-tenant substrates claim their own closures and the collector unions them before sweeping — is unchanged; only the type crossing the boundary is. Read the signature in this document as historical. Guides the upstream chunk-GC plan. Canonical copy lives in the
engine repo (the deciding layer); mirrored into the RDF ring's series because the
decision creates an obligation there (the RDF face must ship its contributor before any
garbage collection runs on a store it shares).

## Context

**The engine already has a correct stop-the-world collector with one documented
capability constraint.** `GarbageCollector` (prolly-storage) runs mark-and-sweep under
`Database.gcLock()`'s write lock — writers advance the manifest under the read lock, so
no commit can land between mark and sweep (the flush-window race is fixed and pinned;
`docs/write-ups/gc-concurrent-write-flush-window.md`). Its mark phase walks branch heads
→ the commit graph → each commit's data tree; its sweep deletes every unmarked 20-byte
key (non-20-byte keys — manifests, meta rows — are structurally unsweepable).

**The constraint: mark only sees what `Commit` carries.** A substrate that keeps live
roots *outside* the engine commit graph loses them. The concrete case is the RDF face,
which does not use the engine `Database` at all: its refs live in its own `RefsStore`,
its commit records in `commits.log`, and each commit's real state fans out from a
`RootMetaTree` chunk (four SPOC permutation index roots + provenance / event-sink /
prefix / term-stats / namespace roots) that the engine `Commit` never references. On a
shared `RocksNodeStore`, the engine collector would sweep every one of those live index
trees. Today this is *latent* — nothing wires `collect()` into any server — and the
class javadoc carries the warning, pinned by `GcRootReachabilityTest`.

**Why act:** copy-on-write with no collection is the engine's unbounded-disk wall, and
the public ring now hosts multi-process replication (`prolly-sync-grpc`) where staged
packs add further unanchored chunks. A collector the ecosystem can actually run needs
the gap closed by design, not by warning.

## Options

| | A — `collect(extraRoots)` | B — reachability-contributor SPI | C — commits carry aux roots in-format | D — status quo (never GC shared stores) |
|---|---|---|---|---|
| shape | caller passes data-tree roots per call | substrate registers `Set<hex> reachable(store)` suppliers; GC unions | engine `Commit` format grows an aux-roots list | warning stands; no sweep on shared stores |
| who owns the closure logic | the caller re-derives it per call | the substrate, which already has it (the RDF face's `ChunkReachability` is exactly this function) | the engine, for everyone | nobody |
| expressiveness | data-tree roots only — cannot express "walk a `RootMetaTree` and everything under it" | full: a contributor returns any closure it can compute | full but only for engine-`Database` users; the RDF face keeps its own refs/log and never mints engine commits | — |
| coupling | none, but every call site must remember every root kind | one core interface; implementations live with their substrate | format change rippling through every writer/reader, pre-1.0 churn for one consumer's benefit | none |
| failure mode | a forgotten root kind = silent data loss at every call site | an under-reporting contributor = data loss, in ONE audited place per substrate | substrates outside engine commits (the RDF face today) still uncovered | unbounded disk |

## Decision

**Option B — a `GcReachabilityContributor` SPI in the engine CORE** (`com.earasoft.prolly.gc`,
dolthub-java-port — it speaks only core types, so a substrate on ANY `NodeStore` can implement it
without a RocksDB-substrate dependency; the collector in prolly-storage consumes it. A first
draft placed it in prolly-storage justified by "the Sail's main tree is store-agnostic" — that
premise was WRONG (the Sail already compiles against prolly-storage transitively; its evaluation
strategy uses the pool package), corrected here in place. The core home still stands, on the
honest ground: the interface's types are core's, and future substrates should not need the
RocksDB module to declare a claim.)**

- `GcReachabilityContributor { Set<String> reachableHexes(NodeStore store); }` — a
  substrate returns the hex content-addresses of every chunk it holds live *outside*
  the engine commit graph. Coarse on purpose: the substrate owns its whole closure
  (the RDF face's implementation is its existing `ChunkReachability` walk over its own
  refs), and the engine unions rather than interprets.
- `GarbageCollector` takes the contributor list at construction; mark = engine walk ∪
  every contributor's set; sweep unchanged. The no-contributor constructor stays for
  engine-only stores.
- This is the sync SPI's sibling (`SubstrateSyncContributor`): the engine owns the
  mechanism, substrates contribute their semantics, dependencies point down only.

## Consequences

- **A contributor is safety-critical code.** Under-reporting is deletion of live data —
  the same trust class as the sweep itself. Each contributor must be pinned by a test
  that garbage-collects a real store of its substrate and proves the substrate still
  reads everything (the positive twin of `GcRootReachabilityTest`, which flips from
  documenting the gap to verifying the fix).
- **The RDF face may not run garbage collection until its contributor ships** (the
  obligation this ADR creates in the RDF ring). The engine-side warning narrows from
  "unsafe on stores with out-of-band roots" to "unsafe unless every co-tenant substrate
  registered its contributor" — a checkable wiring rule rather than an open hazard.
- **Interaction with staged sync packs, accepted and documented:** a pack's chunks are
  staged before its ref moves (torn-pack healing depends on staged chunks being
  harmless). A collection running in that window sweeps the staged chunks; the
  in-flight apply then fails its head-closure verification and the client retries —
  packs are content-addressed and idempotent, so the retry heals. No corruption, one
  wasted transfer; a time-based grace window is deliberately NOT added (RocksDB gives
  no cheap per-key write time, and the retry semantics are already safe).
- **Memory bound stays as-is:** the mark set holds every live chunk's hex in memory
  (~60 bytes/chunk). Acceptable for the stores this targets today; an incremental or
  bitmap-based mark is future work, orthogonal to this SPI.
- **Collection on a Sail-shared store is an OFFLINE (quiesced) operation in v1.** The
  gcLock write/read contract coordinates the collector with *engine-`Database`*
  writers only; the RDF face's writers never touch that lock, so a collection
  concurrent with Sail commits could sweep freshly-written, not-yet-referenced Sail
  chunks (the same flush-window shape the engine already fixed for its own writers).
  Run the collector only with the Sail quiesced; giving the Sail a read-side stake in
  the gcLock is a product wiring decision, out of scope here.
- Production *wiring* (scheduling, endpoints, multi-tenant fan-out) stays with the
  products — the ring ships the correct primitive only.
