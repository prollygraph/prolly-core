---
id: ADR-20260904-27f3
---

# ADR-prolly-core-0076: Reachability sets are keyed by bytes, not hex

## Status

Accepted, 2026-09-04. **Amends [ADR-0074](0074-chunk-gc-reachability-contributor-spi.md)**: the
contributor SPI's method becomes `ChunkSet reachable(NodeStore)` in place of
`Set<String> reachableHexes(NodeStore)`. ADR-0074's decision — that co-tenant substrates claim their
own closures and the collector unions them before sweeping — is unchanged; only the type crossing
the boundary changes. Mirrored into the RDF ring's series, which implements the SPI.

## Context

Every reachability walk in the engine and the RDF ring carried its results as a `Set<String>` of
40-character hex. That representation costs about **117 bytes to store a 20-byte hash** — a 24-byte
`String`, a 56-byte LATIN1 payload, a 32-byte `HashMap.Node`, and a table slot — and it allocates on
every visit, because `HashUtils.toHex` builds a `char[40]` and then the `String` wrapping it.

Two consumers pay it:

- **The mark**, in `DataTreeReachability.collectInto` and `ReachabilityWalker`, once per visited node.
- **The sweep**, in `GarbageCollector`, which called `toHex(key)` for **every 20-byte key in the
  store** — not just the live ones.

On the 4.08M-chunk corpus that is roughly 12M `String` pairs allocated per collection, and a mark set
of ~477 MB. `ReachabilityWalker`'s own hex helper was worse than the shared one: it used
`String.format("%02x", b)` **per byte**, so ~82M format calls for a full mark.

**What this is not.** It is measured that the walk is IO-bound to three significant figures — the
Java-side work (hex formatting plus set operations for 4.08M elements) is **1.2–2.1 s against a
3,760 s walk, 0.03–0.06%**. So this change buys **no meaningful wall-clock time**, and it should not
be described as a speedup. It is a memory and allocation change.

**What makes it worth doing anyway** is that the memory is the thing that scales badly. At ~3% of
logical store size, resident for the whole collection — which is exactly when writes are blocked — a
1 TiB store would want roughly 30 GB of mark set. And the sync path has already hit the wall: the
forge ran out of heap building a pack for this corpus at ~522 MB with hex keys, and worked around it
by writing its own byte-keyed set. This adopts that fix upstream rather than leaving it downstream.

## Options

| Option | Bytes per 20-byte hash | Verdict |
|---|---|---|
| **A** — keep `Set<String>` of hex | ~117 | the status quo; the allocation is pure loss |
| **B** — `Set<ByteBuffer>` | ~125 | **worse**: the wrapper costs more than the hex it saves |
| **C** — a `Set<Long>` of truncated hashes | ~40 | **unsafe**, see below |
| **D** — a byte-keyed open-addressed set behind an interface | ~41 | chosen |

**Why C is not merely slower but wrong.** A truncation collision makes two distinct hashes
indistinguishable. In the *sweep* that would be harmless — a garbage chunk retained. In the *walk* it
is fatal: `add` reports "already present" for a hash never visited, the walk prunes that subtree, its
chunks are never marked, and the sweep deletes live data. The full 20 bytes must be compared.

## Decision

**D-1 — `ChunkSet`, an interface, is the currency of every reachability walk.** Three methods:
`add` (a test-and-set, which is the one operation a walk needs to terminate on a shared subtree),
`contains`, and `forEach`. `PackedChunkSet` is the production implementation — one flat `byte[]` of
open-addressed slots, keys inline, slot index taken straight from the hash's leading bytes because a
digest is already uniform. `ConcurrentChunkSet` is the synchronized variant the parallel walker uses.

**D-2 — an interface rather than the concrete class, because a differential test must not share one.**
`NcitMarkWalkAgreementIT` compares a leaf-skipping walk against the full-reading walk on a real 7.81M
chunk store; a bug in a *shared* set implementation would corrupt both arms identically and the
comparison would report agreement. The interface keeps the two arms on independently written
structures.

**D-3 — equality stays identity, and that is documented at the type.** These sets are mutable, and
value equality on a mutable collection breaks the moment one becomes a map key. Comparisons go
through `toHexSet()`. This is not a theoretical concern: converting the walks turned four working
assertions in `SyncEndToEndTest` into identity checks that **compiled clean**, because `assertEquals`
binds to `(Object, Object)`. Those failed loudly. Their sibling `assertNotEquals` would have passed
vacuously forever.

**D-4 — no compatibility overload.** Pre-1.0: the signatures change outright, in one commit, across
the engine, the RDF ring, and the consuming service. A `Set<String>` overload left beside the new one
is precisely the deprecation shim this project forbids.

## Consequences

**The SPI's name changes, so every implementor breaks at compile time.** That is the intended
property — `reachableHexes` was named for a representation that no longer exists, and a silent
adaptation would have been worse than a build failure.

**The sweep stops allocating entirely.** `reachable.contains(key)` replaces
`reachable.contains(toHex(key))` over every key in the store.

**`PackBuilder` loses a round-trip.** It used to assemble a pack by iterating a hex set and calling
`fromHex` per chunk; it now reads straight from the hashes.

**A downstream duplicate is now justified rather than tolerated.** The forge's `ChunkHashSet` is not
retired by this decision. It is the independent implementation that makes the agreement test's
differential meaningful, and replacing it with `PackedChunkSet` would blind that test — the same
trap D-2 guards.

**It does not help the collector's runtime.** The bottleneck is filesystem geometry (~150 KiB read
per ~5 KiB chunk against a 128 KiB ZFS record), one layer below RocksDB, and a block cache sized to
hold index and filter blocks was measured at **0.96×** on this corpus. Anyone reaching for this change
to make a collection faster is reaching for the wrong lever.

## Open questions

- **Q1 — should the packed set use a higher load factor?** The power-of-two capacity pins it near
  0.49 for this corpus. Robin Hood or cuckoo probing at 0.9 with a non-power-of-two capacity would
  roughly halve the footprint again. Unmeasured, and not needed at present scale.
- **Q2 — should the mark set move off-heap or to a memory-mapped file?** That is the real answer at
  1 TiB. It is also moot if the collector converges on copy-forward, where the destination store *is*
  the dedup structure and there is no mark set at all.
