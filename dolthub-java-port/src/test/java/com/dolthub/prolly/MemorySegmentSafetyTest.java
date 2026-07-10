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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Panama / {@code MemorySegment} safety as real JUnit assertions
 * (plans/core-engine-test-strategy.md Step 15). The whole point is that misuse of the off-heap /
 * Foreign-Function-and-Memory surface produces a <b>clean Java exception, never a native
 * segfault</b> that would take the JVM down. Each test here drives a misuse and asserts the
 * exception type.
 *
 * <p>NOTE: a pre-existing {@code PanamaLowLevelTest} covers similar ground but is a {@code public
 * static void main} program with zero {@code @Test} annotations — it never runs under surefire (a
 * "dark test"). This class supersedes its load-bearing checks as actually-executed JUnit; whether
 * to delete the dark file is left to the maintainer (see Step 15 wrap-up).
 */
class MemorySegmentSafetyTest {

    // ---- bounds: out-of-range access throws, never faults ----

    @Test
    void sliceBeyondSegmentEnd_throwsIndexOutOfBounds() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(16);
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> seg.asSlice(8, 16),
                    "a slice that runs past the segment end must throw, not fault");
        }
    }

    @Test
    void readPastSliceEnd_throwsIndexOutOfBounds() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(16);
            MemorySegment slice = seg.asSlice(8, 4); // [8,12)
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> slice.get(ValueLayout.JAVA_INT_UNALIGNED, 4),
                    "reading past a slice's own bounds must throw");
        }
    }

    // ---- lifecycle: use-after-close throws IllegalStateException ----

    @Test
    void useAfterArenaClose_throwsIllegalState() {
        MemorySegment escaped;
        try (Arena arena = Arena.ofConfined()) {
            escaped = arena.allocate(8);
            escaped.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 1L); // fine while open
        }
        assertThrows(
                IllegalStateException.class,
                () -> escaped.get(ValueLayout.JAVA_LONG_UNALIGNED, 0),
                "accessing a segment after its arena closed must throw, not read freed memory");
    }

    // ---- confinement: a confined segment rejects another thread ----

    @Test
    void confinedSegmentAccessedFromAnotherThread_throwsWrongThread() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread other =
                    new Thread(
                            () -> {
                                try {
                                    seg.get(ValueLayout.JAVA_LONG_UNALIGNED, 0); // wrong thread
                                } catch (Throwable t) {
                                    thrown.set(t);
                                }
                            });
            other.start();
            other.join();
            assertTrue(
                    thrown.get() instanceof WrongThreadException,
                    "a confined segment must reject a non-owning thread with WrongThreadException, got "
                            + thrown.get());
        }
    }

    // ---- Tuple: zero-length field round-trips as null (the empty==null encoding) ----

    @Test
    void zeroLengthTupleField_roundTripsAsNull() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, new byte[0]);
            Tuple t = tb.build();
            assertEquals(1, t.count(), "the field still occupies an offset-table slot");
            // start == end for an empty field → decoded as the null encoding.
            assertNull(
                    t.getField(0),
                    "an empty byte[] field round-trips as null (start==end null encoding) — "
                            + "this is why an empty key is indistinguishable from a null field");
        }
    }

    // ---- Tuple: uint16 offset limit at and over the 64KB boundary ----

    @Test
    void tupleAtUint16Boundary_builds() {
        // totalSize = dataSize + count*2 + 2; one field → L + 4. L=65531 → 65535 (max).
        try (HeapBufferPool pool = new HeapBufferPool()) {
            byte[] big = new byte[65531];
            for (int i = 0; i < big.length; i++) big[i] = (byte) i;
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, big);
            Tuple t = tb.build();
            assertEquals(
                    65535L,
                    t.segment().byteSize(),
                    "max representable tuple is exactly 65535 bytes");
            assertArrayEquals(big, t.getField(0), "the boundary-sized field must read back intact");
        }
    }

    @Test
    void tupleOverUint16Boundary_throwsCleanly() {
        // L=65532 → totalSize 65536 > 65535 → must throw IllegalArgumentException,
        // NOT silently truncate the uint16 offset (which would corrupt the tuple).
        try (HeapBufferPool pool = new HeapBufferPool()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, new byte[65532]);
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            tb::build,
                            "a tuple whose size exceeds the uint16 offset range must be rejected");
            assertTrue(
                    ex.getMessage().contains("65535") || ex.getMessage().contains("too large"),
                    "the rejection must name the limit, got: " + ex.getMessage());
        }
    }
}
