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
package com.earasoft.prolly.monitor;

import com.dolthub.prolly.*;
import com.earasoft.prolly.pool.*;
import java.lang.foreign.MemorySegment;

/**
 *
 *
 * <h3>BufferPool MXBean Surface Test</h3>
 *
 * <p>Pins the JMX-exposed counters of {@link DirectBufferPool} via the {@link BufferPoolMXBean}
 * interface: borrow / release counts, total allocated bytes, distinct active bucket count.
 *
 * <p><b>The Gap:</b> {@link BufferPoolMXBean} is the JMX surface — broken counters mean operators
 * looking at observability dashboards see lies. Both {@link BufferPoolMXBean} and {@link
 * DirectBufferPool}'s implementation of those four methods had zero direct test references; only
 * incidental exercise via {@code MemoryLeakTest} which reads {@code totalAllocatedBytes} but
 * doesn't pin the increment semantics.
 *
 * <p><b>Oracles:</b>
 *
 * <ol>
 *   <li>Initial state: every counter is zero, no active buckets.
 *   <li>Each {@code borrow(N)} increments {@code borrowedCount}; first borrow at a new size also
 *       increments {@code totalAllocatedBytes} by the bucket size (next power of two, min 1024) and
 *       adds an active bucket.
 *   <li>{@code release(seg)} increments {@code releasedCount} but does NOT change {@code
 *       totalAllocatedBytes}.
 *   <li>Borrowing a previously-released segment of the same bucket size reuses it: {@code
 *       borrowedCount} goes up, {@code totalAllocatedBytes} does NOT.
 *   <li>{@code activeBucketCount} reflects the number of distinct power-of-two bucket sizes that
 *       have ever been allocated.
 * </ol>
 */
public class DirectBufferPoolMxBeanTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- BufferPool MXBean Surface Test ---");

        try (DirectBufferPool pool = new DirectBufferPool()) {
            BufferPoolMXBean mx = pool;

            // Oracle 1: initial state.
            if (mx.getBorrowedCount() != 0)
                throw new RuntimeException("initial borrowedCount != 0");
            if (mx.getReleasedCount() != 0)
                throw new RuntimeException("initial releasedCount != 0");
            if (mx.getTotalAllocatedBytes() != 0)
                throw new RuntimeException("initial allocatedBytes != 0");
            if (mx.getActiveBucketCount() != 0)
                throw new RuntimeException("initial bucketCount != 0");
            System.out.println("Initial state is all zero. (1/5)");

            // Oracle 2: first borrow of a small size.
            // borrow(100) → bucket size 1024 (min); first allocation at this bucket
            // adds 1024 bytes and 1 active bucket.
            MemorySegment s100 = pool.borrow(100);
            if (mx.getBorrowedCount() != 1)
                throw new RuntimeException("borrowedCount=" + mx.getBorrowedCount());
            if (mx.getTotalAllocatedBytes() != 1024) {
                throw new RuntimeException(
                        "allocatedBytes=" + mx.getTotalAllocatedBytes() + " expected 1024");
            }
            if (mx.getActiveBucketCount() != 1) {
                throw new RuntimeException("bucketCount=" + mx.getActiveBucketCount());
            }
            System.out.println("First borrow allocates 1024 (the min bucket). (2/5)");

            // Oracle 3: release does NOT change allocatedBytes; it does increment releasedCount.
            long allocBefore = mx.getTotalAllocatedBytes();
            pool.release(s100);
            if (mx.getReleasedCount() != 1)
                throw new RuntimeException("releasedCount=" + mx.getReleasedCount());
            if (mx.getTotalAllocatedBytes() != allocBefore) {
                throw new RuntimeException("release should not change allocatedBytes");
            }
            System.out.println("release() bumps releasedCount only. (3/5)");

            // Oracle 4: re-borrowing same size reuses the bucket — no new allocation.
            MemorySegment s100b = pool.borrow(100);
            if (mx.getBorrowedCount() != 2)
                throw new RuntimeException("borrowedCount=" + mx.getBorrowedCount());
            if (mx.getTotalAllocatedBytes() != allocBefore) {
                throw new RuntimeException(
                        "re-borrow should reuse, not allocate: allocatedBytes="
                                + mx.getTotalAllocatedBytes());
            }
            System.out.println("Re-borrow reuses the released segment. (4/5)");

            // Oracle 5: distinct bucket sizes register as separate buckets.
            // borrow(2000) → power of two = 2048, new bucket.
            MemorySegment s2000 = pool.borrow(2000);
            if (mx.getActiveBucketCount() != 2) {
                throw new RuntimeException(
                        "bucketCount after second size=" + mx.getActiveBucketCount());
            }
            if (mx.getTotalAllocatedBytes() != allocBefore + 2048) {
                throw new RuntimeException(
                        "allocatedBytes after 2KB borrow=" + mx.getTotalAllocatedBytes());
            }
            // borrow(50000) → 65536. 3rd bucket.
            MemorySegment s50k = pool.borrow(50000);
            if (mx.getActiveBucketCount() != 3) {
                throw new RuntimeException(
                        "bucketCount after third size=" + mx.getActiveBucketCount());
            }
            if (mx.getTotalAllocatedBytes() != allocBefore + 2048 + 65536) {
                throw new RuntimeException(
                        "allocatedBytes after 50KB borrow=" + mx.getTotalAllocatedBytes());
            }
            System.out.println("activeBucketCount tracks distinct power-of-two sizes. (5/5)");

            System.out.println("--- BufferPool MXBean Surface Test PASSED ---");
        }
    }
}
