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
     * The budget tier-change: past the byte budget the exact table CONVERTS to a blocked Bloom
     * filter spanning the same budget — never a crash, never an OOM, and above all never a false
     * absent: every added key keeps answering maybe. Absent keys keep a real chance of a fast "no"
     * (the graceful curve that replaced the always-maybe saturation cliff).
     */
    @Test
    void conversionToBloomKeepsEveryAddedKeyMaybeAndSomeAbsentsFast() {
        LongPresenceSet set = new LongPresenceSet(16 * 1024); // 2048-slot budget: converts at 1024
        assertFalse(set.isBloomForTest());
        for (long v = 1; v <= 5000; v++) {
            set.add(v);
        }
        assertTrue(set.isBloomForTest(), "the budget forced the Bloom tier");
        for (long v = 1; v <= 5000; v++) {
            assertTrue(set.mightContain(v), "NO FALSE ABSENT, ever — added key " + v);
        }
        int fastNo = 0;
        for (long v = 1_000_000; v < 1_002_000; v++) {
            if (!set.mightContain(v)) {
                fastNo++;
            }
        }
        assertTrue(fastNo > 0, "a Bloom is not always-maybe: some absents answer fast");
        assertTrue(set.size() <= 5000, "size freezes at the conversion point");
        set.clear();
        assertFalse(set.isBloomForTest(), "clear returns to the exact tier");
        set.add(7L);
        assertTrue(set.mightContain(7L));
        assertFalse(set.mightContain(8L), "exact again after clear");
    }

    /**
     * A budget above one shard's bit cap converts into MULTIPLE Bloom shards (a partitioned
     * filter), and the invariant holds across all of them: every added key stays maybe, absents
     * keep a fast no. 32 MiB budget = 2^28 bits = two 2^27-bit shards.
     */
    @Test
    void multiShardConversionKeepsEveryAddedKey() {
        LongPresenceSet set = new LongPresenceSet(32L * 1024 * 1024);
        long n = (32L * 1024 * 1024 / 8) / 2 + 8; // adds crossing the half-load conversion point
        for (long v = 1; v <= n; v++) {
            set.add(v * 0x9e3779b97f4a7c15L); // well-spread distinct values
        }
        assertTrue(set.isBloomForTest(), "the budget forced conversion");
        assertEquals(2, set.shardCountForTest(), "2^28 budget bits = two 2^27-bit shards");
        for (long v = 1; v <= n; v += 997) { // sampled: NO FALSE ABSENT across shards
            assertTrue(set.mightContain(v * 0x9e3779b97f4a7c15L));
        }
        int fastNo = 0;
        for (long v = 1; v <= 2000; v++) {
            if (!set.mightContain(v)) {
                fastNo++;
            }
        }
        assertTrue(fastNo > 0, "absents keep a fast no after conversion");
    }

    /**
     * Re-adding an EXISTING value at the capacity boundary must not force a spurious doubling — or,
     * at the budget ceiling, an irreversible Bloom conversion — when no new distinct key needs a
     * slot: duplicate detection runs before the capacity decision.
     */
    @Test
    void duplicateAddAtTheBoundaryDoesNotConvert() {
        LongPresenceSet set = new LongPresenceSet(16 * 1024); // 2048-slot ceiling
        for (long v = 1; v <= 1024; v++) {
            set.add(v); // exactly half load of the ceiling table: the boundary
        }
        assertFalse(set.isBloomForTest());
        for (long v = 1; v <= 1024; v++) {
            set.add(v); // duplicates at the boundary: no growth pressure exists
        }
        assertFalse(set.isBloomForTest(), "duplicate re-adds must not trigger conversion");
        assertEquals(1024, set.size());
        set.add(4242); // a genuinely NEW key at the ceiling converts
        assertTrue(set.isBloomForTest());
        assertTrue(set.mightContain(4242));
    }

    /** Zero survives the conversion pour — the sentinel value must not get lost mid-tier. */
    @Test
    void zeroSurvivesBloomConversion() {
        LongPresenceSet set = new LongPresenceSet(16 * 1024);
        set.add(0L);
        for (long v = 1; v <= 3000; v++) {
            set.add(v);
        }
        assertTrue(set.isBloomForTest());
        assertTrue(set.mightContain(0L), "zero was added pre-conversion and must stay maybe");
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
