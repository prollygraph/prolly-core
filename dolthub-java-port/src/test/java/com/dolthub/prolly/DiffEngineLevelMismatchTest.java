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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Diffing two trees of <em>different height</em> — a small single-leaf tree against a large
 * multi-level tree.
 *
 * <p>Regression for a former crash: the old internal-node-alignment {@link DiffEngine} routed a
 * leaf-vs-internal root pair into a code path that read a leaf's value bytes as a child-node hash
 * and threw {@code NoSuchElementException}. The leaf-cursor lockstep walk descends both trees to
 * the leaf level, so root level never matters.
 *
 * <p>Every tree here is built end-to-end via {@link TreeMutator} through a real {@link
 * InMemoryNodeStore} — no mocks.
 */
class DiffEngineLevelMismatchTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static Node tree(HeapBufferPool pool, InMemoryNodeStore store, int from, int to) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        for (int i = from; i < to; i++) {
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, String.format("k-%05d", i)),
                            MemorySegment.ofArray(("v-" + i).getBytes())));
        }
        return m.applyMutations(null, edits.iterator());
    }

    private static final class Collector implements DiffEngine.DiffHandler {
        final List<DiffEngine.DiffEntry> entries = new ArrayList<>();

        @Override
        public boolean onDiff(DiffEngine.DiffEntry e) {
            entries.add(e);
            return true;
        }

        Set<String> keysOfType(DiffEngine.DiffType t) {
            Set<String> out = new HashSet<>();
            for (var e : entries) {
                if (e.type() == t) out.add(new String(new Tuple(e.key()).getField(0)));
            }
            return out;
        }
    }

    private static Collector diff(InMemoryNodeStore store, Node a, Node b) {
        Collector c = new Collector();
        new DiffEngine(store, STRING_DESC).diff(a, b, c);
        return c;
    }

    @Test
    void small_leaf_tree_vs_large_multi_level_tree_emits_adds() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            // A: 8 keys → single leaf (level 0). B: 2000 keys → multi-level.
            Node small = tree(pool, store, 0, 8);
            Node large = tree(pool, store, 0, 2000);
            assertEquals(0, small.level(), "A must be a single-leaf tree");
            assertTrue(large.level() >= 1, "B must be multi-level");

            Collector c = diff(store, small, large);

            // B is a strict superset of A with identical values for shared keys.
            assertEquals(
                    1992,
                    c.keysOfType(DiffEngine.DiffType.ADD).size(),
                    "every key in B but not A must surface as an ADD");
            assertTrue(
                    c.keysOfType(DiffEngine.DiffType.MOD).isEmpty(),
                    "shared keys have identical values → no MODs");
            assertTrue(
                    c.keysOfType(DiffEngine.DiffType.DEL).isEmpty(),
                    "A is a subset of B → no DELs");
        }
    }

    @Test
    void large_multi_level_tree_vs_small_leaf_tree_emits_dels() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node large = tree(pool, store, 0, 2000);
            Node small = tree(pool, store, 0, 8);

            Collector c = diff(store, large, small);

            assertEquals(
                    1992,
                    c.keysOfType(DiffEngine.DiffType.DEL).size(),
                    "keys dropped going from the large tree to the small one are DELs");
            assertTrue(c.keysOfType(DiffEngine.DiffType.ADD).isEmpty());
            assertTrue(c.keysOfType(DiffEngine.DiffType.MOD).isEmpty());
        }
    }

    @Test
    void level_mismatch_with_a_modified_shared_key() {
        // Small tree shares keys with the large tree but one value differs.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node large = tree(pool, store, 0, 2000);

            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String v = (i == 3) ? "CHANGED" : ("v-" + i);
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(v.getBytes())));
            }
            Node small = m.applyMutations(null, edits.iterator());

            Collector c = diff(store, small, large);

            assertEquals(
                    Set.of("k-00003"),
                    c.keysOfType(DiffEngine.DiffType.MOD),
                    "the one differing shared value must surface as exactly one MOD");
            assertEquals(1992, c.keysOfType(DiffEngine.DiffType.ADD).size());
            assertTrue(c.keysOfType(DiffEngine.DiffType.DEL).isEmpty());
        }
    }

    @Test
    void single_leaf_vs_single_leaf_still_works() {
        // Guard against a regression in the simplest case.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node a = tree(pool, store, 0, 5);
            Node b = tree(pool, store, 0, 6);
            Collector c = diff(store, a, b);
            assertEquals(Set.of("k-00005"), c.keysOfType(DiffEngine.DiffType.ADD));
            assertEquals(1, c.entries.size());
        }
    }
}
