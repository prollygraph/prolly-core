# ADR-0068: Tree write re-emit and fast-forwarding

## Status

Accepted, 2026-06-23 (recorded the re-emit posture + the restoration path). **Superseded in part,
2026-06-24 — Option B (structural fast-forwarding) is implemented; see the Update below.** Grounded in
[`commit-latency-vs-history-benchmark`](../../an upstream plan) and its
an upstream build-log; the fix is recorded in
the upstream restoring-fast-forwarding build-log and
the upstream tree-write fast-forwarding plan.

## Update (2026-06-24) — Option B (fast-forwarding) implemented; D-1 superseded

The deferral in D-1 has ended: **structural fast-forwarding is restored.** `TreeMutator.Chunker.advanceTo`
now skips unchanged subtrees by reference (synchronize-then-skip, ported from Dolt's chunker), with
`processPrefix` re-emitting the edited node's prefix and `finalizeCursor` skipping the suffix. A
single-key commit now does **`O(log n)`** work (the affected root→leaf spine), not `O(n)`.

- **No tag, no format change (Q1 confirmed).** The D-3 "natural-boundary tag" — the central B-gating
  requirement the Options table and D-3 below hypothesized — was **never needed.** The landed mechanism
  *derives* skip-safe boundaries from structure (re-emit until a new boundary aligns with an old one,
  then skip via the parent cursor). The node format is **unchanged**, so Dolt parity (Layers 0–2) holds
  with no re-pin — verified ("Binary Parity" + "Full Parity E2E" PASSED). This **retracts D-3(a)** and
  the "a format change" consequence below.
- **Convergence preserved exactly.** A fast-forwarded build yields the byte-identical root to a
  from-scratch build — pinned by `TreeMutatorFastForwardDifferentialProperty` (500 random tries) plus
  the Merkle convergence + determinism stress tests. The splitter-desync risk that motivated removing
  fast-forwarding is handled by the *alignment* check, not by re-emitting.
- **Measured `O(log n)` (the speedup claim, now evidence-backed).** `TreeMutatorFastForwardComplexityTest`
  counts per-commit `NodeStore` read/write calls vs history depth: **flat across 16× history** (≈ 2–5
  calls at every n ∈ {1k…16k}, a 1.00× ratio where a re-emit `O(n)` would be ~16×). This isolating
  in-memory microbench (no compaction/input-output confound) is the right instrument for the
  *algorithmic* claim;
  the RocksDB wall-clock `commit-latency-vs-history` bench (dev-box shape-only, D-7) stays a deferred
  end-to-end view that does not change the conclusion.
- **One bug, caught by the gate.** The first cut produced a duplicate child on an insert that merges two
  leaves: `createParentChunker` eagerly called Dolt's `processPrefix`, which double-emits in the port's
  build-from-`atStart` driver (Dolt builds at the first edit). The convergence differential auto-shrank
  it to a 1-second repro; the fix was to drop the eager `processPrefix`. No silent corruption shipped.

Net: history *storage* was already `O(log n)` (unchanged); write **time** is now `O(log n)` too, so the
commit-per-change loop is `O(n log n)`, not `O(n²)`. Option C (batching) stays complementary for bulk
ingest (D-4 stands).

## Context

An upstream Sail commit re-builds its trees through `TreeMutator.applyMutations` (the structural
tree-mutation engine every write goes through). The
[`commit-latency-vs-history-benchmark`](../../an upstream plan) measured
how a single-triple commit scales with history depth and found a **surprise that refuted the
intuitive cost model**: a commit is **`O(n)` in the corpus size**, not `O(log n)` — so a loop of *N*
single-triple commits is **`O(n²)`**. On the repo's N150 dev box (shape-only) the per-commit
tree-build mean climbed **2.4 ms → ~53 ms over n = 1k → 17k** while the fixed RootMetaTree + log +
refs residual stayed flat (~0.5 ms); attribution pins the growth entirely on the tree builds
(`sail.commit.tables`).

**Why `O(n)`, not `O(log n)`.** A correct prolly insert *should* be `O(log n)`: rewrite the
root→leaf spine, reference-share the untouched subtrees. The port does **not** do this on the write
path. `TreeMutator.Chunker.advanceTo` **re-emits every existing entry through the `RollingHashSplitter`**
rather than skipping unchanged subtrees by reference — its own comment says so: *"This is
O(existing-tree) per call rather than O(diff); a future optimisation can re-introduce fast-forwarding
once natural-boundary chunks are tagged."*

**Why fast-forwarding was removed (the convergence constraint).** Skipping a subtree's bytes leaves
the `RollingHashSplitter` (BuzHash) in a different rolling-window state than a from-scratch build
would have, which shifts *later* chunk boundaries → a **non-convergent** tree. History-independence
requires that the same content always yields the same root hash (pinned by
`MerkleConvergenceStressTest`). The force-flush of the rightmost spine in `Chunker.done()` ends at
*non-natural* boundaries, so resuming the splitter after a skipped span is unsafe. Re-emitting every
entry guarantees the splitter sees the same byte sequence as a single-batch build, so the tree
converges — at `O(n)` cost.

**Disk vs CPU — the distinction the cost model conflated.** The *storage* delta of an edit **is**
`O(log n)`: only the root→leaf path produces *new* chunks; re-emitted unchanged chunks are
content-addressed and **dedup** in `store.write` (pinned by `InvIntegrityProperty`, which bounds
*new nodes* by tree height). What is `O(n)` is the **CPU/serialization** to re-emit + re-hash every
entry to *discover* it dedups. So: cheap history *storage* (the structural-sharing law holds), `O(n)`
write *time*. (an upstream foundations doc claimed the *cost* was
`O(log n)` and that chunks are shared "by reference" — corrected in the same change as this ADR.)

This matters now because the commit-count axis (many small commits) is the natural shape for
"Git-for-data" usage (a commit per change), and it is the one axis the prior write-path work did not
characterize — the upstream two-walls build-log and
the upstream ADR-0061 (bulk-load writer) measured *statement-count* ingest.

## Options

| Option | per-commit cost | convergence-safe? | format change? | workload it serves |
|---|---|---|---|---|
| **A** — re-emit every entry through the splitter (**current**) | `O(n)` → loop `O(n²)` | **yes** (splitter sees the full byte stream) | no | correctness + simplicity |
| **B** — restore structural fast-forwarding (skip unchanged subtrees by reference) + a **natural-boundary tag** in the node encoding | `O(log n)` → loop `O(n log n)` | **yes, but only** once boundaries are tagged (else desync → non-convergent) | **yes** (a boundary bit in the node format) | the **commit-count / churn** workload (a version per change) |
| **C** — batch into fewer, larger commits (the upstream ADR-0061 bulk-load writer) | amortized `O(1)`/triple within a batch → `O(total)` for the batch | yes | no | bulk **ingest** (one commit of many triples) — *not* per-change versioning |

## Decision

- **D-1 — Keep Option A (re-emit) as the current accepted state.** Convergence-correctness outranks
  write speed at this stage, and A is the simplest provably-convergent build. The accepted cost:
  `O(n)` per commit → `O(n²)` for an *N*-commit loop. The `O(n)` is the **tree re-emit**, not the
  `O(history)` `CommitLog.latest()` (that runs only on boot — correctly ruled out by the bench).
  **(Superseded 2026-06-24 — Option B implemented; see the Update above.)**

- **D-2 — The achievable floor for the per-change-versioning workload is `O(log n)` per commit →
  `O(n log n)` total (Option B). `O(n)` total is impossible for this workload.** Every commit
  publishes its own content-addressed root, which *must* rewrite the root→leaf spine (`O(log n)`
  chunks; the root hash changes every commit). So *N* separate commits are `≥ O(n log n)`; `O(1)`
  per commit cannot exist while each commit is its own version. **`O(n)` total is only reachable by
  *batching*** — Option C, a different workload (one commit of *N* triples), which
  the upstream ADR-0061 bulk-load writer already serves for ingest.

- **D-3 — Restoring Option B is gated on two things, both required.** (a) A **natural-boundary tag**
  in the node encoding, so a skipped subtree's right edge is a known BuzHash boundary and the
  splitter resumes without desync; (b) `MerkleConvergenceStressTest` staying green — byte-for-byte
  convergence is the load-bearing invariant, not a nice-to-have. Pre-1.0 (no backwards-compat) means
  the format change is permitted; it is still a format change and owes the format-versioning posture
  of the upstream ADR-0067 (root-meta versioning, not exported — RDF-woven).
  **(Update 2026-06-24: requirement (a) — the tag — proved UNNECESSARY. The landed mechanism derives
  skip-safe boundaries from structure; there is no format change. See the Update + Q1.)**

- **D-4 — Batching (Option C) is the answer for ingest, not a substitute for B.** The bulk writer
  gives `O(total)` for "load a corpus," but does nothing for "a version per change" (each commit its
  own root). The two are complementary: C for ingest throughput, B for commit-count scaling.

## Consequences

- **(+) History storage is already cheap.** An edit writes only `O(log n)` *new* chunks; the rest
  dedup. Branch / time-travel / diff-by-matching-root-hash all stay cheap — the structural-sharing
  law is intact. This ADR does **not** weaken it.
- **(−) Write *time* is `O(n)` per commit → commit-count workloads are `O(n²)`.** A million
  single-triple commits is **infeasible** under Option A (extrapolated ~seconds per commit, ~weeks
  total — order-of-magnitude, not measured). Operators doing high-frequency per-change commits hit
  this; the mitigation today is **batching** (Option C). **(Superseded 2026-06-24 — write time is now
  `O(log n)`; the commit-per-change loop is `O(n log n)`. See the Update.)**
- **(+) The fix is localized.** `TreeMutator` is shared by all seven trees (dict + four quad indexes
  + namespaces + stats) and by `MergeEngine` / cherryPick / revert — so restoring fast-forwarding
  lifts the entire write path in one change.
- **(−) The fix is convergence-critical and a format change.** The boundary tag changes the node
  encoding; a subtle desync silently breaks history-independence. `MerkleConvergenceStressTest` is
  the gate; the risk is real, which is why B is deferred rather than rushed.
  **(Update 2026-06-24: convergence-critical, yes — and the gate did catch one desync (the
  `processPrefix` double-emit). But NOT a format change: the tag proved unnecessary. See the Update.)**
- **(neutral) The regime caveat survives the fix.** Even at `O(log n)` per commit, the hash-scattered
  keys make each descent touch random nodes; once the tree exceeds RAM those are cold RocksDB reads,
  so the *constant* grows with cache-miss rate (the RAM/input-output regime), and the
  compaction-driven tail latency remains. `O(n log n)` is the *algorithmic* improvement, not a
  constant-factor cure.

## Follow-up / future work

- **Restoration plan — IMPLEMENTED (2026-06-24):**
  the upstream tree-write fast-forwarding plan (+ its
  sub-plan `tree-write-fast-forwarding-impl`). Phase 0 resolved the mechanism: **re-synchronize-then-skip**
  (port Dolt's `advanceTo` + the stripped `Cursor`/`Chunker` primitives) — **no encoding tag, no format
  change** (Q1). Reference-skipping is now in `advanceTo` + the `done()`/`finalizeCursor` tail;
  `MerkleConvergenceStressTest` + the differential stay green; the slope is measured flat (`O(log n)` per
  commit). See the Update at the top.
- **The clean-box absolute run** of the commit-latency bench (the dev box is shape-only, D-7 of that
  plan) — run `test-support/commit-latency-bench.sh` on a box with `zfs_arc_max` capped / no ZFS.

## Open questions

- **Q1 — The boundary-tag encoding. RESOLVED (2026-06-24): no tag.** The restoration plan's Phase 0
  (the upstream tree-write fast-forwarding plan)
  read Dolt's `go/store/prolly/tree` `advanceTo` + probed the port: the mechanism is
  **re-synchronize-then-skip** — re-emit until a *new* boundary aligns with an *old* one, then skip
  via the parent cursor — and reads **no** boundary flag. A `FastForwardPremiseTest` confirms 95/95
  non-rightmost leaves end at reproducible boundaries. So **the hash-affecting encoding tag this ADR
  hypothesized is unnecessary** — option B (derive from structure) holds, no format change, no parity
  re-pin. (Original tag hypothesis retracted here.)
- **Q2 — Skip both sides. ANSWERED:** Dolt covers the tail via `Done()`/`finalizeCursor` (not just
  `advanceTo`); the restoration plan ports both (its D-4 + Phase 2).
- **Q3 — Is per-change versioning worth the fix? RESOLVED: yes, owner-greenlit (2026-06-24).** The
  restoration plan is **drafted + active**:
  the upstream tree-write fast-forwarding plan.
