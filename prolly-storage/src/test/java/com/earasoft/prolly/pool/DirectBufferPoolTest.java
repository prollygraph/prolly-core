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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage for {@link DirectBufferPool}, complementing {@code PoolStressTest}
 * (concurrent burn-in). Pins sizing math, release/reuse semantics, zero-fill on reuse (so leaked
 * bytes from a previous tenant can't leak through), and MXBean counters.
 */
class DirectBufferPoolTest {

    @Test
    void borrow_returns_at_least_requested_size() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            assertTrue(p.borrow(7).byteSize() >= 7);
        }
    }

    @Test
    void borrow_minimum_is_1024() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            assertEquals(1024, p.borrow(1).byteSize());
            assertEquals(1024, p.borrow(512).byteSize());
            assertEquals(1024, p.borrow(1024).byteSize());
        }
    }

    @Test
    void borrow_rounds_up_to_next_power_of_two() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            assertEquals(2048, p.borrow(1025).byteSize());
            assertEquals(2048, p.borrow(2048).byteSize());
            assertEquals(4096, p.borrow(2049).byteSize());
            assertEquals(8192, p.borrow(4097).byteSize());
        }
    }

    @Test
    void zero_request_yields_minimum_size() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            assertEquals(1024, p.borrow(0).byteSize());
        }
    }

    @Test
    void release_returns_segment_to_pool() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            MemorySegment s = p.borrow(1024);
            // Write a marker so we can detect reuse via zero-fill.
            s.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x42);
            p.release(s);
            MemorySegment s2 = p.borrow(1024);
            // The pool reuses the same bucket; reuse should zero the buffer.
            assertEquals(
                    0,
                    s2.get(ValueLayout.JAVA_BYTE, 0),
                    "reused segments must be zero-filled to prevent data leaks");
        }
    }

    @Test
    void release_without_matching_bucket_is_silent_noop() {
        // Releasing a segment for a bucket that was never borrowed is a no-op
        // (computeIfAbsent only happens on borrow). Construct a segment we
        // didn't borrow and confirm release() doesn't throw.
        try (DirectBufferPool p = new DirectBufferPool()) {
            MemorySegment foreign = MemorySegment.ofArray(new byte[2048]);
            assertDoesNotThrow(() -> p.release(foreign));
        }
    }

    // ---- MXBean counters ----

    @Test
    void borrow_count_increments() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            long before = p.getBorrowedCount();
            p.borrow(1024);
            p.borrow(2048);
            p.borrow(4096);
            assertEquals(before + 3, p.getBorrowedCount());
        }
    }

    @Test
    void release_count_increments() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            MemorySegment s = p.borrow(1024);
            long before = p.getReleasedCount();
            p.release(s);
            assertEquals(before + 1, p.getReleasedCount());
        }
    }

    @Test
    void allocated_bytes_track_fresh_allocations_only() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            long before = p.getTotalAllocatedBytes();
            MemorySegment s = p.borrow(1024); // fresh: +1024
            assertEquals(before + 1024, p.getTotalAllocatedBytes());
            p.release(s);
            p.borrow(1024); // reused: no delta
            assertEquals(
                    before + 1024,
                    p.getTotalAllocatedBytes(),
                    "reuse from the pool must NOT bump allocatedBytes");
        }
    }

    @Test
    void allocated_bytes_grow_with_distinct_bucket_sizes() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            long before = p.getTotalAllocatedBytes();
            p.borrow(1024);
            p.borrow(2048);
            p.borrow(4096);
            assertEquals(before + 1024 + 2048 + 4096, p.getTotalAllocatedBytes());
        }
    }

    @Test
    void active_bucket_count_tracks_distinct_sizes() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            int before = p.getActiveBucketCount();
            p.borrow(1024);
            p.borrow(2048);
            p.borrow(2048); // same bucket as previous → no new bucket
            assertEquals(before + 2, p.getActiveBucketCount());
        }
    }

    // ---- determinism / sanity ----

    @Test
    void borrowed_segments_independent_writes_do_not_collide() {
        try (DirectBufferPool p = new DirectBufferPool()) {
            MemorySegment a = p.borrow(1024);
            MemorySegment b = p.borrow(1024);
            a.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x11);
            b.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x22);
            assertEquals(0x11, a.get(ValueLayout.JAVA_BYTE, 0));
            assertEquals(0x22, b.get(ValueLayout.JAVA_BYTE, 0));
        }
    }

    @Test
    void same_bucket_different_borrows_can_coexist() {
        // Two borrows from the same bucket before any release — must produce
        // two distinct segments.
        try (DirectBufferPool p = new DirectBufferPool()) {
            MemorySegment a = p.borrow(1024);
            MemorySegment b = p.borrow(1024);
            assertNotSame(a, b);
            assertEquals(a.byteSize(), b.byteSize());
        }
    }

    @Test
    void close_releases_arena() {
        DirectBufferPool p = new DirectBufferPool();
        p.borrow(1024);
        // Closing must not throw.
        assertDoesNotThrow(p::close);
        // A second close on a shared arena throws — pin that we don't pretend
        // to be idempotent (caller must not double-close).
        assertThrows(IllegalStateException.class, p::close);
    }

    // ---- transaction scope (the off-heap write-path leak fix) ----

    @Test
    void newTransactionScope_isAFreshIndependentPool_parentUntouched() {
        // The leak fix (docs/write-ups/direct-buffer-pool-write-path-leak.md): the write path
        // borrows scratch
        // and never releases it into the single shared arena, so the off-heap footprint grew
        // without bound. An arena-backed pool's transaction scope is a FRESH child whose arena is
        // freed wholesale at the scope boundary — bounding the footprint to one transaction.
        try (DirectBufferPool shared = new DirectBufferPool()) {
            com.dolthub.prolly.BufferPool scope = shared.newTransactionScope();
            assertNotSame(
                    shared, scope, "an arena-backed pool's scope is a FRESH child, not itself");
            assertInstanceOf(
                    DirectBufferPool.class, scope, "the scope is itself a DirectBufferPool");

            // Borrowing from the scope allocates in the SCOPE's arena; the shared pool stays at
            // zero — this is what keeps the shared pool from accumulating per-transaction scratch.
            scope.borrow(64);
            assertTrue(
                    ((DirectBufferPool) scope).getTotalAllocatedBytes() > 0, "the scope allocated");
            assertEquals(
                    0L,
                    shared.getTotalAllocatedBytes(),
                    "the shared pool must NOT allocate when its scope is borrowed from");

            scope.close(); // frees the scope's arena wholesale; close() throws nothing
            assertDoesNotThrow(
                    () -> shared.borrow(32), "the shared pool is independent of a closed scope");
        }
    }
}
