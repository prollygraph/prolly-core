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
 * Regression for the former <em>boundary-shift over-reporting</em> bug.
 *
 * <p>The old {@link DiffEngine} aligned internal-node children by separator-key equality. A
 * separator key is the last key of a child's subtree — and inserting/deleting a key at a chunk
 * boundary shifts it. Mismatched separators made the old engine declare an entire boundary leaf a
 * wholesale DEL+ADD, so a one-key insert into a 2000-key tree reported 517 diff entries instead of
 * 1.
 *
 * <p>The leaf-cursor lockstep walk compares actual keys, so a boundary-shifting edit produces
 * exactly the edits that changed. These tests pin that exact-count behavior.
 *
 * <p>All trees built end-to-end via {@link TreeMutator} — no mocks.
 */
class DiffEngineBoundaryShiftBugTest {

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
    void appending_one_key_to_a_multi_level_tree_emits_exactly_one_add() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node a = tree(pool, store, 0, 2000); // multi-level
            Node b = tree(pool, store, 0, 2001); // a ∪ {k-02000}
            assertTrue(a.level() >= 1 && b.level() >= 1, "both must be multi-level");

            Collector c = diff(store, a, b);

            assertEquals(
                    1, c.entries.size(), "a one-key insert must produce exactly one diff entry");
            assertEquals(DiffEngine.DiffType.ADD, c.entries.get(0).type());
            assertEquals("k-02000", new String(new Tuple(c.entries.get(0).key()).getField(0)));
        }
    }

    @Test
    void deleting_a_boundary_key_from_a_multi_level_tree_emits_exactly_one_del() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node big = tree(pool, store, 0, 2001);
            Node small = tree(pool, store, 0, 2000); // big minus k-02000

            Collector c = diff(store, big, small);

            assertEquals(
                    1, c.entries.size(), "a one-key delete must produce exactly one diff entry");
            assertEquals(DiffEngine.DiffType.DEL, c.entries.get(0).type());
            assertEquals("k-02000", new String(new Tuple(c.entries.get(0).key()).getField(0)));
        }
    }

    @Test
    void no_false_positive_for_unchanged_keys_in_a_shifted_leaf() {
        // A key present + identical in both trees must never appear in the
        // diff — not as a DEL, not as an ADD, not as a MOD.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node a = tree(pool, store, 0, 2000);
            Node b = tree(pool, store, 0, 2001);

            Collector c = diff(store, a, b);
            Set<String> touched = new HashSet<>();
            for (var e : c.entries) touched.add(new String(new Tuple(e.key()).getField(0)));

            assertEquals(
                    Set.of("k-02000"),
                    touched,
                    "only the genuinely-new key may appear in the diff");
        }
    }

    @Test
    void inserting_a_key_in_the_middle_of_a_multi_level_tree() {
        // The insert lands mid-tree (not at the tail), shifting an interior
        // chunk boundary — still must report exactly one ADD.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node a = tree(pool, store, 0, 2000);

            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node b =
                    m.applyMutations(
                            a,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    key(pool, "k-00999x"), // sorts between k-00999
                                                    // and k-01000
                                                    MemorySegment.ofArray("inserted".getBytes())))
                                    .iterator());

            Collector c = diff(store, a, b);
            assertEquals(1, c.entries.size(), "a mid-tree insert is exactly one ADD");
            assertEquals(DiffEngine.DiffType.ADD, c.entries.get(0).type());
            assertEquals("k-00999x", new String(new Tuple(c.entries.get(0).key()).getField(0)));
        }
    }

    @Test
    void mod_only_change_on_a_multi_level_tree_emits_one_mod() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node a = tree(pool, store, 0, 2000);

            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node b =
                    m.applyMutations(
                            a,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    key(pool, "k-01000"),
                                                    MemorySegment.ofArray("MODIFIED".getBytes())))
                                    .iterator());

            Collector c = diff(store, a, b);
            assertEquals(1, c.entries.size(), "a value-only MOD → exactly one entry");
            assertEquals(DiffEngine.DiffType.MOD, c.entries.get(0).type());
        }
    }
}
