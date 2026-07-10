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
 * Direction-reversal coverage for {@link TreeIter}. {@code TreeIterTest} pins the forward,
 * backward, and seek happy paths in isolation; this file pins the transitions <em>between</em> them
 * — the spots where bidirectional iterators classically misbehave.
 *
 * <p>The load-bearing contract here is the {@code if (!valid) return false} short-circuit in both
 * {@code next()} and {@code prev()}: once an iterator exhausts in one direction it does
 * <strong>not</strong> resurrect by reversing — only {@code seek()} re-arms it.
 */
class TreeIterDirectionTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static Node tree(HeapBufferPool pool, InMemoryNodeStore store, String... keys) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        for (String k : keys) {
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, k), MemorySegment.ofArray(("v-" + k).getBytes())));
        }
        return m.applyMutations(null, edits.iterator());
    }

    private static String curKey(TreeIter it) {
        return new String(new Tuple(it.key()).getField(0));
    }

    // ---- exhaustion is not reversible ----

    @Test
    void prev_after_forward_exhaustion_stays_false() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            while (it.next()) {
                /* exhaust forward */
            }
            assertFalse(it.prev(), "a forward-exhausted iterator must NOT resurrect via prev()");
            assertFalse(it.prev(), "repeated prev() on an exhausted iter stays false");
        }
    }

    @Test
    void next_after_backward_exhaustion_stays_false() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            // First prev() seeds validity at "a"; second prev() exhausts backward.
            assertTrue(it.prev());
            assertFalse(it.prev(), "prev() off the first entry exhausts the iterator");
            assertFalse(it.next(), "a backward-exhausted iterator must NOT resurrect via next()");
        }
    }

    // ---- seek re-arms an exhausted iterator ----

    @Test
    void seek_re_arms_a_forward_exhausted_iterator() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c", "d");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            while (it.next()) {
                /* exhaust */
            }
            assertFalse(it.next(), "precondition: iterator is exhausted");

            it.seek(key(pool, "b"));
            assertTrue(it.next(), "seek() must reset started/valid so the iterator works again");
            assertEquals("b", curKey(it));
            assertTrue(it.next());
            assertEquals("c", curKey(it));
        }
    }

    // ---- direction changes mid-stream ----

    @Test
    void zigzag_next_prev_tracks_exact_position() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c", "d");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            assertTrue(it.next());
            assertEquals("a", curKey(it));
            assertTrue(it.next());
            assertEquals("b", curKey(it));
            assertTrue(it.next());
            assertEquals("c", curKey(it));
            assertTrue(it.prev());
            assertEquals("b", curKey(it));
            assertTrue(it.next());
            assertEquals("c", curKey(it));
            assertTrue(it.prev());
            assertEquals("b", curKey(it));
            assertTrue(it.prev());
            assertEquals("a", curKey(it));
        }
    }

    @Test
    void seek_then_prev_walks_backward_from_seek_point() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c", "d", "e");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            it.seek(key(pool, "d"));
            // First call after seek (started==false) seeds validity at "d".
            assertTrue(it.prev());
            assertEquals("d", curKey(it), "first prev() after seek seeds at the sought key");
            assertTrue(it.prev());
            assertEquals("c", curKey(it));
            assertTrue(it.prev());
            assertEquals("b", curKey(it));
            assertTrue(it.prev());
            assertEquals("a", curKey(it));
            assertFalse(it.prev(), "prev() off the smallest key exhausts the iterator");
        }
    }

    @Test
    void prev_then_next_from_mid_tree_seek() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c", "d", "e");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            it.seek(key(pool, "c"));
            assertTrue(it.prev());
            assertEquals("c", curKey(it)); // seed
            assertTrue(it.prev());
            assertEquals("b", curKey(it));
            assertTrue(it.next());
            assertEquals("c", curKey(it));
            assertTrue(it.next());
            assertEquals("d", curKey(it));
        }
    }
}
