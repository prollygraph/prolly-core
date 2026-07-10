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
package com.dolthub.prolly.gen;

import com.dolthub.prolly.ByteUtils;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

/**
 * Shared jqwik generators for the core engine test strategy (plans/core-engine-test-strategy.md
 * D-2). The ONE place the engine's random input space is curated — every property test draws from
 * here so the boundary cases are defined once and never re-forgotten.
 *
 * <p>Coverage today (the foundation Phases 1-2 build on):
 *
 * <ul>
 *   <li>{@link #keys()} / {@link #values()} — {@code byte[]} with the full byte range incl. {@code
 *       0x00}/{@code 0xFF} (jqwik edge cases).
 *   <li>{@link #maps(int,int)} — a {@code NavigableMap} in the engine's UNSIGNED-byte key order:
 *       the exact shape of the {@code TreeMap} oracle (I-2). Content-duplicate keys collapse (last
 *       wins), so the result is always a valid logical content map.
 *   <li>{@link #int64Descriptor(int)} + {@link #int64Tuples(int)} — the SPOC-style key shape (N
 *       signed Int64 columns) with boundary longs (MIN/MAX/0/-1 are jqwik edge cases).
 * </ul>
 *
 * <p>Edit-script + three-way-scenario generators are added with the diff/merge property (plan Step
 * 9) so their shape is driven by the property that consumes them.
 */
public final class Generators {

    private Generators() {}

    /**
     * Unsigned-lexicographic key order — matches the engine's comparison ({@link
     * ByteUtils#compareUnsigned(byte[], byte[])}). The oracle map MUST use this so its iteration
     * order matches the prolly tree's.
     */
    public static final Comparator<byte[]> UNSIGNED = ByteUtils::compareUnsigned;

    /** {@code byte[]} of length [minLen,maxLen]. Full byte range. */
    public static Arbitrary<byte[]> bytes(int minLen, int maxLen) {
        return Arbitraries.bytes().array(byte[].class).ofMinSize(minLen).ofMaxSize(maxLen);
    }

    /** Keys: 0..32 bytes (empty key is valid + an important edge). */
    public static Arbitrary<byte[]> keys() {
        return bytes(0, 32);
    }

    /** Values: 0..64 bytes. */
    public static Arbitrary<byte[]> values() {
        return bytes(0, 64);
    }

    /**
     * A logical content map in UNSIGNED key order, size in [minSize,maxSize]. The shape of the I-2
     * oracle. Built by collecting random entries into a {@code TreeMap}, so content-duplicate keys
     * collapse — always valid.
     */
    public static Arbitrary<NavigableMap<byte[], byte[]>> maps(int minSize, int maxSize) {
        return Arbitraries.maps(keys(), values())
                .ofMinSize(minSize)
                .ofMaxSize(maxSize)
                .map(
                        m -> {
                            NavigableMap<byte[], byte[]> sorted = new TreeMap<>(UNSIGNED);
                            sorted.putAll(m);
                            return sorted;
                        });
    }

    /**
     * Like {@link #maps(int,int)} but with NON-EMPTY keys (length ≥ 1).
     *
     * <p>The Dolt tuple format encodes a null field and an empty (zero-length) field identically
     * (start==end offset), so an empty {@code byte[]} key round-trips through a single-field tuple
     * as {@code null} — a documented format property (bit-compat with Dolt), not a bug. Properties
     * that read keys back via {@code Tuple.getField} should avoid empty keys to dodge that
     * ambiguity; use this generator.
     */
    public static Arbitrary<NavigableMap<byte[], byte[]>> mapsNonEmptyKeys(
            int minSize, int maxSize) {
        return Arbitraries.maps(bytes(1, 32), values())
                .ofMinSize(minSize)
                .ofMaxSize(maxSize)
                .map(
                        m -> {
                            NavigableMap<byte[], byte[]> sorted = new TreeMap<>(UNSIGNED);
                            sorted.putAll(m);
                            return sorted;
                        });
    }

    /**
     * A {@link TupleDescriptor} of {@code cols} signed Int64 columns (the SPOC key shape;
     * non-binary-parity → signed-Long compare).
     */
    public static TupleDescriptor int64Descriptor(int cols) {
        List<Type> types = new ArrayList<>(cols);
        for (int i = 0; i < cols; i++) {
            types.add(new Type(Encoding.Int64, false));
        }
        return new TupleDescriptor(types);
    }

    /**
     * Random {@code cols}-column Int64 tuples; boundary longs (MIN/MAX/0/-1) are jqwik edge cases.
     * Each built with a fresh on-heap pool.
     */
    public static Arbitrary<Tuple> int64Tuples(int cols) {
        return Arbitraries.longs()
                .list()
                .ofSize(cols)
                .map(
                        longs -> {
                            TupleBuilder tb =
                                    new TupleBuilder(new HeapBufferPool(), int64Descriptor(cols));
                            for (int i = 0; i < cols; i++) {
                                tb.putInt64(i, longs.get(i));
                            }
                            return tb.build();
                        });
    }

    /**
     * Raw {@code cols}-length Int64 column arrays, handy when a property needs the longs alongside
     * the built {@link Tuple}.
     */
    public static Arbitrary<long[]> int64Cols(int cols) {
        return Arbitraries.longs().array(long[].class).ofSize(cols);
    }
}
