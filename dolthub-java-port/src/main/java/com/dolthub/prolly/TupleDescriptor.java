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
import java.util.List;

/**
 * The schema + comparator for {@link Tuple}s: an ordered list of {@link Type}s and the logic that
 * compares two tuples field-by-field — the bridge between raw tuple bytes and logical types.
 *
 * <p><b>Why comparison lives here and not on {@code Tuple}:</b> a tuple is just bytes; how two
 * tuples order depends on the <em>schema</em> (an {@code Int64} field compares numerically, a
 * string field lexicographically). Every tree operation that navigates by key — cursor seek,
 * split-point choice, merge alignment — funnels through {@link #compare}, which makes this the
 * hottest comparator in the engine.
 *
 * @apiNote Two modes (the constructor flag): type-aware (delegates numeric comparison to {@link
 *     TypeCodec}) and <b>binary parity</b> ({@code binaryParity == true}) — pure byte comparison,
 *     valid because the builder bit-flipped numeric fields at write time so their bytes already
 *     order lexicographically. A field is NULL iff its byte range is empty; null sorts before any
 *     value. Immutable and thread-safe (two final fields).
 * @implNote <b>Collaborators:</b> {@link Type}/{@link Encoding} (the schema vocabulary), {@link
 *     TypeCodec} ({@code compareAt} — the in-place field compare), {@link Tuple} (the byte layout
 *     it interprets). <b>Dependents:</b> {@code StaticMap}/{@code Cursor} seeks and the key
 *     ordering of every tree in the upstream codec / index layers. {@link #compare} deliberately
 *     reads field ranges in place (no slice, no {@code Tuple} wrapper, no copy) — the marquee
 *     descent-allocation fix of the triejoin performance work (see the method doc).
 */
public class TupleDescriptor {
    private final List<Type> types;
    private final boolean binaryParity;

    public TupleDescriptor(List<Type> types) {
        this(types, false);
    }

    public TupleDescriptor(List<Type> types, boolean binaryParity) {
        this.types = types;
        this.binaryParity = binaryParity;
    }

    /**
     * In-place tuple comparison — reads each field's byte range by offset (no {@code
     * getFieldSegment} slice, no {@code Tuple} wrapper, no byte[] copy) and compares via {@link
     * TypeCodec#compareAt} ({@code long} read+compare for {@code Int64}/TermId; range-mismatch for
     * variable-length). Semantically identical to the prior slice-based compare; this is the
     * marquee descent-allocation lever (the upstream triejoin-performance plan, Phase 3). A field
     * is NULL iff its range is empty ({@code start == end}), preserving the previous null ordering
     * (null sorts before any value).
     */
    public int compare(Tuple a, Tuple b) {
        int countA = a.count();
        int countB = b.count();
        int minCount = Math.min(countA, countB);
        MemorySegment sa = a.segment();
        MemorySegment sb = b.segment();

        for (int i = 0; i < minCount; i++) {
            long ra = a.fieldRange(i);
            long rb = b.fieldRange(i);
            int aStart = (int) (ra >>> 32), aEnd = (int) ra;
            int bStart = (int) (rb >>> 32), bEnd = (int) rb;
            boolean aNull = aStart == aEnd; // start == end → null (matches getFieldSegment)
            boolean bNull = bStart == bEnd;

            int cmp;
            if (aNull || bNull) {
                if (aNull && bNull) cmp = 0;
                else cmp = aNull ? -1 : 1;
            } else if (binaryParity || i >= types.size()) {
                cmp = TypeCodec.compareRangeUnsigned(sa, aStart, aEnd, sb, bStart, bEnd);
            } else {
                cmp =
                        TypeCodec.compareAt(
                                types.get(i).encoding(), sa, aStart, aEnd, sb, bStart, bEnd);
            }
            if (cmp != 0) return cmp;
        }

        return Integer.compare(countA, countB);
    }

    public boolean isBinaryParity() {
        return binaryParity;
    }

    /** Number of columns in this schema. */
    public int size() {
        return types.size();
    }

    /** The {@link Type} of column {@code index}. */
    public Type typeAt(int index) {
        return types.get(index);
    }
}
