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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Differential parity for the streaming three-way merge (out-of-memory hardening, Phase 2). The
 * LIVE {@link MergeEngine#merge} (a streaming sorted-merge-join of two {@code diffIterator}s, heap
 * O(tree height)) must produce a <b>byte-identical</b> result to the trusted, materialised {@link
 * MergeEngine#mergeMaterialized} (the prior implementation, kept as the differential reference):
 * identical merged root <i>and</i> identical conflict set.
 *
 * <p>Merge is data-integrity-critical, so the strongest guarantee available is "identical to the
 * implementation we already trust" rather than "matches my re-derivation of the merge semantics".
 * base / ours / theirs are drawn over a deliberately <b>small key domain</b> (keys 0–11, values
 * 0–3) so the three trees overlap heavily — that is what exercises every per-key case:
 * same-key/same-value (no conflict), same-key/different-value (conflict), one-sided change, add,
 * and delete.
 */
class MergeStreamingParityProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    /** Maps over a small key/value domain → base/ours/theirs overlap heavily → conflicts arise. */
    @Provide
    Arbitrary<NavigableMap<byte[], byte[]>> smallMaps() {
        return Arbitraries.maps(
                        Arbitraries.integers().between(0, 11), Arbitraries.integers().between(0, 3))
                .ofMaxSize(12)
                .map(
                        m -> {
                            NavigableMap<byte[], byte[]> t = new TreeMap<>(Generators.UNSIGNED);
                            m.forEach(
                                    (k, v) ->
                                            t.put(
                                                    new byte[] {k.byteValue()},
                                                    new byte[] {v.byteValue()}));
                            return t;
                        });
    }

    @Property(tries = 500)
    void streamingMergeIsByteIdenticalToMaterialised(
            @ForAll @From("smallMaps") NavigableMap<byte[], byte[]> base,
            @ForAll @From("smallMaps") NavigableMap<byte[], byte[]> ours,
            @ForAll @From("smallMaps") NavigableMap<byte[], byte[]> theirs) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node baseRoot = build(store, pool, base);
            Node oursRoot = build(store, pool, ours);
            Node theirsRoot = build(store, pool, theirs);
            MergeEngine engine = new MergeEngine(store, DESC, pool);

            MergeEngine.MergeResult streaming = engine.merge(baseRoot, oursRoot, theirsRoot);
            MergeEngine.MergeResult reference =
                    engine.mergeMaterialized(baseRoot, oursRoot, theirsRoot);

            assertEquals(
                    rootHash(reference.root()),
                    rootHash(streaming.root()),
                    "merged root must be byte-identical to the materialised merge");
            assertEquals(
                    conflictReprs(reference.conflicts()),
                    conflictReprs(streaming.conflicts()),
                    "conflict set must be identical to the materialised merge");
        }
    }

    // ---- helpers ----

    private static String rootHash(Node root) {
        return root == null ? "∅" : HashUtils.toHex(HashUtils.hash(root.segment().asByteBuffer()));
    }

    private static List<String> conflictReprs(List<MergeEngine.Conflict> conflicts) {
        List<String> out = new ArrayList<>();
        for (MergeEngine.Conflict c : conflicts) {
            out.add(
                    hx(c.key())
                            + "|"
                            + hx(c.baseVal())
                            + "|"
                            + hx(c.ourVal())
                            + "|"
                            + hx(c.theirVal()));
        }
        out.sort(null); // set-equality (both sides are key-sorted, but sort defends against
        // ordering)
        return out;
    }

    private static String hx(MemorySegment s) {
        return s == null
                ? "∅"
                : HashUtils.toHex(s.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }

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
