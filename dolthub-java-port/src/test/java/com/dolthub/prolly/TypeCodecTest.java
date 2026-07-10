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
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link TypeCodec}. Encode/decode round-trips for every primitive,
 * binary-parity ordering invariants (negatives sort before positives in raw byte order), and the
 * readXxx variants that intentionally use little-endian (without parity transformations) for
 * tuple-column reads.
 *
 * <p>Existing {@code BinaryParityTest} covers some of the same ground; this file adds boundary
 * values, float edge cases (NaN, ±Inf, ±0), UTF-8 strings, and the {@code compare} dispatch table.
 */
class TypeCodecTest {

    // ---- encodeInt64 / decodeInt64 round-trip ----

    @Test
    void int64_roundtrip_zero() {
        roundtripInt64(0);
    }

    @Test
    void int64_roundtrip_boundaries() {
        roundtripInt64(Long.MIN_VALUE);
        roundtripInt64(Long.MAX_VALUE);
        roundtripInt64(-1);
        roundtripInt64(1);
    }

    @Test
    void int64_roundtrip_random() {
        Random rng = new Random(42);
        for (int i = 0; i < 1000; i++) {
            roundtripInt64(rng.nextLong());
        }
    }

    @Test
    void int64_binary_parity_negative_sorts_before_positive() {
        // -1 must encode to bytes that sort BEFORE 0 (which sorts before 1).
        byte[] neg = new byte[8];
        byte[] zero = new byte[8];
        byte[] pos = new byte[8];
        TypeCodec.encodeInt64(-1, MemorySegment.ofArray(neg));
        TypeCodec.encodeInt64(0, MemorySegment.ofArray(zero));
        TypeCodec.encodeInt64(1, MemorySegment.ofArray(pos));
        assertTrue(ByteUtils.compareUnsigned(neg, zero) < 0);
        assertTrue(ByteUtils.compareUnsigned(zero, pos) < 0);
    }

    @Test
    void int64_binary_parity_extreme_negative_sorts_first() {
        byte[] minVal = new byte[8];
        byte[] maxVal = new byte[8];
        TypeCodec.encodeInt64(Long.MIN_VALUE, MemorySegment.ofArray(minVal));
        TypeCodec.encodeInt64(Long.MAX_VALUE, MemorySegment.ofArray(maxVal));
        assertTrue(ByteUtils.compareUnsigned(minVal, maxVal) < 0);
    }

    @Test
    void int64_binary_parity_total_order() {
        // For a sequence of N longs sorted naturally, their encodings sort
        // in byte order. Property test.
        Random rng = new Random(7);
        long[] values = new long[100];
        for (int i = 0; i < values.length; i++) values[i] = rng.nextLong();
        java.util.Arrays.sort(values);
        byte[] prev = null;
        for (long v : values) {
            byte[] cur = new byte[8];
            TypeCodec.encodeInt64(v, MemorySegment.ofArray(cur));
            if (prev != null) {
                assertTrue(
                        ByteUtils.compareUnsigned(prev, cur) <= 0,
                        "encoding broke total order at " + v);
            }
            prev = cur;
        }
    }

    // ---- encodeFloat64 / decodeFloat64 round-trip ----

    @Test
    void float64_roundtrip_zero() {
        roundtripFloat64(0.0);
        roundtripFloat64(-0.0);
    }

    @Test
    void float64_roundtrip_boundaries() {
        roundtripFloat64(Double.MIN_VALUE);
        roundtripFloat64(Double.MAX_VALUE);
        roundtripFloat64(-Double.MIN_VALUE);
        roundtripFloat64(-Double.MAX_VALUE);
        roundtripFloat64(1.0);
        roundtripFloat64(-1.0);
        roundtripFloat64(Math.PI);
        roundtripFloat64(Math.E);
    }

    @Test
    void float64_roundtrip_infinities() {
        roundtripFloat64(Double.POSITIVE_INFINITY);
        roundtripFloat64(Double.NEGATIVE_INFINITY);
    }

    @Test
    void float64_roundtrip_nan_preserved() {
        // NaN→bits→encode→decode→bits should equal the original raw bits.
        byte[] buf = new byte[8];
        double nan = Double.NaN;
        TypeCodec.encodeFloat64(nan, MemorySegment.ofArray(buf));
        double decoded = TypeCodec.decodeFloat64(MemorySegment.ofArray(buf));
        assertTrue(Double.isNaN(decoded), "NaN must round-trip as NaN");
    }

    // The encoded form 0x0000000000000000 is the boundary of decodeFloat64's
    // sign test `(flipped < 0)`. These two tests pin that boundary — a survived
    // ConditionalsBoundary mutation (`<` → `<=`) at TypeCodec.java:67 showed the
    // suite never exercised flipped == 0. (Mutation testing, 2026-05-15.)

    @Test
    void float64_decode_allZeroEncoding_yieldsAllOnesBits() {
        // flipped == 0 → original takes the `~flipped` branch → ~0 == -1L.
        // The `<=` mutant would take the XOR branch → Long.MIN_VALUE.
        double decoded = TypeCodec.decodeFloat64(MemorySegment.ofArray(new byte[8]));
        assertEquals(
                -1L,
                Double.doubleToRawLongBits(decoded),
                "the all-zero (minimal) encoded form must decode to the all-ones bit pattern");
    }

    @Test
    void float64_roundtrip_flippedZeroBoundary() {
        // longBitsToDouble(-1L) is exactly the value that encodes to flipped == 0.
        double original = Double.longBitsToDouble(-1L);
        byte[] buf = new byte[8];
        TypeCodec.encodeFloat64(original, MemorySegment.ofArray(buf));
        for (byte b : buf) {
            assertEquals(0, b, "bits -1L must encode to the all-zero form");
        }
        double decoded = TypeCodec.decodeFloat64(MemorySegment.ofArray(buf));
        assertEquals(
                -1L,
                Double.doubleToRawLongBits(decoded),
                "the flipped == 0 boundary value must round-trip bit-exactly");
    }

    @Test
    void float64_roundtrip_random() {
        Random rng = new Random(99);
        for (int i = 0; i < 1000; i++) {
            roundtripFloat64(rng.nextDouble() * 1e6 - 5e5);
        }
    }

    @Test
    void float64_binary_parity_negative_lt_positive() {
        byte[] neg = new byte[8];
        byte[] zero = new byte[8];
        byte[] pos = new byte[8];
        TypeCodec.encodeFloat64(-1.0, MemorySegment.ofArray(neg));
        TypeCodec.encodeFloat64(0.0, MemorySegment.ofArray(zero));
        TypeCodec.encodeFloat64(1.0, MemorySegment.ofArray(pos));
        assertTrue(
                ByteUtils.compareUnsigned(neg, zero) < 0,
                "-1.0 must encode before 0.0 in byte order");
        assertTrue(
                ByteUtils.compareUnsigned(zero, pos) < 0,
                "0.0 must encode before 1.0 in byte order");
    }

    @Test
    void float64_binary_parity_neg_infinity_first() {
        byte[] negInf = new byte[8];
        byte[] posInf = new byte[8];
        TypeCodec.encodeFloat64(Double.NEGATIVE_INFINITY, MemorySegment.ofArray(negInf));
        TypeCodec.encodeFloat64(Double.POSITIVE_INFINITY, MemorySegment.ofArray(posInf));
        assertTrue(ByteUtils.compareUnsigned(negInf, posInf) < 0);
    }

    // ---- readInt64/Int32 — little-endian, no parity ----

    @Test
    void readInt64_little_endian_no_parity() {
        // 0x01 0x02 0x03 0x04 0x05 0x06 0x07 0x08 (LE) = 0x0807060504030201
        byte[] bytes = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        long decoded = TypeCodec.readInt64(MemorySegment.ofArray(bytes));
        assertEquals(0x0807060504030201L, decoded);
    }

    @Test
    void readInt32_little_endian() {
        byte[] bytes = {0x01, 0x02, 0x03, 0x04};
        int decoded = TypeCodec.readInt32(MemorySegment.ofArray(bytes));
        assertEquals(0x04030201, decoded);
    }

    @Test
    void readFloat64_matches_javadouble() {
        // Encode 3.14159 as little-endian bytes, read back.
        long bits = Double.doubleToRawLongBits(3.14159);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) bytes[i] = (byte) (bits >>> (i * 8));
        double decoded = TypeCodec.readFloat64(MemorySegment.ofArray(bytes));
        assertEquals(3.14159, decoded);
    }

    // ---- readString — UTF-8 ----

    @Test
    void readString_ascii() {
        byte[] bytes = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("hello", TypeCodec.readString(MemorySegment.ofArray(bytes)));
    }

    @Test
    void readString_unicode() {
        byte[] bytes = "アリス 🚀 café".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("アリス 🚀 café", TypeCodec.readString(MemorySegment.ofArray(bytes)));
    }

    @Test
    void readString_empty() {
        assertEquals("", TypeCodec.readString(MemorySegment.ofArray(new byte[0])));
    }

    // ---- compare dispatch ----

    @Test
    void compare_Int64_uses_natural_ordering() {
        byte[] aBytes = new byte[8];
        byte[] bBytes = new byte[8];
        // Use little-endian (readInt64 path) for Int64 compare.
        java.nio.ByteBuffer.wrap(aBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(-5);
        java.nio.ByteBuffer.wrap(bBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(5);
        assertTrue(
                TypeCodec.compare(
                                Encoding.Int64,
                                MemorySegment.ofArray(aBytes),
                                MemorySegment.ofArray(bBytes))
                        < 0);
    }

    @Test
    void compare_String_uses_unsigned_byte_order() {
        byte[] a = "abc".getBytes();
        byte[] b = "abd".getBytes();
        assertTrue(
                TypeCodec.compare(
                                Encoding.String, MemorySegment.ofArray(a), MemorySegment.ofArray(b))
                        < 0);
    }

    @Test
    void compare_Float32_natural_order() {
        byte[] aBytes = new byte[4];
        byte[] bBytes = new byte[4];
        java.nio.ByteBuffer.wrap(aBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(-1.5f);
        java.nio.ByteBuffer.wrap(bBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(1.5f);
        assertTrue(
                TypeCodec.compare(
                                Encoding.Float32,
                                MemorySegment.ofArray(aBytes),
                                MemorySegment.ofArray(bBytes))
                        < 0);
    }

    // ---- helpers ----

    private static void roundtripInt64(long v) {
        byte[] buf = new byte[8];
        TypeCodec.encodeInt64(v, MemorySegment.ofArray(buf));
        long decoded = TypeCodec.decodeInt64(MemorySegment.ofArray(buf));
        assertEquals(v, decoded, "int64 round-trip failed for " + v);
    }

    private static void roundtripFloat64(double v) {
        byte[] buf = new byte[8];
        TypeCodec.encodeFloat64(v, MemorySegment.ofArray(buf));
        double decoded = TypeCodec.decodeFloat64(MemorySegment.ofArray(buf));
        if (Double.isNaN(v)) {
            assertTrue(Double.isNaN(decoded));
        } else {
            // Use raw bits comparison so -0.0 vs 0.0 doesn't confuse anything.
            assertEquals(
                    Double.doubleToRawLongBits(v),
                    Double.doubleToRawLongBits(decoded),
                    "float64 round-trip failed for " + v);
        }
    }
}
