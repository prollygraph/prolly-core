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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/**
 * Self-test for {@link PoisoningBufferPool} — the instrument the whole use-after-free plan relies
 * on must itself be proven to detect the silent hazard (plans/off-heap-use-after-free-tests.md
 * Phase 0 Step 1). Pins: a use-after-release reads poison; a reused block aliases a stale {@code
 * asSlice} view; a live segment is not flagged (no false positive); borrow/release accounting.
 */
class PoisoningBufferPoolTest {

    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;

    @Test
    void useAfterRelease_readsPoison_isDetected() {
        try (PoisoningBufferPool pool = new PoisoningBufferPool()) {
            MemorySegment block = pool.borrow(8);
            MemorySegment view = block.asSlice(0, 8);
            view.set(LONG, 0, 42L);
            assertEquals(42L, view.get(LONG, 0), "live read returns the written value");
            assertFalse(PoisoningBufferPool.isPoisoned(view), "a live segment must not be flagged");

            pool.release(block); // free + poison

            // The stale view now reads poison, not the value it held — the use-after-free is
            // detectable.
            assertTrue(
                    PoisoningBufferPool.isPoisoned(view),
                    "use-after-release must be detectable as poison (the silent hazard made loud)");
            assertNotEquals(42L, view.get(LONG, 0));
        }
    }

    @Test
    void reuse_handsBackTheSameBlock_andAliasesAStaleSlice() {
        try (PoisoningBufferPool pool = new PoisoningBufferPool()) {
            MemorySegment a = pool.borrow(8);
            MemorySegment staleView = a.asSlice(0, 8);
            staleView.set(LONG, 0, 111L);
            pool.release(a); // poison + quarantine

            MemorySegment b = pool.borrow(8); // same bucket -> hands back a's block, poison intact
            assertTrue(
                    PoisoningBufferPool.isPoisoned(b),
                    "a reused block is handed back un-zeroed (poison intact) — unlike DirectBufferPool");

            // Writing the new tenant aliases the stale view — the exact silent corruption (H3) the
            // TableTest bug was. The poison pool reproduces it deterministically.
            b.asSlice(0, 8).set(LONG, 0, 222L);
            assertEquals(
                    222L,
                    staleView.get(LONG, 0),
                    "the reused block must alias the stale slice (proves the silent aliasing hazard)");
        }
    }

    @Test
    void freshBlocksAreZeroed_andNotFlaggedAsPoison() {
        try (PoisoningBufferPool pool = new PoisoningBufferPool()) {
            MemorySegment fresh = pool.borrow(8);
            assertEquals(
                    0L,
                    fresh.asSlice(0, 8).get(LONG, 0),
                    "a fresh arena block is zero-initialised");
            assertFalse(
                    PoisoningBufferPool.isPoisoned(fresh.asSlice(0, 8)),
                    "zeroed memory is not poison — no false positive");
        }
    }

    @Test
    void borrowAndReleaseAreCounted() {
        try (PoisoningBufferPool pool = new PoisoningBufferPool()) {
            MemorySegment a = pool.borrow(8);
            MemorySegment c = pool.borrow(4096);
            pool.release(a);
            assertEquals(2, pool.borrowedCount());
            assertEquals(1, pool.releasedCount());
            // reuse: borrowing size 8 again pulls a's quarantined block (bucket 1024), not a fresh
            // one
            MemorySegment reused = pool.borrow(8);
            assertEquals(PoisoningBufferPool.bucketSize(8), reused.byteSize());
            assertEquals(3, pool.borrowedCount());
            // touch c so it is not flagged unused
            assertEquals(PoisoningBufferPool.bucketSize(4096), c.byteSize());
        }
    }
}
