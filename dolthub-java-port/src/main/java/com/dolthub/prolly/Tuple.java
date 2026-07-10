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
import org.jspecify.annotations.Nullable;

/**
 * A vector of fields encoded as one contiguous {@link MemorySegment} — the unit every prolly-tree
 * key and value is made of. Dolt's layout: {@code [Value 0][Value 1]…[Value K][Offset 1]…[Offset
 * K][Count]}, with the offsets and count as little-endian {@code uint16} at the tail.
 *
 * <p><b>Why a byte-layout record and not a field object:</b> tuples live inside node bytes; a
 * cursor descent compares thousands of them. Reading a field is an offset lookup + slice over the
 * existing segment — <b>zero-copy</b>, no deserialization — which is what keeps the read path
 * allocation-free.
 *
 * @apiNote {@link #getFieldSegment} returns a zero-copy slice, or {@code null} when the index is
 *     out of range or the field is NULL-encoded (identical start and end offsets — an empty range
 *     IS the null representation, so null sorts before any value). The whole-tuple byte size is
 *     capped at 65535 (the {@code uint16} offset space; {@code TupleBuilder.build} enforces it).
 *     Immutable by convention: nothing mutates a built tuple's segment.
 * @implNote <b>Collaborators:</b> {@link TupleBuilder} (the writer — produces this layout), {@link
 *     TupleDescriptor} + {@link TypeCodec} (the type-aware comparator over the raw bytes).
 *     <b>Dependents:</b> the upstream key/value codecs and every index tuple the RDF layers write.
 *     The cached {@code LE_U16} layout constant below is a measured allocation fix — see its own
 *     doc.
 */
public record Tuple(MemorySegment segment) {
    private static final int UINT16_SIZE = 2;

    /**
     * Cached little-endian uint16 layout. Hoisted to a {@code static final} (was created inline per
     * access via {@code .withOrder(...)}): a fresh, non-constant layout forced {@code
     * MemorySegment.get} to allocate a {@code VarHandle} + boxing on every field read — the #1
     * descent allocator in the triejoin profile (the upstream triejoin-performance plan, Phase 3).
     * A stable constant lets the JIT intrinsify the access with zero allocation.
     */
    private static final ValueLayout.OfShort LE_U16 =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public int count() {
        if (segment.byteSize() < UINT16_SIZE) return 0;
        return Short.toUnsignedInt(segment.get(LE_U16, segment.byteSize() - UINT16_SIZE));
    }

    /**
     * Returns the field at the given index as a MemorySegment slice. This is zero-copy.
     *
     * @return the field slice, or {@code null} when {@code index >= count} or the field is
     *     NULL-encoded ({@code start == end})
     */
    public @Nullable MemorySegment getFieldSegment(int index) {
        int count = count();
        if (index >= count) return null;

        long size = segment.byteSize();
        long offsetPos = size - UINT16_SIZE - (long) (count - index) * UINT16_SIZE;

        int start =
                (index == 0)
                        ? 0
                        : Short.toUnsignedInt(
                                segment.get(
                                        LE_U16,
                                        size
                                                - UINT16_SIZE
                                                - (long) (count - index + 1) * UINT16_SIZE));
        int end = Short.toUnsignedInt(segment.get(LE_U16, offsetPos));

        if (start == end) return null; // Null encoding

        return segment.asSlice(start, end - start);
    }

    /** Sentinel for {@link #fieldRange}: {@code index >= count} (no such field). */
    public static final long FIELD_ABSENT = -1L;

    /**
     * Packed {@code (start << 32) | end} byte offsets of field {@code index} within {@link
     * #segment}, or {@link #FIELD_ABSENT} when {@code index >= count}. A field is NULL iff {@code
     * start == end} (the same encoding {@link #getFieldSegment} uses). No slice / wrapper / copy —
     * backs the allocation-free in-place comparison in {@link TypeCodec#compareAt} (the upstream
     * triejoin-performance plan, Phase 3, lever 2).
     */
    public long fieldRange(int index) {
        int count = count();
        if (index >= count) return FIELD_ABSENT;
        long size = segment.byteSize();
        long offsetPos = size - UINT16_SIZE - (long) (count - index) * UINT16_SIZE;
        int start =
                (index == 0)
                        ? 0
                        : Short.toUnsignedInt(
                                segment.get(
                                        LE_U16,
                                        size
                                                - UINT16_SIZE
                                                - (long) (count - index + 1) * UINT16_SIZE));
        int end = Short.toUnsignedInt(segment.get(LE_U16, offsetPos));
        return ((long) start << 32) | (end & 0xFFFFFFFFL);
    }

    /**
     * For compatibility, still allow getting field as byte[].
     *
     * @return the field bytes, or {@code null} for a NULL-encoded or absent field (mirrors {@link
     *     #getFieldSegment})
     */
    public byte @Nullable [] getField(int index) {
        MemorySegment field = getFieldSegment(index);
        if (field == null) return null;
        return field.toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * Allocation-free equality of field {@code index} against {@code expected} — semantically
     * {@code Arrays.equals(getField(index), expected)} but with <b>no</b> {@code asSlice} view, no
     * {@code byte[]} copy, and no wrapping of {@code expected} as a segment. Compares the field's
     * bytes in place at their offset within {@link #segment}. The hot-equality counterpart to
     * {@link TypeCodec#compareAt} (the upstream triejoin-performance plan, Phase 3,
     * residual-allocation lever (a)): the trie's {@code prefixMatches}/{@code valid} checks ran
     * {@code getField}+{@code Arrays.equals} per field per iteration, a top source of the {@code
     * Tuple.getField} byte[] churn.
     *
     * <p>A NULL-encoded or absent field ({@code start == end} or {@code index >= count}) equals
     * only a {@code null} {@code expected}, matching {@code getField}'s {@code null} return.
     */
    public boolean fieldEquals(int index, byte[] expected) {
        long r = fieldRange(index);
        if (r == FIELD_ABSENT) return expected == null;
        int start = (int) (r >>> 32);
        int end = (int) (r & 0xFFFFFFFFL);
        if (start == end) return expected == null; // NULL-encoded field == getField's null
        if (expected == null) return false;
        int len = end - start;
        if (len != expected.length) return false;
        for (int j = 0; j < len; j++) {
            if (segment.get(ValueLayout.JAVA_BYTE, start + j) != expected[j]) return false;
        }
        return true;
    }
}
