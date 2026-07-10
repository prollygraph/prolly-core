---
tags:
  - storage
---
# Anatomy of a read

*From `StaticMap.get` down through the tree to one value.*

> **What you'll learn** — how a point lookup descends a prolly tree: the
> `Cursor` that walks root-to-leaf, the binary search inside each node, the
> exact-match check a lookup must not skip, and where caching does (and does
> not) sit in the read path.
>
> _Reading time: ~9 minutes._
> _Prerequisites: [the-prolly-tree](../foundations/the-prolly-tree.md),
> [the-on-disk-format](../foundations/the-on-disk-format.md)._

## 0 · The problem

A caller asks a tree for one key:

```java
StaticMap map = ...;                       // an immutable tree at some root
Optional<MemorySegment> value = map.get(aliceKey);
```

`map` is a `StaticMap` — a read-only view of a prolly tree at a fixed root. The
job: return the value bytes for `aliceKey`, or `empty` if the key is not in the
tree. Follow `get`.

## 1 · The read view

`StaticMap` is the immutable counterpart of [`MutableMap`](B2-a-write.md) — a
snapshot at one root, supporting point lookups and iterators. `get` delegates
straight to a `Cursor`:

```java
public Optional<MemorySegment> get(MemorySegment key) {
    if (root == null) return Optional.empty();         // empty tree
    Cursor cur = Cursor.atKey(store, root, key, descriptor);
    if (cur.isValid()) {
        int cmp = descriptor.compare(new Tuple(cur.currentKey()), new Tuple(key));
        if (cmp == 0) return Optional.of(cur.currentValue());
    }
    return Optional.empty();
}
```

Two steps: position a cursor at the key (`Cursor.atKey`), then *verify* what it
landed on. Section 4 explains why the verification is not optional.

## 2 · Descending the tree

`Cursor.atKey` walks from the root down to a leaf, one node per level:

```java
public static Cursor atKey(NodeStore store, Node root, MemorySegment key, TupleDescriptor desc) {
    Cursor cur = new Cursor(store, null, root, 0);
    while (true) {
        cur.index = searchInNode(cur.node, key, desc);   // where does key go in this node?
        if (cur.isLeaf()) break;                         // arrived

        int childIdx = Math.min(cur.index, cur.node.count() - 1);
        byte[] childHash = cur.node.getValue(childIdx);  // an internal node's value IS a child hash
        Node child = store.read(childHash).map(Node::fromBytes).orElseThrow(...);
        cur = new Cursor(store, cur, child, 0);          // descend, remembering the parent
    }
    return cur;
}
```

At each level it binary-searches the current node for the key's position. If
the node is **internal**, that position selects a child: the node's *value* at
that index is the child's content hash, so `store.read(childHash)` fetches the
child's bytes and `Node.fromBytes` parses them (see
[the-on-disk-format](../foundations/the-on-disk-format.md)). The new `Cursor`
keeps a link to its parent — that is what lets the cursor later `advance` past
the end of a leaf. The loop stops at a **leaf**.

> **Key idea** — a read costs **O(log n) node reads**. Because prolly-tree
> nodes are large (a 512 B–16 KB chunk holds many entries), the fan-out is high
> and the tree is *shallow* — a few levels even for a large dataset. Each level
> is one `store.read` plus an in-node binary search.

> **Gotcha** — `store.read` returning empty is treated as corruption: the
> cursor throws "child node missing from store". In a content-addressed tree a
> referenced hash *must* resolve; a miss is an integrity failure, not a
> not-found.

## 3 · Binary search within a node

Within each node the search is an ordinary binary search — node entries are
sorted by key:

```java
private static int searchInNode(Node node, MemorySegment key, TupleDescriptor desc) {
    int low = 0, high = node.count() - 1;
    while (low <= high) {
        int mid = (low + high) >>> 1;
        int cmp = desc.compare(new Tuple(node.getKeySegment(mid)), new Tuple(key));
        if (cmp < 0)      low  = mid + 1;
        else if (cmp > 0) high = mid - 1;
        else              return mid;          // exact hit
    }
    return low;                                 // miss — the insertion point
}
```

`desc` is the [`TupleDescriptor`](../foundations/the-on-disk-format.md) — it
compares keys field by field, by type. On an exact hit it returns the index; on
a miss it returns `low`, the position where the key *would* go.

## 4 · The exact-match check

That `low`-on-miss is the subtlety. `Cursor.atKey` always returns a cursor
positioned *somewhere* — at the matching entry if the key exists, or at the
next entry (the insertion point) if it does not. The cursor being "valid" only
means it points at a real entry, **not** that it found the key.

So `StaticMap.get` must confirm:

```java
int cmp = descriptor.compare(new Tuple(cur.currentKey()), new Tuple(key));
if (cmp == 0) return Optional.of(cur.currentValue());
```

> **Gotcha** — never treat a positioned cursor as a found key. `atKey` lands on
> the insertion point for an absent key; only the explicit `compare == 0` test
> distinguishes a hit from a near-miss. (Range scans, by contrast, *want* the
> insertion point — that is where iteration should begin.)

## 5 · The node cache (opt-in, at the store layer)

Every descent reads internal nodes — and the nodes near the root are read by
*every* lookup. `NodeCache` is a bounded, lock-free cache of parsed `Node`
objects keyed by hash, with a *byte budget* rather than an entry count:

```java
public Optional<Node> get(byte[] hash) { ... }
public void put(byte[] hash, Node node) { ... }
```

**In this repo it is opt-in, not wired into the core descent** (corrected
2026-07-11 — an earlier version of this page implied every read went through
it). The engine's `Cursor` reads straight from the `NodeStore`; caching
attaches where the read actually costs something:
`RocksNodeStore.setNodeCache(...)` makes the RocksDB store consult the cache
before hitting disk. A hit skips both the disk read and the Flatbuffers
parse, and the cache is safe without invalidation because nodes are
**content-addressed and immutable** — a hash maps to the same bytes forever.
(The lesson behind the placement: cache the layer where the cost lives; a
cache in front of already-cheap reads is pure overhead.)

## Takeaways

- `StaticMap.get` is a `Cursor` descent plus an exact-match check.
- The cursor walks root-to-leaf, binary-searching each node; an internal node's
  value at the search position *is* the next child's hash.
- A read is O(log n) node reads, and the tree is shallow because nodes are
  large — high fan-out.
- A positioned cursor is **not** a found key — `get` must verify `compare == 0`
  because the search returns the insertion point on a miss.
- `NodeCache` caches parsed nodes by hash, invalidation-free thanks to
  content-addressed immutability.

## Where this lives

- `dolthub-java-port/src/main/java/com/dolthub/prolly/StaticMap.java` — `get`
- `dolthub-java-port/src/main/java/com/dolthub/prolly/Cursor.java` — `atKey`,
  `searchInNode`, the descent
- `dolthub-java-port/src/main/java/com/dolthub/prolly/Node.java` — node access
- `dolthub-java-port/src/main/java/com/dolthub/prolly/NodeCache.java` — the
  parsed-node cache (opt-in via `prolly-storage`'s `RocksNodeStore.setNodeCache`)
- Deep dive: [`prolly-web-playground/cursor-read-path.md`](../../prolly-web-playground/cursor-read-path.md)
  — rank over the counted tree, scan leaf-hops, the iterator family, costs
- Builds on: [the-on-disk-format](../foundations/the-on-disk-format.md),
  [B2 · a write](B2-a-write.md)
- Continues in: [B4 · a commit](B4-a-commit.md) — what makes a particular root
  a durable, named version.
