---
tags:
  - versioning
  - memory
---
<!-- provenance: exported 2026-07-24 from the private monorepo's newcomer-docs/foundations/structural-sharing-and-churn.md; citations adapted to this repo -->

# Structural sharing and churn

*Why editing one row in a billion-row tree rewrites a handful of nodes, not a billion — and why that's a law, not an optimization.*

> **What you'll learn** — what "structural sharing" means concretely, why a
> single edit forces a *path rewrite* from the touched leaf up to the root
> (and nothing else), why an edit produces only **O(height) ≈ O(log n)** *new*
> nodes — cheap history *storage* — rather than O(n), why the write *path* also
> does only **O(log n)** work (fast-forwarding, restored 2026-06-24 — it skips
> unchanged subtrees by reference; see the note below), and the
> classic testing trap of measuring churn as a
> *fraction of size* instead of a path length. This is the property that
> makes "Git for data" affordable.
>
> _Reading time: ~10 minutes._

> **Prerequisites** —
> [the-prolly-tree](the-prolly-tree.md) (content addressing, immutability),
> [the-on-disk-format](the-on-disk-format.md) (`Node`, leaf vs internal).

## Why it matters

A prolly-port commit isn't a copy of the database — it's a new **root** that
re-uses almost all of the previous commit's nodes. That reuse, **structural
sharing**, is the entire reason you can keep a million commits of a huge
dataset without a million copies of it. If you understand *which* nodes a
change forces to be rewritten and which are shared, you understand the cost
model of every write, branch, and merge in the system.

The short version: **an edit rewrites exactly the path from the changed leaf
to the root, and shares everything else.**

## The mechanic: content addressing forces a path rewrite

Recall from [the-prolly-tree](the-prolly-tree.md): every node's *address is
the hash of its own bytes* (`HashUtils.hash`, SHA-512 truncated to 20 bytes).
A **leaf** holds key→value entries; an **internal** node holds nothing but the
**hashes of its children** — that's how it points at them
(`Node.getValue(i)` on an internal node returns the i-th child's 20-byte
hash, not a value).

Now watch what one edit does. Take this 3-node tree — one root over two leaves:

```
BEFORE                              root's bytes contain:  [ hash_A , hash_B ]
            root  (addr R)
           /            \
   leafA (addr A)     leafB (addr B)
   {apple: 1}          {cherry: 3}
```

Change `apple`'s value from `1` to `2`:

```
AFTER
            root' (addr R')         root' bytes contain:  [ hash_A' , hash_B ]
           /            \                                          ↑ changed
   leafA' (addr A')   leafB (addr B)   ← SAME node, shared
   {apple: 2}          {cherry: 3}
```

Step by step:

1. `leafA`'s contents changed → its bytes changed → **its hash changed** (`A → A'`). You cannot change the data and keep the hash; that's what a hash *is*. So there is now a *new* node, `leafA'`.
2. The root's bytes literally embed `hash_A`. That embedded hash must now read `A'`. So the **root's bytes changed → the root's hash changed** (`R → R'`). New node, `root'`.
3. `leafB` was never touched → identical bytes → identical hash → `root'` re-references **the very same `leafB`**. Shared, for free.

Of the 3 original nodes, the new version reuses **1** (`leafB`) and creates
**2** (`leafA'`, `root'`). In a taller tree the chain in step 2 continues:
every node on the path from the edited leaf up to the root must be reborn,
because each one embeds the (now-changed) hash of the child below it. That
mandatory chain is the **path rewrite**.

> **This is not an optimization the engine *chose* — it's forced.** Content
> addressing makes it physically impossible to change a leaf without changing
> every ancestor's hash. The engine's job is only to make sure it rewrites
> *nothing more* than the path.

## The cost law: O(height), not O(size)

The number of nodes a single edit *must* rewrite equals the length of that
root→leaf path, which is the tree's **height + 1**. The height of a prolly
tree is **logarithmic** in the number of entries (it's a balanced-ish B-tree;
the [chunker](the-prolly-tree.md) keeps fan-out healthy). So:

| Tree size (nodes) | Height (~log₂ n) | Nodes rewritten by one edit | Nodes shared |
|---|---|---|---|
| 3 | 1 | **2** | 1 (33%) |
| ~1,000 | ~10 | ~11 | ~99% |
| ~1,000,000 | ~20 | ~21 | ~99.998% |

This is the whole value proposition. A one-row change to a billion-row table
writes ~30 new chunks (a few kilobytes), not a billion. A new commit shares
all but a log-sized sliver with its parent — which is why branching and
time-travel are cheap, and why `diff` between two commits can skip entire
subtrees whose root hashes match (covered in [the-prolly-tree](the-prolly-tree.md)).

`TreeMutator.applyMutations` realizes the **storage** half of this: the new tree
references every untouched subtree by its (unchanged) hash, so only a path's
worth of *new* chunks reach disk.

> **The write *path* is `O(log n)` too (fast-forwarding, restored 2026-06-24).**
> The table above counts *new nodes* — the **disk delta**, always `O(log n)`. The
> write *time* now matches: `TreeMutator.Chunker.advanceTo` **skips unchanged
> subtrees by reference** (synchronize-then-skip, ported from Dolt's chunker), so a
> single-key edit touches only the affected root→leaf spine — `O(log n)` work,
> measured flat across 16× history (`TreeMutatorFastForwardComplexityTest`).
> Convergence is preserved byte-for-byte (`TreeMutatorFastForwardDifferentialProperty`
> + the Merkle convergence/determinism stress tests).
>
> *History (why this note exists).* Fast-forwarding had been **removed** for a
> while: skipping bytes desyncs the rolling-hash splitter and breaks chunk-boundary
> convergence, so the engine fell back to **re-emitting every entry** — `O(n)` write
> *time*, an `O(n²)` commit-per-change loop. Restoring it turned out to need **no
> encoding tag**: the skip-safe boundaries are *derived* from structure (re-emit
> until a new boundary aligns with an old one, then skip via the parent cursor). See
> **ADR-0068** (Update, 2026-06-24) and the commit-latency build-log. The *storage*
> law in this section never changed; only the write *time* did — `O(n)` while
> fast-forwarding was gone, `O(log n)` again now.

## The testing trap: "fraction reused" is the wrong yardstick

When we added a property test for this invariant
(`InvIntegrityProperty`), the first version asserted the intuitive-sounding:

> "after a single-key edit, at least **half** the nodes should be reused."

The intent was right — catch a regression where the engine dumbly rebuilds the
whole tree. But the assertion measured the wrong quantity, and a property-based
test (jqwik) shrank it to a **minimal failing case: a 3-node tree**, where the
engine reused 1 of 3 (33%) — *below half*, yet **provably optimal** (you just
saw why 2 of 3 must change).

The bug was in the *test's model*, not the engine:

- The amount that *must* change is the **path length = O(height) ≈ O(log n)**.
- "Half" is a **fraction of the size (n)**.
- Those agree only when `n` is large (height is a tiny slice). At small `n`,
  the height is a *large* fraction of the tree, so the forced churn
  legitimately exceeds half.

The fix asserts the real law — churn bounded by height, not by a fraction:

```java
// new nodes created by a single-key edit ≈ the root→leaf path length
int heightBound = (int) (4 * (1 + Math.ceil(Math.log(Math.max(2, sA.size())) / Math.log(2))));
assertTrue(newNodes <= heightBound, ...);
```

— from `InvIntegrityProperty`. This passes at *both* ends (a 3-node tree: bound
≈ 10, actual 2; a 10k-node tree: bound ≈ 57, actual ~11) but a whole-tree
rewrite (10k new nodes) blows past 57 and fails — exactly the regression we
wanted to catch.

> **Gotcha — value *length* can shift a chunk boundary.** The clean
> "only the path changes" picture assumes the edit doesn't move a
> [chunk boundary](the-prolly-tree.md). The boundary is content-defined
> (a rolling hash over the bytes), so changing a value's *length* can split
> or merge a leaf and ripple to a neighbour, rewriting a few extra nodes. The
> test sidesteps this by editing a value *in place* (XORing one byte, same
> length) so boundaries can't move; the `×4` slack in the bound absorbs the
> general case.

> **Trade-off — sharing is per-*node*, not per-*entry*.** Editing one entry
> rewrites the whole leaf it lives in (a leaf holds ~hundreds of entries),
> not just that entry. So the unit of churn is a *chunk*, sized 512 B–16 KB
> by the chunker. That's a deliberate space/CPU trade: bigger chunks mean
> fewer, cheaper hashes but coarser sharing.

## The takeaway

The *storage* cost of any change is the depth of the change, not the size of the
data — the new chunks, the disk a commit adds. "1 of 3 reused" and "999,979 of
1,000,000 reused" are the *same law* seen at different scales. When you reason
about what a write, a branch, or a commit *stores*, count the **path to the
root** — that's what got rewritten; everything else was shared. (The write
*time*, as of 2026-06-24, is the depth too — the engine fast-forwards, skipping
unchanged subtrees by reference; see the note above and ADR-0068.)

## Where to go next

- [the-prolly-tree](the-prolly-tree.md) — the structure these nodes form, and
  how `diff`/`merge` exploit shared subtrees via matching root hashes.
- [the-on-disk-format](the-on-disk-format.md) — what's actually in a leaf vs an
  internal node's bytes (the child-hash array that drives the path rewrite).
- [anatomy/B2-a-write](../anatomy/B2-a-write.md) — a single write traced through
  `TreeMutator`, where the path rewrite actually happens.
- [anatomy/B4-a-commit](../anatomy/B4-a-commit.md) — a commit as a new shared
  root over mostly-reused nodes.
- [anatomy/B1-a-chunk-boundary](../anatomy/B1-a-chunk-boundary.md) — how the
  content-defined boundary that the Gotcha warns about is placed.

## Where this lives

- `dolthub-java-port/src/main/java/com/dolthub/prolly/HashUtils.java` — `hash()`: a node's address is the SHA-512/20 of its bytes (content addressing).
- `dolthub-java-port/src/main/java/com/dolthub/prolly/Node.java` — `getValue(i)` returns a child's 20-byte hash on an internal node; the array of child hashes is what forces the path rewrite.
- `dolthub-java-port/src/main/java/com/dolthub/prolly/TreeMutator.java` — `applyMutations()` / `Chunker.advanceTo`: fast-forwards — skips unchanged subtrees by reference (`O(log n)` write *time*; convergence pinned by `TreeMutatorFastForwardDifferentialProperty`, cost by `TreeMutatorFastForwardComplexityTest`). Restored 2026-06-24 (ADR-0068 Update).
- `dolthub-java-port/src/test/java/com/dolthub/prolly/InvIntegrityProperty.java` — the property pinning O(height) churn (and the corrected bound).
