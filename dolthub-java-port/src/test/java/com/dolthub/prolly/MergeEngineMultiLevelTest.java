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
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Three-way merge across <em>multi-level</em> trees, including boundary-shifting edits and
 * ancestor/head level mismatches.
 *
 * <p>{@link MergeEngine#collectChanges} delegates to {@link DiffEngine}, so before the leaf-cursor
 * diff rewrite these merges either crashed ({@code NoSuchElementException} on a level mismatch) or
 * built a wrong conflict set (a boundary-shifting add made unchanged keys look like conflicting
 * DEL+ADD pairs). {@code MergeEngineTest} only exercises small single-leaf trees, so it never
 * reached either path.
 *
 * <p>All trees built end-to-end via {@link TreeMutator} through a real {@link InMemoryNodeStore} —
 * no mocks.
 */
class MergeEngineMultiLevelTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static Node range(HeapBufferPool pool, InMemoryNodeStore store, int from, int to) {
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

    private static Node apply(
            HeapBufferPool pool,
            InMemoryNodeStore store,
            Node base,
            List<TreeMutator.Mutation> edits) {
        return new TreeMutator(store, STRING_DESC, pool).applyMutations(base, edits.iterator());
    }

    private static TreeMutator.Mutation put(HeapBufferPool pool, String k, String v) {
        return new TreeMutator.Mutation(
                key(pool, k), v == null ? null : MemorySegment.ofArray(v.getBytes()));
    }

    private static String get(InMemoryNodeStore store, HeapBufferPool pool, Node root, String k) {
        return new StaticMap(store, root, STRING_DESC)
                .get(key(pool, k))
                .map(s -> new String(s.toArray(ValueLayout.JAVA_BYTE)))
                .orElse(null);
    }

    @Test
    void disjoint_tail_appends_merge_cleanly() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node ancestor = range(pool, store, 0, 2000);
            assertTrue(ancestor.level() >= 1, "ancestor must be multi-level");

            List<TreeMutator.Mutation> ourEdits = new ArrayList<>();
            for (int i = 2000; i < 2010; i++)
                ourEdits.add(put(pool, String.format("k-%05d", i), "ours-" + i));
            Node ours = apply(pool, store, ancestor, ourEdits);

            List<TreeMutator.Mutation> theirEdits = new ArrayList<>();
            for (int i = 3000; i < 3010; i++)
                theirEdits.add(put(pool, String.format("k-%05d", i), "theirs-" + i));
            Node theirs = apply(pool, store, ancestor, theirEdits);

            MergeEngine.MergeResult r =
                    new MergeEngine(store, STRING_DESC, pool).merge(ancestor, ours, theirs);

            assertTrue(
                    r.conflicts().isEmpty(),
                    "disjoint tail appends on a multi-level tree must merge cleanly");
            assertEquals("ours-2005", get(store, pool, r.root(), "k-02005"));
            assertEquals("theirs-3005", get(store, pool, r.root(), "k-03005"));
            assertEquals(
                    "v-1000",
                    get(store, pool, r.root(), "k-01000"),
                    "an untouched ancestor key must survive the merge unchanged");
        }
    }

    @Test
    void boundary_shifting_add_plus_distant_mod_merge_cleanly() {
        // ours appends a tail key (shifts the last leaf's separator);
        // theirs modifies a mid-tree key. No overlap → clean merge.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node ancestor = range(pool, store, 0, 2000);
            Node ours = apply(pool, store, ancestor, List.of(put(pool, "k-02000", "appended")));
            Node theirs = apply(pool, store, ancestor, List.of(put(pool, "k-01000", "modified")));

            MergeEngine.MergeResult r =
                    new MergeEngine(store, STRING_DESC, pool).merge(ancestor, ours, theirs);

            assertTrue(r.conflicts().isEmpty(), "non-overlapping edits must not conflict");
            assertEquals("appended", get(store, pool, r.root(), "k-02000"));
            assertEquals("modified", get(store, pool, r.root(), "k-01000"));
        }
    }

    @Test
    void same_key_modified_differently_on_multi_level_yields_one_conflict() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node ancestor = range(pool, store, 0, 2000);
            Node ours = apply(pool, store, ancestor, List.of(put(pool, "k-01000", "OURS")));
            Node theirs = apply(pool, store, ancestor, List.of(put(pool, "k-01000", "THEIRS")));

            MergeEngine.MergeResult r =
                    new MergeEngine(store, STRING_DESC, pool).merge(ancestor, ours, theirs);

            assertEquals(
                    1,
                    r.conflicts().size(),
                    "the same mid-tree key modified differently → exactly one conflict");
            MergeEngine.Conflict c = r.conflicts().get(0);
            assertEquals("v-1000", new String(c.baseVal().toArray(ValueLayout.JAVA_BYTE)));
            assertEquals("OURS", new String(c.ourVal().toArray(ValueLayout.JAVA_BYTE)));
            assertEquals("THEIRS", new String(c.theirVal().toArray(ValueLayout.JAVA_BYTE)));
        }
    }

    @Test
    void both_sides_delete_same_boundary_key_no_conflict() {
        // k-01999 is the ancestor's largest key — a chunk boundary. Both
        // branches delete it: a DEL/DEL is the same change, never a conflict.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node ancestor = range(pool, store, 0, 2000);
            Node ours = apply(pool, store, ancestor, List.of(put(pool, "k-01999", null)));
            Node theirs = apply(pool, store, ancestor, List.of(put(pool, "k-01999", null)));

            MergeEngine.MergeResult r =
                    new MergeEngine(store, STRING_DESC, pool).merge(ancestor, ours, theirs);

            assertTrue(r.conflicts().isEmpty(), "DEL/DEL of the same key is not a conflict");
            assertNull(
                    get(store, pool, r.root(), "k-01999"),
                    "the doubly-deleted key must be absent from the merged tree");
            assertEquals(
                    "v-1998", get(store, pool, r.root(), "k-01998"), "its neighbour must remain");
        }
    }

    @Test
    void ancestor_leaf_grows_to_multi_level_on_one_side() {
        // Level mismatch: ancestor is a single leaf; ours grows it into a
        // multi-level tree; theirs modifies an original key. Before the diff
        // rewrite, collectChanges(leaf-ancestor, multi-level-ours) crashed.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node ancestor = range(pool, store, 0, 8);
            assertEquals(0, ancestor.level(), "ancestor must be a single leaf");

            List<TreeMutator.Mutation> grow = new ArrayList<>();
            for (int i = 8; i < 2000; i++)
                grow.add(put(pool, String.format("k-%05d", i), "v-" + i));
            Node ours = apply(pool, store, ancestor, grow);
            assertTrue(ours.level() >= 1, "ours must have grown multi-level");

            Node theirs = apply(pool, store, ancestor, List.of(put(pool, "k-00003", "THEIRS")));

            MergeEngine.MergeResult r =
                    new MergeEngine(store, STRING_DESC, pool).merge(ancestor, ours, theirs);

            assertTrue(
                    r.conflicts().isEmpty(),
                    "growing one side and modifying a disjoint key must merge cleanly");
            assertEquals("THEIRS", get(store, pool, r.root(), "k-00003"));
            assertEquals(
                    "v-1500",
                    get(store, pool, r.root(), "k-01500"),
                    "a key added only by ours must be present in the merge");
        }
    }

    @Test
    void mid_tree_disjoint_inserts_from_both_sides_merge_cleanly() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node ancestor = range(pool, store, 0, 2000);
            Node ours = apply(pool, store, ancestor, List.of(put(pool, "k-00500x", "ours-insert")));
            Node theirs =
                    apply(pool, store, ancestor, List.of(put(pool, "k-01500x", "theirs-insert")));

            MergeEngine.MergeResult r =
                    new MergeEngine(store, STRING_DESC, pool).merge(ancestor, ours, theirs);

            assertTrue(r.conflicts().isEmpty(), "two disjoint interior inserts must not conflict");
            assertEquals("ours-insert", get(store, pool, r.root(), "k-00500x"));
            assertEquals("theirs-insert", get(store, pool, r.root(), "k-01500x"));
        }
    }
}
