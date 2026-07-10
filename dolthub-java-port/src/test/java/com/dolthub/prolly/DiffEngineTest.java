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
 * SQLite-grade coverage for {@link DiffEngine}. Diff is what powers three-way merge, event-log
 * streaming, and the audit UI — silent misses or false ADDs/DELs would corrupt every downstream
 * consumer.
 *
 * <p>All trees built via {@link TreeMutator} so internal-node short-circuit paths and Flatbuffer
 * parsing are exercised end-to-end.
 */
class DiffEngineTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static Node buildTree(
            HeapBufferPool pool, InMemoryNodeStore store, List<TreeMutator.Mutation> edits) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        return m.applyMutations(null, edits.iterator());
    }

    private static class CollectingHandler implements DiffEngine.DiffHandler {
        final List<DiffEngine.DiffEntry> entries = new ArrayList<>();
        boolean stopAfter = false;
        int stopAfterN = Integer.MAX_VALUE;

        @Override
        public boolean onDiff(DiffEngine.DiffEntry e) {
            entries.add(e);
            return entries.size() < stopAfterN;
        }

        Set<String> keysOfType(DiffEngine.DiffType t) {
            Set<String> out = new HashSet<>();
            for (var e : entries) {
                if (e.type() == t) out.add(new String(new Tuple(e.key()).getField(0)));
            }
            return out;
        }
    }

    // ---- Null-handling boundary cases ----

    @Test
    void both_null_roots_returns_no_diffs() {
        try (InMemoryNodeStore store = new InMemoryNodeStore()) {
            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(null, null, h);
            assertEquals(0, h.entries.size());
        }
    }

    @Test
    void null_to_leaf_yields_all_adds() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> b = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                b.add(
                        new TreeMutator.Mutation(
                                key(pool, "k-" + i), MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node rootB = buildTree(pool, store, b);

            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(null, rootB, h);

            assertEquals(5, h.entries.size());
            assertEquals(5, h.keysOfType(DiffEngine.DiffType.ADD).size());
            assertEquals(0, h.keysOfType(DiffEngine.DiffType.MOD).size());
            assertEquals(0, h.keysOfType(DiffEngine.DiffType.DEL).size());
        }
    }

    @Test
    void leaf_to_null_yields_all_dels() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> a = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                a.add(
                        new TreeMutator.Mutation(
                                key(pool, "k-" + i), MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node rootA = buildTree(pool, store, a);

            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(rootA, null, h);
            assertEquals(7, h.entries.size());
            assertEquals(7, h.keysOfType(DiffEngine.DiffType.DEL).size());
        }
    }

    // ---- Identical trees ----

    @Test
    void identical_roots_yield_zero_diffs() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node root = buildTree(pool, store, edits);
            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(root, root, h);
            assertEquals(0, h.entries.size(), "Merkle short-circuit: identical bytes → zero work");
        }
    }

    // ---- Pure ADD / DEL / MOD ----

    @Test
    void adds_in_b_emit_as_add() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> a = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                a.add(
                        new TreeMutator.Mutation(
                                key(pool, "k-" + i), MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node rootA = buildTree(pool, store, a);

            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> add = new ArrayList<>();
            add.add(
                    new TreeMutator.Mutation(
                            key(pool, "z-99"), MemorySegment.ofArray("new".getBytes())));
            Node rootB = m.applyMutations(rootA, add.iterator());

            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(rootA, rootB, h);
            assertEquals(1, h.entries.size());
            assertEquals(DiffEngine.DiffType.ADD, h.entries.get(0).type());
            assertEquals("z-99", new String(new Tuple(h.entries.get(0).key()).getField(0)));
            assertNull(h.entries.get(0).valueA(), "ADD has no valueA");
            assertNotNull(h.entries.get(0).valueB());
        }
    }

    @Test
    void deletes_emit_as_del() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> a = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                a.add(
                        new TreeMutator.Mutation(
                                key(pool, "k-" + i), MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node rootA = buildTree(pool, store, a);

            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> del = new ArrayList<>();
            del.add(new TreeMutator.Mutation(key(pool, "k-2"), null));
            Node rootB = m.applyMutations(rootA, del.iterator());

            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(rootA, rootB, h);
            assertEquals(1, h.entries.size());
            assertEquals(DiffEngine.DiffType.DEL, h.entries.get(0).type());
            assertEquals("k-2", new String(new Tuple(h.entries.get(0).key()).getField(0)));
            assertNotNull(h.entries.get(0).valueA(), "DEL has valueA");
            assertNull(h.entries.get(0).valueB());
        }
    }

    @Test
    void value_change_emits_as_mod() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> a = new ArrayList<>();
            a.add(new TreeMutator.Mutation(key(pool, "k"), MemorySegment.ofArray("v1".getBytes())));
            Node rootA = buildTree(pool, store, a);

            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> mod = new ArrayList<>();
            mod.add(
                    new TreeMutator.Mutation(
                            key(pool, "k"), MemorySegment.ofArray("v2".getBytes())));
            Node rootB = m.applyMutations(rootA, mod.iterator());

            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(rootA, rootB, h);

            assertEquals(1, h.entries.size());
            assertEquals(DiffEngine.DiffType.MOD, h.entries.get(0).type());
            byte[] valA =
                    h.entries.get(0).valueA().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            byte[] valB =
                    h.entries.get(0).valueB().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            assertEquals("v1", new String(valA));
            assertEquals("v2", new String(valB));
        }
    }

    // ---- Multi-level (internal-node short-circuit) ----

    @Test
    void large_tree_with_one_mod_emits_one_entry() {
        // Forces multi-level tree, exercises internal-node hash short-circuit.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node rootA = buildTree(pool, store, edits);
            assertTrue(rootA.level() >= 1, "expected multi-level tree");

            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> mod = new ArrayList<>();
            mod.add(
                    new TreeMutator.Mutation(
                            key(pool, String.format("k-%05d", 1000)),
                            MemorySegment.ofArray("MUTATED".getBytes())));
            Node rootB = m.applyMutations(rootA, mod.iterator());

            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(rootA, rootB, h);
            assertEquals(1, h.entries.size());
            assertEquals(DiffEngine.DiffType.MOD, h.entries.get(0).type());
            assertEquals("k-01000", new String(new Tuple(h.entries.get(0).key()).getField(0)));
        }
    }

    @Test
    void mixed_add_del_mod_in_single_diff() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> a = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                a.add(
                        new TreeMutator.Mutation(
                                key(pool, "k-" + i), MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node rootA = buildTree(pool, store, a);

            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> mix = new ArrayList<>();
            mix.add(new TreeMutator.Mutation(key(pool, "k-3"), null)); // DEL
            mix.add(
                    new TreeMutator.Mutation(
                            key(pool, "k-5"), MemorySegment.ofArray("changed".getBytes()))); // MOD
            mix.add(
                    new TreeMutator.Mutation(
                            key(pool, "z-new"), MemorySegment.ofArray("added".getBytes()))); // ADD
            Node rootB = m.applyMutations(rootA, mix.iterator());

            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(rootA, rootB, h);
            assertEquals(3, h.entries.size());
            assertTrue(h.keysOfType(DiffEngine.DiffType.DEL).contains("k-3"));
            assertTrue(h.keysOfType(DiffEngine.DiffType.MOD).contains("k-5"));
            assertTrue(h.keysOfType(DiffEngine.DiffType.ADD).contains("z-new"));
        }
    }

    // ---- Handler control flow ----

    @Test
    void handler_returning_false_stops_iteration_early() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> b = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                b.add(
                        new TreeMutator.Mutation(
                                key(pool, "k-" + i), MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node rootB = buildTree(pool, store, b);

            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            h.stopAfterN = 3;
            de.diff(null, rootB, h);
            assertEquals(
                    3,
                    h.entries.size(),
                    "handler returning false on entry #3 must stop further callbacks");
        }
    }

    // ---- Keys emitted in sorted order ----

    @Test
    void diff_entries_emit_in_key_order() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> b = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                b.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node rootB = buildTree(pool, store, b);

            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(null, rootB, h);
            for (int i = 1; i < h.entries.size(); i++) {
                int cmp =
                        STRING_DESC.compare(
                                new Tuple(h.entries.get(i - 1).key()),
                                new Tuple(h.entries.get(i).key()));
                assertTrue(cmp < 0, "entries must be emitted in ascending key order");
            }
        }
    }

    // ---- Equal-value MOD suppression ----

    @Test
    void same_key_same_value_emits_nothing() {
        // Re-apply identical value: TreeMutator may or may not produce a new
        // root, but diff() must see no MOD when the value bytes are equal.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> a = new ArrayList<>();
            a.add(new TreeMutator.Mutation(key(pool, "k"), MemorySegment.ofArray("v".getBytes())));
            Node rootA = buildTree(pool, store, a);
            Node rootB = buildTree(pool, store, a);

            DiffEngine de = new DiffEngine(store, STRING_DESC);
            CollectingHandler h = new CollectingHandler();
            de.diff(rootA, rootB, h);
            assertEquals(
                    0, h.entries.size(), "two trees with identical content must produce no diffs");
        }
    }
}
