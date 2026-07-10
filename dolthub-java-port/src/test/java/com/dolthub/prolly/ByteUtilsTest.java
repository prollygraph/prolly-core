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
 * SQLite-grade coverage for {@link ByteUtils}. compareUnsigned drives every binary-parity index
 * lookup; isPrefix gates range scans; increment is the "successor key" used by all upper-bound
 * exclusive scans. Drift here corrupts every higher layer.
 */
class ByteUtilsTest {

    // ---- compareUnsigned(MemorySegment) ----

    @Test
    void compare_equal_empty() {
        assertEquals(0, ByteUtils.compareUnsigned(seg(), seg()));
    }

    @Test
    void compare_equal_identical_content() {
        assertEquals(0, ByteUtils.compareUnsigned(seg(0x01, 0x02), seg(0x01, 0x02)));
    }

    @Test
    void compare_first_byte_differs() {
        assertTrue(ByteUtils.compareUnsigned(seg(0x01), seg(0x02)) < 0);
        assertTrue(ByteUtils.compareUnsigned(seg(0x02), seg(0x01)) > 0);
    }

    @Test
    void compare_treats_bytes_as_unsigned() {
        // 0xFF should be GREATER than 0x01 (not less, which signed Java would say).
        assertTrue(ByteUtils.compareUnsigned(seg(0xFF), seg(0x01)) > 0);
        assertTrue(ByteUtils.compareUnsigned(seg(0x80), seg(0x7F)) > 0);
    }

    @Test
    void compare_shorter_is_less_when_prefix_matches() {
        // "ab" < "abc"
        assertTrue(ByteUtils.compareUnsigned(seg(0x61, 0x62), seg(0x61, 0x62, 0x63)) < 0);
    }

    @Test
    void compare_longer_is_greater_when_prefix_matches() {
        assertTrue(ByteUtils.compareUnsigned(seg(0x61, 0x62, 0x63), seg(0x61, 0x62)) > 0);
    }

    @Test
    void compare_empty_lt_anything_nonempty() {
        assertTrue(ByteUtils.compareUnsigned(seg(), seg(0x00)) < 0);
        assertTrue(ByteUtils.compareUnsigned(seg(0x00), seg()) > 0);
    }

    @Test
    void compare_mismatched_lengths_with_differing_byte_uses_byte() {
        // "abc" vs "abd" — same length, last byte differs.
        assertTrue(ByteUtils.compareUnsigned(seg(0x61, 0x62, 0x63), seg(0x61, 0x62, 0x64)) < 0);
    }

    @Test
    void compare_uses_first_differing_byte() {
        // "axb" vs "ayb" — first byte at index 1 differs, ignore the trailing match.
        assertTrue(ByteUtils.compareUnsigned(seg(0x61, 0x78, 0x62), seg(0x61, 0x79, 0x62)) < 0);
    }

    // ---- compareUnsigned(byte[]) ----

    @Test
    void compare_byte_array_mirrors_segment() {
        assertEquals(0, ByteUtils.compareUnsigned(new byte[] {1, 2}, new byte[] {1, 2}));
        assertTrue(ByteUtils.compareUnsigned(new byte[] {1, 2}, new byte[] {1, 3}) < 0);
        assertTrue(ByteUtils.compareUnsigned(new byte[] {(byte) 0xFF}, new byte[] {0x01}) > 0);
    }

    // ---- isPrefix ----

    @Test
    void isPrefix_self_is_prefix() {
        MemorySegment s = seg(0x01, 0x02, 0x03);
        assertTrue(ByteUtils.isPrefix(s, s));
    }

    @Test
    void isPrefix_empty_is_prefix_of_anything() {
        assertTrue(ByteUtils.isPrefix(seg(), seg(0x01, 0x02)));
        assertTrue(ByteUtils.isPrefix(seg(), seg()));
    }

    @Test
    void isPrefix_strict_prefix() {
        assertTrue(ByteUtils.isPrefix(seg(0x01, 0x02), seg(0x01, 0x02, 0x03)));
    }

    @Test
    void isPrefix_longer_than_target_is_false() {
        assertFalse(ByteUtils.isPrefix(seg(0x01, 0x02, 0x03), seg(0x01, 0x02)));
    }

    @Test
    void isPrefix_first_byte_mismatch() {
        assertFalse(ByteUtils.isPrefix(seg(0x01), seg(0x02, 0x03)));
    }

    @Test
    void isPrefix_late_byte_mismatch() {
        assertFalse(ByteUtils.isPrefix(seg(0x01, 0x02, 0x03), seg(0x01, 0x02, 0x04)));
    }

    // ---- increment ----

    @Test
    void increment_single_byte_normal_case() {
        assertArrayEquals(new byte[] {0x02}, ByteUtils.increment(new byte[] {0x01}));
        assertArrayEquals(new byte[] {0x10}, ByteUtils.increment(new byte[] {0x0F}));
    }

    @Test
    void increment_trailing_FF_carries_truncates() {
        // [0x01, 0xFF] — last byte saturated; carry to position 0 → [0x02]
        // (NOT [0x02, 0x00] — the result truncates the now-irrelevant trailing bytes
        //  because we want the SMALLEST greater-and-not-prefix sequence)
        assertArrayEquals(new byte[] {0x02}, ByteUtils.increment(new byte[] {0x01, (byte) 0xFF}));
    }

    @Test
    void increment_multiple_trailing_FF() {
        assertArrayEquals(
                new byte[] {0x02},
                ByteUtils.increment(new byte[] {0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
    }

    @Test
    void increment_all_FF_returns_null() {
        assertNull(ByteUtils.increment(new byte[] {(byte) 0xFF}));
        assertNull(ByteUtils.increment(new byte[] {(byte) 0xFF, (byte) 0xFF}));
    }

    @Test
    void increment_empty_returns_null() {
        assertNull(ByteUtils.increment(new byte[0]));
    }

    @Test
    void increment_zero_byte() {
        assertArrayEquals(new byte[] {0x01}, ByteUtils.increment(new byte[] {0x00}));
    }

    @Test
    void increment_does_not_mutate_input() {
        byte[] input = new byte[] {0x01, 0x02, 0x03};
        byte[] before = input.clone();
        ByteUtils.increment(input);
        assertArrayEquals(before, input);
    }

    @Test
    void increment_result_is_greater_than_input() {
        // Property: for any non-saturated input, increment(x) > x lex.
        byte[][] cases = {
            {0x00},
            {0x01},
            {0x7F},
            {(byte) 0x80},
            {(byte) 0xFE},
            {0x00, 0x00},
            {0x01, 0x02},
            {0x7F, (byte) 0xFF},
            {0x01, (byte) 0xFF},
        };
        for (byte[] c : cases) {
            byte[] inc = ByteUtils.increment(c);
            assertNotNull(inc, "non-saturated input must produce a successor");
            assertTrue(
                    ByteUtils.compareUnsigned(c, inc) < 0,
                    "increment(" + java.util.Arrays.toString(c) + ") must be greater");
            // And NOT a prefix of the original (use byte-array overload).
            assertFalse(
                    ByteUtils.compareUnsigned(
                                            MemorySegment.ofArray(c),
                                            MemorySegment.ofArray(inc)
                                                    .asSlice(0, Math.min(inc.length, c.length)))
                                    == 0
                            && inc.length > c.length,
                    "increment must not be a prefix-extending result");
        }
    }

    // ---- helpers ----

    private static MemorySegment seg(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) b[i] = (byte) bytes[i];
        return MemorySegment.ofArray(b);
    }
}
