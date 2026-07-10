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
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Coverage-gap closer for {@link TypeCodec}. Existing TypeCodecTest covers the encode/decode
 * round-trips for Int64 and Float64 plus a subset of {@code compare}'s switch arms. This file pins
 * every remaining branch — {@code readFloat32} and every {@code compare} case — so the wire-format
 * dispatch can't silently drift on a refactor.
 */
class TypeCodecCoverageTest {

    private static MemorySegment leInt32(int v) {
        MemorySegment seg = MemorySegment.ofArray(new byte[4]);
        seg.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, v);
        return seg;
    }

    private static MemorySegment leInt64(long v) {
        MemorySegment seg = MemorySegment.ofArray(new byte[8]);
        seg.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, v);
        return seg;
    }

    private static MemorySegment leFloat32(float v) {
        MemorySegment seg = MemorySegment.ofArray(new byte[4]);
        seg.set(ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, v);
        return seg;
    }

    private static MemorySegment leFloat64(double v) {
        MemorySegment seg = MemorySegment.ofArray(new byte[8]);
        seg.set(ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, v);
        return seg;
    }

    private static MemorySegment bytes(byte... b) {
        return MemorySegment.ofArray(b);
    }

    // ---- readFloat32 ----

    @Test
    void readFloat32_roundtrips_zero() {
        assertEquals(0.0f, TypeCodec.readFloat32(leFloat32(0.0f)), 0.0f);
    }

    @Test
    void readFloat32_roundtrips_negative() {
        assertEquals(-2.5f, TypeCodec.readFloat32(leFloat32(-2.5f)), 0.0f);
    }

    @Test
    void readFloat32_roundtrips_boundaries() {
        assertEquals(Float.MIN_VALUE, TypeCodec.readFloat32(leFloat32(Float.MIN_VALUE)));
        assertEquals(Float.MAX_VALUE, TypeCodec.readFloat32(leFloat32(Float.MAX_VALUE)));
        assertEquals(
                Float.POSITIVE_INFINITY, TypeCodec.readFloat32(leFloat32(Float.POSITIVE_INFINITY)));
        assertEquals(
                Float.NEGATIVE_INFINITY, TypeCodec.readFloat32(leFloat32(Float.NEGATIVE_INFINITY)));
    }

    @Test
    void readFloat32_preserves_nan() {
        assertTrue(Float.isNaN(TypeCodec.readFloat32(leFloat32(Float.NaN))));
    }

    // ---- compare: every switch arm ----

    @Test
    void compare_Int32_uses_signed_natural_ordering() {
        // readInt32 is little-endian (no parity) — Integer.compare on the values.
        assertTrue(TypeCodec.compare(Encoding.Int32, leInt32(-100), leInt32(100)) < 0);
        assertTrue(TypeCodec.compare(Encoding.Int32, leInt32(100), leInt32(-100)) > 0);
        assertEquals(0, TypeCodec.compare(Encoding.Int32, leInt32(42), leInt32(42)));
    }

    @Test
    void compare_Float64_uses_double_compare() {
        assertTrue(TypeCodec.compare(Encoding.Float64, leFloat64(-1.0), leFloat64(1.0)) < 0);
        assertEquals(0, TypeCodec.compare(Encoding.Float64, leFloat64(3.14), leFloat64(3.14)));
        // NaN sorts highest per Double.compare contract.
        assertTrue(
                TypeCodec.compare(
                                Encoding.Float64,
                                leFloat64(Double.MAX_VALUE),
                                leFloat64(Double.NaN))
                        < 0);
    }

    @Test
    void compare_Uint64_unsigned_byte_order() {
        // ByteUtils.compareUnsigned — high bytes compare unsigned, so any
        // segment with high bit set compares greater than one without.
        MemorySegment hi =
                bytes(
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF);
        MemorySegment zero = leInt64(0L);
        assertTrue(TypeCodec.compare(Encoding.Uint64, zero, hi) < 0);
        assertTrue(TypeCodec.compare(Encoding.Uint64, hi, zero) > 0);
    }

    @Test
    void compare_Uint32_unsigned_byte_order() {
        MemorySegment hi = bytes((byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF);
        MemorySegment zero = leInt32(0);
        assertTrue(TypeCodec.compare(Encoding.Uint32, zero, hi) < 0);
    }

    @Test
    void compare_Uint16_unsigned_byte_order() {
        MemorySegment hi = bytes((byte) 0xFF, (byte) 0xFF);
        MemorySegment lo = bytes((byte) 0x00, (byte) 0x00);
        assertTrue(TypeCodec.compare(Encoding.Uint16, lo, hi) < 0);
    }

    @Test
    void compare_Uint8_unsigned_byte_order() {
        MemorySegment hi = bytes((byte) 0xFF);
        MemorySegment lo = bytes((byte) 0x00);
        assertTrue(TypeCodec.compare(Encoding.Uint8, lo, hi) < 0);
    }

    @Test
    void compare_Float32_natural_order() {
        assertTrue(TypeCodec.compare(Encoding.Float32, leFloat32(-1.0f), leFloat32(1.0f)) < 0);
        assertEquals(0, TypeCodec.compare(Encoding.Float32, leFloat32(0.5f), leFloat32(0.5f)));
    }

    @Test
    void compare_IRI_unsigned_byte_order() {
        // IRI shares the byte-order path with String.
        MemorySegment a = MemorySegment.ofArray("http://a".getBytes());
        MemorySegment b = MemorySegment.ofArray("http://b".getBytes());
        assertTrue(TypeCodec.compare(Encoding.IRI, a, b) < 0);
        assertEquals(0, TypeCodec.compare(Encoding.IRI, a, a));
    }

    @Test
    void compare_Bytes_unsigned_byte_order() {
        MemorySegment a = bytes((byte) 0x00, (byte) 0x01);
        MemorySegment b = bytes((byte) 0xFF);
        assertTrue(
                TypeCodec.compare(Encoding.Bytes, a, b) < 0, "0x00 < 0xFF in unsigned byte order");
    }

    @Test
    void compare_default_arm_uses_unsigned_byte_order() {
        // Pick an Encoding not in any specific switch arm — Null, Int8 — to
        // hit the default branch.
        MemorySegment small = bytes((byte) 0x01);
        MemorySegment large = bytes((byte) 0x80); // high bit set
        // Default arm falls through to ByteUtils.compareUnsigned for any
        // unmatched Encoding kind. Pin behavior for Null (or Int8).
        assertTrue(
                TypeCodec.compare(Encoding.Null, small, large) < 0,
                "unmatched Encoding (Null) must fall through to unsigned byte order");
        assertTrue(
                TypeCodec.compare(Encoding.Int8, small, large) < 0,
                "Int8 hits the default arm — unsigned byte compare");
    }

    // ---- determinism / consistency ----

    @Test
    void compare_self_is_zero_for_every_encoding() {
        // Reflexivity property across the full switch.
        MemorySegment seg = leInt64(42L);
        for (Encoding e : Encoding.values()) {
            // Only encodings whose underlying readers accept 8-byte input.
            // For variable-length kinds (String/Bytes/IRI), use a smaller payload.
            MemorySegment x =
                    (e == Encoding.Int32 || e == Encoding.Uint32 || e == Encoding.Float32)
                            ? leInt32(0)
                            : seg;
            assertEquals(
                    0, TypeCodec.compare(e, x, x), "compare(x, x) must be 0 for encoding: " + e);
        }
    }

    // ---- readFloat64 + readFloat32 are little-endian native ----

    @Test
    void readFloat64_matches_javadouble_with_negative() {
        // Pin sign handling — readFloat64 is the native LE reader, not the
        // binary-parity-encoded path.
        assertEquals(-1.5, TypeCodec.readFloat64(leFloat64(-1.5)), 0.0);
    }
}
