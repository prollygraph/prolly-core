/*
 * Copyright 2026 Earasoft
 * Copyright 2021 Dolthub, Inc.
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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Encodes primitive values into <b>lexicographically comparable</b> byte forms and back — so that
 * comparing the raw bytes of two encoded values orders them the same as comparing the values.
 *
 * <p><b>Why order-preserving encodings matter:</b> the prolly tree orders everything by raw key
 * bytes. Without a transform, two's-complement integers sort wrong (-1 &gt; 1 bytewise) and IEEE
 * doubles sort wrong for negatives. The transforms here (sign-bit XOR for int64; sign-dependent
 * flip for float64, big-endian in both cases) make plain byte comparison equal value comparison —
 * which is what lets {@link TupleDescriptor}'s binary-parity mode and the tree's byte-wise seeks
 * work at all.
 *
 * @apiNote Static utility (not instantiated). Encoders write into a caller-provided {@link
 *     MemorySegment} at offset 0 using unaligned big-endian layouts (packed node bytes give no
 *     alignment guarantee). {@code compareAt} compares a field range in place without slicing.
 * @implNote <b>Collaborators:</b> {@link TupleBuilder} (calls {@code encodeInt64} when a descriptor
 *     requests binary parity), {@link TupleDescriptor} ({@code compareAt} from its compare loop).
 *     <b>Dependents:</b> the upstream term/value codecs that build on these primitives. History
 *     wart worth knowing: {@code decodeFloat64}'s branch order was once inverted — every non-zero
 *     round-trip returned NaN (#147, caught by {@code TypeCodecTest}); the inline comment in that
 *     method preserves the reasoning so the inverse transform is never "simplified" back into the
 *     bug.
 */
public class TypeCodec {

    /**
     * Encodes a long into a 8-byte segment with bit-flipping for lexicographical parity.
     * Transformation: XOR the sign bit.
     */
    public static void encodeInt64(long value, MemorySegment segment) {
        // XOR the MSB to make it unsigned-comparable
        long flipped = value ^ Long.MIN_VALUE;
        // MUST use Big-Endian for the bytes to be lexicographically comparable!
        // Use UNALIGNED to prevent exceptions in packed Prolly Trees.
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 0, flipped);
    }

    public static long decodeInt64(MemorySegment segment) {
        long flipped =
                segment.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 0);
        return flipped ^ Long.MIN_VALUE;
    }

    /** Encodes a double into a 8-byte segment for lexicographical parity. */
    public static void encodeFloat64(double value, MemorySegment segment) {
        long bits = Double.doubleToRawLongBits(value);
        // If negative, flip all bits. If positive, flip only sign bit.
        long flipped = (bits < 0) ? ~bits : (bits ^ Long.MIN_VALUE);
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 0, flipped);
    }

    public static double decodeFloat64(MemorySegment segment) {
        long flipped =
                segment.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 0);
        // Inverse of encodeFloat64's transform:
        //   originally positive  → encoded high bit SET → flipped < 0 → recover with XOR sign-bit
        //   originally negative  → encoded high bit CLR → flipped ≥ 0 → recover by bitwise
        // complement
        // The previous code had the branches swapped; round-trips returned NaN
        // for any non-zero value (#147 — surfaced by the new TypeCodecTest).
        long bits = (flipped < 0) ? (flipped ^ Long.MIN_VALUE) : ~flipped;
        return Double.longBitsToDouble(bits);
    }

    // Cached little-endian layouts — hoisted to static finals (were created inline per read via
    // .withOrder(...)). A fresh, non-constant layout forced MemorySegment.get to allocate a
    // VarHandle
    // every read, on the hot Int64-compare path (the upstream triejoin-performance plan, Phase
    // 3).
    private static final ValueLayout.OfLong LE_I64 =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_I32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfDouble LE_F64 =
            ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat LE_F32 =
            ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public static long readInt64(MemorySegment segment) {
        return segment.get(LE_I64, 0);
    }

    public static int readInt32(MemorySegment segment) {
        return segment.get(LE_I32, 0);
    }

    public static double readFloat64(MemorySegment segment) {
        return segment.get(LE_F64, 0);
    }

    public static float readFloat32(MemorySegment segment) {
        return segment.get(LE_F32, 0);
    }

    public static String readString(MemorySegment segment) {
        return new String(segment.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    public static int compare(Encoding enc, MemorySegment a, MemorySegment b) {
        return switch (enc) {
            case Int64 -> Long.compare(readInt64(a), readInt64(b));
            case Int32 -> Integer.compare(readInt32(a), readInt32(b));
            case Uint64, Uint32, Uint16, Uint8 -> ByteUtils.compareUnsigned(a, b);
            case Float64 -> Double.compare(readFloat64(a), readFloat64(b));
            case Float32 -> Float.compare(readFloat32(a), readFloat32(b));
            case String, IRI, Bytes -> ByteUtils.compareUnsigned(a, b);
            default -> ByteUtils.compareUnsigned(a, b);
        };
    }

    /**
     * In-place field comparison over byte ranges of two parent segments — no {@code asSlice}, no
     * {@code Tuple} wrapper, no byte[] copy. Semantically identical to {@link #compare} on the
     * sliced fields, but reads the primitive (or compares the byte range) directly at the offset.
     * The marquee descent-allocation lever (the upstream triejoin-performance plan, Phase 3, lever
     * 2): for {@code Int64}/TermId it's a single {@code long} read + compare per field.
     */
    public static int compareAt(
            Encoding enc,
            MemorySegment a,
            long aStart,
            long aEnd,
            MemorySegment b,
            long bStart,
            long bEnd) {
        return switch (enc) {
            case Int64 -> Long.compare(a.get(LE_I64, aStart), b.get(LE_I64, bStart));
            case Int32 -> Integer.compare(a.get(LE_I32, aStart), b.get(LE_I32, bStart));
            case Float64 -> Double.compare(a.get(LE_F64, aStart), b.get(LE_F64, bStart));
            case Float32 -> Float.compare(a.get(LE_F32, aStart), b.get(LE_F32, bStart));
            default ->
                    compareRangeUnsigned(
                            a, aStart, aEnd, b, bStart, bEnd); // Uint*, String, IRI, Bytes
        };
    }

    /**
     * Unsigned lexicographic compare of two byte ranges in place (the static {@code mismatch}
     * overload).
     */
    public static int compareRangeUnsigned(
            MemorySegment a, long aStart, long aEnd, MemorySegment b, long bStart, long bEnd) {
        long aLen = aEnd - aStart, bLen = bEnd - bStart;
        long m = MemorySegment.mismatch(a, aStart, aEnd, b, bStart, bEnd);
        if (m == -1) return 0;
        long minLen = Math.min(aLen, bLen);
        if (m >= minLen) return Long.compare(aLen, bLen);
        return Integer.compare(
                Byte.toUnsignedInt(a.get(ValueLayout.JAVA_BYTE, aStart + m)),
                Byte.toUnsignedInt(b.get(ValueLayout.JAVA_BYTE, bStart + m)));
    }
}
