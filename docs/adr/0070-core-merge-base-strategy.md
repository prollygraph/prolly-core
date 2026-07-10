# ADR-0070: Core merge base strategy

## Status

Accepted, 2026-06-26. Guides an upstream plan.

## Context

<!--
What's the problem? Sketch the situation + constraints. Sub-sections OK.
Include the strategic position when relevant — "what makes this decision
non-trivial and why are we deciding NOW".

Bad: "We need to pick a graph layout library."
Better: "Commit counts in this codebase's deployments are in the thousands,
not the millions. SVG nodes are inspectable + animatable + screenreader-
traversable; Canvas would scale further but degrades all three. The
decision is whether to optimize for current scale + iterability or the
eventual scale."
-->

The core three-way merge (`Database.merge`, `prolly-storage`) finds a **single** merge base via
`findLCA` and feeds it to `MergeEngine`. For a **criss-cross** history (two branches that each merged
the other → *two* minimal common ancestors), `findLCA` "degrades to 'any single base'" — it picks the
latest-timestamp minimal ancestor, equivalent to git's default `resolve` strategy
(`Database.java:420-422`). `--recursive` (a three-way merge against a *virtual* base = the merge of the
multiple minimal ancestors) was "left as a future improvement."

**Step 1 of `criss-cross-merge-correctness.md` measured the divergence frontier** (the
`CrissCrossMergeContentProperty` characterization) and it is **not** uniform:

- **Via the auto-merge API (`Database.merge`): safe.** Both-branches-change-K-differently *surfaces a
  conflict*, so the disagreeing cross-merge cannot auto-commit; the only criss-crosses that form are
  conflict-free (disjoint) ones, for which single-base `resolve` == `--recursive`.
- **Via the public 2-parent `Database.commitMerge` (manual resolution): a reachable SILENT divergence.**
  A human resolves the surfaced cross-merge conflict (M1=K1, M2=K2, both parented by a1,b1); the final
  merge then **silently auto-resolves** K to one side — measured **0 conflicts, K=1**,
  base-pick/timestamp dependent — where `--recursive` would surface a conflict.

**Why decide now:** pre-public, a *silently-wrong* merge is unrecoverable trust damage (the project's
calibrated-honesty ethic applied to the engine), and this is the one core-engine correctness gap left to
assumption. The reachable silent divergence forces a decision rather than continued degradation.

## Options

| Option | Removes the silent divergence | Matches git default | Cost / complexity | Correctness posture |
|---|---|---|---|---|
| **A** — Accept `resolve`, document the bound | No (accepts it, documented + tested as an accepted case) | No (git `resolve` is the *fallback*, not the default) | Lowest (a checked-in accepted-divergence test) | Ships a *named* silent divergence — at odds with "no silently-wrong" |
| **C** — Surface a conflict on disagreeing bases | Partial (removes the *silent* part — surfaces a conflict) | No | Medium-high — distinguishing disagreeing-from-disjoint is most of `--recursive`'s work | No silent wrong, but no merged result either (always a manual conflict) |
| **B** — Implement `--recursive` (CHOSEN) | **Yes** (virtual base makes the result well-defined) | **Yes** | Highest — recursive merge of N bases is itself possibly criss-cross | Correct + matches git; surfaces a conflict only when genuinely contested |

## Decision

**D-1. Implement `--recursive` (Option B).** *Deciding tradeoff (owner's call, 2026-06-26):* the measured
silent divergence is precisely the "silently-wrong = unrecoverable trust damage" the project forbids, and a
versioned database's core merge must be *correct*, not merely cheap. Option A documents a silent wrong
(unacceptable for the substrate); Option C removes the silence but costs nearly as much as B while giving a
*worse* result (a forced conflict where a correct merge exists). B is the only option that both removes the
silent divergence **and** yields the correct merged content.

**The algorithm** (standard git-recursive):

1. **`findLCA` exposes the *set* of minimal common ancestors** (it already computes the set, then collapses
   to latest-timestamp — return the set instead of one).
2. **`mergeBase(A, B)` → a virtual base node:** if there is a single minimal ancestor, that node; if ≥2,
   fold them pairwise — `merge(mergeBase(b_i, b_j), b_i, b_j)` — recursively, where each pair's own base is
   `mergeBase` of the pair. **Termination:** each recursive call descends to *strict ancestors*, so it
   bottoms out at a single common ancestor (or the root).
3. **A conflict in the virtual-base computation is a genuinely contested key → surface it** (the
   disagreement is real; do not silently pick).
4. **`Database.merge`** uses `mergeBase(ourHead, theirHead)` as the ancestor, then three-way as today.

**D-2. The content oracle proves it.** `CrissCrossMergeContentProperty` asserts criss-cross merge content
equals the independent recursive-merge result (not by re-running `MergeEngine` — D-3 of the plan), and the
Step-1 `manually_resolved_…` case flips from "silently K=1" to "the recursive result (or a surfaced
conflict on the genuinely-contested key)".

## Consequences

- **Positive:** the silent divergence is removed — criss-cross merges become correct (== `--recursive`) or
  surface a genuine conflict; matches git's default; closes the last core-engine correctness-by-assumption.
- **Cost / negative (named up front):** `--recursive` is genuinely hard — the recursive merge of N bases is
  itself possibly criss-cross, so the implementation is recursive with a real base case + termination
  argument; virtual-base conflicts need representation (a conflict in the virtual base must propagate). The
  `findLCA` refactor (return the set) touches the merge-base path that `LcaCorrectnessProperty` pins — its
  latest-timestamp-tiebreak example becomes a *single-ancestor* selection detail, re-pinned deliberately.
- **Neutral / format:** no on-disk format change — this changes how the *base* is computed, not how trees
  or commits are stored.

## Follow-up / future work

- **Step 3** (the plan's Phase 2) implements the algorithm + the content oracle; **Step 4** updates the
  `Database.java:420-422` comment (the "future improvement" → the resolution) and adds the merge path to the
  prepublic correctness/mutation gate.

## Open questions

- **Q1 — virtual-base conflict representation.** When `mergeBase` itself conflicts (the minimal ancestors
  genuinely disagree on a key both heads then touch), the cleanest representation of the virtual base is a
  Step-3 implementation detail (git writes conflict markers into the virtual tree; the port may instead
  surface the conflict at the outer merge). To be resolved in Step 3, not a blocker on D-1.
