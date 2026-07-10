---
tags:
  - versioning
---
# Anatomy of a merge

*Three trees in, one tree plus a conflict list out.*

> **What you'll learn** — how `MergeEngine` performs a three-way merge: why it
> needs a common ancestor, how it reuses the [diff](B5-a-diff.md) and
> [write](B2-a-write.md) machinery, how it tells a clean merge from a conflict,
> and the null-handling case that was once a real bug.
>
> _Reading time: ~10 minutes._
> _Prerequisites: [B5 · a diff](B5-a-diff.md), [B2 · a write](B2-a-write.md)._

## 0 · The problem

Two branches edited the same tree independently. Reconcile them:

```java
MergeEngine.MergeResult result =
    new MergeEngine(store, descriptor, pool).merge(ancestor, ours, theirs);
// result.root()      — the merged tree
// result.conflicts() — keys both sides changed incompatibly
```

`ancestor` is the commit both branches forked from; `ours` and `theirs` are the
two divergent roots. The output is one merged tree, plus a list of every place
the merge could not decide. Follow `merge`.

## 1 · Three-way, not two

Why three trees? Because two are not enough to know *intent*. If `ours` has key
`K` and `theirs` does not, did *we add* `K`, or did *they delete* it? With only
`ours` and `theirs` you cannot tell — and the wrong guess silently loses data.

The **common ancestor** resolves it. `K` present in the ancestor and absent in
`theirs` → they deleted it. Absent in the ancestor and present in `ours` → we
added it. Every decision below is really "what did each side change *relative
to the ancestor*".

## 2 · Fast-forward short-circuits

Before any real work, `merge` checks the easy cases:

```java
if (ancestor != null && ours != null && theirs != null) {
    byte[] ancHash = HashUtils.hash(ancestor.segment().asByteBuffer());
    if (Arrays.equals(HashUtils.hash(ours.segment().asByteBuffer()), ancHash))
        return new MergeResult(theirs, List.of());      // we didn't change → take theirs
    if (Arrays.equals(HashUtils.hash(theirs.segment().asByteBuffer()), ancHash))
        return new MergeResult(ours, List.of());        // they didn't change → take ours
}
```

If one side is byte-identical to the ancestor, it made no changes — the merge
*is* the other side, with no conflicts possible. This is the **fast-forward**.
It is decided by hashing each root once (`O(node bytes)`), cheaper than
materializing and comparing both trees.

## 3 · Diff each side against the base

When both sides genuinely diverged, `merge` asks the *exact same question*
twice — once per side — and the answer is just a [diff](B5-a-diff.md):

```java
Map<MemorySegment, DiffEntry> ourChanges   = collectChanges(ancestor, ours);
Map<MemorySegment, DiffEntry> theirChanges = collectChanges(ancestor, theirs);
```

`collectChanges` runs `DiffEngine.diff(base, head, …)` and collects the
`ADD`/`MOD`/`DEL` entries into a key-indexed map. So a merge is built directly
on [B5](B5-a-diff.md): *the set of changes each branch made.*

> **Key idea** — a three-way merge is not a special tree algorithm. It is two
> diffs against the ancestor, then a reconciliation of those two change-sets.
> The hard tree work — comparing, rebuilding — is all borrowed.

## 4 · Union the changes, find the conflicts

Now reconcile. Take every key either side touched, and decide per key:

```java
for (MemorySegment key : allKeys) {                       // ourChanges ∪ theirChanges
    DiffEntry ourChange   = ourChanges.get(key);
    DiffEntry theirChange = theirChanges.get(key);

    if (ourChange != null && theirChange != null) {        // both sides touched it
        if (isSameChange(ourChange, theirChange))
            mergedMutations.add(new Mutation(key, ourChange.valueB()));   // convergent — fine
        else
            conflicts.add(new Conflict(key, ourChange.valueA(),           // divergent — CONFLICT
                                       ourChange.valueB(), theirChange.valueB()));
    } else if (ourChange != null) {
        mergedMutations.add(new Mutation(key, ourChange.valueB()));        // only we changed it
    } else {
        mergedMutations.add(new Mutation(key, theirChange.valueB()));      // only they changed it
    }
}
```

The rule set is small:

- **Only one side changed a key** → take that change. No ambiguity.
- **Both sides changed it the same way** (`isSameChange` — same type, same
  resulting value) → take it once. Convergent edits are not conflicts.
- **Both sides changed it differently** → a `Conflict`, recording the base
  value and both sides' values.

> **Gotcha** — `MergeEngine` *detects and reports* conflicts; it does not
> resolve them. The `MergeResult` hands the caller a `conflicts` list with
> base/ours/theirs for each key, and the caller (or a human) decides. A merge
> "succeeding" is not the same as `conflicts()` being empty.

## 5 · Apply and report

The reconciled changes are a sorted list of `Mutation`s — exactly what
[B2](B2-a-write.md)'s `TreeMutator` consumes. The merge applies them *to the
ancestor*:

```java
Node newRoot = new TreeMutator(store, descriptor, pool)
    .applyMutations(ancestor, mergedMutations.iterator());
return new MergeResult(newRoot, conflicts);
```

Applying the union of both sides' changes onto the common base produces a tree
that contains both branches' work. And because `applyMutations` shares every
untouched subtree ([B2](B2-a-write.md)), the merged tree shares nodes with all
three inputs.

> **The bug** — the null/empty-tree cases were once mishandled. A `null` root
> means an *empty* tree, and the instinct is "an empty side contributes
> nothing, keep the other side". That is right only with **no ancestor**. With
> an ancestor, a now-empty side means that side **deleted everything** since
> the fork — and a correct merge must replay those deletions, not ignore them.
> The fix keeps the "keep the other side" shortcut strictly for the
> no-ancestor case and routes an empty-with-ancestor side through the normal
> diff-based path. The lesson: empty is data — "they deleted it all" is a
> change, not an absence.

The upstream store's own merge layer drives this
core engine once per indexed tree to merge a whole branch upstream.

## Takeaways

- A three-way merge needs the **common ancestor** to distinguish an add from a
  delete — two trees alone cannot.
- If one side is hash-identical to the ancestor, the merge fast-forwards to the
  other side.
- The merge is **two [diffs](B5-a-diff.md) against the ancestor**, reconciled
  key by key: one-sided changes apply; convergent changes apply once; divergent
  changes become `Conflict`s.
- Reconciled changes are applied through [B2](B2-a-write.md)'s `TreeMutator`, so
  the merged tree shares nodes with all three inputs.
- The engine *reports* conflicts, it does not resolve them — and an empty side
  with an ancestor means "deleted everything", not "nothing".

## Where this lives

- `dolthub-java-port/src/main/java/com/dolthub/prolly/MergeEngine.java` — the
  three-way merge, `collectChanges`, `isSameChange`
- `dolthub-java-port/src/main/java/com/dolthub/prolly/DiffEngine.java`,
  `TreeMutator.java` — the reused diff and write machinery
- Builds on: [B5 · a diff](B5-a-diff.md), [B2 · a write](B2-a-write.md)
- This completes the prolly-tree track — back to the index.
