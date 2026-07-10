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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reusable use-after-free probes for the test suite (plans/off-heap-use-after-free-tests.md Phase 0
 * Step 2). Two instruments, one per the two detectable hazard families:
 *
 * <ul>
 *   <li>{@link #assertThrowsAfterClose} — H1: Panama already makes a closed-arena access *loud*
 *       ({@code IllegalStateException}); this asserts the exception fires through a class's own
 *       segment, i.e. the class did not defeat the net by copying to a longer-lived arena (D-2).
 *   <li>{@link #assertSameThroughPoolAndHeap} — H2/H3: the differential (D-3). Runs an operation
 *       through the {@link PoisoningBufferPool} and the {@link HeapBufferPool}; a use-after-free in
 *       the operation reads poisoned/aliased memory under the poison pool but live memory under the
 *       heap pool, so the results diverge (or the poison surfaces) — and this flags it.
 * </ul>
 *
 * @implNote test-only, beside {@link PoisoningBufferPool}; promote to a {@code test-jar} when a
 *     downstream module needs it (D-5).
 */
public final class ArenaScopeProbe {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private ArenaScopeProbe() {}

    /**
     * Assert a segment obtained from {@code arena} dies with it: it is readable while open, and
     * accessing it after {@code arena.close()} throws {@link IllegalStateException} (Panama's
     * temporal-safety net). The supplier is invoked once, before the close.
     */
    public static void assertThrowsAfterClose(
            Supplier<MemorySegment> segmentFromArena, Arena arena) {
        MemorySegment seg = segmentFromArena.get();
        seg.get(BYTE, 0); // alive while the arena is open
        arena.close();
        assertThrows(
                IllegalStateException.class,
                () -> seg.get(BYTE, 0),
                "segment access after Arena.close() must throw IllegalStateException, not read freed memory");
    }

    /**
     * Run {@code op} through the poison pool and the heap pool and assert the results are
     * byte-identical and carry no poison run. A divergence (or a poison run) means {@code op} read
     * freed/reused memory: under the heap pool a released block is never freed (the garbage
     * collector keeps it), so {@code op} sees its original bytes; under the poison pool the
     * released block is poisoned + reused, so a use-after-free reads something else. Identical
     * results + no poison ⇒ {@code op} is free of the release/aliasing hazards.
     */
    public static void assertSameThroughPoolAndHeap(Function<BufferPool, byte[]> op) {
        byte[] viaHeap;
        try (HeapBufferPool heap = new HeapBufferPool()) {
            viaHeap = op.apply(heap);
        }
        byte[] viaPoison;
        try (PoisoningBufferPool poison = new PoisoningBufferPool()) {
            viaPoison = op.apply(poison);
        }
        assertArrayEquals(
                viaHeap,
                viaPoison,
                "result differs between the heap pool and the poison pool — op reads freed/reused memory");
        assertFalse(
                containsPoisonRun(viaPoison, 4),
                "result contains a run of poison bytes — op read released memory");
    }

    /**
     * True iff {@code bytes} has {@code minRun}+ consecutive {@link PoisoningBufferPool#POISON}
     * bytes (a run, not a lone {@code 0xDE} that could occur in legitimate data).
     */
    public static boolean containsPoisonRun(byte[] bytes, int minRun) {
        int run = 0;
        for (byte b : bytes) {
            run = (b == PoisoningBufferPool.POISON) ? run + 1 : 0;
            if (run >= minRun) {
                return true;
            }
        }
        return false;
    }
}
