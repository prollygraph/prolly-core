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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link HeapBufferPool}. The pool's borrow-size rounding is what makes downstream
 * consumers (which target {@code DirectBufferPool} sizes) work uniformly regardless of which pool
 * is used. Drift in the power-of-two math, the 1024-byte minimum, or close()/release() no-op
 * semantics would silently degrade those downstream contracts.
 */
class HeapBufferPoolTest {

    @Test
    void borrow_returns_at_least_requested_size() {
        try (HeapBufferPool p = new HeapBufferPool()) {
            MemorySegment s = p.borrow(7);
            assertTrue(s.byteSize() >= 7);
        }
    }

    @Test
    void borrow_minimum_size_is_1024() {
        try (HeapBufferPool p = new HeapBufferPool()) {
            // Even a 1-byte borrow gets bumped to 1024 to match DirectBufferPool's
            // bucket layout.
            assertEquals(1024, p.borrow(1).byteSize());
            assertEquals(1024, p.borrow(512).byteSize());
            assertEquals(1024, p.borrow(1024).byteSize());
        }
    }

    @Test
    void borrow_zero_yields_minimum_size() {
        try (HeapBufferPool p = new HeapBufferPool()) {
            // The implementation guards against zero (`if (n <= 0) return 1024`).
            assertEquals(1024, p.borrow(0).byteSize());
        }
    }

    @Test
    void borrow_negative_yields_minimum_size() {
        try (HeapBufferPool p = new HeapBufferPool()) {
            // Defensive: negative request must not throw or allocate something
            // huge via underflow.
            assertEquals(1024, p.borrow(-1).byteSize());
            assertEquals(1024, p.borrow(Integer.MIN_VALUE).byteSize());
        }
    }

    @Test
    void borrow_rounds_up_to_next_power_of_two() {
        try (HeapBufferPool p = new HeapBufferPool()) {
            assertEquals(
                    2048, p.borrow(1025).byteSize(), "1025 → 2048 (next power of two above 1024)");
            assertEquals(2048, p.borrow(2048).byteSize());
            assertEquals(4096, p.borrow(2049).byteSize());
            assertEquals(4096, p.borrow(4096).byteSize());
            assertEquals(8192, p.borrow(4097).byteSize());
        }
    }

    @Test
    void borrow_large_size_rounds_up() {
        try (HeapBufferPool p = new HeapBufferPool()) {
            assertEquals(65536, p.borrow(65535).byteSize());
            assertEquals(65536, p.borrow(65536).byteSize());
            assertEquals(131072, p.borrow(65537).byteSize());
        }
    }

    @Test
    void borrow_returns_fresh_segments() {
        // No pooling — each borrow returns a distinct allocation.
        try (HeapBufferPool p = new HeapBufferPool()) {
            MemorySegment a = p.borrow(1024);
            MemorySegment b = p.borrow(1024);
            assertNotSame(a, b);
            // Cross-check: mutating one must not affect the other.
            a.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0x42);
            assertEquals(
                    0,
                    b.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0),
                    "borrows must be independent allocations");
        }
    }

    @Test
    void close_is_idempotent_noop() {
        HeapBufferPool p = new HeapBufferPool();
        p.close();
        // Closing twice must not throw — the pool is a no-op stand-in.
        assertDoesNotThrow(p::close);
    }

    @Test
    void release_is_noop_and_safe_with_null() {
        try (HeapBufferPool p = new HeapBufferPool()) {
            assertDoesNotThrow(() -> p.release(null));
            assertDoesNotThrow(() -> p.release(p.borrow(64)));
        }
    }

    @Test
    void segments_are_zero_initialized() {
        // MemorySegment.ofArray(new byte[N]) is zero-init per JLS;
        // pin that the pool doesn't hand out reused dirty buffers.
        try (HeapBufferPool p = new HeapBufferPool()) {
            MemorySegment s = p.borrow(1024);
            for (long i = 0; i < s.byteSize(); i++) {
                assertEquals(
                        0,
                        s.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i),
                        "borrowed segment must be zero-initialized at offset " + i);
            }
        }
    }

    /**
     * borrowRetained allocates EXACT size — no power-of-two, no 1024 floor. A retained key is never
     * recycled (ADR-0062 D-3), so rounding is pure live-heap amplification: the 1 KiB floor turned
     * every 42-byte staged quad key into 24× its size for a whole transaction (the measured
     * bulk-ingest OOM this method exists to end). The backing array length is the proof — a segment
     * slice could lie about it.
     */
    @Test
    void borrowRetainedAllocatesExactSizeWithNoFloor() {
        try (HeapBufferPool p = new HeapBufferPool()) {
            for (int size : new int[] {1, 42, 1000, 1024, 1025}) {
                MemorySegment s = p.borrowRetained(size);
                assertEquals(size, s.byteSize(), "segment size for " + size);
                byte[] backing =
                        (byte[])
                                s.heapBase()
                                        .orElseThrow(
                                                () ->
                                                        new AssertionError(
                                                                "heap pool must return heap-backed segments"));
                assertEquals(size, backing.length, "BACKING array must be exact for " + size);
            }
        }
    }

    /** The interface default passes through to borrow — arena pools keep their bucket layout. */
    @Test
    void borrowRetainedDefaultsToPlainBorrow() {
        BufferPool bucketed =
                new BufferPool() {
                    @Override
                    public MemorySegment borrow(int size) {
                        return MemorySegment.ofArray(new byte[2048]); // a "bucket"
                    }

                    @Override
                    public void close() {}
                };
        assertEquals(2048, bucketed.borrowRetained(42).byteSize(), "default = borrow's bucket");
    }
}
