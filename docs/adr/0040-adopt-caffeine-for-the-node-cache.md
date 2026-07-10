# ADR-0040 — Adopt Caffeine for the node cache

**Status:** Accepted, 2026-06-02. Realizes the read-path node-cache work
(an upstream plan
Step 2, advanced-LRU follow-on). Evolves the cache introduced under
[ADR-0039](0039-read-path-node-cache-and-zero-copy.md): the byte-budget contract stands; the
synchronized `LinkedHashMap` LRU implementation is replaced.

## Context

The read-path node cache (ADR-0039) shipped as a `synchronized` `LinkedHashMap` LRU keyed by content
hash. A three-experiment series (upstream build-logs) measured how it
behaves in the regimes that actually occur:

1. **Concurrency (microbench, monitor isolated + lock-free control).** The `synchronized` relink-on-read
   *negatively scales* — throughput collapses from 20.0 → 12.9 → 11.9 ops/µs at 1/4/8 threads — while a
   lock-free `ConcurrentHashMap` control scales 15.5 → 40.4 → 41.6. Lock-free is ~3× under contention.
2. **Hit rate (trace-driven, realistic workload).** On a Zipfian point-lookup + scan mix, a Caffeine
   Window-TinyLFU beats LRU by up to +23.6 percentage points at sub-working-set budgets; LRU is
   evicted by scan pollution, W-TinyLFU's frequency admission resists it. (On pure repeated queries the
   two tie — the win is scan-resistance, not skew alone.)
3. **End-to-end (real Sail).** Under scan pollution the hit-rate gap translates: real-Sail hit rate
   62.0% (LRU) → 86.8% (Caffeine), ~2.6× lower latency, and far lower tail variance (±10 ms vs ±235 ms).

Crucially, the production cache is **one instance shared across every tenant repo and every connection
thread** — so the high-contention, scan-mixed regime is the *default* deployment, not an edge case. The
synchronized LRU is the wrong fit for it.

## Options

| Option | read concurrency | scan resistance | byte-weighted bound | dependency | single-thread | code to own |
|---|---|---|---|---|---|---|
| **A — `synchronized` `LinkedHashMap` LRU** (status quo) | serializes; *negative* scaling | none (scan evicts hot set) | yes | none | fastest | low |
| **B — Caffeine W-TinyLFU** | lock-free (batched ring buffers) | yes (frequency admission) | yes (`maximumWeight`+`weigher`) | +1 (Apache-2.0, no transitive) | ~25% slower on pure gets | none (library) |
| **C — hand-rolled sharded CLOCK over open-addressed `long`-key** | lock-free | partial (CLOCK ≈ recency) | yes | none | fast | high (concurrency-correctness, verify-on-hit) |

## Decision

**Adopt Option B — Caffeine.** The existing `NodeCache` type (`com.dolthub.prolly.NodeCache`) becomes a
thin Caffeine wrapper: `maximumWeight(byteBudget)` + a `weigher` returning each node's segment size
(the byte-budget contract from ADR-0039 is preserved), lock-free reads, W-TinyLFU eviction. The public
API (`get`/`put`/`bytes`/`hits`/`misses`, `NodeCache(long maxBytes)`, `maxBytes<=0` = disabled) is
unchanged, so `RocksNodeStore.setNodeCache` and all call sites are untouched. The synchronized
`LinkedHashMap` implementation is **removed** — not kept as a selectable alternative (pre-1.0; an
unused second implementation is pure maintenance cost). Caffeine becomes an engine-module
production dependency.

Option C was rejected as premature: it matches Caffeine's concurrency but not its scan resistance, and
costs real hand-rolled concurrency-correctness work for no measured advantage over a battle-tested
library. Option A was refuted by all three experiments for the shared-cache regime.

## Consequences

- **+** Concurrent read throughput ~3× under contention; scan-resistant hit rate (62% → 87% real-Sail
  under scan pollution); predictable tail latency; byte-weighted bounding retained.
- **−** A new engine-module dependency (Caffeine 3.x, Apache-2.0, zero required transitive deps).
  The foundational module now depends on a cache library — judged acceptable, the cache is a core
  concern.
- **−** ~25% single-thread overhead vs the bare LRU on a *pure get-loop* microbench. Accepted: it
  dilutes to noise in the real Sail point lookup (experiment #3), and the multi-tenant shared-cache
  regime — where lock-free + scan-resistance win — is the default.
- **−** Eviction is no longer exact-LRU or synchronous (async W-TinyLFU). Unit tests therefore pin the
  *invariants* a cache must hold — roundtrip, bounded weight, telemetry counters, disabled budget — not
  eviction order; policy quality is validated by experiment #3 and Caffeine's own suite. The two
  LRU-order tests from ADR-0039 are removed.
- **Correctness unchanged.** Values are content-addressed and immutable → no invalidation problem; an
  evicted-but-still-referenced `Node` stays alive via the JVM garbage collector → no use-after-evict
  hazard, which is what makes lock-free reads safe here.
- **Follow-on (not in this ADR):** other caches in the codebase may benefit from the same move (the
  warm-set Sail registry; a future term/dictionary decode cache). To be explored separately and
  decided on their own evidence.
