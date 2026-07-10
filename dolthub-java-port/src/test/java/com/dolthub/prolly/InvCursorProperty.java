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

import com.dolthub.prolly.gen.Generators;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * I-2 cursor / range semantics as properties (plans/core-engine-test-strategy.md Step 13). Where
 * {@code InvOracleProperty} pins whole-tree forward/reverse iteration, this pins the <b>seek</b>
 * surface:
 *
 * <ul>
 *   <li><b>Insertion-point on absent keys</b>: {@code seek(k)} then a forward walk equals {@code
 *       TreeMap.tailMap(k, true)} — i.e. it lands on the first key ≥ k, including the leaf-boundary
 *       case where the answer is the first key of the <em>next</em> leaf. An off-by-one in {@code
 *       Cursor.atKey} at a node boundary would surface here.
 *   <li><b>Reverse from any position</b>: {@code seek(presentKey)} then a {@code prev()} walk
 *       equals {@code headMap(key, true)} in descending order.
 *   <li><b>Empty range</b>: seeking past the last key yields nothing.
 *   <li><b>Single-item tree</b>: seek below / at / above the lone key.
 *   <li><b>Prefix scan across leaf boundaries</b>: {@code iterPrefix} over a prefix whose members
 *       span multiple leaves returns exactly the prefixed keys, in order.
 * </ul>
 */
class InvCursorProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Provide
    Arbitrary<NavigableMap<byte[], byte[]>> maps() {
        return Generators.mapsNonEmptyKeys(1, 120); // non-empty: seek needs a real root
    }

    @Provide
    Arbitrary<byte[]> probe() {
        return Generators.bytes(1, 32);
    }

    // ---- forward seek == tailMap(k, true): insertion point + absent + empty ----

    @Property(tries = 400)
    void seekThenForwardEqualsTailMap(
            @ForAll @From("maps") NavigableMap<byte[], byte[]> oracle,
            @ForAll @From("probe") byte[] k) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap sm = build(store, pool, oracle);
            MapIterator it = sm.iter();
            it.seek(keyTuple(pool, k));
            assertEquals(
                    entries(oracle.tailMap(k, true)),
                    walkForward(it),
                    "seek(k) + forward walk must equal tailMap(k, inclusive)");
        }
    }

    // ---- reverse from a present key == headMap(k, true) descending ----

    @Property(tries = 400)
    void seekThenReverseEqualsHeadMapDescending(
            @ForAll @From("maps") NavigableMap<byte[], byte[]> oracle) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap sm = build(store, pool, oracle);
            // Probe from every present key — reverse-from-any-position.
            for (byte[] k : oracle.keySet()) {
                MapIterator it = sm.iter();
                it.seek(keyTuple(pool, k));
                List<Map.Entry<String, String>> expected =
                        new ArrayList<>(entries(oracle.headMap(k, true)));
                Collections.reverse(expected);
                assertEquals(
                        expected,
                        walkReverse(it),
                        "seek(present) + prev walk must equal headMap(k, inclusive) descending");
            }
        }
    }

    // ---- targeted edge cases ----

    @Example
    void singleItemTree_seekBelowAtAbove() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            NavigableMap<byte[], byte[]> one = new TreeMap<>(Generators.UNSIGNED);
            one.put(new byte[] {0x40}, new byte[] {0x01}); // "m"
            StaticMap sm = build(store, pool, one);

            assertEquals(
                    1, seekForwardCount(sm, pool, new byte[] {0x10}), "seek below → the one key");
            assertEquals(1, seekForwardCount(sm, pool, new byte[] {0x40}), "seek at → the one key");
            assertEquals(0, seekForwardCount(sm, pool, new byte[] {0x7F}), "seek above → empty");
        }
    }

    @Example
    void emptyRange_seekPastLastKey() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            NavigableMap<byte[], byte[]> m = new TreeMap<>(Generators.UNSIGNED);
            for (int i = 0; i < 50; i++) m.put(new byte[] {(byte) i}, new byte[] {1});
            StaticMap sm = build(store, pool, m);
            // 0xFF sorts after every 0x00..0x31 key under unsigned compare.
            assertEquals(
                    0,
                    seekForwardCount(sm, pool, new byte[] {(byte) 0xFF}),
                    "seeking past the last key yields an empty forward range");
        }
    }

    @Example
    void prefixScanSpansLeafBoundaries() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            NavigableMap<byte[], byte[]> m = new TreeMap<>(Generators.UNSIGNED);
            byte prefix = 0x42;
            List<String> expectedPrefixed = new ArrayList<>();
            // 300 keys under prefix 0x42 (2-byte counter) — enough to span leaves —
            // interleaved with out-of-prefix sentinels below (0x10) and above (0x90).
            for (int i = 0; i < 300; i++) {
                byte[] k = new byte[] {prefix, (byte) (i >> 8), (byte) i};
                m.put(k, new byte[] {1});
                expectedPrefixed.add(HashUtils.toHex(k));
            }
            for (int i = 0; i < 20; i++) {
                m.put(new byte[] {0x10, (byte) i}, new byte[] {1});
                m.put(new byte[] {(byte) 0x90, (byte) i}, new byte[] {1});
            }
            StaticMap sm = build(store, pool, m);

            byte[] pfx = new byte[] {prefix};
            MapIterator it = sm.iterPrefix(keyTuple(pool, pfx), MemorySegment.ofArray(pfx));
            List<String> got = new ArrayList<>();
            while (it.next()) got.add(HashUtils.toHex(new Tuple(it.key()).getField(0)));
            assertEquals(
                    expectedPrefixed,
                    got,
                    "iterPrefix must return exactly the prefixed keys in order, across leaf boundaries");
        }
    }

    // ---- helpers ----

    private static StaticMap build(
            InMemoryNodeStore store, HeapBufferPool pool, NavigableMap<byte[], byte[]> content) {
        List<TreeMutator.Mutation> muts = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> e : content.entrySet()) {
            muts.add(
                    new TreeMutator.Mutation(
                            keyTuple(pool, e.getKey()), MemorySegment.ofArray(e.getValue())));
        }
        Node root = new TreeMutator(store, DESC, pool).applyMutations(null, muts.iterator());
        return new StaticMap(store, root, DESC);
    }

    private static MemorySegment keyTuple(HeapBufferPool pool, byte[] key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key);
        return tb.build().segment();
    }

    private static List<Map.Entry<String, String>> entries(Map<byte[], byte[]> m) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> e : m.entrySet()) {
            out.add(Map.entry(HashUtils.toHex(e.getKey()), HashUtils.toHex(e.getValue())));
        }
        return out;
    }

    private static List<Map.Entry<String, String>> walkForward(MapIterator it) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        while (it.next()) {
            out.add(
                    Map.entry(
                            HashUtils.toHex(new Tuple(it.key()).getField(0)),
                            HashUtils.toHex(it.value().toArray(ValueLayout.JAVA_BYTE))));
        }
        return out;
    }

    private static List<Map.Entry<String, String>> walkReverse(MapIterator it) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        while (it.prev()) {
            out.add(
                    Map.entry(
                            HashUtils.toHex(new Tuple(it.key()).getField(0)),
                            HashUtils.toHex(it.value().toArray(ValueLayout.JAVA_BYTE))));
        }
        return out;
    }

    private static int seekForwardCount(StaticMap sm, HeapBufferPool pool, byte[] k) {
        MapIterator it = sm.iter();
        it.seek(keyTuple(pool, k));
        int n = 0;
        while (it.next()) n++;
        return n;
    }
}
