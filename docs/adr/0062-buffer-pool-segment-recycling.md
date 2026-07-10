# ADR-0062: Buffer pool segment recycling

## Status

Accepted, 2026-06-15 — owner-directed ("Wire + commit to recycling"). Guides
an upstream plan (the
implementation). Sibling of the per-transaction-scope leak fix
(an upstream bug write-up) and
[ADR-0039](0039-read-path-node-cache-and-zero-copy.md) (read-path node cache + zero-copy). **Surfaced by
the dead-resource-API guard** (an upstream dead-resource-API guard script,
an upstream plan Step 7),
which flagged `DirectBufferPool.release` as having zero `src/main` callers — forcing the "wire or delete"
decision this ADR answers with *wire*.

## Context

The `DirectBufferPool` off-heap leak was fixed by **per-transaction scoping**: each transaction gets a
child pool with its own `Arena`, freed *wholesale* at the transaction boundary (`newTransactionScope()` /
`close()`). That bounded the leak — the shared arena no longer grows without end. But it left
`release(segment)` — the per-segment *recycle* (return a borrowed segment to its size bucket for reuse) —
with **zero callers**, which the dead-resource-API guard correctly flagged.

"Wire or delete `release()`" turns on a memory fact. With the per-transaction scope *alone*, every
`borrow()` in a transaction occupies the transaction's arena **until `close()`**, because nothing returns
a segment mid-transaction. For the off-heap pool that is **O(transaction-size), not O(working-set)**.
Grounded in `TupleBuilder.build()`: building one SPOC tuple borrows ~4 `int64` field-scratch segments
(8 bytes each, rounded up to the 1024-byte minimum bucket), **copies** them into the final tuple segment
(`MemorySegment.copy`, `TupleBuilder.java:104`), and discards them — but that dead ~4×1024 B of scratch
stays resident in the arena. Over a 10-million-statement transaction that is on the order of **tens of
gibibytes of dead scratch** held until the transaction ends. The per-transaction scope is a *coarse* net
(it bounds to one transaction); it does **not** bound a single huge transaction's intra-transaction churn.

Production uses `HeapBufferPool`, where dead scratch is ordinary garbage the garbage collector reclaims —
no accumulation — so this is specifically about the off-heap `DirectBufferPool`, the zero-copy write path
[ADR-0039](0039-read-path-node-cache-and-zero-copy.md) targets and that the bulk-load throughput work
(the upstream ADR-0061 bulk-load writer, not exported) drives huge single transactions through.

Three constraints shape any "wire" design: (1) a *missed* release must **degrade, not leak forever** (the
coarse net still frees it at transaction end); (2) recycling must be **use-after-free-safe** — a segment
released while still referenced (a retained tuple key, a `Node`-wrapped segment) corrupts; (3) release must
hit the **right bucket** — releasing a `borrow(8).asSlice(0, 8)` *view* (byte size 8) of a 1024-byte
borrowed block mis-buckets.

## Options

| Option | Intra-transaction footprint (off-heap, huge transaction) | Use-after-free surface | Discipline cost |
|---|---|---|---|
| **A** — delete `release()`; per-transaction scope only (wholesale free) | **O(transaction-size)** — dead scratch resident until the transaction ends | none | none |
| **B** — keep `release()` unused (status quo) | O(transaction-size) | none (uncalled) | the camouflage the guard forbids (resource-invariant D-4) |
| **C** — wire `release()` as a first-class recycler (coarse scope + fine recycle) | **O(working-set)** — scratch recycled through buckets | narrow + *ruled* (recycle only consumed, unretained, pool-borrowed scratch) | per-borrow-site lifecycle care, pinned by tests |

## Decision

**Option C.** `release()` becomes a first-class `BufferPool` contract method, and the write path recycles
its transient scratch through it. Four sub-decisions:

**D-1 — `release()` joins the `BufferPool` interface.** It was concrete-only on `DirectBufferPool`, so a
caller holding a `BufferPool` reference *could not* call it — part of why it went uncalled. Promote it to
the interface; `HeapBufferPool`'s no-op (the garbage collector reclaims) is the legitimate heap
implementation, `DirectBufferPool`'s bucket-offer the off-heap one. Deciding tradeoff: a uniform contract
lets the write path recycle polymorphically without knowing the pool kind — the same "the caller wires the
same code regardless" property `newTransactionScope()` already gives.

**D-2 — Two-tier memory model: the per-transaction scope is the coarse net; per-segment `release()` is the
fine recycler.** The scope bounds the arena to one transaction and handles **retained** segments — the
final tuple keys held in `MutableMap.edits` until flush, `Node`-wrapped segments — freeing them wholesale
at the transaction boundary. `release()` bounds a single huge transaction's **transient** churn to the
working set and handles consumed-then-dead scratch. Neither subsumes the other: the scope cannot free a
retained segment early; `release()` cannot safely free a retained one. Deciding tradeoff: the coarse net
alone is O(transaction-size) for a huge transaction (the tens-of-gibibytes above); the fine recycler alone
cannot bound retained data; **together** they bound both.

**D-3 — (SAFETY-CRITICAL) Recycle only a segment that is (a) pool-borrowed, (b) fully consumed — its bytes
already copied/written to their durable home — and (c) retained by nothing.** The canonical safe site is
`TupleBuilder.build()`'s field scratch: after the `MemorySegment.copy` into the final tuple
(`TupleBuilder.java:104`) the field scratch is dead and unreferenced → recycle it. **Never** recycle: the
final tuple segment (retained as a `MutableMap` key until flush); a `Node`'s wrapped segment (`Node`
zero-copies its bytes and the cache/cursor retain it — the ADR-0039 retention hazard); or an
externally-supplied field (`putField(byte[])` wraps a caller array — not pool-borrowed). Deciding tradeoff:
the recycle's entire risk is use-after-free, and this rule confines recycling to the provably-dead
transient surface, leaving everything retained to the coarse net.

**D-4 — Recycle the *borrowed* segment, not a slice of it; track owned scratch explicitly.**
`DirectBufferPool.release` buckets by `segment.byteSize()`, so releasing a `borrow(8).asSlice(0, 8)` view
(byte size 8) mis-buckets versus the 1024-byte borrowed block. `TupleBuilder` must retain the original
borrowed segments it owns and release *those*; a release of a non-pool or wrong-size segment must be a
safe no-op, never silent pool corruption. Deciding tradeoff: tracking adds builder state, but releasing
the slice is a latent pool-corruption bug.

## Consequences

**Positive.** A huge single transaction's `DirectBufferPool` arena becomes O(working-set), not
O(transaction-size) — at 10 million statements the difference between tens of gibibytes of dead scratch
and a handful of recycled buckets; the zero-copy write path becomes viable at scale; `release()` stops
camouflaging (the guard passes once a real caller exists).

**Negative / cost (the honest tradeoff the owner accepted over delete).** It re-introduces a
per-segment-release discipline — a *missed* release degrades to the coarse net's O(transaction-size)
(bounded, not a forever-leak, but a regression to watch); it opens a **use-after-free surface** that did
not exist with wholesale-free-only (mitigated by D-3's rule + tests, but real); and it adds builder state
to track borrowed scratch.

**Neutral.** Production (`HeapBufferPool`) is unaffected at runtime — `release()` is a no-op there and the
garbage collector already recycled — so the win is `DirectBufferPool`-only: the zero-copy *future*, not
today's default.

**Pinned by.** The `DirectBufferPool` leak test + a within-transaction read-your-writes test + the full
Sail suite staying green (no corruption from early release); a **footprint test** (building M tuples in one
transaction → `DirectBufferPool` `allocatedBytes` *plateaus*, via the resource-invariant harness); and the
dead-resource-API guard passing (then `--strict` in CI) once a real caller exists.

## Follow-up / future work

- The implementation plan:
  an upstream plan — interface
  promotion → `TupleBuilder` scratch recycling → footprint test → `SpocKey`/`Int64Key` transient-key
  recycling → guard-clean + `--strict` in CI.
- [ADR-0039](0039-read-path-node-cache-and-zero-copy.md) Steps 4–5 (read-path zero-copy / per-scan arena
  reuse) share D-3's retention-hazard analysis — coordinate so the two do not diverge on which segments are
  safe to free early.

## Open questions

- **Q1** — Is interface `release()` a **default no-op** (implementations opt in) or **abstract** (every
  pool decides)? Leaning default no-op — most pools (heap, and any future garbage-collected one) need no
  recycle; only an arena-backed pool overrides — decided at implementation Step 1.
- **Q2** — Are `SpocKey` / `Int64Key` lookup keys **transient** (recycle after the get/scan) or
  **retained**? Verify per call site before recycling (D-3 (c)); if any retains the key, it stays on the
  coarse net.
