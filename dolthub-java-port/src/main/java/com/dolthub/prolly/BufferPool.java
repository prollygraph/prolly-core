/*
 * Copyright 2026 Earasoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dolthub.prolly;

import java.lang.foreign.MemorySegment;

/**
 * The allocator seam for tuple and node serialization scratch: {@link #borrow} a {@link
 * MemorySegment}, optionally {@link #release} it back, and scope a transaction's whole allocation
 * lifetime with {@link #newTransactionScope}.
 *
 * <p><b>Why the seam exists:</b> it decouples the ported engine (this package) from the memory
 * primitive — on-heap garbage-collected allocation versus an off-heap arena — so the same tree code
 * runs on either, and the choice stays with the pool implementation, not the call sites.
 *
 * @apiNote Borrowed segments have capacity <em>at least</em> the requested size (implementations
 *     may round up for bucketing) — callers slice to the exact size they wrote. The lifecycle
 *     contract that matters: on an arena-backed pool, memory is reclaimed either per-segment
 *     ({@link #release}, with the use-after-free caveats on that method) or wholesale at the
 *     transaction boundary (close the {@link #newTransactionScope} scope); on a garbage-collected
 *     pool both are no-ops by design.
 * @implNote <b>Collaborators / implementations:</b> {@link HeapBufferPool} (the <b>production</b>
 *     write pool — garbage-collected, no arena) and {@code
 *     com.earasoft.prolly.pool.DirectBufferPool} (off-heap arena, the zero-copy target — NOT
 *     production until it passes its own resource net; the promotion gate in CLAUDE.md "Test the
 *     production primitive"). The pair is the founding entry of the production-primitive parity
 *     registry — the {@code DirectBufferPool} write-path leak ({@code
 *     bugs/direct-buffer-pool-write-path-leak.md}) survived ~1000 commits precisely because tests
 *     exercised the non-production pool. Test instruments: {@code ScopeTrackingPool}
 *     (scope-discipline decorator) and {@code PoisoningBufferPool} (use-after-free probe, this
 *     module's test-jar). <b>Dependents:</b> {@link TupleBuilder} (int64 scratch), {@code
 *     TreeMutator} (node build scratch), and every Sail transaction via {@link
 *     #newTransactionScope}.
 */
public interface BufferPool extends AutoCloseable {
    /**
     * Returns a segment with capacity at least {@code size} bytes. Implementations may round up
     * (e.g. to a power of two) for pooling efficiency. Callers are expected to slice the returned
     * segment to the exact size they wrote.
     */
    MemorySegment borrow(int size);

    /**
     * Recycles a previously {@link #borrow}ed segment back to the pool for reuse — the fine-grained
     * counterpart to {@link #newTransactionScope()}'s wholesale free (ADR-0062). The default is a
     * no-op: a garbage-collected pool (e.g. {@link HeapBufferPool}) has nothing to reclaim — the
     * segment is ordinary garbage — so only an arena-backed pool (e.g. {@code DirectBufferPool})
     * overrides this to return the block to its size bucket, bounding a single huge transaction's
     * scratch churn to the working set rather than to the whole transaction.
     *
     * @implNote <b>Safety (ADR-0062 D-3) — the caller's contract.</b> Recycle a segment ONLY when
     *     it is (a) one this pool {@link #borrow}ed (not a wrapped external array), (b) fully
     *     consumed — its bytes already copied/written to their durable home — and (c) retained by
     *     nothing (never a segment a {@code Node} wraps, or one held as a key until flush).
     *     Releasing a still-referenced segment is a use-after-free. Pass the originally-borrowed
     *     segment, not an {@code asSlice} view of it — the pool buckets by byte size (D-4).
     */
    default void release(MemorySegment segment) {}

    /**
     * Returns a buffer pool scoped to a single transaction's lifetime. The caller MUST {@link
     * #close()} the returned scope at the transaction boundary so any off-heap arena it owns is
     * freed <em>wholesale</em>.
     *
     * @implNote <b>The fix for the {@code DirectBufferPool} write-path off-heap leak</b> ({@code
     *     bugs/direct-buffer-pool-write-path-leak.md}). The write path borrows scratch segments and
     *     never releases them; an arena-backed pool therefore grows without bound. The default
     *     returns {@code this}: a garbage-collected pool (e.g. {@link HeapBufferPool}) needs no
     *     per-transaction freeing — unreleased segments are simply reclaimable garbage — so it is
     *     its own scope and its {@link #close()} is a no-op. An arena-backed pool overrides this to
     *     hand back a fresh, independently-closeable child whose arena is freed when the scope
     *     closes. This keeps the choice of memory primitive (on-heap vs off-heap) entirely with the
     *     pool: the caller wires the same code regardless.
     */
    default BufferPool newTransactionScope() {
        return this;
    }

    /**
     * Releases pool-owned resources. The default is a no-op so a garbage-collected pool (and any
     * implementation with nothing to free) needs no override; an arena-backed pool overrides this
     * to free its arena. Declared narrower than {@link AutoCloseable#close()} (throws nothing) so
     * callers holding a {@code BufferPool} reference need no checked-exception handling.
     */
    @Override
    default void close() {}
}
