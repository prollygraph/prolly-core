---
tags:
  - storage
  - format
---
# Anatomy of a chunk boundary

*How a rolling hash decides where one node ends — the single mechanism the
whole prolly tree rests on.*

> **What you'll learn** — how `RollingHashSplitter` places node boundaries from
> content alone, why the boundary probability rises as a chunk grows, what the
> per-level salt is for, and why this one function is what makes structural
> sharing and history-independence work.
>
> _Reading time: ~11 minutes._
> _Prerequisites: [the-prolly-tree](../foundations/the-prolly-tree.md)._

## 0 · The problem

A tree level is being built by streaming sorted key/value pairs into a chunker.
The chunker must answer one question, over and over:

> *Does this node end here, or does the next pair go into it too?*

An ordinary B-tree answers by counting — "16 entries, split". A prolly tree
must answer **from the content**, so that the same data always chunks the same
way regardless of how it was assembled. That answer is `RollingHashSplitter`.

## 1 · A rolling hash over a window

The splitter wraps a **BuzHash** — a *rolling* hash over a fixed 67-byte
window. As key/value bytes stream in, the hash continuously reflects only the
last 67 bytes seen:

```java
public void append(MemorySegment key, MemorySegment value) {
    hashSegment(key);
    hashSegment(value);
}

private void hashByte(byte b) {
    offset++;
    if (crossedBoundary) return;
    bz.hashByte((byte) ((long) Byte.toUnsignedInt(b) ^ salt));
    // ... boundary test ...
}
```

Every byte of every pair in the current node is fed in. `offset` counts bytes
since the node started; `bz` always holds the hash of the trailing window.
(Ignore the `^ salt` for a moment — section 4.)

> **Key idea** — "rolling" is the whole trick. The hash at any point depends
> *only* on the last 67 bytes, not on where the node started or what came
> before. So the boundary decision is **local**: it cannot be perturbed by a
> distant edit.

## 2 · The boundary test

After each byte, `hashByte` decides whether the node ends:

```java
if (offset < MIN_CHUNK_SIZE) return;        // 512 — too small, never split
if (offset >= MAX_CHUNK_SIZE) {             // 16384 — too big, force a split
    crossedBoundary = true;
    return;
}
int hash = bz.sum32();
int patt = rollingHashPattern(offset);
if ((hash & patt) == patt) crossedBoundary = true;
```

Three outcomes. Below `MIN_CHUNK_SIZE` (512 B) the node is too small — never
split. At or above `MAX_CHUNK_SIZE` (16 KB) it is too big — force a split. In
between, the node ends *iff the rolling hash matches a bit pattern*. Because the
hash is effectively random, that match happens at a content-determined,
position-independent point.

`crossedBoundary` is a latch: once set, the remaining bytes are skipped and the
caller knows to start a new node.

## 3 · The pattern that targets a size

If the pattern were fixed, chunk sizes would be exponentially distributed —
many tiny nodes, occasional huge ones. `rollingHashPattern` prevents that by
making the match *easier* the longer the node has run:

```java
private int rollingHashPattern(int offset) {
    int shift = 15 - (offset >> 10);   // offset >> 10  ==  offset / 1024
    return (1 << shift) - 1;           // a mask of `shift` low bits
}
```

The pattern is a mask of `shift` low bits, and `shift` *shrinks* by one for
every 1024 bytes the node grows:

| `offset` | `shift` | `patt` (bits to match) | split probability |
|---|---|---|---|
| ~512 B | 15 | 15 bits | ~1 / 32768 |
| ~4 KB | 12 | 12 bits | ~1 / 4096 |
| ~12 KB | 3 | 3 bits | ~1 / 8 |
| ~15 KB | 0 | 0 bits — `hash & 0 == 0` | always |

So a short node almost never splits; a long one almost certainly does. Chunk
sizes converge on a few-KB target with a tight distribution — without ever
counting entries.

> **Key idea** — the boundary is *probabilistic but self-correcting*. Node
> sizes are not fixed and not guaranteed; they are statistically centred. Never
> write code that assumes a node has N entries or a fixed byte size.

## 4 · The level salt

A prolly tree is built level by level: leaves first, then a level of internal
nodes over their hashes, and so on. If every level ran the *identical* hash,
boundaries would tend to **vertically align** — a split at the leaf level would
encourage a split at the same offset one level up, cascading.

The fix is the `^ salt` from section 1. Each level gets its own salt:

```java
public static long saltFromLevel(int level) {
    // SHA-512 of the single level byte, cached per level
}
```

Every byte is XORed with the level's salt before hashing, so each level sees a
differently-perturbed stream and chooses boundaries independently.

> **Gotcha** — the salt, the window size (67), `MIN`/`MAX`, and the pattern
> formula are all **frozen constants**. They are not tuning knobs: change any
> one and every node boundary moves, so every node hash and every tree root
> hash changes — invalidating every stored tree. This function is part of the
> on-disk contract.

## 5 · Why this is the linchpin

Everything that makes a prolly tree special traces back to this function being
**content-defined and local**:

- **History-independence.** The same key/value set always produces the same
  boundaries — the splitter never looks at insertion order — so the same data
  always yields the same tree shape and the same root hash. (See
  [the-prolly-tree](../foundations/the-prolly-tree.md).)
- **Structural sharing.** Inserting one key changes the rolling hash only
  *within a window of that key*. Boundaries far away are byte-for-byte
  unchanged → those nodes hash the same → the new tree *shares* them with the
  old one. An edit touches O(log n) nodes, not the whole level.
- **Cheap diff and merge.** Shared subtrees have equal hashes, so
  [B5 · a diff](B5-a-diff.md) and [B6 · a merge](B6-a-merge.md) skip them
  wholesale.

Get this function wrong and none of those hold. Get it right — content-defined,
local, deterministic — and they all fall out for free.

## Takeaways

- A node boundary is decided by a **rolling hash** over a 67-byte window — from
  content, never from a counter or a position.
- The boundary test clamps to `[512 B, 16 KB]` and, in between, splits when the
  hash matches a bit pattern.
- The pattern shrinks as the node grows, so chunk sizes self-correct toward a
  few-KB target — probabilistic, not fixed.
- A per-level salt keeps boundaries from vertically aligning across tree
  levels.
- This locality is *the* mechanism behind history-independence, structural
  sharing, and cheap diff/merge — the constants are a frozen on-disk contract.

## Where this lives

- `dolthub-java-port/src/main/java/com/dolthub/prolly/RollingHashSplitter.java`
  — the splitter, the boundary test, the pattern, the salt
- `dolthub-java-port/src/main/java/com/dolthub/prolly/BuzHash.java`,
  `BuzHashTable.java` — the rolling hash
- Builds on: [the-prolly-tree](../foundations/the-prolly-tree.md)
- Continues in: [B2 · a write](B2-a-write.md) — the chunker driving this
  splitter to build a new tree.
