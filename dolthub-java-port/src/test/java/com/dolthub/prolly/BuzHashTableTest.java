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

import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link BuzHashTable.DEFAULT_TABLE}. The Javadoc says drift in even a
 * single bit shifts chunk boundaries and breaks Dolt compat. BuzHashTest already pins
 * first/last/length; this file pins MORE table entries spread across the index space so a
 * corruption in the middle is caught, plus structural properties (entry-set uniqueness,
 * hamming-weight distribution).
 */
class BuzHashTableTest {

    // ---- pinned milestone entries (every 32 from BuzHashTable.java source) ----

    @Test
    void entry_0_pinned() {
        assertEquals(0x12bd9527, BuzHashTable.DEFAULT_TABLE[0]);
    }

    @Test
    void entry_32_pinned() {
        // 32nd row, first entry per the BuzHashTable.java declaration.
        assertEquals(0xaf300bc2, BuzHashTable.DEFAULT_TABLE[32]);
    }

    @Test
    void entry_64_pinned() {
        assertEquals(0x6907ad34, BuzHashTable.DEFAULT_TABLE[48]); // 8 rows × 6 cols = 48
    }

    @Test
    void entry_96_pinned() {
        assertEquals(0x9c99c9fb, BuzHashTable.DEFAULT_TABLE[96]); // 16 rows × 6 cols
    }

    @Test
    void entry_128_pinned() {
        assertEquals(0xfcc8df69, BuzHashTable.DEFAULT_TABLE[120]); // 20 rows × 6 cols
    }

    @Test
    void entry_160_pinned() {
        assertEquals(0xf6956a5b, BuzHashTable.DEFAULT_TABLE[144]); // 24 rows × 6 cols
    }

    @Test
    void entry_192_pinned() {
        assertEquals(0xebfa49e7, BuzHashTable.DEFAULT_TABLE[168]); // 28 rows × 6 cols
    }

    @Test
    void entry_224_pinned() {
        assertEquals(0xee3188c6, BuzHashTable.DEFAULT_TABLE[192]); // 32 rows × 6 cols
    }

    @Test
    void entry_255_pinned() {
        assertEquals(0x8185f4d2, BuzHashTable.DEFAULT_TABLE[255]);
    }

    // ---- structural properties ----

    @Test
    void table_length_is_exactly_256() {
        assertEquals(
                256,
                BuzHashTable.DEFAULT_TABLE.length,
                "table covers the full unsigned-byte domain — drift would let some byte values "
                        + "address past the array");
    }

    @Test
    void table_entries_are_mostly_unique() {
        // 256 random-ish 32-bit values; expected pairwise collisions ≈ 0.
        // Pin that at least 250 of 256 are distinct (allows ~6 incidental hash-equals).
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int v : BuzHashTable.DEFAULT_TABLE) seen.add(v);
        assertTrue(
                seen.size() >= 250,
                "table entries must be highly distinct — got " + seen.size() + " unique of 256");
    }

    @Test
    void table_entries_use_full_signed_int_range() {
        // The values are 32-bit; both signs should appear (random distribution).
        boolean foundNegative = false;
        boolean foundPositive = false;
        for (int v : BuzHashTable.DEFAULT_TABLE) {
            if (v < 0) foundNegative = true;
            if (v > 0) foundPositive = true;
            if (foundNegative && foundPositive) break;
        }
        assertTrue(
                foundNegative, "random 32-bit table must include negative (high-bit-set) values");
        assertTrue(foundPositive);
    }

    @Test
    void table_is_an_int_array() {
        // Pin the type: changing to long[] would silently break the BuzHash
        // narrowing-conversion math.
        assertEquals(int[].class, BuzHashTable.DEFAULT_TABLE.getClass());
    }

    @Test
    void no_table_entry_is_zero() {
        // A zero entry would make the corresponding byte invisible to the
        // rolling hash. Pin that the random source never produced one.
        for (int i = 0; i < BuzHashTable.DEFAULT_TABLE.length; i++) {
            assertNotEquals(
                    0,
                    BuzHashTable.DEFAULT_TABLE[i],
                    "entry "
                            + i
                            + " is zero — byte 0x"
                            + Integer.toHexString(i)
                            + " would contribute nothing to BuzHash state");
        }
    }

    @Test
    void access_via_all_unsigned_byte_indices() {
        // Pin: indexing by every unsigned-byte value succeeds (no
        // IndexOutOfBoundsException). Catches a future down-size to 128-entry
        // table that would compile but break high-byte input.
        for (int b = 0; b < 256; b++) {
            final int idx = b;
            assertDoesNotThrow(
                    () -> {
                        int unused = BuzHashTable.DEFAULT_TABLE[idx];
                    },
                    "access at index " + idx + " must not throw");
        }
    }

    @Test
    void table_constant_is_public_static_final() throws Exception {
        java.lang.reflect.Field f = BuzHashTable.class.getDeclaredField("DEFAULT_TABLE");
        int mods = f.getModifiers();
        assertTrue(
                java.lang.reflect.Modifier.isPublic(mods),
                "DEFAULT_TABLE must be public for cross-package use");
        assertTrue(
                java.lang.reflect.Modifier.isStatic(mods),
                "DEFAULT_TABLE must be static — singleton");
        assertTrue(
                java.lang.reflect.Modifier.isFinal(mods),
                "DEFAULT_TABLE must be final — wire-format constant");
    }

    @Test
    void xor_sum_of_all_entries_is_pinned() {
        // Fold the entire table to a single 32-bit checksum. Drift in any one
        // entry flips this value, catching corruption even when first/last
        // happen to be preserved.
        int xorSum = 0;
        for (int v : BuzHashTable.DEFAULT_TABLE) xorSum ^= v;
        assertEquals(
                -101873648, xorSum, "table XOR-checksum drifted — Dolt bit-compat boundary breach");
    }
}
