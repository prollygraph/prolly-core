# Cursor — the read path: point, rank, and range over the counted tree

The module-level deep-dive on the engine's read machinery — the sibling of
[`treemutator-write-path.md`](treemutator-write-path.md) (the write path). Where a write
asks *which chunks must be rewritten* (answer: one spine), a read asks *which nodes must
be touched* — and the answer is again a root-to-leaf path, never the tree. Three read
shapes share one primitive: a **`Cursor`** positioned by key, by ordinal, or at an end,
then optionally advanced. The narrative newcomer walk is
[`docs/anatomy/B3-a-read.md`](../docs/anatomy/B3-a-read.md) (one point lookup,
end-to-end); this doc adds the ordinal descent over the counted tree, the leaf-hop
mechanics of a scan, the iterator family, and the costs — grounded in the current source
(read 2026-07-11).

An **interactive companion** lives beside this file:
[`write-path-explorer.html`](write-path-explorer.html) (best served —
`mvn -pl prolly-playground-service spring-boot:run`, then http://localhost:8080/). Its
Read rail runs all three shapes. In **sim** mode the descent animates step by step; in
**real engine** mode the backend runs the actual `StaticMap`/`Cursor` code and returns
the **measured** descent — the store records which node reads it served
(`readPath`, root first) and the pane lights exactly those nodes. Measured, not
re-derived: the panel's claim is "this is what the engine read", so the instrument must
not compute the path a second way. The **⏱ read bench** button measures this chapter at
scale: N point lookups timed per-op *server-side* (engine time, not HTTP), percentiles
and nodes-read-per-op reported — bench at 10k keys, bulk to 1M, re-bench, and watch
latency track the tree's HEIGHT, not its key count.

## Part 1 — the point lookup (`StaticMap.get`)

```java
Cursor cur = Cursor.atKey(store, root, key, descriptor);
if (cur.isValid()) {
    int cmp = descriptor.compare(new Tuple(cur.currentKey()), new Tuple(key));
    if (cmp == 0) return Optional.of(cur.currentValue());
}
return Optional.empty();
```

`Cursor.atKey` walks root → leaf: binary-search the current node for the key's position;
if the node is internal, the entry's *value at that position* is the next child's
content hash — `store.read(childHash)`, parse, descend, remembering the parent. One node
per level, `O(height)` store reads, and the tree is shallow because nodes are big
(512 B–16 KiB holds hundreds of int64 entries per leaf).

Two details that bite:

- **A positioned cursor is not a found key.** On a miss, the in-node binary search
  returns the *insertion point*, and the cursor lands there — valid, pointing at a real
  entry, just not yours. The explicit `compare == 0` is the hit test. (A range scan
  *wants* the insertion point — that's where iteration begins.)
- **The child index is clamped**: `Math.min(index, count - 1)` — a key greater than
  every separator in an internal node must still descend into the last child, not off
  the end.

`atRawKey` is the same walk comparing raw bytes instead of typed fields — usable
because tuple encodings are **order-preserving by design** (byte order = key order;
that property is what the whole tree is sorted by). `CursorAtRawKeyTest` +
`CursorModelProperty` pin the two walks against each other.

## Part 2 — rank: the counted B-tree

Every internal node stores, per child, the **cumulative** entry count through that
child — `Node.getSubtreeCount(i)` is a *prefix sum*, a documented contract pinned by
`SubtreeCountContractProperty` (per-child counts are recovered as deltas by
`Cursor.currentSubtreeSize()`). That vector makes *position* a first-class coordinate:

```java
Node cur = root;
long pos = n;                          // position within cur's subtree
while (!cur.isLeaf()) {
    int i = 0;
    while (pos >= cur.getSubtreeCount(i)) i++;      // first child whose prefix exceeds pos
    pos -= i == 0 ? 0 : cur.getSubtreeCount(i - 1); // re-base into that child
    cur = Node.fromBytes(store.read(cur.getValue(i)).orElseThrow());
}
// pos now indexes the leaf directly: entry #n of the whole tree
```

The Nth key costs exactly what a key lookup costs — `O(height)` reads — and the
children stepped past are **never read**: the prefix sums answer "how many keys are
below?" without touching them. (This loop is
`prolly-playground-service`'s `TreeService.rank`, a compact reference implementation;
the playground's *Seek Nth key* runs it and lights the descent.)

## Part 3 — range scan: one descent, then leaf hops

A scan positions once (`atKey` at `from`) and then walks rightward with
`Cursor.advance()`:

```java
public boolean advance() {
    if (index < node.count() - 1) { index++; return true; }   // within the leaf
    if (parent == null)      { index = node.count(); return false; }  // true end
    if (!parent.advance())   { index = node.count(); return false; }  // no next leaf
    fetchNodeFromParent();   // the parent moved to the next child — enter it
    index = 0;
    return true;
}
```

The parent chain built during the descent *is* the iteration machinery: stepping past a
leaf's last entry recursively advances the parent, which fetches the next leaf. The stop
predicate is "first key past `to`" — no foreknowledge of the last key needed, which is
why a **prefix** over ordered keys is exactly such a range.

**A wart that shipped, told honestly:** `atNodeEnd()` returns
`index == node.count() - 1` — *at* the final entry, still valid, **not past it**. Its
job is the write path's fast-forward test ("this whole run is skippable by reference"),
not loop control. The playground backend's first scan implementation used it as a loop
guard and silently dropped **every node's last key** — a full scan of a 33-key tree
returned 32, missing exactly the maximum. The end-to-end test against the real engine
caught it; a scan-the-tail unit case pins it now. `advance()` returning `false` is the
only correct termination.

## Part 4 — the iterator family

`StaticMap` wraps cursors into `MapIterator`s (`next()`/`prev()`, first call positions):

| call | cursor start | stops when |
|---|---|---|
| `iter()` | `Cursor.atStart` | the tree ends |
| `iterRange(startKey)` | `Cursor.atKey(startKey)` | the tree ends (caller stops at its own bound) |
| `iterPrefix(prefix…)` | `Cursor.atKey(prefix)` | the current key no longer byte-prefixes (`ByteUtils.isPrefix`) |
| `reverseIter()` | `Cursor.atEnd` | the tree begins (`retreat()` mirrors `advance()`) |

All of them are the same primitive with a different start and stop.

## Costs and warts

- **Every read re-descends from the root.** `get` and each iterator construction walk
  the whole path again — there is no persistent path cache (a documented wart on
  `StaticMap`). Cheap in practice (shallow tree, big nodes), but a hot loop of point
  reads pays `height` store reads each time.
- **Caching lives at the store layer, opt-in.** `NodeCache` (bounded, lock-free, byte
  budget) exists, but the core descent is uncached by default;
  `RocksNodeStore.setNodeCache(...)` wires it as a read-through where the store read is
  the actual cost. Cache the layer where the cost lives — a cache in front of cheap
  reads is pure tax.
- **A missing child is corruption, not not-found.** In a content-addressed tree a
  referenced hash *must* resolve; the cursor throws rather than returning empty.
  Read-integrity re-verification is its own decision record —
  [`docs/adr/0064`](../docs/adr/0064-node-read-integrity-verification.md).

## Where this lives

- [`dolthub-java-port/src/main/java/com/dolthub/prolly/Cursor.java`](../dolthub-java-port/src/main/java/com/dolthub/prolly/Cursor.java) —
  `atKey` / `atRawKey` / `atStart` / `atEnd`, `advance`/`retreat`, `atNodeEnd`,
  `currentSubtreeSize`
- [`dolthub-java-port/src/main/java/com/dolthub/prolly/StaticMap.java`](../dolthub-java-port/src/main/java/com/dolthub/prolly/StaticMap.java) —
  `get` + the iterator family
- [`dolthub-java-port/src/main/java/com/dolthub/prolly/MapIterator.java`](../dolthub-java-port/src/main/java/com/dolthub/prolly/MapIterator.java) /
  [`TreeIter.java`](../dolthub-java-port/src/main/java/com/dolthub/prolly/TreeIter.java) —
  the iterator contract + the stop-predicate wrapper
- [`dolthub-java-port/src/main/java/com/dolthub/prolly/Node.java`](../dolthub-java-port/src/main/java/com/dolthub/prolly/Node.java) —
  the `getSubtreeCount` prefix-sum contract
- [`dolthub-java-port/src/test/java/com/dolthub/prolly/SubtreeCountContractProperty.java`](../dolthub-java-port/src/test/java/com/dolthub/prolly/SubtreeCountContractProperty.java),
  [`CursorAdvanceInvariantTest.java`](../dolthub-java-port/src/test/java/com/dolthub/prolly/CursorAdvanceInvariantTest.java),
  [`CursorModelProperty.java`](../dolthub-java-port/src/test/java/com/dolthub/prolly/CursorModelProperty.java) —
  the pins
- [`prolly-playground-service/src/main/java/com/earasoft/prolly/playground/TreeService.java`](../prolly-playground-service/src/main/java/com/earasoft/prolly/playground/TreeService.java) —
  `find`/`rank`/`scan`: compact reference implementations returning the measured descent
- Narrative walk: [`docs/anatomy/B3-a-read.md`](../docs/anatomy/B3-a-read.md) · the
  write sibling: [`treemutator-write-path.md`](treemutator-write-path.md) · class index:
  [`class-roles.md`](class-roles.md)
