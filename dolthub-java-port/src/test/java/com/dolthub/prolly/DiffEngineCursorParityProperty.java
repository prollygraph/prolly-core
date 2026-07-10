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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.gen.Generators;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Correctness + parity for {@link DiffEngine#diffIterator} — the streaming, pull-based form added
 * for the out-of-memory hardening of cherry-pick / revert / merge (see {@code
 * plans/oom-hardening.md}).
 *
 * <p><b>Non-circular by design.</b> {@code diff(handler)} now delegates to {@code diffIterator}, so
 * a test comparing the two forms would be circular (it could never fail). Instead the primary
 * property validates {@code diffIterator}'s <b>full output</b> — key, {@link DiffEngine.DiffType},
 * {@code valueA} <i>and</i> {@code valueB}, and emission <b>order</b> — against an independent
 * {@link TreeMap} oracle (the existing {@code InvDiffMergeProperty} oracle checks only key→type,
 * not values or order). Cherry-pick / revert feed this iterator straight into {@code
 * TreeMutator.applyMutations}, so a wrong value or out-of-order key would silently corrupt those
 * operations.
 */
class DiffEngineCursorParityProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Provide
    Arbitrary<NavigableMap<byte[], byte[]>> maps() {
        return Generators.mapsNonEmptyKeys(0, 60);
    }

    @Property(tries = 300)
    void diffIteratorMatchesTheTreeMapOracleAndEmitsInKeyOrder(
            @ForAll @From("maps") NavigableMap<byte[], byte[]> a,
            @ForAll @From("maps") NavigableMap<byte[], byte[]> b) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node rootA = build(store, pool, a);
            Node rootB = build(store, pool, b);

            // Independent oracle: classify every key present in either map (hexKey →
            // TYPE|valA|valB).
            Map<String, String> expected = new TreeMap<>();
            for (byte[] k : union(a, b)) {
                boolean inA = a.containsKey(k), inB = b.containsKey(k);
                if (inA && !inB) expected.put(HashUtils.toHex(k), "DEL|" + hx(a.get(k)) + "|∅");
                else if (!inA && inB) expected.put(HashUtils.toHex(k), "ADD|∅|" + hx(b.get(k)));
                else if (!java.util.Arrays.equals(a.get(k), b.get(k)))
                    expected.put(HashUtils.toHex(k), "MOD|" + hx(a.get(k)) + "|" + hx(b.get(k)));
                // equal in both → no diff
            }

            // diffIterator output, plus an order check (keys strictly increasing in descriptor
            // order).
            Map<String, String> got = new TreeMap<>();
            List<MemorySegment> keysInOrder = new ArrayList<>();
            Iterator<DiffEngine.DiffEntry> it =
                    new DiffEngine(store, DESC).diffIterator(rootA, rootB);
            while (it.hasNext()) {
                DiffEngine.DiffEntry e = it.next();
                got.put(HashUtils.toHex(new Tuple(e.key()).getField(0)), valuePart(e));
                keysInOrder.add(e.key());
            }

            assertEquals(
                    expected, got, "diffIterator entries (key, type, valueA, valueB) vs oracle");
            for (int i = 1; i < keysInOrder.size(); i++) {
                assertTrue(
                        DESC.compare(
                                        new Tuple(keysInOrder.get(i - 1)),
                                        new Tuple(keysInOrder.get(i)))
                                < 0,
                        "diffIterator must emit keys in strictly increasing order (for applyMutations)");
            }
        }
    }

    @Example
    void identicalRootsYieldEmptyIterator() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            NavigableMap<byte[], byte[]> m = new java.util.TreeMap<>(Generators.UNSIGNED);
            m.put(new byte[] {1, 2, 3}, new byte[] {9});
            Node root = build(store, pool, m);
            Iterator<DiffEngine.DiffEntry> it =
                    new DiffEngine(store, DESC).diffIterator(root, root);
            assertFalse(it.hasNext(), "identical roots → no differences");
            assertThrows(NoSuchElementException.class, it::next, "next() past end must throw");
        }
    }

    @Example
    void bothNullRootsYieldEmptyIterator() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Iterator<DiffEngine.DiffEntry> it =
                    new DiffEngine(store, DESC).diffIterator(null, null);
            assertFalse(it.hasNext(), "both roots absent → no differences");
            assertThrows(NoSuchElementException.class, it::next);
        }
    }

    // ---- helpers (mirrors InvDiffMergeProperty) ----

    /** "TYPE|hexValueA|hexValueB" — matches the oracle's value-part format (key is the map key). */
    private static String valuePart(DiffEngine.DiffEntry e) {
        return e.type().name() + "|" + hx(e.valueA()) + "|" + hx(e.valueB());
    }

    private static String hx(MemorySegment s) {
        return s == null ? "∅" : HashUtils.toHex(s.toArray(ValueLayout.JAVA_BYTE));
    }

    private static String hx(byte[] b) {
        return b == null ? "∅" : HashUtils.toHex(b);
    }

    private static List<byte[]> union(Map<byte[], byte[]> a, Map<byte[], byte[]> b) {
        NavigableMap<byte[], byte[]> u = new TreeMap<>(Generators.UNSIGNED);
        for (byte[] k : a.keySet()) u.put(k, EMPTY);
        for (byte[] k : b.keySet()) u.put(k, EMPTY);
        return new ArrayList<>(u.keySet());
    }

    private static final byte[] EMPTY = new byte[0];

    private static Node build(
            InMemoryNodeStore store, HeapBufferPool pool, NavigableMap<byte[], byte[]> m) {
        if (m.isEmpty()) return null;
        return new TreeMutator(store, DESC, pool)
                .applyMutations(null, mutations(pool, m).iterator());
    }

    private static List<TreeMutator.Mutation> mutations(
            HeapBufferPool pool, NavigableMap<byte[], byte[]> m) {
        List<TreeMutator.Mutation> out = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> e : m.entrySet()) {
            out.add(
                    new TreeMutator.Mutation(
                            keyTuple(pool, e.getKey()), MemorySegment.ofArray(e.getValue())));
        }
        return out;
    }

    private static MemorySegment keyTuple(HeapBufferPool pool, byte[] key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key);
        return tb.build().segment();
    }
}
