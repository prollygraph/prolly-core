# ADR-0071: Commit identity includes parents (commit-log-native identity)

## Status

Accepted, 2026-06-26. Guides an upstream implementation plan.
Resolves the root cause behind the deferred bug plan
an upstream implementation plan.
Supersedes the identity model documented in `CommitLog`'s class javadoc ("the RootMetaTree hash *is* the
commit id").

## Context

A commit's identity is today the RootMetaTree content hash (`metaTreeHash`):
`CommitLog.Entry.hashHex()` (`CommitLog.java:313-315`) returns `HashUtils.toHex(metaTreeHash)` and
**ignores the entry's `parents`**. So **two distinct commits with the same resulting tree but different
parent graphs collapse to one identity.** The parents are already recorded on disk — `append()` writes
them (`CommitLog.java:191-193`), `Entry` holds them, `parse()` reads them back — they are simply absent
from the identity function. This ADR widens *what content the id covers*; it captures no new data.

The collision is not hypothetical. It is the **measured** root cause of a sync-convergence failure
(diagnosed in the bug plan via append-dump instrumentation on a pinned fuzz seed):

- `SyncFuzzTest` (a jqwik property over random commit/pull op-sequences across N peers) deterministically
  fails for seed `-2542101598926231265` with `commit-log batch has a dangling parent`.
- The peers run **provenance-off**. Two peers that merge the **same triple-set in different orders**
  produce byte-identical trees → identical `metaTreeHash` → identical commit id, but **different
  parents**. Commit `ffee2651…` was observed appended with ≥5 distinct parent-sets.
- On pull, `CommitClosure.reachable` excludes the ancestors of the receiver's "haves" over the
  *sender's* graph. Because the receiver "has" `ffee2651` *by identity* (a collided id), the sender
  excludes `ffee2651`'s real parent `19a48b1b` — which the receiver genuinely lacks — and the
  dangling-parent guard in `CommitLogSync.mergeInto` correctly rejects the batch.

The same collision makes `CommitGraph.isAncestor` and the merge-base latently wrong under provenance-off,
so this is a **commit-identity** bug surfacing in sync, *not* a sync-protocol bug — a sync-only patch
treats a symptom.

**Why a redesign and not a patch.** The owner first chose Option C (require provenance for merge-sync).
It was implemented and measured **insufficient**: it fixed `SyncFuzzTest` triple-convergence but **broke
`ConcurrentPushRaceTest`** (0 winners, was 1). Under provenance-on the pull/merge writes a peer-local
provenance subtree → a different tree → a different id, so the *same logical commit diverges in id*. That
is the **mirror image** of the provenance-off collision: *off* makes different commits **share** an id;
*on* makes the same logical commit **diverge** in id. Both are symptoms of `id = metaTreeHash`. Only
fixing the identity function itself is sound under *either* provenance setting (Option C is withdrawn;
the half-fix was reverted).

**A constraint the redesign must honor.** The current design *deliberately* kept wall-clock time out of
identity (CommitLog class javadoc: "baking a timestamp into the RootMetaTree would break determinism").
That instinct is correct and survives this ADR — identity stays a deterministic content hash; we widen
*what content* it covers (add parents + commit metadata), not *whether* it is content-addressed.

## Options

**Identity model:**

| Option | Fixes the collision | Sound under both provenance settings | Scope | Treats a symptom? |
|---|---|---|---|---|
| **A** — id = `hash(tree + parents + metadata)` | **Yes** — distinct parent graphs → distinct ids | **Yes** | Large: id ≠ metaTreeHash across refs, commits.log, CommitClosure, CommitGraph, mergeInto, MergeEngine, sync | No — fixes identity at the source |
| **B** — Conservative sync (batch always parent-closed) | Masks this symptom | No — isAncestor / merge-base collisions stay latent | Medium (sync only) | Yes |
| **C** — Require provenance for merge-sync | No — trades collision for divergence (breaks `ConcurrentPushRaceTest`) | No | Medium; **WITHDRAWN** (refuted 2026-06-16) | Yes |

**Sub-decision (given A) — does the id cover the wall-clock timestamp?**

| Sub-option | Determinism | Convergence robustness | Distinct no-op commits | Matches existing philosophy |
|---|---|---|---|---|
| **A1** — git-exact: id includes the committer timestamp | Non-deterministic (re-deriving a commit yields a new id) | Weak — needs transmit-wholesale everywhere; a fixed-point merge loop can fail to converge on heads (each peer stamps its own time) | Yes (timestamp distinguishes them) | No — reintroduces wall-clock into identity |
| **A2** — id = `hash(tree + ordered parents + author + message)`, timestamp **excluded** | **Deterministic** — same logical commit → same id on any peer, any time | **Strong** — identical logical merges → identical id → automatic head convergence without transmit-wholesale | No — two commits identical in tree+parents+author+message collapse (content-addressing as intended) | **Yes** — wall-clock stays out of identity |

## Decision

**D-1 — Adopt commit-log-native identity (Option A).** A commit's id becomes a content hash over its
logically-meaningful content, not its tree alone. The id is computed once at commit time and stored;
downstream consumers (refs, `CommitClosure`, `CommitGraph`, `mergeInto`, `MergeEngine` merge-base, the
sync protocol) address commits by this id. Like git, the id is a Merkle-DAG node: it hashes the
*already-computed ids* of its parents, so distinct parent graphs are distinct ids by construction. The
recursion is well-founded — genesis has no parents; every other commit hashes parents that appear earlier
in topological order.

**D-2 — Exclude the wall-clock timestamp from the id (Option A2).**
`id = hash(metaTreeHash ‖ parent-ids ‖ author ‖ message)`. The timestamp is still recorded in commits.log
(Memento / `/sparql/timemap` need it) but is **not** part of identity. This is the deciding tradeoff: a
deterministic id means the *same logical commit* produced independently on two peers gets the *same id*,
so sync converges **by construction** — a fixed-point merge loop terminates because a merge of two
equal-tree commits yields a deterministic id on *both* peers. A1 (timestamp in id, git-exact) would make
that loop diverge — each peer stamps its own time → a different merge-commit id → heads never equal. The
cost of A2: two no-op commits identical in tree+parents+author+message collapse to one id; under
content-addressing that is *correct* (byte-identical commit records *are* one commit), and
distinct-empty-commits is not a load-bearing use case pre-1.0.

**D-3 — Hash parents in their recorded order (do not sort).** `parents[0]` (the branch that received a
merge) is load-bearing for merge semantics, and order-sensitivity is *correct*: "merge B into A" and
"merge A into B" are distinct commits, exactly as in git. Determinism for convergence is preserved because
the *operation* fixes the order — two peers performing the same merge record the same parent order → the
same id. (Sorting would wrongly collapse opposite-direction merges; the observed collision was a different
parent *set*, not order, so order-sensitivity does not reintroduce it.)

**D-4 — A fast-forward pull adopts the source commit wholesale (id + all fields); it never re-derives a
peer-local commit.** This is the necessary-not-sufficient sub-fix the bug plan flagged. Under
provenance-on a re-derived pull commit gets a different provenance subtree → a different tree → (even
under D-1) a different id; adopting the source commit verbatim keeps ids stable across a fast-forward.
Required to make `ConcurrentPushRaceTest` sound under *both* provenance settings — the second of the two
axes the bug plan's resume trap demands.

**D-5 — One-shot format migration; no defensive reader (pre-1.0).** commits.log changes from
`<datetime> <metaTreeHash> [<parent:metaTreeHash>…]` to `<datetime> <id> <metaTreeHash> [<parent:id>…]`
— parents are referenced by the new id and the tree hash is a separate stored field. Per the project's
no-backwards-compat rule the reader requires the new shape; existing stores are migrated by an
operator-run one-shot (deterministic script: recompute ids in topological order, rewriting parent refs
from metaTreeHash to id). The migration is **exact for a collision-free log** (the common single-peer
case) and **refuses a log that already contains a metaTreeHash collision** (ambiguous parent refs) →
the operator reimports from source data (back up + reimport is acceptable at v0.2.0-BETA). No
auto-migrator in the boot path.

## Consequences

- **Positive:** the dangling-parent collision is eliminated at the source; `isAncestor` / merge-base
  become sound under *either* provenance setting; sync convergence no longer depends on the provenance
  flag. Identity stays deterministic and content-addressed (D-2 preserves the existing philosophy).
- **Cost / format:** commits.log format changes (D-5); refs store the new id; a one-shot migration is
  required (clean logs exact, collided logs reimport). Blast radius: `CommitLog`, `CommitClosure`,
  `CommitGraph`, the rdf4j `MergeEngine`, `mergeInto`/`CommitLogSync`, `RepoSync`, `PackBuilder`,
  the upstream Sail.
- **Cost / behavior:** two no-op commits identical in tree+parents+author+message collapse to one id
  (D-2). Not load-bearing pre-1.0; flagged.
- **API surface shift:** the id is no longer equal to the RootMetaTree hash, so `?commit=<hash>` and the
  `X-Prolly-Commit-Id` header now carry the **commit id**, not the tree hash. `CommitLog.findByHash`
  becomes `findById` (matching the id field); time-travel still resolves a snapshot internally via the
  stored tree hash.
- **Proof obligation (what the plan MEASURES, not asserts — pin BOTH axes per the bug plan's resume
  trap):** `SyncFuzzTest` green at raised tries + the pinned seed, *both* provenance settings;
  `ConcurrentPushRaceTest` green, *both* provenance settings; a focused commit-id-stability test (same
  logical commit → same id; differing parents/author/message → differing id; genesis well-founded); a
  `CommitClosure` parent-completeness property (no reachable batch ever omits an ancestor the receiver
  lacks). The head-convergence reasoning in D-2 is a *designed* outcome to be proven by these tests, not
  a measured claim today.

## Follow-up / future work

- If distinct no-op commits become load-bearing, add an explicit nonce or reinstate timestamp-in-id
  behind a new ADR (which would then also require transmit-wholesale on every transfer, not just
  fast-forward).
- The deferred bug plan `sync-dangling-parent-convergence-bug.md` is resolved by the plan this ADR
  guides — close it when `SyncFuzzTest` is green on both axes.
