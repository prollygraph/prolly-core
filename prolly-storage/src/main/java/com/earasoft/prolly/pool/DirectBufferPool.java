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
package com.earasoft.prolly.pool;

import com.dolthub.prolly.*;
import com.dolthub.prolly.BufferPool;
import com.earasoft.prolly.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.monitor.BufferPoolMXBean;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * An off-heap {@link MemorySegment} pool (Project Panama / the Foreign Function &amp; Memory API)
 * that recycles buffers by power-of-two size bucket — minimizing allocation overhead and keeping
 * the buffers out of the Java garbage collector's working set.
 *
 * @apiNote <b>Not the production buffer pool today.</b> The production write path runs the on-heap
 *     {@link HeapBufferPool} (wired in the upstream per-repo Sail factory), per the project's "test
 *     the production primitive, gate the non-production one" convention. This off-heap pool is the
 *     zero-copy <i>target</i> — see the upstream write-path zero-copy plan and ADR-0039 / ADR-0062
 *     / an upstream buffer-lifecycle decision.
 * @implNote <b>Promotion gate — when this becomes the production default.</b> Flip the production
 *     buffer pool from {@link HeapBufferPool} to this pool only once <b>both</b> criteria hold:
 *     <ol>
 *       <li><b>Stable</b> — leak-safe (the {@code BufferPool#newTransactionScope()} per-transaction
 *           arena, fixed 2026-06-14; {@code DirectBufferPoolWritePathLeakTest} green + enabled)
 *           <i>and</i> soak-validated: a full {@code SoakLeakDriver} run on the off-heap path that
 *           reaches its {@code [soak] DONE} verdict with a flat post-garbage-collection live-heap
 *           trough and a bounded resident set — not a run cut short by a premature kill.
 *       <li><b>Optimum resource utilization</b> — a <i>measured</i> resident-set / allocation-churn
 *           win over {@link HeapBufferPool} on a real write workload ({@code GraphIngestBench
 *           -Dpool=direct|heap}), not mere parity. The off-heap path only earns its complexity if
 *           the garbage-collection cost it removes actually binds.
 *     </ol>
 *     The flip itself is gated on this pool's resource test being green + enabled with the test's
 *     pool default flipped to it <i>first</i> (CLAUDE.md "test the production primitive"). Until
 *     both criteria hold, the simpler, safer {@link HeapBufferPool} stays production <b>by choice,
 *     not by gate</b>. See docs/write-ups/direct-buffer-pool-write-path-leak.md.
 */
public class DirectBufferPool implements BufferPool, AutoCloseable, BufferPoolMXBean {
    private final Arena arena = Arena.ofShared();
    private final Map<Integer, ConcurrentLinkedQueue<MemorySegment>> buckets =
            new ConcurrentHashMap<>();

    private final LongAdder allocatedBytes = new LongAdder();
    private final LongAdder borrowCount = new LongAdder();
    private final LongAdder releaseCount = new LongAdder();

    @Override
    public MemorySegment borrow(int size) {
        borrowCount.increment();
        int bucketSize = nextPowerOfTwo(size);
        var bucket = buckets.computeIfAbsent(bucketSize, k -> new ConcurrentLinkedQueue<>());

        MemorySegment segment = bucket.poll();
        if (segment == null) {
            // Fresh allocation from a shared Arena is already zero-initialised.
            segment = arena.allocate(bucketSize);
            allocatedBytes.add(bucketSize);
        } else {
            // Reused segment: zero so the previous tenant's bytes can't leak
            // through any caller that reads or hashes beyond the slice they wrote.
            segment.fill((byte) 0);
        }
        return segment;
    }

    @Override
    public void release(MemorySegment segment) {
        releaseCount.increment();
        int bucketSize = (int) segment.byteSize();
        var bucket = buckets.get(bucketSize);
        if (bucket != null) {
            bucket.offer(segment);
        }
    }

    private int nextPowerOfTwo(int n) {
        if (n == 0) return 1024;
        n--;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
        n++;
        return Math.max(n, 1024);
    }

    // MXBean Implementation
    @Override
    public long getTotalAllocatedBytes() {
        return allocatedBytes.sum();
    }

    @Override
    public int getActiveBucketCount() {
        return buckets.size();
    }

    @Override
    public long getBorrowedCount() {
        return borrowCount.sum();
    }

    @Override
    public long getReleasedCount() {
        return releaseCount.sum();
    }

    /**
     * Hands back a <b>fresh</b> {@code DirectBufferPool} as the transaction scope — its own {@link
     * Arena}, freed wholesale when the caller closes the scope at the transaction boundary. This is
     * the leak fix (docs/write-ups/direct-buffer-pool-write-path-leak.md): the write path never
     * releases its borrowed scratch, so a single never-freed shared arena grows without bound;
     * scoping a child arena to the transaction bounds the off-heap footprint to one transaction's
     * working set. The shared pool this is called on is left untouched (and write-unused once every
     * caller scopes), so it never accumulates.
     */
    @Override
    public BufferPool newTransactionScope() {
        return new DirectBufferPool();
    }

    @Override
    public void close() {
        arena.close();
    }
}
