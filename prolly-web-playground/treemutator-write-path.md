# TreeMutator — the write path through the layers, and why an insert only touches its spine

The module-level deep-dive on `TreeMutator.applyMutations` — the structural engine
every write goes through. The read sibling is
[`cursor-read-path.md`](cursor-read-path.md) (point, rank, and range over the counted
tree). Companion to [`class-roles.md`](class-roles.md) (the class
index); the narrative newcomer walks are
[`docs/anatomy/B2-a-write.md`](../docs/anatomy/B2-a-write.md)
(one concrete write end-to-end) and
[`docs/anatomy/B1-a-chunk-boundary.md`](../docs/anatomy/B1-a-chunk-boundary.md)
(the boundary decision itself). This doc adds the layer map and the fast-forward
(synchronize-then-skip) mechanics, grounded in the current source (read 2026-07-06).

An **interactive companion** lives beside this file:
[`write-path-explorer.html`](write-path-explorer.html) — open it in any browser
(a self-contained *set*: keep the five sibling scripts beside it —
[`core`](write-path-explorer.core.js) (the pure sim, spec-extracted) /
[`state`](write-path-explorer.state.js) / [`render`](write-path-explorer.render.js) /
[`controls`](write-path-explorer.controls.js) / [`app`](write-path-explorer.app.js),
loaded in that order; `dev-scripts/bundle_explorer.py` emits a single-file
bundle for publishing). It chunks with a real BuzHash rolling over serialized entry
bytes (toy-scaled: 8-byte window vs the engine's 67), lets you insert/update/delete
against a live tree, renders the before/after reachable-set diff — the rewritten
spine — per commit (the leaf ribbon pairs its scrolling detail row with an
always-fully-visible **minimap**: one segment per leaf, width ∝ keys, the
same amber/cyan status language, a draggable viewport window — so the write
spine's locality stays visible at any tree size), and includes a **boundary lens** showing the rolling hash byte
by byte (min-zone, trigger, latch, cap; boundaries born/kept/died across a write)
(the lens grew four instruments of its own: a **⚡ what-if mode** — click any
byte and the SAME stream replays with that one byte changed, ghost ticks
showing which boundaries held, which moved, and where the stream resyncs —
content-defined chunking's locality claim, demonstrated per byte; a
**chunk-length ruler** with the geometric-expectation tick on each band;
its own **minimap**; and **cross-view links** — hovering a byte lights its
chunk in the ribbon and tree, clicking a ribbon chunk frame scrolls the lens
to its bytes) plus a **node store** panel — the content-addressed pool banded by birth commit,
with a real reachability-sweep garbage collection that prunes the commits whose
roots it drops (the space↔history trade, live).
Its node test measures the flat-cost claim: one insert writes 5, 5, and 8 chunks at
n=16, 48, 96 — tracking tree height, not key count. (In **real engine** mode the
**⏱ write bench** button measures the same claim on the actual engine: N single-key
insert+flush ops timed per-op server-side, nodes-written-per-op ≈ the spine — and
afterwards every bench key is deleted and the root hash must return **byte-identical**,
history-independence asserted live on every run.) The **leaf-level
fast-forward walk is simulated** (skip-by-reference / open-and-merge / resync;
the write trace's L0 steps come from it) and differential-pinned to the
from-scratch build across seeds, sizes, and edit patterns. (An earlier per-key-hash version
of the model measured 3/3/3 here; corrected 2026-07-06 when chunking moved to the
byte-window splitter.) The chunking config (avg/seed) is pinned to the history at genesis — the controls
apply on Rebuild only, since the config is part of the format; a **Prove
convergence** button replays the same keys in a random insertion order on a scratch
replica and shows the byte-identical root (history-independence, live); and the commit history renders
as a true **DAG graph** — lanes per branch, curved lane-jump edges, the selected
commit's ancestry highlighted, pruned commits dashed — with **named branch refs**
(the manifest, drawn): chips on the tips to check out or delete, "Branch here" to
name a detached position, the checked-out ref advancing on write, and a
keep-branch-tips garbage-collection policy so delete-then-sweep abandons a
branch's commits exactly the way a real ref deletion does.
The instrument also covers **diff** (shift-click any commit to pin a diff base;
the panes recolor against it and a diff-walk trace shows every subtree skipped
UNREAD by hash equality — O(changed) made visible), **merge** (three-way against
the common ancestor, with one-sided takes, stated conflict policy, fast-forward
detection, and a two-parent merge commit drawn as a dashed edge), **batch
commits** (a staged textarea editor — one op per line: `k`, `k=v`, `-k`,
comments — with a live tally, per-line errors that block the whole batch
(atomicity as UI), sequential read-your-own-writes validation, and one commit
for N ops), an **18-stop guided tour** covering the whole instrument — writes,
reads/scans/blame, history, merging, the object model, durability, and the
integrity lab (its planted corruption left as stated homework) — light/dark themes over a fully tokenized palette, a **scale card** (the
same math at engine parameters — a billion keys ≈ 5 fetches), **key blame**
(select a key → the commits that changed it, clickable), **ordinal seek** (the
subtree-count vector driving an Nth-key descent; out-of-range answered in one
fetch — size queries are O(1)), a **range scan** (one descent + leaf hops via
the parent stack, ending on the `TreeIter`-style stop predicate — the first key
past the end stops the walk with no foreknowledge of the last key; a prefix
over ordered keys IS such a range), and a **chunk-size histogram** with the
geometric expectation overlaid (the "probabilistic" in probabilistically
balanced, measured — plus the avg-live-leaf-fill vs the target), and a
**writer race** (two writers build against one snapshot; one compare-and-set
wins, the loser replays on the new base — linear history, no fork, and the
losing attempt lands in the store as a labeled abandoned-work band, sweepable). The stats row
leads the stage: chunks + BYTES written (preimage lengths — the real cost
currency), sharing, height, dedup, and a **sweepable gauge** computed from the
same keep-set as the sweep button, so it predicts the deletion count exactly;
the cost chart overlays total-tree-size as a contrast line — the tree climbs
while the written bars stay flat. **Commits are Merkle objects in the same store**: each
commit is minted as a chunk — hash(root + parent hashes + message) — shown as a
⋄ tile in its store band, browsable through the inspector (root and parent
links are Merkle edges), with the stated policy that the sweep treats parent
links as weak references (shallow, per-snapshot retention). **Tag objects**
complete git's object quartet: immutable, content-addressed pins (⊙ chips on
the graph, dashed-green tiles in the store) that retain their commit through
every sweep policy — deleting the name frees the object for the next sweep.
And the store is **verifiable**: every object's canonical preimage is
reconstructable from the object alone (internal nodes carry their child keys),
so "Verify store" re-hashes everything against its own names, "Corrupt a
chunk" plants a durable lie, and any read descending through it halts with the
mismatch (verify-below-the-cache — the name IS the checksum). Persistence is **CAS-first over IndexedDB**: the chunk store holds the truth
(per-object hash-keyed records, inspectable in devtools), while the meta
persists only the mutable surface — refs by hash, a reflog of commit hashes
(order only: the message is hashed content living solely in the commit object,
so a swept ghost keeps nothing but its name), selection, and the world config. Boot *reconstructs* the whole view by
walking the Merkle objects (parents by hash, entries read from the trees,
written sets as reach-diffs); a swept commit comes back as a pruned reflog
ghost, label intact — git's model exactly. Commit identity displays as the
short content hash everywhere. Rebuild resets the world; Reset storage deletes
the database file, arm-then-confirm. **File-based backup** rounds it out:
Export pack opens a modal showing the pack — refs + reflog + every chunk as
one pretty-printed, syntax-highlighted JSON document (escaped before it
touches `innerHTML`; a pack can carry arbitrary strings) — with Download and
Copy actions that both emit exactly the displayed text: what you see IS the
pack, one source of truth for eyes, clipboard, and disk. Import dedups
by name, re-hashes every incoming object at the boundary (tampered bytes are
REFUSED — a pack is untrusted input), and switches worlds without deleting
anything — the old world's chunks simply become sweepable. Behavior is
pinned by
the upstream monorepo's Playwright net (`write-path-explorer.spec.ts` (46 browser specs,
`file://`-loaded, no server) plus
`write-path-explorer-core.spec.ts`, 11 node-side invariants — determinism,
lens-equals-tree, height-tracking insert cost, shuffled-order convergence,
read-your-writes over every key in O(height) fetches, the counted-B-tree
contract (counts consistent + in the preimage + rank routing in exactly
path-length fetches), empty edges — extracted from the shipped page at test
time so the net can't drift from the model). Internal nodes model the
engine's **subtree-count vector**: per-child counts live in the node's bytes
(so a tampered count fails the re-hash like any other tamper — and a swept
child's count survives, because it lives in the parent), while the accessor
exposes cumulative prefix sums (the `Node.getSubtreeCount` contract) — which
is what makes the ordinal-seek trace honestly O(height): the descent routes
on the parent's own vector and never opens a skipped child. The count
vector's arrival was a format break (internal preimages changed → every
internal name changed): pre-v3 persisted worlds are wiped with the lesson
narrated, and v1 packs are refused — the pre-1.0 no-backwards-compat rule,
enacted in miniature. A
**Read (point lookup)** control walks the read path too — animated: a cursor dot
travels the descent while every algorithm step (node fetch, each maxKey
comparison's skip/descend, the leaf scan, the verdict) logs into a scrubbable
stepper (⏮ ▶ ⏭, honoring reduced-motion); early exit shows as a one-fetch stop,
and reading an old commit demonstrates snapshot reads — same walk, older root,
older value. Every write replays through the same stepper as a **write trace**:
the new chunks start veiled and reveal bottom-up as the log narrates each
level's three zones (skipped-by-reference / re-chunked / resynced), the new
root, the commit mint, and the manifest pointer swap as the finale — the zones
computed honestly from the real before/after trees (per-level hash
prefix/suffix agreement), not scripted. Remaining divergences vs the engine are named in the page footer.

## Part 1 — The layer stack for a write

Every write, from any module, funnels through the same seven layers:

```
L6  Manifest / Commit          "the pointer swap" — the ONLY mutation in the system
L5  NodeStore.write            bytes → content hash (truncated SHA-512); idempotent, dedups
L4  FlatbufferNodeSerializer   items → [MAGIC][VERSION] + ProllyTreeNode flatbuffer
L3  Chunker tower              one Chunker per tree level, built lazily bottom-up
L2  TreeMutator.applyMutations base tree + SORTED edit stream → new root
L1  MutableMap                 buffered overlay: stage puts/deletes, flush() sorts
L0  application verb           an upstream store's put / addStatement, merge, revert…
```

The key inversion to internalize: **layers L2–L5 never modify anything**. They only
*produce new chunks*. The entire concept of "mutation" is compressed into L6 — a
branch name in the `Manifest` re-points from the old root hash to the new one.
Everything below is pure construction of immutable, content-addressed data. That is
why concurrent readers never lock against writers (a reader holding the old root
sees a consistent snapshot forever), and why the garbage-collection contract in
`TreeMutator`'s `@apiNote` is the one coordination point: chunks written during the
build must not be swept before the pointer swap lands (the caller holds the
garbage-collection read lock across the call — see
`bugs/gc-concurrent-write-flush-window.md`).

Two contracts at the L2 boundary matter more than anything above them:

- **The edit stream must be sorted ascending by key.** `applyMutations` throws on an
  out-of-order key and takes the last value on duplicates. This is why L1 exists:
  `MutableMap` buffers arbitrary-order application writes and hands them down
  sorted. An unsorted stream would silently corrupt the tree, so the contract is
  enforced, not trusted.
- **A null value is a tombstone.** Delete is not a separate code path: `applyOne`
  advances to the key (consuming the old entry) and simply emits nothing when the
  value is null.

## Part 2 — Step by step through `applyMutations`

Take a 3-level base tree and one edit at key `K`.

**Step 1 — setup.** `store.beginWriteBatch()` opens one write batch for the whole
build — safe because the build only *reads* the old tree and only *writes* new
chunks, never reading what it just wrote (a mid-build failure leaves only harmless
content-addressed orphans; `endWriteBatch` runs in a `finally`). A level-0 `Chunker`
is created holding a `Cursor` at the start of the base tree. The `Chunker` is the
heart: a `RollingHashSplitter`, a `pending` item list, and a lazily-created `parent`
Chunker — the tower of chunkers mirrors the tree's levels but only materializes
upward as boundaries are crossed.

**Step 2 — `advanceTo(K)`: reach the edit point without re-emitting everything.**
A second cursor `next` is seeked directly to `K`, then `advanceToCursor(next)` runs
the **synchronize-then-skip** loop — the fast-forward machinery ported from Dolt's
chunker (ADR-0068, `plans/tree-write-fast-forwarding.md`):

1. Re-emit old entries through `append()` until a **freshly-built chunk boundary
   aligns with an old node's end** (`split && cur.atNodeEnd()`).
2. At that alignment, everything from here to the edit point is provably unchanged
   *as whole chunks* — so don't touch it: advance the **shared parent cursor** and
   recurse `advanceToCursor` **one level up**, where the parent chunker skips entire
   subtrees by reference (its items are `(key, childHash, subtreeCount)` pointers,
   not contents).
3. Jump the leaf cursor to `next` and re-emit only the **prefix of the one node
   containing `K`** (`processPrefix` — within a single node by construction).

If no boundary aligns before reaching `K`, the prefix was simply re-emitted — the
slow-but-always-correct fallback (D-5 of the fast-forwarding plan).

**Step 3 — the edit itself.** `advanceTo` consumed the old entry at `K` if one
existed (update/delete); `put(K, value)` appends the new value into `pending`. From
the splitter's perspective the edit is just bytes flowing through like any other.

**Step 4 — boundary handling.** Every appended item feeds the
`RollingHashSplitter`. When it declares a boundary, `handleChunkBoundary` serializes
`pending` (L4), writes it (L5) to get its hash, then
`appendToParent(lastKey, childHash, subtreeCount)` — lazily creating the parent
Chunker **seeded with the old tree's parent-level cursor**, so the parent can itself
fast-forward. This is how the tower grows: leaves produce internal-node items,
recursively upward. One load-bearing guard here: an internal node is never emitted
with a single child — without that, one adversarial ~16 KiB key would cascade
single-child nodes upward forever, a denial-of-service on the core write path
(pinned by `DegenerateInternalNodeGuardTest`; ADR-0069).

**Step 5 — `done()`.** `finalizeCursor` drains the suffix after the last edit with
the same trick in mirror image: re-emit until a new boundary aligns with an old node
end, then advance the shared parent cursor so the *parent's* finalize skips the rest
of the right side by reference. The final `pending` run becomes the last chunk,
recursing up until the top-level chunker returns the new root `Node`. (The root
chunk is always persisted — the old skip-on-single-chunk path once meant small trees
never reached disk; `store.write` is idempotent so the extra write dedups.)

## Part 3 — The insert, replayed: why only the modified chunks are rewritten

The deep question: *what makes chunk boundaries stable under an insert at all?*

**A boundary is a pure function of a small window of local content.**
`RollingHashSplitter` rolls a BuzHash over a **67-byte window**; a boundary fires
when the windowed hash matches a bit pattern, checked only between a 512-byte
minimum and a 16 KiB hard cap. Whether a boundary exists at a byte position depends
only on the ~67 bytes before it plus the distance from the previous boundary — not
on tree history, insertion order, or anything far away.

Walk an insert of key `K` through the three zones of the leaf level:

- **Zone 1 — everything left of `K`'s chunk.** Untouched bytes → identical boundary
  decisions → byte-identical chunks → identical hashes. The fast-forward never even
  deserializes them; it skips them by reference at the highest level where whole
  subtrees are unchanged. Zero new chunks written.
- **Zone 2 — the neighborhood of `K`.** The insert shifts bytes inside `K`'s chunk,
  perturbing its boundary decisions: typically that one chunk rebuilds (perhaps
  splitting, perhaps swallowing its neighbor's start). The perturbation propagates
  rightward only until a new boundary lands exactly where an old one was — and
  because the boundary is content-local, resynchronization is fast (usually within a
  chunk or two). That is the `split && cur.atNodeEnd()` alignment test: "my new
  chunk just ended exactly where an old node ended → everything after is unchanged."
- **Zone 3 — everything right of the resync point.** Skipped by reference through
  the parent, mirror of zone 1 (`finalizeCursor`'s right-side fast-forward).

The same three-zone story then repeats **one level up**: the parent node holding
pointers to zone-2's rebuilt chunks must itself rebuild (its child hashes changed),
while its siblings resync-and-skip; and so on to the root. Net effect: an insert
rewrites **the root→leaf spine touching the edit plus a bounded resync neighborhood
per level** — O(log n) chunks. Measured flat across 16× history growth by
`TreeMutatorFastForwardComplexityTest`.

Two properties make this trustworthy rather than merely fast:

1. **Convergence** — a fast-forwarded build yields the *byte-identical* root to a
   from-scratch build of the same data (`TreeMutatorFastForwardDifferentialProperty`).
   The skip has no observable effect; boundaries are deterministic functions of
   content, so both paths must agree.
2. **Content-address dedup as the safety net** — where the resync neighborhood
   re-emits a chunk that turns out identical, `store.write` is idempotent on the
   hash; no duplicate hits disk.

And this invariant is what the rest of the system cashes in everywhere else:
`DiffEngine` walks only where hashes differ (an O(changed) diff), `MergeEngine`
recurses only where both sides diverged from base, and sync ships only the chunks
the receiver's Merkle closure is missing. The insert-only-touches-its-spine behavior
is not a write optimization — it is the architecture's foundational invariant.

To watch it happen: build a tree, insert one key, and diff the two trees'
`ReachabilityWalker` closures — the set difference *is* the rewritten spine, and its
size stays roughly constant as the tree grows.

## Where this lives

- `dolthub-java-port/src/main/java/com/dolthub/prolly/TreeMutator.java` — the engine
  (`applyMutations`, the inner `Chunker`, `advanceTo`/`advanceToCursor`/
  `processPrefix`/`finalizeCursor`).
- `dolthub-java-port/src/main/java/com/dolthub/prolly/RollingHashSplitter.java` — the
  boundary rule (window, minimum/maximum sizes, the ramp).
- `dolthub-java-port/src/main/java/com/dolthub/prolly/MutableMap.java` — the L1
  staging overlay that delivers the sorted stream.
- the upstream monorepo's ADR-0068 (tree-write re-emit + fast-forwarding) +
  `dolthub-java-port/plans/tree-write-fast-forwarding.md` *(private monorepo work tracker)* — the fast-forwarding
  decision + restoration plan.
- [`../docs/adr/0069-chunker-internal-node-constraints.md`](../docs/adr/0069-chunker-internal-node-constraints.md) — the
  single-child cascade guard.
- [`docs/anatomy/B1-a-chunk-boundary.md`](../docs/anatomy/B1-a-chunk-boundary.md), `B2-a-write.md` — the narrative
  walks.
