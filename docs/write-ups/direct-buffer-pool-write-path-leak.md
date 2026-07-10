# Fix: DirectBufferPool off-heap leak — a write path that borrows buffers it never releases

> **Status: FIXED 2026-06-14** via a type-preserving per-transaction pool scope
> (`BufferPool.newTransactionScope()`). See [the fix section](#fixed-2026-06-14--type-preserving-per-transaction-pool-scope)
> below for what landed and the corrections to the original design spec. The sections above are the
> original investigation, kept as the record.

An upstream RDF ingest of web-Google (5.1M edges) through the **off-heap** `DirectBufferPool`
**out-of-memories**: resident set climbs linearly **0.77 → 8.1 GiB in 28 s** and is killed at the
8 GiB cgroup cap. The Java heap stays ~2 GiB (well under `-Xmx5g`) and RocksDB is Phase-1-bounded
(~0.6 GiB), so the **~5 GiB runaway is off-heap** — the `DirectBufferPool`. This is the **third**
write-path memory wall, orthogonal to the two the two-walls build-log (upstream)
named (RocksDB native — fixed by the Phase-1 `WriteBufferManager` bound; single-tx heap — fixed by
batching). Batching and the RocksDB bound do not touch it, which is why it stayed **invisible** until a
RSS sampler was added to `GraphIngestBench` (the prior run was an opaque `exit=15`).

## The A/B that isolates it

`GraphIngestBench prolly -Dpool={direct|heap}` over web-Google, `-Xmx5g`, 8 GiB cap
(`test-support/results/webgoogle-pool-ab-2026-06-14.txt`):

| pool | outcome |
|---|---|
| `DirectBufferPool` (off-heap) | **OOM** — RSS → 8.1 GiB in 28 s, killed (`exit=15`) |
| `HeapBufferPool` (on-heap) | **bounded** — RSS stable ~6.5 GiB, heap oscillating 2–3.9 GiB *under* `-Xmx5g`, ingest proceeds, no OOM |

The decisive inference: `HeapBufferPool`'s heap stays **below `-Xmx5g`** (live working set < 4 GiB),
so the extra ~4–5 GiB the `DirectBufferPool` holds is **retained-but-dead** — not a genuine working
set. (If the buffers were truly live, `HeapBufferPool` would have heap-OOM'd too; it didn't.)

## Root cause

The pool itself is correct: [`DirectBufferPool`](../../prolly-storage/src/main/java/com/earasoft/prolly/pool/DirectBufferPool.java)
`borrow(size)` polls a size-bucketed `ConcurrentLinkedQueue` (reuse) and only `arena.allocate`s on a
miss; `release(segment)` `offer`s it back to its bucket. **But the arena is `Arena.ofShared()`** — it
frees *nothing* until `close()`, so a segment that is **borrowed and never released** is allocated
fresh, never returns to its bucket, and accumulates in the arena forever (`allocatedBytes` only grows).

So the bug is a **borrow/release imbalance on the upstream Sail's write path**. **Confirmed RED 2026-06-14 by
an upstream regression test**
— and sharper than "imbalance": over 20 commits the write path borrows **1,000,975** segments and releases
**0**. It never calls `release()` *at all* — so this is not the subtle bucket-size-mismatch the count
invariant could miss; `release` is simply absent on the build path. The arena grows `allocated 256 MB
(round 5) → 1,025 MB (round 20)`, ~linear, extrapolating to the web-Google 5.1M → ~5 GiB OOM. With
`HeapBufferPool` those un-released buffers become unreferenced garbage and the GC reclaims them, so the leak
is **invisible** there; with `DirectBufferPool` (manual, GC-blind) it is an unbounded off-heap leak → OOM. This also finally **explains** the long-standing
an upstream engine-comparison note that the engine "buffers more / would strain the box" — it isn't a
heavier *working set*, it's this leak.

## Repro

```
GraphIngestBench prolly -Dpool=direct  -Dgraph.zip=…/web-google.zip  (-Xmx5g, 8 GiB cap)   → OOM ~28 s
GraphIngestBench prolly -Dpool=heap    -Dgraph.zip=…/web-google.zip  (-Xmx5g, 8 GiB cap)   → bounded
```

NCIt did **not** hit this because `StreamingNcitIngest` builds its prolly sail with a `HeapBufferPool`
(GC reclaims the leak). **`GraphIngestBench` is the only long-lived `DirectBufferPool` ingest path.**

## Scope / severity — corrected 2026-06-14

**This is NOT a current production-server OOM.** The production per-repo Sail
(the upstream per-repo Sail factory
line ~58) builds the Sail with a **`HeapBufferPool`**, so the server GC-reclaims the leaked buffers — it
is masked there. `DirectBufferPool` is used only in (a) `GraphIngestBench` (this bench — a long-lived pool
held across the whole ingest, never closed mid-run → the leak shows), (b) demos, and (c)
`ProllyEvaluationStrategy`'s *per-query* `try (DirectBufferPool …)` (closed when the query ends → arena
freed → bounded, no cross-query leak). So the leak is **latent**: it bites a long-lived `DirectBufferPool`
on a leaking write path, which today is only the benchmark. **But** if `DirectBufferPool` is the intended
zero-copy *production* write path (`write-path-zero-copy.md`), this leak **blocks that adoption** and must
be fixed first — and meanwhile it means the bench's `engine-comparison.md` memory numbers are for a pool
the server does not run.

## Fix direction

1. **Find + plug the write-path borrow-without-release** (the real fix). Grep the Sail's write
   path (`MutableMap` / `TreeMutator` / the commit build) for `pool.borrow(...)` sites whose segments
   are not `release`d on every path (esp. error/early-return paths). The pool already exposes the
   signal: `borrowCount()` vs `releaseCount()` (the `BufferPoolMXBean`) — a growing gap **is** the leak;
   wire it into the bench / a regression assertion.
2. **Pool-side hardening** (defense in depth): the single never-freeing `Arena.ofShared()` makes any
   leak unbounded. Options — a per-transaction arena freed at commit (so a leaked segment is reclaimed
   at the commit boundary), or a capped pool that frees/evicts beyond a high-water mark. A bounded pool
   turns "leak → OOM" into "leak → re-alloc churn", a far better failure mode.
3. **Regression guard:** a test that ingests N×batch through `DirectBufferPool` under a small `-Xmx`
   + cgroup cap and asserts peak RSS is flat-in-graph-size (and `borrowCount ≈ releaseCount`).

## FIXED 2026-06-14 — type-preserving per-transaction pool scope

**Landed.** The upstream Sail connection now scopes a buffer pool to one transaction and frees it wholesale at
the transaction boundary. The off-heap footprint is bounded to a single transaction's working set.
the upstream regression test
is green (no longer disabled); a curated lifecycle/provenance/concurrency batch (1,164 upstream
tests) is green, confirming production behaviour is unchanged.

**Verified at real scale 2026-06-14 — the original OOM repro no longer OOMs.** Re-ran the exact repro
(`GraphIngestBench prolly -Dpool=direct`, web-Google 5.1M edges, `-Xmx5g`, cap 8 GiB, Phase-1
`write-buffer.mb=128`) against freshly-installed fixed jars (`newTransactionScope` confirmed in the
`~/.m2` core + storage jars first — the bench classpath would otherwise pull stale pre-fix jars). Result
(`test-support/results/webgoogle-poolfix-verify-2026-06-14.txt`): RSS **plateaus at peak 5,751 MiB**
(under the 8,192 cap), **0 out-of-memory signals**, sustained through t=194s — **6.5× past the pre-fix
30s-to-OOM point**, where the leak had climbed monotonically 0.77 → 8.1 GiB and was cgroup-killed
(`exit=15`). The per-transaction scope bounds the off-heap exactly as the `HeapBufferPool` control did
(~4.8 GiB). *Two honest caveats:* (a) the run did not COMPLETE — it was time-capped at 200s; the full
5.1M ingest is slow for an unrelated reason (the disk-bound spine-walk throughput cliff, a perf issue,
not memory) — the leak verdict rests on the bounded RSS plateau, the correct signal (D-1). (b) A JVM
SIGSEGV hit at the ~200s timeout boundary, in a **RocksDB background-compaction thread** (`librocksdbjni`,
`BlockBasedTable::Open`) — the abrupt `timeout` SIGTERM interrupting native compaction threads during
process teardown, **not** the leak and **not** this fix (the Arena/pool code is absent from the crash
stack). **Root-caused + fixed 2026-06-14.** Two wrong turns first (both retracted in the plan): (1) it is
*not* a `RocksNodeStore.close()`-internal bug — a normal close during an active compaction backlog
neither crashes nor loses data (`db.close()` drains; `close()` left unchanged); (2) it is *not* cosmetic
bench noise either (my over-correction; the user caught it — *a reproducible SIGSEGV in mature RocksDB is
our wrong JNI pattern*). **The real cause:** plain-`main` benches (`GraphIngestBench`) close RocksDB only
on normal completion, so a `timeout` SIGTERM never runs `close()` → native compaction threads race
teardown. **Reproduced + fixed**, self-contained control-vs-fix repro: **control (no shutdown hook) 3/3
SIGSEGV** (exact signature: `BlockBasedTable::Open` ← `CompactionJob::Run` ← `BackgroundCompaction`,
native thread); **fix (cooperative `BenchGracefulShutdown` hook) 0/4 SIGSEGV**, close drained 1–2 ms.
Production was never affected (Spring Boot's shutdown hook already closes the sails on SIGTERM). See
an upstream resource-invariant plan
+ `BenchGracefulShutdown` (the fix) + `RocksNodeStoreCloseDuringCompactionTest` (close-safety regression).

**The mechanism — `BufferPool.newTransactionScope()` (type-preserving), not a hard-coded
`DirectBufferPool`.** `forkTables()` opens `poolTx = sail.pool().newTransactionScope()` and passes it
to every per-tx table; the connection frees it at the next fork and at close. The default
(`HeapBufferPool`, **production**) returns *itself* with a no-op `close()` — so production is
**byte-identical** to before; `DirectBufferPool` overrides it to return a fresh child arena freed per
transaction. This keeps the choice of memory primitive with the pool.

**Three corrections to the pre-fix design spec (retracted in place):**
1. *"Field `private DirectBufferPool poolTx`; `poolTx = new DirectBufferPool()`."* — **Wrong: would
   switch production from on-heap to off-heap.** Production wires `HeapBufferPool`
   (the upstream factory + auto-configuration wiring sites); a hard-coded `DirectBufferPool` would silently
   change every production write to off-heap. The field is `BufferPool poolTx`, obtained via
   `newTransactionScope()` — type-preserving.
2. *"Close `poolTx` at commit/rollback."* — **Wrong close point: breaks reads after a commit.** A read
   between a commit and the next `begin` still goes through the per-tx tables. The pool is freed at the
   **next fork** (begin/rollback) and at **connection close** — never at commit — so a post-commit read
   finds a live scope. (The next fork replaces the tables *and* their pool atomically.)
3. *"Contained to the Sail connection; 8 per-tx ctor args."* — **Undercounted.** It also needed the
   `BufferPool` interface change (`newTransactionScope` + `close` defaults) and the `DirectBufferPool`
   override, and the pool flows to **12** sites: dict ×2, the 4 `QuadIndex`, namespaces ×2, stats ×2,
   provenance ×2, the event-sink, **and the commit-time provenance fold peer**. "Ready to execute /
   contained" overstated it — the real change spans three modules.

**Safety — re-verified against current code (not the prior writeup):** `RocksNodeStore.read` returns
`MemorySegment.ofArray(db.get(...))` (a heap copy from RocksDB) and `write` does `segment.toArray()`
(copies out before storing); `InMemoryNodeStore` likewise. So no store chunk or read cursor retains a
pool-backed segment — freeing the scope cannot dangle the store, the tree, or an open `RepositoryResult`.
The 1,164-test lifecycle batch (read-your-writes, contexts, provenance, the commit fan-out, snapshots)
is the use-after-free gate, and it is green.

**Secondary accumulator found — deferred here, FIXED 2026-06-14 in the follow-up audit.**
The Sail connection's term encoding allocated scratch in a separate **per-connection** `Arena`
(`DictionaryTermEncoder.encodeForWrite`), freed only at connection close. It is gated by the per-tx term
cache, so it grows ~O(distinct terms) — for web-Google ~875k IRIs × tens of bytes ≈ tens of MiB,
**structurally ~100× smaller than the pool** (~O(statements × indexes × 1 KiB) ≈ 5 GiB), which is why the
pool fix alone sufficed for the out-of-memory. The safety check this deferral named — *does
`Dictionary.encode` copy the segment or retain it?* — was then done (it **copies**: `Dictionary.java:168`
`toArray`), so the arena was made **per-transaction** (`arenaTx`), mirroring the pool scope. See
an upstream bug write-up (an upstream audit, not exported) (fix #1), which also fixed two more
leaks of the same class (a `ForkJoinPool` field, a constructor exception path).

## Where this lives

- `prolly-storage/src/main/java/com/earasoft/prolly/pool/DirectBufferPool.java` — `borrow`/`release`, the shared `Arena.ofShared()`, the `borrowCount`/`releaseCount` MXBean.
- An upstream ingest bench — the `-Dpool=heap|direct` A/B + the resident-set sampler that surfaced it.
- `test-support/results/webgoogle-pool-ab-2026-06-14.txt` — the A/B result.
- The write path to audit: [`MutableMap`](../../dolthub-java-port/src/main/java/com/dolthub/prolly/MutableMap.java), [`TreeMutator`](../../dolthub-java-port/src/main/java/com/dolthub/prolly/TreeMutator.java), and the upstream commit build.
