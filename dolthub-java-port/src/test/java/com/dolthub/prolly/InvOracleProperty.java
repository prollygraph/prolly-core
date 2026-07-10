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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
 * I-2 (reference equivalence) as a property (plans/core-engine-test-strategy.md Step 7). A built
 * prolly tree is observably a sorted {@code byte[]→byte[]} map: point reads, full iteration order,
 * reverse iteration, and range scans all agree with a {@link TreeMap} oracle in the engine's
 * UNSIGNED key order.
 *
 * <p>Generalizes the fixed-seed `OracleModelTest` to generator-driven cases with shrinking. (Full
 * stateful action-chains over a live `MutableMap` are a later refinement; this pins the read
 * surface of a built tree, which is where iteration/range bugs live.)
 */
class InvOracleProperty {

    @Provide
    Arbitrary<NavigableMap<byte[], byte[]>> maps() {
        // Non-empty keys: an empty byte[] key is encoded identically to a null
        // field in the tuple format (a documented Dolt-compat property), so it
        // would round-trip as null and isn't a meaningful distinct key here.
        return Generators.mapsNonEmptyKeys(0, 100);
    }

    @Provide
    Arbitrary<byte[]> probe() {
        return Generators.bytes(1, 32); // non-empty, same reason as above
    }

    @Property(tries = 300)
    void builtTreeMatchesTreeMapOracle(
            @ForAll @From("maps") NavigableMap<byte[], byte[]> oracle,
            @ForAll @From("probe") byte[] probeKey) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Node root = buildRoot(store, desc, pool, oracle);
            StaticMap sm = new StaticMap(store, root, desc);

            // 1. Point reads agree on every present key.
            for (Map.Entry<byte[], byte[]> e : oracle.entrySet()) {
                var got = sm.get(keyTuple(pool, e.getKey()));
                assertTrue(got.isPresent(), "present key must be found");
                assertArrayEquals(e.getValue(), bytes(got.get()), "value mismatch on present key");
            }
            // 2. A probe key absent from the oracle must be absent in the tree.
            if (!oracle.containsKey(probeKey)) {
                assertTrue(
                        sm.get(keyTuple(pool, probeKey)).isEmpty(), "absent key must not be found");
            }
            // 3. Forward iteration yields the oracle's entries in key order.
            assertEquals(entries(oracle), forward(sm), "forward iteration order/content");
            // 4. Reverse iteration yields them in descending key order.
            List<Map.Entry<String, String>> expectedRev = new ArrayList<>(entries(oracle));
            java.util.Collections.reverse(expectedRev);
            assertEquals(expectedRev, reverse(sm), "reverse iteration order/content");
            // 5. Range scan from probeKey agrees with the oracle's tailMap.
            assertEquals(
                    entries(oracle.tailMap(probeKey, true)),
                    range(sm, pool, probeKey),
                    "range scan from probeKey");
        }
    }

    // ---- build + read helpers ----

    private static Node buildRoot(
            InMemoryNodeStore store,
            TupleDescriptor desc,
            HeapBufferPool pool,
            NavigableMap<byte[], byte[]> content) {
        if (content.isEmpty()) {
            return null; // empty tree — StaticMap handles a null root (see StaticMapTest)
        }
        List<TreeMutator.Mutation> muts = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> e : content.entrySet()) {
            muts.add(
                    new TreeMutator.Mutation(
                            keyTuple(pool, e.getKey()), MemorySegment.ofArray(e.getValue())));
        }
        return new TreeMutator(store, desc, pool).applyMutations(null, muts.iterator());
    }

    private static MemorySegment keyTuple(HeapBufferPool pool, byte[] key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key);
        return tb.build().segment();
    }

    private static byte[] bytes(MemorySegment seg) {
        return seg.toArray(ValueLayout.JAVA_BYTE);
    }

    /** Oracle entries as (hex-key, hex-val) string pairs — order-preserving + value-comparable. */
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
                            HashUtils.toHex(bytes(it.value()))));
        }
        return out;
    }

    private static List<Map.Entry<String, String>> reverse(StaticMap sm) {
        // reverseIter() positions the cursor AT THE END; descending iteration
        // advances via prev() (retreat). next() would call advance() — already
        // past the end — and yield only the last item. (The pre-existing
        // StaticMapTest.reverseIter test is a non-assertion that never pinned
        // this contract; this property does.)
        List<Map.Entry<String, String>> out = new ArrayList<>();
        MapIterator it = sm.reverseIter();
        while (it.prev()) {
            out.add(
                    Map.entry(
                            HashUtils.toHex(new Tuple(it.key()).getField(0)),
                            HashUtils.toHex(bytes(it.value()))));
        }
        return out;
    }

    private static List<Map.Entry<String, String>> range(
            StaticMap sm, HeapBufferPool pool, byte[] start) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        MapIterator it = sm.iterRange(keyTuple(pool, start));
        while (it.next()) {
            out.add(
                    Map.entry(
                            HashUtils.toHex(new Tuple(it.key()).getField(0)),
                            HashUtils.toHex(bytes(it.value()))));
        }
        return out;
    }
}
