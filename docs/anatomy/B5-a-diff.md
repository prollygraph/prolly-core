---
tags:
  - versioning
---
# Anatomy of a diff

*Comparing two tree versions by skipping what the hashes already say is equal.*

> **What you'll learn** — how `DiffEngine` reports what changed between two
> commits: the two Merkle short-circuits that make a near-identical diff cheap,
> the lockstep leaf walk that does the actual comparing, and the buggy earlier
> design that explains why it works the way it does.
>
> _Reading time: ~10 minutes._
> _Prerequisites: [the-prolly-tree](../foundations/the-prolly-tree.md),
> [B3 · a read](B3-a-read.md)._

## 0 · The problem

Two commits, two tree roots. What changed between them?

```java
new DiffEngine(store, descriptor).diff(rootA, rootB, entry -> {
    System.out.println(entry.type() + " " + entry.key());   // ADD / MOD / DEL
    return true;                                             // false would stop early
});
```

The result is a stream of `DiffEntry`s — `ADD`, `MOD`, `DEL` — at the key
level. The question that matters: can this be done in time proportional to
*what changed*, rather than to how big the data is?

## 1 · Why the prolly tree can do better

Diffing two arbitrary datasets is `O(N)` — you must look at everything. But two
*prolly trees* are not arbitrary: from [the prolly tree](../foundations/the-prolly-tree.md),
**equal content ⇒ equal hash ⇒ equal bytes**. If two pieces of two trees hash
the same, they are byte-identical and there is provably nothing to report
inside them. `DiffEngine` turns that into two short-circuits.

## 2 · The whole-tree short-circuit

The first check, before any walking:

```java
if (rootA != null && rootB != null
        && Arrays.equals(rootA.bytes(), rootB.bytes())) {
    return;   // identical roots → zero differences
}
```

Two commits with the same root hash *are* the same data — the diff is empty,
and it costs one comparison to know it. Comparing a commit to itself, or any
two commits that happen to hold identical data, is `O(1)`.

## 3 · The lockstep leaf walk

When the roots differ, `DiffEngine` opens a `Cursor` at the **start of the leaf
level** of each tree and advances them together, in key order:

```java
int cmp = compareKeys(keyA, keyB);
if (cmp < 0) {            // key in A, not B
    handler.onDiff(new DiffEntry(keyA, a.currentValue(), null, DiffType.DEL));
    a.advance();
} else if (cmp > 0) {     // key in B, not A
    handler.onDiff(new DiffEntry(keyB, null, b.currentValue(), DiffType.ADD));
    b.advance();
} else {                  // same key in both
    if (ByteUtils.compareUnsigned(valA, valB) != 0)
        handler.onDiff(new DiffEntry(keyA, valA, valB, DiffType.MOD));
    a.advance(); b.advance();
}
```

It is an ordinary sorted-merge: the cursor that is *behind* in key order
advances, emitting `DEL` or `ADD`; when both sit on the same key, the values
are compared for a `MOD`. Both trees are sorted, so one pass suffices.

## 4 · The per-leaf Merkle skip

The merge walk alone is still `O(total keys)`. The second short-circuit is what
makes it cheap — and it fires *inside* the loop:

```java
if (isValid(a) && isValid(b)
        && a.index() == 0 && b.index() == 0
        && a.isLeaf() && b.isLeaf()
        && Arrays.equals(a.node().bytes(), b.node().bytes())) {
    skipLeaf(a);
    skipLeaf(b);
    continue;
}
```

When both cursors sit at the *start* of a leaf and those two leaf nodes are
byte-identical, the entire leaf — every key in it — is provably unchanged.
`skipLeaf` advances both cursors straight past it with **no per-key
comparison** at all.

> **Key idea** — this is the prolly-tree payoff. An unchanged leaf costs *one*
> `Arrays.equals` instead of K key comparisons. Two near-identical trees share
> almost all their leaves ([B2](B2-a-write.md) only rebuilds edit-adjacent
> nodes), so the diff does real per-key work *only around the actual changes*.

## 5 · Why it is built this way — a cautionary tale

A natural instinct is to skip at a *coarser* grain: recurse over internal
nodes, and when two internal nodes have the same hash, skip their whole
subtree. An earlier `DiffEngine` did exactly that — aligning internal children
by their **separator key** (the last key of a child's subtree).

> **The bug** — separator keys are *not stable identifiers*. Insert or delete a
> key near a chunk boundary and the boundary moves, so the separator key
> changes even though almost all the data did not. The separator-aligned
> recursion then **over-reported** every boundary-shifting edit as a cascade of
> spurious diffs — and **crashed outright** when the two trees had different
> heights, because it had no way to align levels that did not line up. The
> leaf-cursor walk above replaced it: it compares *actual keys*, so a shifted
> boundary and a height mismatch are both just ordinary cases.

> **Trade-off** — the consequence is that the current engine skips at exactly
> two grains: the **whole tree** and a **single leaf**. It does *not* skip
> hash-equal internal subtrees mid-tree. So a diff of two huge, near-identical
> trees still walks the leaf spine (one cheap byte-compare per leaf) rather
> than pruning at the top of an unchanged subtree. That is a deliberate choice:
> a correct, simple leaf walk beat a faster recursion that was wrong. (The
> same lockstep-leaf design also powers [B6 · a merge](B6-a-merge.md).)

## Takeaways

- `DiffEngine` reports `ADD`/`MOD`/`DEL` by a lockstep, sorted-merge walk of
  two trees' leaf levels.
- Short-circuit one: identical root bytes → empty diff in `O(1)`.
- Short-circuit two: a byte-identical leaf is skipped wholesale — no per-key
  work — so real work concentrates around actual changes.
- It compares **actual keys**, not separator keys: an earlier separator-aligned
  recursion over-reported boundary shifts and crashed on unequal-height trees.
- The engine skips at the whole-tree and single-leaf grains only — simple and
  correct over a faster-but-wrong internal-subtree recursion.

## Where this lives

- `dolthub-java-port/src/main/java/com/dolthub/prolly/DiffEngine.java` — `diff`,
  the short-circuits, `skipLeaf`
- `dolthub-java-port/src/main/java/com/dolthub/prolly/Cursor.java` — the
  leaf-level walk
- Builds on: [B3 · a read](B3-a-read.md),
  [the-prolly-tree](../foundations/the-prolly-tree.md)
- Continues in: [B6 · a merge](B6-a-merge.md) — three-way merge built on the
  same walk.
