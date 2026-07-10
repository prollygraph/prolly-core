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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the {@link Cursor} fast-forward primitives re-introduced by the upstream
 * tree-write-fast-forwarding-impl plan (Phase A). These were stripped when structural
 * fast-forwarding was removed; restoring {@code O(log n)} writes (ADR-0068) needs them back. This
 * class grows one step per primitive (Phase A Steps 1–4).
 *
 * <p>Step 1: {@link Cursor#atNodeEnd()} — the single-level "am I at my current node's last item?"
 * predicate the chunker pairs with a fresh splitter boundary to detect alignment.
 *
 * <p>Step 2: {@link Cursor#currentSubtreeSize()} — the per-child <b>individual</b> count a skip
 * emits by reference. The decisive test pins that it corrects for the port's <em>cumulative</em>
 * {@code getSubtreeCount} (D-6): individual counts must <b>sum</b> to the subtree total.
 *
 * <p>Step 3: {@link Cursor#compare(Cursor)} — position comparison (parent level outranks child),
 * the "caught up to the edit point?" test. Step 4: {@link Cursor#invalidateAtEnd()} + {@link
 * Cursor#copy(Cursor)} — the in-place re-point {@code advanceTo} needs ({@code clone()}, which
 * returns a new object, does not serve it).
 */
class CursorFastForwardTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static Node smallTree(HeapBufferPool pool, InMemoryNodeStore store, int n) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, String.format("k-%05d", i)),
                            MemorySegment.ofArray(("v-" + i).getBytes())));
        }
        return m.applyMutations(null, edits.iterator());
    }

    @Test
    void atNodeEnd_true_only_at_last_index() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 5); // 5 tiny entries → single leaf
            Cursor c = Cursor.atStart(store, root);
            assertFalse(c.atNodeEnd(), "index 0 of 5 is not node-end");
            c.advance();
            c.advance();
            c.advance(); // index 3
            assertFalse(c.atNodeEnd(), "index 3 of 5 is not node-end");
            c.advance(); // index 4 (last)
            assertTrue(c.atNodeEnd(), "index 4 of 5 IS node-end");
        }
    }

    @Test
    void atNodeEnd_false_after_advancing_past_end() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 3);
            Cursor c = Cursor.atEnd(store, root); // index 2 (last)
            assertTrue(c.atNodeEnd());
            assertFalse(c.advance(), "advance past last returns false");
            assertFalse(
                    c.atNodeEnd(),
                    "a past-end cursor (index == count) is NOT at node-end (count-1)");
        }
    }

    @Test
    void atNodeEnd_is_a_current_node_not_whole_tree_predicate() {
        // A multi-leaf tree: at the last item of the FIRST (non-final) leaf, atNodeEnd is true at
        // the leaf level, yet advance() still succeeds — proving it's single-level, not whole-tree.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 2000);
            assertTrue(root.level() >= 1, "2000 entries must build a multi-level tree");
            Cursor c = Cursor.atStart(store, root);
            while (!c.atNodeEnd()) {
                assertTrue(c.advance(), "must reach the first leaf's last item before its end");
            }
            assertTrue(c.atNodeEnd(), "at the first leaf's last item, atNodeEnd is true");
            assertTrue(
                    c.advance(),
                    "yet the tree is not at its end — advance crosses into the next leaf");
            assertEquals(0, c.index(), "now at index 0 of the next leaf");
        }
    }

    @Test
    void currentSubtreeSize_is_one_at_a_leaf_entry() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 5); // single leaf
            Cursor c = Cursor.atStart(store, root);
            assertTrue(c.isLeaf());
            assertEquals(1L, c.currentSubtreeSize());
            c.advance();
            assertEquals(1L, c.currentSubtreeSize(), "every leaf entry counts as one");
        }
    }

    @Test
    void currentSubtreeSize_at_internal_is_individual_not_cumulative() {
        // The decisive test (D-6): the port's getSubtreeCount is a cumulative prefix sum, so
        // summing
        // currentSubtreeSize() over a node's children must equal the subtree total. The naive
        // isLeaf()?1:getSubtreeCount(index) would sum cumulative values and badly over-count.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            int n = 2000;
            Node root = smallTree(pool, store, n);
            assertTrue(root.level() >= 1, "2000 entries must build a multi-level tree");
            // Climb to the root-level cursor, then clone so we can sweep its children
            // independently.
            Cursor top = Cursor.atStart(store, root);
            while (top.parent() != null) top = top.parent();
            assertFalse(top.isLeaf(), "root of a multi-level tree is internal");
            Cursor sweep = top.clone();
            long sum = 0;
            do {
                long sz = sweep.currentSubtreeSize();
                assertTrue(sz >= 1, "an internal child stands for at least one leaf entry");
                sum += sz;
            } while (sweep.advance());
            assertEquals(
                    (long) n,
                    sum,
                    "individual child subtree sizes must partition all " + n + " leaf entries");
        }
    }

    @Test
    void compare_orders_cursors_in_the_same_tree() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 2000); // multi-level
            Cursor a = Cursor.atStart(store, root);
            Cursor b = a.clone();
            assertEquals(0, a.compare(b), "same position compares equal");
            b.advance();
            assertTrue(a.compare(b) < 0, "a is before b");
            assertTrue(b.compare(a) > 0, "b is after a");
            a.advance();
            assertEquals(0, a.compare(b), "re-aligned to the same position");
        }
    }

    @Test
    void compare_parent_level_outranks_child() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 2000);
            Cursor a = Cursor.atStart(store, root); // first leaf, index 0
            Cursor b = Cursor.atEnd(store, root); // last leaf, last index
            assertTrue(a.compare(b) < 0, "atStart is before atEnd across leaves");
            assertTrue(b.compare(a) > 0);
        }
    }

    @Test
    void invalidateAtEnd_makes_cursor_invalid() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 3);
            Cursor c = Cursor.atStart(store, root);
            assertTrue(c.isValid());
            c.invalidateAtEnd();
            assertFalse(c.isValid(), "past-end index → invalid");
            assertEquals(root.count(), c.index());
            assertFalse(c.atNodeEnd(), "past-end is not at-node-end");
        }
    }

    @Test
    void copy_repoints_in_place_preserving_identity() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 2000); // multi-level → exercises the parent chain
            Cursor target = Cursor.atStart(store, root); // first leaf, index 0
            Cursor source = Cursor.atEnd(store, root); // last leaf, last index
            target.copy(source);
            assertEquals(0, target.compare(source), "after copy, target aligns with source");
            assertEquals(
                    new String(new Tuple(source.currentKey()).getField(0)),
                    new String(new Tuple(target.currentKey()).getField(0)),
                    "copied cursor reads source's key (self-invalidating cache recomputed)");
        }
    }

    @Test
    void copy_rejects_unequal_height() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node tall = smallTree(pool, store, 2000); // multi-level
            Node flat = smallTree(pool, store, 3); // single leaf
            Cursor deep = Cursor.atStart(store, tall); // has a parent chain
            Cursor shallow = Cursor.atStart(store, flat); // parent == null
            assertThrows(IllegalStateException.class, () -> deep.copy(shallow));
            assertThrows(IllegalStateException.class, () -> shallow.copy(deep));
        }
    }
}
