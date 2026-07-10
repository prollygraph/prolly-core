---
tags:
  - storage
  - versioning
---
# Anatomy of a write

*From `MutableMap.put` to a brand-new immutable tree root.*

> **What you'll learn** — how an edit becomes a new prolly tree: how edits are
> buffered, how `TreeMutator` merges a sorted edit stream with the old tree,
> how the `Chunker` rebuilds only the affected nodes and shares the rest, and
> how the levels close up into a new root.
>
> _Reading time: ~11 minutes._
> _Prerequisites: [the-prolly-tree](../foundations/the-prolly-tree.md),
> [B1 · a chunk boundary](B1-a-chunk-boundary.md)._

## 0 · The problem

A caller changes the map and asks for the result:

```java
MutableMap map = new MutableMap(base, store, descriptor, pool);
map.put(aliceKey, aliceValue);
map.delete(bobKey);
StaticMap updated = map.flush();      // a new immutable tree
```

`base` is the existing tree; `updated` must be a new tree reflecting both
edits — without mutating `base`, and without rewriting the nodes the edits
didn't touch. Follow `put` to `flush`.

## 1 · Buffering the edit

`MutableMap` does not touch the tree on `put`. It is an **edit buffer** — a
`SpillableSortedBuffer` keyed by `Tuple` (a sorted in-heap map that spills sorted
runs to disk once a very large transaction crosses a threshold), so edits stay
sorted by key however many there are:

```java
public void put(MemorySegment key, MemorySegment value) {
    edits.put(new Tuple(key), value);
}
public void delete(MemorySegment key) {
    edits.put(new Tuple(key), null);     // a null value marks a deletion
}
```

A `get` checks this buffer first, then falls back to `base` — so the map reads
consistently before a flush. Nothing is built yet; `edits` is just an ordered
list of pending changes.

> **Key idea** — buffering lets many edits be applied to the tree in **one**
> pass. Rebuilding the tree per `put` would be wasteful; the sorted buffer is
> what makes a single merge walk possible.

## 2 · Flush — hand a sorted stream to the mutator

`flush` turns the buffer into a new tree:

```java
public StaticMap flush() {
    if (edits.isEmpty()) return base;                 // nothing changed — same tree

    TreeMutator mutator = new TreeMutator(store, descriptor, pool);
    // (simplified) the real flush drains via edits.merged() — a sorted, closeable
    // iterator over the in-heap tail + any spilled runs; each entry becomes a Mutation:
    var mutationIter = mutationsOver(edits.merged());

    Node newRoot = mutator.applyMutations(base.root(), mutationIter);
    edits.clear();
    return new StaticMap(store, newRoot, descriptor);
}
```

> **Gotcha** — `applyMutations` requires the edit stream to be **sorted
> ascending by key**. The `TreeMap` guarantees that here; a caller feeding the
> mutator directly must too. Unsorted input would corrupt the tree, so the
> mutator throws on any out-of-order edit rather than trusting the caller.

## 3 · Merging edits with the old tree

`applyMutations` runs the `ApplyMutations` algorithm: a **lockstep merge** of
the sorted edit stream with the existing tree. It opens a leaf-level `Chunker`
over a `Cursor` positioned at the start of the old root:

```java
Chunker leafChkr = new Chunker(0, (root == null) ? null : Cursor.atStart(store, root));
```

For each edit, the chunker **fast-forwards** to the edit's key rather than
re-emitting every entry before it. `advanceTo` builds a fresh cursor at the edit
point over the base tree, then re-emits existing entries only until a
freshly-built chunk boundary *aligns* with an old node's end. At that alignment
the run up to the edit is unchanged, so the chunker **skips that whole subtree
by reference** — it advances the shared parent cursor and jumps ahead — instead
of feeding the run through the splitter again:

```java
public void advanceTo(MemorySegment targetKey) {
    // build a cursor at the edit point, then synchronize-then-skip:
    // re-emit until a new boundary aligns with an old node end, then
    // skip the unchanged subtree by reference (advance the parent cursor)
}
public void put(MemorySegment key, MemorySegment value, long subtreeCount) {
    splitter.append(key, value);
    pending.add(...);
    if (splitter.crossedBoundary()) handleChunkBoundary();
}
```

Each `put` feeds the [`RollingHashSplitter`](B1-a-chunk-boundary.md); when it
signals a boundary, `handleChunkBoundary` closes the node. The upshot: a
single-key edit does `O(log n)` work — it touches only the affected root-to-leaf
spine, not the whole tree (see
ADR-0068).

> **Key idea — structural sharing happens *here*.** An unchanged subtree is
> shared **by reference**: the fast-forward skips it entirely, so its nodes are
> never re-read or re-emitted — the new tree simply *points at the old subtree
> root*. The short prefix that *is* re-emitted (up to the first aligned boundary)
> sees the *identical* byte sequence it saw at first build, so it reproduces the
> *identical* boundaries and content hashes; `store.write` on those chunks is a
> no-op because the content-addressed chunk already exists. Only the nodes within
> a splitter window of an actual edit are genuinely rebuilt.

## 4 · Closing the levels into a root

`handleChunkBoundary` serializes a completed node, stores it, and promotes its hash to
the parent level:

```java
private void handleChunkBoundary() {
    byte[] nodeBytes = serializer.serialize(level, pending);
    byte[] hash = store.write(nodeBytes);          // content-addressed write
    ensureParent();
    parent.put(pending.get(pending.size() - 1).key(),   // last key as the separator
               MemorySegment.ofArray(hash), ...);       // child hash as the value
    pending.clear(); splitter.reset();
}
```

The parent is *itself* a `Chunker`, one level up — its "entries" are
`(lastKey → childHash)` pairs, and it runs its own splitter (with its own
[level salt](B1-a-chunk-boundary.md)). So the tree builds bottom-up: leaves
close into a level of internal nodes, which close into the next level, until
`done()` finds a level with a single chunk — **that chunk is the new root**:

```java
byte[] hash = store.write(nodeBytes);
if (parent == null) return Node.fromBytes(MemorySegment.ofArray(nodeBytes));   // the root
```

> **The bug** — `done()` once *skipped* the `store.write` on the `parent == null`
> path. For a small tree that fit in a single chunk, the root node was returned
> but **never persisted** — the tree existed in memory and vanished on reload.
> The fix is the unconditional `store.write` above; it is content-addressed and
> idempotent, so writing a chunk a later `parent.put` also references is a
> harmless duplicate. The lesson: the single-chunk tree is a real case, not a
> degenerate one — test the small input.

## 5 · The result — a new immutable root

`applyMutations` returns the new root `Node`; `flush` wraps it in a fresh
`StaticMap`. `base` is **untouched** — still a valid, complete tree at its old
root. The new tree shares every node the edits didn't reach. Two full versions
of the data now exist, for the storage cost of the handful of nodes within an
edit window.

## Takeaways

- `MutableMap` buffers edits in a sorted `SpillableSortedBuffer` (in-heap, with
  disk spill for very large transactions); `delete` is a `null`-valued put. The
  tree is untouched until `flush`.
- `flush` hands a **sorted** edit stream to `TreeMutator.applyMutations`, which
  lockstep-merges it with the old tree.
- Unchanged subtrees are shared **by reference** — the fast-forward skips them,
  never re-emitting them; only the short prefix up to a boundary alignment is
  re-emitted, and those content-addressed chunks dedupe on write. A single-key
  edit is `O(log n)`, touching only the affected spine.
- Completed nodes are content-addressed-written and promoted level by level
  until a single-chunk level — the new root.
- The old root stays valid; the write is non-destructive and copy-on-path.

## Where this lives

- `dolthub-java-port/src/main/java/com/dolthub/prolly/MutableMap.java` — the
  edit buffer, `put`/`delete`/`flush`
- `dolthub-java-port/src/main/java/com/dolthub/prolly/TreeMutator.java` —
  `applyMutations`, the `Chunker`, `handleChunkBoundary`, `done`
- `dolthub-java-port/src/main/java/com/dolthub/prolly/StaticMap.java` — the
  resulting immutable tree
- Builds on: [B1 · a chunk boundary](B1-a-chunk-boundary.md)
- Continues in: [B3 · a read](B3-a-read.md) — reading a key back out of the
  tree just built.
