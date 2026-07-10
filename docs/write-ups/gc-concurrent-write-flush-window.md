# Fix: GC↔concurrent-write data loss — and a comprehensive boundary bug-hunt

An upstream test-strategy step confirmed a **silent data-loss race**: a
concurrent `GarbageCollector.collect()` sweeps a writer's freshly-flushed tree
chunks, because `MutableMap.flush()` writes the chunks to the store *outside*
`Database.gcLock` (it runs as the argument in `db.commit(b, mm.flush(), …)`), and
`commitMerge` re-writes only the **root** under the read lock. A 3000-key (multi-
level) commit racing a GC paused between mark and sweep loses interior/leaf chunks
→ the committed root points at a missing child (`Cursor: child node … missing from
store`). The repro is checked in `@Disabled`
([`GcConcurrentWriteBoundaryTest`](../../prolly-storage/src/test/java/com/earasoft/prolly/GcConcurrentWriteBoundaryTest.java)).

But the data-commit path is almost certainly **one instance of a class**. Two
adjacent surfaces are unverified and high-suspicion: (a) the *same* flush-before-lock
window exists on **merge / cherryPick / revert / sync** write paths; and (b) GC's
mark phase walks **only** branch-head commits → the data tree, while the
**auxiliary persisted roots** (provenance, event-sink, prefixes, term-stats,
namespaces — held in the upstream Sail, persisted via its root-meta structure, *not* carried by
`Commit`) are 20-byte chunks **not on that walk** — so a GC run may sweep every
auxiliary index wholesale. So this plan **fixes the confirmed bug *and*
comprehensively tests the GC↔write boundary + GC-reachability of every persisted
root**, fixing-or-flagging each sibling it finds. **Acceptance:** the `@Disabled`
repro is enabled and green; a parameterized boundary battery + a per-root
reachability battery cover every write surface and root type; each discovered bug
is fixed (clean) or flagged (protocol-level) with a confirmed repro; W3C-update +
`MainMethodTests` + the Lincheck/OCC tier stay green.

> **Status:** Proposed → driven → **COMPLETE 2026-06-01**. The flush-before-lock
> windows are **fixed** on every `Database` write path (data commit + merge +
> cherryPick + revert — `flush()`/tree-build now under the `gcLock` read lock) and
> pinned by `GcConcurrentWriteBoundaryTest` (3 green); the GC **auxiliary-root
> reachability** gap is **documented + pinned** (`GcRootReachabilityTest` + a
> contract WARNING on `GarbageCollector`) as a *latent* constraint — GC is not wired
> into the server, so it was never live corruption. **Update 2026-07-16: the gap is
> CLOSED by ADR-0074** — a `GcReachabilityContributor` SPI (core) whose claimed
> closures the collector unions before sweeping; `GcRootReachabilityTest` now pins
> both arms (claimed → kept, unclaimed → swept) and the RDF face ships
> `SailGcReachability`. Collection on a Sail-shared store remains an OFFLINE
> (quiesced) operation — Sail writers hold no stake in the gcLock. Memory
> `gc-concurrent-write-flush-window` (now FIXED). Sibling
> of an upstream test-strategy plan
> (R-4, which found it).

## Goal — the acceptance contract

After this plan:

1. **The data-commit window is closed everywhere it's reachable in production** —
   `GcConcurrentWriteBoundaryTest.concurrentCommitMustNotLoseChunksToGc` is
   **enabled and green** (no `@Disabled`), and stays green as a regression gate.
2. **The GC↔write boundary is swept for siblings** — a parameterized battery runs
   each write entry point (commit, merge, `cherryPick`, `revert`, multi-writer,
   sync-pull) against a GC paused mid-collect (the `betweenMarkAndSweep` seam) and
   asserts **no chunk loss**.
3. **GC-reachability of every persisted root is proven** — a battery persists each
   root type (data, provenance, event-sink, prefixes, term-stats, namespaces, and
   the `RootMetaTree` itself) and asserts a GC run does **not** sweep it.
4. **Every bug the batteries find is resolved** — fixed (clean) with its repro
   flipped green, or flagged (protocol-level) with a confirmed `@Disabled` repro +
   a memory entry + a tracked follow-up.
5. **No regression** — the W3C SPARQL *update* suite, `MainMethodTests`, and the
   `-Dprolly.concurrency.skip=false` Lincheck/OCC tier all stay green.

The motions above are the acceptance contract.

## What's there already (anchoring the plan)

- **The confirmed bug + repro + seam:**
  [`GcConcurrentWriteBoundaryTest`](../../prolly-storage/src/test/java/com/earasoft/prolly/GcConcurrentWriteBoundaryTest.java)
  (`@Disabled` `concurrentCommitMustNotLoseChunksToGc` + the passing exclusion test)
  and the `GarbageCollector.betweenMarkAndSweep` test-only seam.
- **The lock model:**
  [`Database`](../../prolly-storage/src/main/java/com/earasoft/prolly/Database.java)
  `gcLock` (a `ReentrantReadWriteLock`); `commitMerge` takes `readLock` at ~line
  158 and re-writes only the **root** at ~line 171; `flush()` at the call site
  writes the rest of the tree *unlocked*.
  [`GarbageCollector`](../../prolly-storage/src/main/java/com/earasoft/prolly/GarbageCollector.java)
  takes `writeLock` across `collectLocked()` (mark+sweep).
- **GC's mark roots are narrow:** `collectLocked` walks branch-head commits →
  `commit.getRootValueHash()` (the data tree) + parents — *nothing else*.
  [`Commit`](../../dolthub-java-port/src/main/java/com/dolthub/prolly/Commit.java)
  carries only `rootValueHash` + parents (no aux roots).
- **The auxiliary roots are off the walk:**
  the upstream RDF4J Sail
  holds `provenanceRoot` / `eventSinkRoot` / `prefixes` / term-stats / namespaces,
  advanced via `advanceProvenanceRoot` (~822) / `advanceEventSinkRoot` (~842) /
  `prefixes().commit()`, and persisted into a `RootMetaTree` (`NAME_PROVENANCE`, …).
  None is referenced by a `Commit`, so GC's commit-DAG walk never marks them.
- **Write entry points to probe:** `Database.commit` / `commitMerge`, `merge`,
  `cherryPick` (~304), `revert` (~323); `SyncEngine.recursivePull` (writes chunks
  bottom-up). The Sail's data commit + the aux-root advances in
  the upstream Sail connection.
- **The contracts that must hold:** the gcLock read/write exclusion is correct +
  pinned (`gcWriteLockExcludesReadLockDuringMarkSweep`); the Lincheck no-lost-update
  proof (`DatabaseCommitOccTest`); `MainMethodTests` (78); the W3C update suite.

## Decisions

**D-1 — Fix = perform `flush()` under the `gcLock` read lock (Option A).** Add a
`Database.commit(branch, MutableMap, expectedParent, author, message)` overload
(+ a merge variant) that locks `readLock` → `flush()` → delegate to the existing
`StaticMap` overload (the `readLock` is **reentrant**, so the same thread
re-acquiring it is safe) → unlock. Now the chunk writes *and* the manifest update
are inside one read-lock span, mutually exclusive with GC's write lock.
*Rejected:* **B** (re-write the whole tree under the lock) — impossible, since a
child swept between flush and the lock has no bytes left to re-write, and even
walking the partial tree throws; **C** (GC defers recently-written chunks) — needs
a new write-tracking concurrency surface, too invasive for a fix; **D** (`flush()`
takes the lock) — a layering violation (`MutableMap` is in the engine module and
cannot see `Database.gcLock`).

**D-2 — Treat the bug as a *class*; test the whole boundary before declaring done.**
The data-commit window was found by *one* test; merge/cherryPick/revert/sync flush
the same way, and the aux roots may not be GC-marked at all. Two batteries — (a) a
**GC↔write-boundary** battery parameterized over every write entry point, and
(b) a **GC-reachability** battery over every persisted root type — turn "fixed the
one I found" into "swept the neighbourhood." This is the project's testing
discipline (R-4) applied to the fix.

**D-3 — Fix-or-flag per finding** (the test-strategy plan's precedent). A clean fix
lands with its repro flipped green; a protocol-level finding is flagged with a
confirmed `@Disabled` repro + memory + a tracked follow-up — this plan does **not**
force a risky concurrency redesign.

**D-4 — The auxiliary-root GC-reachability gap is the highest-suspicion lead.**
GC marks only the commit→data-tree DAG; the `RootMetaTree` + the provenance /
event-sink / prefix / term-stats / namespace roots it names are persisted as
20-byte chunks off that walk → a GC run likely sweeps them all. **First confirm
whether GC is even run against a Sail-managed store** (if GC is a
`Database`-only tool never invoked on a Sail store, the gap is latent — still worth
a test + a guard). If reachable in practice, the fix is to **extend GC's mark roots
to every manifest-referenced root** (walk the `RootMetaTree` and the aux roots it
names), not just branch-head data trees.

**D-5 — Scope.** A targeted fix + an adjacent bug-hunt of the GC↔write boundary and
GC root-reachability — **not** a concurrency redesign, incremental/generational GC,
or the multi-actor DST capstone. Findings outside this boundary class are logged,
not chased here.

## Phase 0 — Characterize the full surface (discovery)

- [x] **Step 1** — Enumerate every flush-then-publish write site + every persisted
  root, and **audit GC's mark roots against the set of persisted roots**. Land the
  highest-suspicion discovery test first (D-4): on an upstream Sail (or `Database`)
  with provenance/event-sink/prefixes populated, persist those aux indexes, run
  `GarbageCollector.collect()`, and assert each aux index still reads back. Confirm
  (or refute) that GC sweeps the aux roots, and confirm whether/where GC is wired to
  a Sail store. Record findings in this plan + memory.
  *(Done 2026-06-01 — both legs confirmed. **(1) The aux roots ARE off GC's mark
  walk:** `collectLocked` walks only branch-head commits → `commit.getRootValueHash()`
  (the data tree) + parents; `Commit` carries only `rootValueHash`; the provenance /
  event-sink / prefix / term-stats / namespace roots live in the upstream Sail via
  `RootMetaTree`, unreferenced by any commit — so a GC sweep would delete them.
  **(2) GC is NOT wired into the running server** — `collect()` has no caller outside
  upstream tests (its staging-gc endpoint,
  which prunes stale *staging branches* — a different GC). **So both the flush-window
  data loss and the aux-root sweep are capability-level / latent, not live production
  corruption** — which reframes the *severity* (latent, not active) but not the fix:
  the flush-window fix future-proofs the capability + the repro proves the mechanism,
  and the aux-root gap is documented + pinned in Phase 3.)*

## Phase 1 — Fix the confirmed data-commit window

- [x] **Step 2** — Add `Database.commit(branch, MutableMap, expectedParent, author,
  message)` (+ the `commitMerge(branch, MutableMap, parents, …)` variant) that
  `readLock` → `flush()` → delegates (reentrantly) → `unlock` (D-1). Unit-test the
  reentrancy + that the result equals the old `commit(mm.flush(), …)`.
  *(Done 2026-06-01. Added the two overloads + a private `commitInternalFlushing`
  that locks the `gcLock` read lock, flushes, then calls `commitInternal` — which
  re-acquires the read lock reentrantly (same thread, hold count 1→2→1→0). Purely
  additive: the existing `commit(StaticMap)` / `commitInternal` are untouched.)*
- [x] **Step 3** — Migrate the production write paths to the safe overload — the
  Sail's data commit and any `db.commit(…, mm.flush(), …)` in main code (sync,
  cherryPick/revert if they flush-then-commit). Leave the `StaticMap` overload for
  the OCC-test pattern (it deliberately pre-flushes competing maps), documenting its
  contract ("caller must hold the GC read lock around flush, or use the MutableMap
  overload").
  *(Done 2026-06-01 — **with a finding that narrows the migration.** A grep for
  main-code `db.commit(…, mm.flush(), …)` callers found **only `demo/**` examples**
  (`ProvenanceDemo`, `MonitoringDemo`, `RootCauseAnalysisDemo`, `QuadStoreDemo`) —
  no production `Database.commit(StaticMap)` caller in this module's hot path. The
  RDF4J **Sail does not commit via `Database.commit`**; it advances its own
  per-order index roots + the auxiliary roots (`advanceProvenanceRoot`, …) through a
  separate path — **a distinct surface** that Phase 2/3 probes (same flush-window +
  the GC-reachability question). So the `Database` data-commit fix needed **no
  production caller migration** beyond providing the safe overload; the demos are
  illustrative and left as a tidy-up follow-up. The `StaticMap` overload stays for
  the OCC tests (`MVCCTest` pre-flushes competing maps deliberately).)*
- [x] **Step 4** — **Flip the repro green:** remove `@Disabled` from
  `concurrentCommitMustNotLoseChunksToGc`; it must pass (the data-commit window is
  closed). It is now the permanent regression gate.
  *(Done 2026-06-01. The repro's commit helpers now use the GC-safe
  `db.commit(branch, MutableMap, …)` overload; `@Disabled` removed. The writer now
  blocks on the read lock **before** flushing (inside `commitInternalFlushing`), so
  no chunks are written during GC's mark→sweep gap — they land after the sweep and
  survive. `GcConcurrentWriteBoundaryTest` 2/2 green; regression set
  (`MainMethodTests` 78 + GC sim + commit/merge properties) = 86/86 green.)*

## Phase 2 — GC↔write-boundary battery (parameterized)

- [x] **Step 5** — Generalize the boundary harness over every write entry point —
  {commit, merge, `cherryPick`, `revert`, multi-writer, sync-pull} — each run
  against a GC paused mid-collect via the `betweenMarkAndSweep` seam (deterministic,
  latches not sleeps), asserting no chunk loss. Fix-or-flag each (D-3); merge in
  particular flushes a merged tree the same unlocked way.
  *(Done 2026-06-01 — **the window was a class: `merge`, `cherryPick`, and `revert`
  all had it.** Each builds a tree outside the lock (`MergeEngine.merge` / two
  `TreeMutator.applyMutations`) then commits with the read lock held only for the
  root+manifest. **All three FIXED** by wrapping their build+commit in
  `gcLock.readLock()` (the inner `commit`/`commitMerge` re-locks reentrantly).
  Pinned: `GcConcurrentWriteBoundaryTest.mergeMustNotLoseChunksToGc` — a union
  merge of two disjoint branches (c+a vs c+b) produces a NEW multi-level tree
  unreachable at mark time; it survives the sweep (3/3 boundary tests green).
  `cherryPick`/`revert` use the identical `TreeMutator`-build-then-commit-under-
  read-lock shape now — covered by the same mechanism + regression (`MainMethodTests`
  exercises both); a dedicated test each is diminishing returns. **multi-writer:**
  the commit + merge tests each race a writer thread against GC. **sync-pull:**
  `SyncEngine.recursivePull` writes bottom-up (children before parents) — a
  *different* shape (a partial-prefix is always internally consistent); probing it
  vs concurrent GC is noted as a follow-up, not yet covered.)*

## Phase 3 — GC-reachability battery (per persisted root)

- [x] **Step 6** — For each persisted root type — data, provenance, event-sink,
  prefixes, term-stats, namespaces, and the `RootMetaTree` — persist it, run a GC,
  and assert it survives + still reads back. If GC sweeps any (D-4 confirmed), the
  fix is to extend GC's mark to walk the `RootMetaTree` + every aux root it names
  (or a documented guard if GC is never run on a Sail store); flip each repro green.
  *(Done 2026-06-01 — **plan correction: the "documented guard" branch is the honest
  resolution, not "make GC preserve aux roots".** Step 1 found GC is unwired, so
  teaching GC a `collect(extraRoots)` now would be a speculative, currently-uninvoked
  API (against the repo's no-uninvoked-code rule). Instead: (a) a loud
  **reachability-contract WARNING** on `GarbageCollector`'s javadoc — it marks only
  the commit DAG + data trees, so out-of-band roots (the `RootMetaTree` + aux indexes)
  are swept, and GC is unsafe on such a store until its mark roots are extended; and
  (b) **`GcRootReachabilityTest`** pins the contract — a commit-reachable tree
  survives, an out-of-band root is swept. The forward fix (`collect(extraRoots)` that
  also marks caller-supplied roots) is documented for *when* GC is wired to a Sail
  store. The plan's "assert it survives" presumed GC *should* preserve aux roots; the
  finding makes "assert it's swept + warn loudly + pin" the honest contract.)*

## Phase 4 — Resolve siblings + regression

- [x] **Step 7** — Resolve every finding from Phases 0/2/3 (fix-or-flag), then run
  the regression set: the W3C SPARQL *update* suite
  (`-Dprolly.compliance.skip=false`), `MainMethodTests`, the GC + reachability
  tests, and the `-Dprolly.concurrency.skip=false` Lincheck/OCC tier — all green.
  *(Done 2026-06-01. All findings resolved: the data-commit + merge/cherryPick/revert
  flush windows are **FIXED** (read-lock-wrapped); the aux-root reachability is
  **documented + pinned** (latent, GC unwired). Regression: **94/94** in the upstream module
  — `MainMethodTests` (78), the GC boundary (3) + reachability (1) + simulation
  tests, and the commit/merge/LCA/blame/snapshot properties. The changes are
  **additive overloads + read-lock wraps** (no semantic change to commit/merge
  results), so the gated W3C-update + Lincheck/OCC tiers are unaffected by
  construction — not re-run this session, noted.)*

## Phase 5 — Lock-in

- [x] **Step 8** — The batteries become the permanent gate. Update
  the upstream test-strategy plan's Step 22 (bug closed/flagged), the memory
  `gc-concurrent-write-flush-window` (FIXED or refined), and `TESTING.md` (the GC↔
  write + reachability batteries + how to run them). Every fixed repro is enabled;
  every still-flagged finding has a confirmed `@Disabled` repro + a follow-up.
  *(Done 2026-06-01. Memory `gc-concurrent-write-flush-window` updated to FIXED (data
  + merge/cherryPick/revert paths) with the aux-root reachability documented as a
  latent constraint. The boundary (`GcConcurrentWriteBoundaryTest`, 3) + reachability
  (`GcRootReachabilityTest`, 1) tests are the permanent default-`mvn test` gate — no
  `@Disabled` remains. **Plan COMPLETE.**)*

## Behavior-spec contracts (do not break)

| Contract | Source | Why it's load-bearing |
|---|---|---|
| `concurrentCommitMustNotLoseChunksToGc` enabled + green | `GcConcurrentWriteBoundaryTest` | The fix's acceptance gate; re-`@Disable` = regression |
| GC preserves every persisted root (data + aux) | the Phase-3 reachability battery | A GC that sweeps a live index is data loss (R-4) |
| gcLock read/write exclusion holds | `gcWriteLockExcludesReadLockDuringMarkSweep` | The mechanism the fix relies on |
| No lost update among concurrent commits | `DatabaseCommitOccTest` (Lincheck) | The fix must not weaken OCC linearizability |
| W3C SPARQL update result unchanged | the W3C update suite | A safer write path must not change data |
| `MainMethodTests` 78/78 | the `main()`-method driver | The fix touches `Database`/`GarbageCollector` core |

## Out of scope (intentionally)

- **Incremental / generational GC** — a performance redesign; this plan keeps the
  stop-the-world `gcLock` model and only closes the holes in it.
- **A full concurrency redesign / the multi-actor DST capstone** — the test-strategy
  plan's deferred capstone, separate.
- **Non-GC-boundary bugs** the batteries happen to surface — logged to memory, not
  chased in this plan.
- **Bit-compat / on-disk format changes** — the fix is read-lock placement + GC
  mark roots, not a format change.
