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
 * The <b>production</b> {@link BufferPool}: a fresh {@code byte[]}-backed {@link MemorySegment} on
 * every borrow, reclaimed by the garbage collector — no pooling, no arena, nothing to leak.
 *
 * <p><b>Why the trivial pool is the production one:</b> the write path borrows scratch and never
 * releases it, which on an arena-backed pool is an unbounded off-heap leak (the {@code
 * DirectBufferPool} write-path leak, docs/write-ups/direct-buffer-pool-write-path-leak.md) — on this
 * pool the same pattern is simply short-lived garbage. {@code DirectBufferPool} (the zero-copy
 * target) may take over only after passing its own resource net (the promotion gate in CLAUDE.md
 * "Test the production primitive"); until then correctness-by-construction wins over allocation
 * throughput.
 *
 * @apiNote Stateless and thread-safe. {@code release}/{@code close}/{@code newTransactionScope} are
 *     the inherited no-op defaults — a garbage-collected pool is its own transaction scope.
 * @implNote <b>Collaborators:</b> {@link BufferPool} (the seam; this class is deliberately the
 *     no-override implementation of it). <b>Dependents / wiring:</b> {@code
 *     upstream per-repo write pools and server defaults — the wiring sites the upstream
 *     production-primitive parity registry sync-checks. {@link #borrow} sizes round up to the
 *     next power of two (minimum 1024) to match {@code DirectBufferPool}'s bucket layout, so
 *     SCRATCH borrows see consistent segment sizes regardless of which pool is wired; {@link
 *     #borrowRetained} deliberately breaks that symmetry — exact-size here, bucket-size on an
 *     arena pool — because a retained key is never recycled and callers slice to exact size
 *     anyway, so only the backing allocation differs, never the observable segment.
 */
public final class HeapBufferPool implements BufferPool, AutoCloseable {
    @Override
    public void close() {}

    // release(MemorySegment) is inherited from BufferPool's default no-op (ADR-0062 D-1): a
    // garbage-collected pool has nothing to recycle, so the heap pool needs no override.

    @Override
    public MemorySegment borrow(int size) {
        return MemorySegment.ofArray(new byte[nextPowerOfTwo(size)]);
    }

    /**
     * Exact-size, no floor: a retained key is never recycled (ADR-0062 D-3), so the power-of-two
     * bucket layout that {@link #borrow}'s rounding mirrors buys nothing here — it only multiplies
     * live heap for the transaction's lifetime (the 1024-byte floor turned every 42-byte staged
     * quad key into 24× its size in the consumer's measured bulk-ingest OOM —
     * quarkus-ontology-editor {@code docs/benchmarks/ncit-runs/e2e-one-flush.txt}, run 4).
     */
    @Override
    public MemorySegment borrowRetained(int size) {
        return MemorySegment.ofArray(new byte[Math.max(size, 1)]);
    }

    private static int nextPowerOfTwo(int n) {
        if (n <= 0) return 1024;
        n--;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
        n++;
        return Math.max(n, 1024);
    }
}
