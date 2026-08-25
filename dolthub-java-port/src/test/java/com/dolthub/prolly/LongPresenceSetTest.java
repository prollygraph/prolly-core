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
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LongPresenceSetTest {

    @Test
    void addedValuesAreContainedAbsentValuesAreNot() {
        LongPresenceSet set = new LongPresenceSet();
        long[] added = {1L, -1L, 42L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long v : added) {
            set.add(v);
        }
        for (long v : added) {
            assertTrue(set.mightContain(v), "added value must be contained: " + v);
        }
        assertEquals(added.length, set.size());
        assertFalse(set.mightContain(7L));
        assertFalse(set.mightContain(-42L));
    }

    @Test
    void zeroIsAnOrdinaryValueNotTheEmptySlot() {
        // Open-addressing sets classically reserve one value as "empty"; the
        // reserved value must still be storable or a key hashing to it would
        // silently report absent — the one failure mode a presence set must
        // never have.
        LongPresenceSet set = new LongPresenceSet();
        assertFalse(set.mightContain(0L));
        set.add(0L);
        assertTrue(set.mightContain(0L));
        assertEquals(1, set.size());
        set.add(0L); // idempotent
        assertEquals(1, set.size());
    }

    @Test
    void growthAcrossManyAddsLosesNothing() {
        LongPresenceSet set = new LongPresenceSet();
        Random rnd = new Random(7);
        Set<Long> reference = new HashSet<>();
        for (int i = 0; i < 200_000; i++) {
            long v = rnd.nextLong();
            reference.add(v);
            set.add(v);
        }
        assertEquals(reference.size(), set.size());
        for (long v : reference) {
            assertTrue(set.mightContain(v));
        }
        // absent probes stay absent (fresh RNG stream, filtered against reference)
        Random probe = new Random(8);
        for (int i = 0; i < 50_000; i++) {
            long v = probe.nextLong();
            if (!reference.contains(v)) {
                assertFalse(set.mightContain(v));
            }
        }
    }

    @Test
    void clearResetsToEmpty() {
        LongPresenceSet set = new LongPresenceSet();
        for (long v = -100; v < 100; v++) {
            set.add(v);
        }
        set.clear();
        assertEquals(0, set.size());
        assertFalse(set.mightContain(5L));
        set.add(5L); // usable after clear
        assertTrue(set.mightContain(5L));
    }

    /**
     * Saturation is the overflow-safety valve: past the max table size the set must degrade to
     * always-maybe (sound — every probe falls through to the real lookups), never crash on a
     * doubling that would overflow to a negative array size. The natural trigger is 2^29 distinct
     * adds; the seam forces the state so the behavior is pinned without an 8 GiB table.
     */
    @Test
    void saturationMeansAlwaysMaybeNeverACrash() {
        LongPresenceSet set = new LongPresenceSet();
        set.add(7L);
        set.saturateForTest();
        assertTrue(set.mightContain(7L));
        assertTrue(set.mightContain(999L), "saturated answers maybe for everything");
        assertTrue(set.mightContain(0L), "including the zero sentinel");
        set.add(123L); // no-op, no crash
        assertTrue(set.mightContain(123L));
        set.clear();
        assertFalse(set.mightContain(999L), "clear resets saturation with everything else");
        set.add(5L);
        assertTrue(set.mightContain(5L));
    }

    @Test
    void hashBytesIsStableAndSeparatesDifferingBytes() {
        MemorySegment a = MemorySegment.ofArray("term-a".getBytes(StandardCharsets.UTF_8));
        MemorySegment a2 = MemorySegment.ofArray("term-a".getBytes(StandardCharsets.UTF_8));
        MemorySegment b = MemorySegment.ofArray("term-b".getBytes(StandardCharsets.UTF_8));
        MemorySegment empty = MemorySegment.ofArray(new byte[0]);

        assertEquals(LongPresenceSet.hashBytes(a), LongPresenceSet.hashBytes(a2));
        assertNotEquals(LongPresenceSet.hashBytes(a), LongPresenceSet.hashBytes(b));
        // the empty segment hashes to the FNV offset basis — defined, not a crash
        assertEquals(LongPresenceSet.hashBytes(empty), LongPresenceSet.hashBytes(empty));
    }
}
