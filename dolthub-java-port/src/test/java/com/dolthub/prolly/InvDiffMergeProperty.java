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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.gen.Generators;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * I-6 (diff/merge algebra) as properties (plans/core-engine-test-strategy.md Step 9):
 *
 * <ul>
 *   <li><b>diff matches the oracle</b>: {@code DiffEngine.diff(treeA,treeB)} reports exactly the
 *       symmetric difference a {@code TreeMap} computes — keys only in A → DEL, only in B → ADD, in
 *       both with different values → MOD, equal → not reported.
 *   <li><b>disjoint three-way merge is conflict-free</b>: when ours and theirs add edits over
 *       disjoint key spaces, {@code MergeEngine.merge} reports no conflicts and the merged tree ==
 *       base ∪ ours-edits ∪ theirs-edits.
 * </ul>
 */
class InvDiffMergeProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Provide
    Arbitrary<NavigableMap<byte[], byte[]>> maps() {
        return Generators.mapsNonEmptyKeys(0, 60);
    }

    // ---- diff vs TreeMap oracle ----

    @Property(tries = 250)
    void diffReportsExactlyTheSymmetricDifference(
            @ForAll @From("maps") NavigableMap<byte[], byte[]> a,
            @ForAll @From("maps") NavigableMap<byte[], byte[]> b) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node rootA = build(store, pool, a);
            Node rootB = build(store, pool, b);

            // Oracle: classify every key present in either map.
            Map<String, String> expected = new TreeMap<>();
            for (byte[] k : union(a, b)) {
                boolean inA = a.containsKey(k), inB = b.containsKey(k);
                if (inA && !inB) expected.put(HashUtils.toHex(k), "DEL");
                else if (!inA && inB) expected.put(HashUtils.toHex(k), "ADD");
                else if (!java.util.Arrays.equals(a.get(k), b.get(k)))
                    expected.put(HashUtils.toHex(k), "MOD");
                // equal in both → no diff
            }

            Map<String, String> got = new TreeMap<>();
            new DiffEngine(store, DESC)
                    .diff(
                            rootA,
                            rootB,
                            e -> {
                                got.put(
                                        HashUtils.toHex(new Tuple(e.key()).getField(0)),
                                        e.type().name());
                                return true;
                            });
            assertEquals(expected, got, "diff must equal the TreeMap symmetric difference");
        }
    }

    // ---- disjoint three-way merge ----

    @Property(tries = 200)
    void disjointThreeWayMergeIsConflictFree(
            @ForAll @From("maps") NavigableMap<byte[], byte[]> base,
            @ForAll @From("maps") NavigableMap<byte[], byte[]> oursAdd,
            @ForAll @From("maps") NavigableMap<byte[], byte[]> theirsAdd) {
        // Prefix the three key spaces with distinct lead bytes so they're
        // guaranteed disjoint — base(0x00), ours(0x01), theirs(0x02).
        NavigableMap<byte[], byte[]> b = prefixed(base, (byte) 0x00);
        NavigableMap<byte[], byte[]> o = prefixed(oursAdd, (byte) 0x01);
        NavigableMap<byte[], byte[]> t = prefixed(theirsAdd, (byte) 0x02);

        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator mut = new TreeMutator(store, DESC, pool);
            Node baseRoot = build(store, pool, b);
            // ours = base + ours-additions; theirs = base + theirs-additions.
            Node oursRoot = mut.applyMutations(baseRoot, mutations(pool, o).iterator());
            Node theirsRoot = mut.applyMutations(baseRoot, mutations(pool, t).iterator());

            MergeEngine.MergeResult res =
                    new MergeEngine(store, DESC, pool).merge(baseRoot, oursRoot, theirsRoot);

            assertTrue(
                    res.conflicts().isEmpty(),
                    "disjoint edits must not conflict, got " + res.conflicts().size());

            // Merged content == base ∪ ours ∪ theirs.
            NavigableMap<byte[], byte[]> expected = new TreeMap<>(Generators.UNSIGNED);
            expected.putAll(b);
            expected.putAll(o);
            expected.putAll(t);
            assertEquals(
                    entries(expected),
                    forward(new StaticMap(store, res.root(), DESC)),
                    "merged tree content");
        }
    }

    // ---- helpers ----

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

    private static NavigableMap<byte[], byte[]> prefixed(
            NavigableMap<byte[], byte[]> m, byte lead) {
        NavigableMap<byte[], byte[]> out = new TreeMap<>(Generators.UNSIGNED);
        for (Map.Entry<byte[], byte[]> e : m.entrySet()) {
            byte[] k = e.getKey();
            byte[] pk = new byte[k.length + 1];
            pk[0] = lead;
            System.arraycopy(k, 0, pk, 1, k.length);
            out.put(pk, e.getValue());
        }
        return out;
    }

    private static List<byte[]> union(Map<byte[], byte[]> a, Map<byte[], byte[]> b) {
        NavigableMap<byte[], byte[]> u = new TreeMap<>(Generators.UNSIGNED);
        for (byte[] k : a.keySet()) u.put(k, EMPTY);
        for (byte[] k : b.keySet()) u.put(k, EMPTY);
        return new ArrayList<>(u.keySet());
    }

    private static final byte[] EMPTY = new byte[0];

    private static List<Map.Entry<String, String>> entries(Map<byte[], byte[]> m) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> e : m.entrySet()) {
            out.add(Map.entry(HashUtils.toHex(e.getKey()), HashUtils.toHex(e.getValue())));
        }
        return out;
    }

    private static List<Map.Entry<String, String>> forward(StaticMap sm) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        MapIterator it = sm.iter();
        while (it.next()) {
            out.add(
                    Map.entry(
                            HashUtils.toHex(new Tuple(it.key()).getField(0)),
                            HashUtils.toHex(it.value().toArray(ValueLayout.JAVA_BYTE))));
        }
        return out;
    }
}
