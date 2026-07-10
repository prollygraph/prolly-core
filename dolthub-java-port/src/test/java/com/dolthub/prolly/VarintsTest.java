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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link Varints}. Varint encoding is on the critical path for every
 * chunk serialization; every boundary value matters and any drift breaks bit-compat with Dolt's Go
 * side.
 */
class VarintsTest {

    // ---- single-value boundary tests ----

    @Test
    void encode_zero_uses_one_byte() {
        ByteBuffer bb = ByteBuffer.allocate(10);
        Varints.putUvarint(bb, 0);
        assertEquals(1, bb.position());
        assertEquals(0, bb.get(0));
    }

    @Test
    void encode_127_uses_one_byte_max_one_byte_value() {
        ByteBuffer bb = ByteBuffer.allocate(10);
        Varints.putUvarint(bb, 127);
        assertEquals(1, bb.position());
        assertEquals(127, bb.get(0));
    }

    @Test
    void encode_128_uses_two_bytes_first_two_byte_value() {
        ByteBuffer bb = ByteBuffer.allocate(10);
        Varints.putUvarint(bb, 128);
        assertEquals(2, bb.position());
        assertEquals((byte) 0x80, bb.get(0)); // continuation bit set, payload=0
        assertEquals((byte) 0x01, bb.get(1));
    }

    @Test
    void encode_16383_uses_two_bytes_max_two_byte_value() {
        // 16383 = 2^14 - 1
        ByteBuffer bb = ByteBuffer.allocate(10);
        Varints.putUvarint(bb, 16383);
        assertEquals(2, bb.position());
    }

    @Test
    void encode_16384_uses_three_bytes_first_three_byte_value() {
        ByteBuffer bb = ByteBuffer.allocate(10);
        Varints.putUvarint(bb, 16384);
        assertEquals(3, bb.position());
    }

    @Test
    void encode_max_long_uses_ten_bytes() {
        // 0x7FFF_FFFF_FFFF_FFFF (max long) — 63 bits → 9 bytes.
        // Negative as signed (Long.MIN_VALUE) — unsigned 0x8000... = 10 bytes.
        ByteBuffer bb = ByteBuffer.allocate(10);
        Varints.putUvarint(bb, Long.MIN_VALUE); // = 0x8000...
        assertEquals(10, bb.position(), "max unsigned varint encodes as 10 bytes");
    }

    // ---- round-trip ----

    @Test
    void roundtrip_zero() {
        roundtripOne(0);
    }

    @Test
    void roundtrip_powers_of_two() {
        for (int shift = 0; shift < 63; shift++) {
            roundtripOne(1L << shift);
        }
    }

    @Test
    void roundtrip_powers_of_two_minus_one() {
        for (int shift = 1; shift < 64; shift++) {
            roundtripOne((1L << shift) - 1);
        }
    }

    @Test
    void roundtrip_max_signed_long() {
        roundtripOne(Long.MAX_VALUE);
    }

    @Test
    void roundtrip_max_unsigned_long_as_signed_min() {
        // Long.MIN_VALUE as unsigned is the max u64.
        roundtripOne(Long.MIN_VALUE);
    }

    @Test
    void roundtrip_random_1000_values() {
        Random rng = new Random(42);
        for (int i = 0; i < 1000; i++) {
            roundtripOne(rng.nextLong());
        }
    }

    private void roundtripOne(long v) {
        ByteBuffer bb = ByteBuffer.allocate(10);
        Varints.putUvarint(bb, v);
        bb.flip();
        long decoded = Varints.getUvarint(bb);
        assertEquals(v, decoded, "round-trip mismatch for " + v);
    }

    // ---- multi-value list encoding ----

    @Test
    void encodeVarints_empty_list_empty_output() {
        byte[] out = Varints.encodeVarints(List.of());
        assertEquals(0, out.length);
    }

    @Test
    void encodeVarints_and_decodeVarints_roundtrip() {
        List<Long> input = List.of(0L, 1L, 127L, 128L, 16383L, 16384L, Long.MAX_VALUE);
        byte[] encoded = Varints.encodeVarints(input);
        List<Long> decoded = Varints.decodeVarints(encoded, input.size());
        assertEquals(input, decoded);
    }

    @Test
    void encodeVarints_packs_small_values_tightly() {
        // All values <128 should encode as 1 byte each.
        List<Long> input = new ArrayList<>();
        for (int i = 0; i < 100; i++) input.add((long) i);
        byte[] encoded = Varints.encodeVarints(input);
        assertEquals(100, encoded.length, "100 values <128 should be 100 bytes");
    }

    // ---- getUvarintAt — prefix sums ----

    @Test
    void getUvarintAt_returns_prefix_sum() {
        byte[] encoded = Varints.encodeVarints(List.of(10L, 20L, 30L, 40L));
        ByteBuffer bb = ByteBuffer.wrap(encoded);
        assertEquals(10L, Varints.getUvarintAt(bb, 0));
        assertEquals(30L, Varints.getUvarintAt(bb, 1));
        assertEquals(60L, Varints.getUvarintAt(bb, 2));
        assertEquals(100L, Varints.getUvarintAt(bb, 3));
    }

    @Test
    void getUvarintAt_does_not_advance_position() {
        byte[] encoded = Varints.encodeVarints(List.of(10L, 20L, 30L));
        ByteBuffer bb = ByteBuffer.wrap(encoded);
        int posBefore = bb.position();
        Varints.getUvarintAt(bb, 2);
        assertEquals(posBefore, bb.position(), "getUvarintAt must preserve caller's position");
    }

    // ---- error paths ----

    @Test
    void getUvarint_throws_on_buffer_underflow() {
        // Empty buffer — no bytes to read → BufferUnderflowException
        ByteBuffer bb = ByteBuffer.allocate(0);
        assertThrows(java.nio.BufferUnderflowException.class, () -> Varints.getUvarint(bb));
    }

    @Test
    void getUvarint_throws_on_truncated_multi_byte() {
        // Continuation byte set, but no follow-up.
        ByteBuffer bb = ByteBuffer.wrap(new byte[] {(byte) 0x80});
        assertThrows(java.nio.BufferUnderflowException.class, () -> Varints.getUvarint(bb));
    }

    @Test
    void getUvarint_rejects_an_overlong_varint() {
        // 11 continuation bytes — a malformed varint that, before the 10-byte cap, looped unbounded
        // (and shifted `s` past 63, which is undefined). It now fails closed with a clear message
        // rather than reading on. core-fail-closed-bounds Step 1 / D-2.
        byte[] overlong = new byte[11];
        java.util.Arrays.fill(overlong, (byte) 0x80);
        ByteBuffer bb = ByteBuffer.wrap(overlong);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> Varints.getUvarint(bb));
        assertTrue(ex.getMessage().contains("varint exceeds 10 bytes"), ex.getMessage());
    }

    @Test
    void getUvarint_still_accepts_a_valid_10_byte_varint() {
        // Max unsigned u64 (= Long.MIN_VALUE as signed) encodes to exactly 10 bytes — the cap must
        // NOT reject a legitimately-maximal varint (the in-range-unchanged contract, D-3).
        ByteBuffer bb = ByteBuffer.allocate(10);
        Varints.putUvarint(bb, Long.MIN_VALUE);
        bb.flip();
        assertEquals(Long.MIN_VALUE, Varints.getUvarint(bb), "valid 10-byte varint still decodes");
    }

    // ---- determinism + bit-compat ----

    @Test
    void encoding_is_deterministic() {
        // Same input → same encoding bytes every time.
        ByteBuffer bb1 = ByteBuffer.allocate(10);
        ByteBuffer bb2 = ByteBuffer.allocate(10);
        Varints.putUvarint(bb1, 0xDEADBEEFL);
        Varints.putUvarint(bb2, 0xDEADBEEFL);
        assertArrayEquals(bb1.array(), bb2.array());
    }
}
