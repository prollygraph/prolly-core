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

import com.dolthub.prolly.gen.Generators;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * I-1 (determinism / history-independence) as a property (plans/core-engine-test-strategy.md Step
 * 6). The root hash is a pure function of the logical content — insertion order, batching, and
 * prior tree history are irrelevant.
 *
 * <p>For a generated content map, build the tree four ways and assert one root hash. Because a
 * prolly tree is a Merkle DAG, identical root hash ⟺ identical node set, so root-hash equality is
 * the whole-structure check.
 *
 * <p>Supersedes the fixed-seed {@code MerkleDeterminismTest} loop with generator-driven cases +
 * jqwik shrinking — a divergence shrinks to the minimal map (e.g. two keys straddling a chunk
 * boundary) instead of a 1000-key corpus.
 */
class InvDeterminismProperty {

    /** Non-empty content maps (the empty tree is covered by CommitEmptyRootTest). */
    @Provide
    Arbitrary<NavigableMap<byte[], byte[]>> nonEmptyMaps() {
        return Generators.maps(1, 80);
    }

    @Property(tries = 300)
    void rootHashIsIndependentOfBuildPath(
            @ForAll @From("nonEmptyMaps") NavigableMap<byte[], byte[]> content) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            // Single-field key tuple, like MerkleDeterminismTest. A single
            // Bytes/String field sorts by unsigned byte compare, matching the
            // generator's UNSIGNED map order — so map iteration order is the
            // sorted order applyMutations requires for a batch build.
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            List<TreeMutator.Mutation> sorted = new ArrayList<>();
            for (Map.Entry<byte[], byte[]> e : content.entrySet()) {
                sorted.add(
                        new TreeMutator.Mutation(
                                keyTuple(pool, e.getKey()), MemorySegment.ofArray(e.getValue())));
            }

            // A — one batch (sorted).
            byte[] hashA = root(store, mutator.applyMutations(null, sorted.iterator()));

            // B — shuffled, one mutation at a time, accumulating.
            List<TreeMutator.Mutation> shuffled = new ArrayList<>(sorted);
            Collections.shuffle(shuffled, new Random(0xB));
            Node rootB = null;
            for (TreeMutator.Mutation m : shuffled) {
                rootB = mutator.applyMutations(rootB, List.of(m).iterator());
            }
            assertArrayEquals(hashA, root(store, rootB), "batch vs shuffled-incremental diverged");

            // C — sorted, in random-sized chunks.
            Node rootC = null;
            int i = 0;
            Random chunkRng = new Random(0xC);
            while (i < sorted.size()) {
                int chunk = 1 + chunkRng.nextInt(7);
                int end = Math.min(i + chunk, sorted.size());
                rootC = mutator.applyMutations(rootC, sorted.subList(i, end).iterator());
                i = end;
            }
            assertArrayEquals(hashA, root(store, rootC), "batch vs chunked diverged");

            // D — build, delete everything, reinsert: history must not stick.
            Node rootD = mutator.applyMutations(null, sorted.iterator());
            List<TreeMutator.Mutation> deletes = new ArrayList<>();
            for (Map.Entry<byte[], byte[]> e : content.entrySet()) {
                deletes.add(new TreeMutator.Mutation(keyTuple(pool, e.getKey()), null));
            }
            rootD = mutator.applyMutations(rootD, deletes.iterator());
            rootD = mutator.applyMutations(rootD, sorted.iterator());
            assertArrayEquals(
                    hashA,
                    root(store, rootD),
                    "batch vs build-delete-reinsert diverged (history leaked)");
        }
    }

    private static MemorySegment keyTuple(HeapBufferPool pool, byte[] key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key);
        return tb.build().segment();
    }

    private static byte[] root(InMemoryNodeStore store, Node root) {
        return store.write(root.segment());
    }
}
