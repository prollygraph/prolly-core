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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for {@link Tuple} + {@link TupleBuilder}
 * (plans/off-heap-use-after-free-tests.md Phase 1 Step 4) — the cluster the recycling regression
 * ({@code TableTest}) lived in, and the exemplar the recycling plan's Step 2 must keep green. Pins:
 * build is byte-identical through the poison pool and the heap pool (no use-after-free in {@code
 * build} today — H2/H3); the build-modify-rebuild reuse pattern stays correct (the {@code
 * TableTest} usage the recycling bug broke — H4); a tuple read after its pool's arena closes throws
 * rather than reading freed memory (H1).
 */
class TupleUseAfterFreeTest {

    private static long leLong(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void buildIsByteIdenticalThroughPoisonAndHeapPool() {
        // The differential gate: TupleBuilder.build copies fields into the final segment and
        // retains it
        // (releases nothing today), so building reads no freed memory — identical through both
        // pools.
        // The recycling plan's Step 2 must keep this green.
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool -> {
                    TupleBuilder tb = new TupleBuilder(pool);
                    tb.putInt64(0, 7L);
                    tb.putInt64(1, 42L);
                    tb.putField(2, utf8("abc"));
                    return tb.build().segment().toArray(ValueLayout.JAVA_BYTE);
                });
    }

    @Test
    void builderReuse_buildModifyRebuild_bothTuplesIntact() {
        // The exact TableTest pattern the recycling bug corrupted: build, modify one field, rebuild
        // —
        // relying on the un-modified fields being retained. Run under the poison pool so that when
        // recycling lands (recycling plan Step 2), any too-early release that aliases the first
        // tuple
        // trips this. Today (no recycling) both tuples must read back their own values.
        try (PoisoningBufferPool pool = new PoisoningBufferPool()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putInt64(0, 11L);
            tb.putField(1, utf8("Alice"));
            Tuple a = tb.build();

            tb.putField(1, utf8("Bob")); // modify one field, reuse the builder
            Tuple b = tb.build();

            assertEquals(11L, leLong(a.getField(0)), "prior tuple's int field corrupted by reuse");
            assertArrayEquals(
                    utf8("Alice"), a.getField(1), "prior tuple's string corrupted by reuse");
            assertEquals(11L, leLong(b.getField(0)));
            assertArrayEquals(utf8("Bob"), b.getField(1));
        }
    }

    @Test
    void closeRecyclesInt64ScratchButNotTheBuiltTuple() {
        // Recycling plan Step 2 (ADR-0062 D-2/D-3/D-4): close() returns the pool-borrowed int64
        // scratch to
        // the pool — and ONLY that. The retained tuple segment is never released, and a
        // heap-wrapped
        // putField segment was never a pool borrow.
        try (PoisoningBufferPool pool = new PoisoningBufferPool()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putInt64(0, 100L);
            tb.putInt64(1, 200L);
            tb.putInt64(2, 300L);
            tb.putField(3, utf8("heap")); // heap-wrapped — not a pool borrow
            Tuple t = tb.build(); // borrows the tuple segment (one pool borrow)

            assertEquals(
                    4,
                    pool.borrowedCount(),
                    "3 int64 scratch + 1 tuple segment (putField is heap)");
            assertEquals(0, pool.releasedCount(), "nothing recycled until close()");

            tb.close();
            assertEquals(
                    3,
                    pool.releasedCount(),
                    "close() recycles ONLY the 3 int64 scratch blocks — never the retained tuple segment");
            // The tuple still reads correctly: its segment was not released (or poisoned).
            assertEquals(100L, leLong(t.getField(0)));
            assertEquals(300L, leLong(t.getField(2)));
        }
    }

    @Test
    void builtTupleSurvivesCloseAndScratchReuseUnderPoison() {
        // The use-after-free the recycling bug risked: close() poisons + recycles the int64
        // scratch; a later
        // builder reuses those same blocks. A previously-built tuple must be unaffected, because
        // build()
        // copied the field bytes into the tuple's OWN (retained, never-released) segment.
        try (PoisoningBufferPool pool = new PoisoningBufferPool()) {
            TupleBuilder a = new TupleBuilder(pool);
            a.putInt64(0, 11L);
            a.putInt64(1, 22L);
            Tuple ta = a.build();
            assertEquals(11L, leLong(ta.getField(0)));
            a.close(); // recycles a's two int64 scratch blocks (poisoned + quarantined for reuse)

            try (TupleBuilder b = new TupleBuilder(pool)) {
                b.putInt64(0, 77L); // reuses a recycled (poisoned) block, refills it
                b.putInt64(1, 88L);
                Tuple tb = b.build();

                assertEquals(
                        11L, leLong(ta.getField(0)), "recycled scratch corrupted a prior tuple");
                assertEquals(
                        22L, leLong(ta.getField(1)), "recycled scratch corrupted a prior tuple");
                assertEquals(77L, leLong(tb.getField(0)));
                assertEquals(88L, leLong(tb.getField(1)));
            }
        }
    }

    @Test
    void useAfterCloseThrows() {
        // Fail-fast on the misuse that WOULD be a use-after-free: build()/putX after close() would
        // copy from
        // freed scratch (fields still alias the recycled blocks), so the guard throws instead.
        PoisoningBufferPool pool = new PoisoningBufferPool();
        try {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putInt64(0, 1L);
            tb.build();
            tb.close();
            assertThrows(IllegalStateException.class, () -> tb.putInt64(1, 2L));
            assertThrows(IllegalStateException.class, tb::build);
        } finally {
            pool.close();
        }
    }

    @Test
    void tupleReadAfterPoolArenaCloses_throws() {
        // H1: a tuple backed by a pool's arena must not be read after the pool (arena) closes.
        PoisoningBufferPool pool = new PoisoningBufferPool();
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putInt64(0, 5L);
        Tuple t = tb.build();
        assertEquals(5L, leLong(t.getField(0))); // alive while the pool's arena is open

        pool.close(); // closes the arena

        assertThrows(
                IllegalStateException.class,
                () -> t.getField(0),
                "a tuple read after its pool's arena closed must throw, not read freed memory");
    }
}
