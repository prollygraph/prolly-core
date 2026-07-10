# ADR-0039: Read path node cache and zero copy

## Status

Accepted, 2026-06-01. Guides an upstream read-path implementation plan.

## Context

Native-inclusive CPU profiling (2026-06-01) localized why the upstream RDF4J Sail's reads trail the
mmap engines by a wide, previously-mis-attributed margin:

- **Measured gap.** Point lookup 81 µs (NativeStore 5.2, LMDB 5.9 — ~15×); the 2-pattern
  acyclic join `?s NEXT ?n . ?n P ?o` 1097 ms (NativeStore 72, LMDB 20 — 15–55×). The join
  runs on **RDF4J's bind-join — the same algorithm NativeStore and LMDB use** — so the gap
  is *not* the join algorithm: `prolly/native` on the join (15.2×) ≈ `prolly/native` on the
  point lookup (15.6×). The slowdown **is** the per-probe cost, multiplied through ~N probes.
- **Where the per-probe time goes.** JFR saw only 5 Java-on-CPU samples for the join
  (vs 282–1261 for the scans) — the time was *outside* Java. An async-profiler `ctimer`
  capture (perf-free; `perf_event_paranoid=4` ruled out the `cpu` event) made it visible:
  the join's CPU is **rocksdb-native 71%, dictionary-decode 66%, tree-walk 88%, alloc 38%**,
  plus a G1 GC band (`trim_queue` + `oop_oop_iterate`, ~12–15% of all JVM samples) fed by
  per-probe allocation. Each probe through the Sail pays four taxes the mmap engines don't:
  1. a content-addressed tree descent **from the root** (`Cursor.searchInNode`, no warm cursor);
  2. a RocksDB `get()` per tree node — [`RocksNodeStore.read`](../../prolly-storage/src/main/java/com/earasoft/prolly/storage/RocksNodeStore.java)
     (~line 139) uses `db.get(cf,hash)`, which **allocates a fresh `byte[]` and memcpys** (the
     `jni_NewByteArray` + `memcpy` frames);
  3. a **dictionary decode** of every result term — the upstream dictionary decode
     is `buffer.get(...)` on a `StaticMap`, i.e. a *second* tree walk over the same node store;
  4. **Panama segment allocation per scan** (`GlobalSession.<init>`, `memset`, `dup`).
- **How LMDB avoids it.** RDF4J's LMDB sail (`org.eclipse.rdf4j.sail.lmdb.*` — `Pool`, `Varint`,
  `TxnManager`) reads values **in place from the mmap as direct `ByteBuffer`s** — no `byte[]`
  copy, no per-get heap allocation — under a long-lived read txn with object pooling. RocksDB's
  *standard* `get()` is copy-out by construction, but its JNI **also** exposes a direct-ByteBuffer
  `get(ColumnFamilyHandle, ReadOptions, ByteBuffer key, ByteBuffer value)` (confirmed via `javap`).
- **The algorithmic alternative is already refuted.** Routing acyclic joins through the
  `LeapfrogTriejoin` is rejected by [`triejoin-evaluation-wiring.md`](../../an upstream plan)
  D-2: the triejoin *loses* ~1.6–1.85× on acyclic shapes. So the lever is **per-probe cost**, not the join.

Why now: the per-probe tax is what blocks the stack as a read/join engine, and the two
cheapest levers are mostly wiring of machinery that **already exists** — a tested `NodeCache`
LRU ([`NodeCache.java`](../../dolthub-java-port/src/main/java/com/dolthub/prolly/NodeCache.java))
that is simply **unwired in the Sail path**, and a direct-ByteBuffer RocksDB API that is already on the classpath.

A decisive enabler: **prolly tree nodes are content-addressed → immutable → trivially correct to
cache**, and a `TermId`→bytes mapping is **append-only / never remapped** → its decode cache needs
no invalidation. The expensive correctness question that usually dooms read caches does not arise here.

## Options

The decision is *how to close the per-probe tax*, not *whether*.

| Option | per-probe effect | correctness risk | effort | reach |
|---|---|---|---|---|
| **A** — Algorithmic re-route (leapfrog-triejoin for acyclic) | unchanged | — | high | **refuted** — the *leapfrog* loses 1.6–1.85× on acyclic (triejoin-wiring D-2); a binary *sort-merge* is distinct → D-7 |
| **B** — Caching (wire `NodeCache` + add a dict-decode LRU) | skips repeat node-get + tree-walk + decode on hot data | trivially correct (content-addressed / append-only) | low (cache exists) | the Sail (+ its dict tree, shared node store) |
| **C** — Zero-copy reads (direct ByteBuffer get; per-scan arena reuse) | kills per-get `byte[]` alloc + memcpy + the GC band | low (mechanical) | medium | the Sail's node store **and** its flat sibling |
| **D** — B then C, each behind a measurement gate | both | low | medium | both engines |

## Decision

**Option D — caching first (cheapest and content-addressed-safe), then zero-copy, with a
re-profile + re-bench gate between every lever.**

- **D-1 — Wire the existing `NodeCache` into the Sail's `RocksNodeStore`** (production
  factories + the benches), sized by an operator property. Content-addressed nodes are
  immutable, so the cache is trivially correct; it turns a tree descent into a pointer-chase
  (LMDB's page-cache behavior). Because the dictionary is a `StaticMap` over the *same* node
  store, this also speeds decode. Deciding tradeoff: highest leverage (tree-walk is 88% of the
  join) for the least code and zero correctness risk.
- **D-2 — Add a bounded `TermId`→`Value` decode LRU** on the resolver/dictionary read path.
  Decode is 66% of join samples and re-decodes the same superclass/label terms; the mapping is
  append-only so no invalidation is needed beyond eviction. Deciding tradeoff: even *with* the
  node cache, a decode cache skips the tree-walk **and** the segment decode for hot terms.
- **D-3 — Direct-ByteBuffer RocksDB reads** (`RocksNodeStore.read` and FlatSail's CF gets) with
  a reusable off-heap buffer (grow on the `int` short-read return). Deciding tradeoff: removes
  the `byte[]` allocation + memcpy per get — i.e. the GC band — mirroring LMDB's zero-copy; the
  one lever that also helps **FlatSail**.
- **D-4 — Per-scan `Arena`/segment reuse** on the upstream Sail connection (a per-tx scratch arena
  instead of `GlobalSession.<init>` per scan). Deciding tradeoff: removes the residual
  allocation the caches don't (the scan's own segments), at the cost of careful lifecycle.
- **D-5 — Every lever is gated by a native-inclusive re-flame + a JMH re-bench; a lever that
  doesn't move the measured number is reverted, not shipped.** The triejoin plan's Phase-4
  micro-opts hit a floor that only profiling revealed — we do not repeat that. Deciding tradeoff:
  pay the measurement cost up front rather than ship plausible-but-inert complexity.
- **D-6 — Read-path only; no format, bind-join routing, or triejoin-routing change.** Correctness
  is held by the existing W3C SPARQL suite + the triejoin agreement property + a read-your-writes
  test staying green. Deciding tradeoff: keep the blast radius to latency, not semantics.
- **D-7 — FlatSail also gets the RocksDB-native levers, and a binary sort-merge for acyclic joins is
  a *gated* investigation, not a commitment.** Beyond the shared direct-ByteBuffer read (D-3),
  FlatSail gets `MultiGet`-batched probes (N JNI crossings → 1), `RocksIterator` prefix scans (random
  probes → one ordered walk), and storage tuning (flush + bloom + block-cache, since the baseline
  flame shows reads hitting the *memtable skiplist*). Separately, a binary **sort-merge** over the two
  sorted index scans (distinct from the refuted leapfrog, Option A) is worth testing because prolly
  *wins sequential scans ~32× and loses random point lookups* — but it ships **only if** it beats the
  per-probe-optimized (D-1..D-4) bind-join with no W3C/agreement regression. Deciding tradeoff: the
  per-probe levers may already close the gap, making the merge-join inert — so it is measured *after*
  them, not assumed.

## Consequences

- **Positive.** Closes the point-lookup and bind-join gap toward NativeStore/LMDB; benefits
  *every* read shape (point, scan, join) and the dictionary; FlatSail gets the zero-copy (and a
  follow-on MultiGet) win too. The two Phase-1 levers are low-risk and reuse tested machinery.
- **Cost / negative.** Bounded memory for the caches (operator-sized LRUs). Direct-ByteBuffer
  reads + arena reuse add buffer-lifecycle complexity — a reused off-heap buffer must not escape
  its scan, and the grow-on-short-read path must be handled. The dict-decode cache is correct only
  because `TermId`→bytes is append-only; if that ever changes, the cache must be re-examined.
- **Punted.** RocksDB MultiGet-batched bind-join (a Phase-3 follow-on); the index-aligned
  streaming `solve()` engine refactor (the triejoin plan's Phase-4 deferral, separate); cost-based
  join routing; new index permutations.

## Follow-up / future work

- FlatSail `MultiGet` / `RocksIterator` / storage tuning land **in this plan** (D-7, Phases 3–4);
  what stays future is `MultiGet` across a *prolly tree descent* (the node reads are
  pointer-dependent, so batching needs a speculative/breadth-first walk — a harder change).
- The index-aligned `solve()` (`byte[][]` rows aligned to var-order) that removes string-keyed
  map churn — deferred from [`triejoin-evaluation-wiring.md`](../../an upstream plan) Phase 4.

## Open questions

- **Q1 — default sizes** for the node cache and decode cache. Resolved empirically in the plan
  (Phase 1 measures the hit-rate/latency curve); defaults ship conservative, operator-tunable.
