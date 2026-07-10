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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/**
 * Self-test for the {@link ArenaScopeProbe} instruments (plans/off-heap-use-after-free-tests.md
 * Phase 0 Step 2) — like the {@link PoisoningBufferPool} self-test, the probes must themselves be
 * proven, both that they pass a safe operation and that they CATCH a deliberate use-after-free.
 */
class ArenaScopeProbeTest {

    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;

    @Test
    void assertThrowsAfterClose_passesForAnArenaBackedSegment() {
        Arena arena = Arena.ofShared();
        ArenaScopeProbe.assertThrowsAfterClose(() -> arena.allocate(8), arena); // closes the arena
    }

    @Test
    void differential_passesForASafeOp() {
        // Borrow → write → return the bytes; no release-then-read, so heap == poison.
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool -> {
                    MemorySegment s = pool.borrow(8);
                    s.asSlice(0, 8).set(LONG, 0, 7L);
                    return s.asSlice(0, 8).toArray(ValueLayout.JAVA_BYTE);
                });
    }

    @Test
    void differential_catchesAUseAfterFree() {
        // op reads a slice AFTER releasing its block: under the heap pool the slice still reads 7
        // (GC
        // keeps it), under the poison pool it reads poison — the differential must flag the
        // divergence.
        assertThrows(
                AssertionError.class,
                () ->
                        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                                pool -> {
                                    MemorySegment s = pool.borrow(8);
                                    MemorySegment v = s.asSlice(0, 8);
                                    v.set(LONG, 0, 7L);
                                    pool.release(s); // free
                                    return v.toArray(ValueLayout.JAVA_BYTE); // use-after-free
                                }));
    }

    @Test
    void containsPoisonRun_detectsRunsAndIgnoresLoneBytes() {
        byte p = PoisoningBufferPool.POISON;
        assertTrue(ArenaScopeProbe.containsPoisonRun(new byte[] {0, p, p, p, p}, 4));
        assertFalse(ArenaScopeProbe.containsPoisonRun(new byte[] {0, p, 1, p, 2, p}, 4));
    }
}
