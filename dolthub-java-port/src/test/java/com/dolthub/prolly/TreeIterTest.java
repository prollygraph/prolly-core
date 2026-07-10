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
 * SQLite-grade coverage for {@link TreeIter}. Iterator state transitions (started/valid/cursor) are
 * the kind of thing that's almost always tested via end-to-end happy paths and never via direct
 * state exercises — until something breaks subtly.
 */
class TreeIterTest {

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

    // ---- start state ----

    @Test
    void first_next_call_returns_initial_validity() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            assertTrue(it.next(), "first next() on valid cursor → true");
            assertEquals("a", new String(new Tuple(it.key()).getField(0)));
        }
    }

    @Test
    void first_next_with_null_cursor_returns_false() {
        try (InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeIter it = new TreeIter(store, null, null, STRING_DESC, c -> true);
            assertFalse(it.next(), "null cursor → first next() must return false without throwing");
        }
    }

    @Test
    void first_next_with_stop_predicate_true_returns_false() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> true);
            assertFalse(
                    it.next(), "stop predicate true on initial position → next() returns false");
        }
    }

    // ---- iteration ----

    @Test
    void next_walks_every_item() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c", "d");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            List<String> visited = new ArrayList<>();
            while (it.next()) {
                visited.add(new String(new Tuple(it.key()).getField(0)));
            }
            assertEquals(List.of("a", "b", "c", "d"), visited);
        }
    }

    @Test
    void next_after_exhaustion_keeps_returning_false() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            while (it.next()) {
                /* exhaust */
            }
            assertFalse(it.next(), "exhausted iter must stay false");
            assertFalse(it.next(), "repeated next() on exhausted iter must be safe");
        }
    }

    // ---- seek ----

    @Test
    void seek_repositions_to_key() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c", "d");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            it.next(); // at "a"
            it.seek(key(pool, "c"));
            assertTrue(it.next(), "seek + first next() must return true");
            assertEquals("c", new String(new Tuple(it.key()).getField(0)));
        }
    }

    @Test
    void seek_past_end_yields_no_items() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            it.seek(key(pool, "zzz"));
            // Cursor lands past-end → next() must return false.
            // (atKey returns a cursor at the lower-bound position; for keys
            // past the last leaf entry that position is invalid.)
            assertFalse(it.next());
        }
    }

    @Test
    void seek_without_store_throws() {
        // The prefix-iterator overload sets store=null on purpose; seek() must
        // refuse rather than silently doing the wrong thing.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b");
            Cursor c = Cursor.atStart(store, root);
            TreeIter prefixIter = new TreeIter(c, cur -> false);
            assertThrows(
                    UnsupportedOperationException.class, () -> prefixIter.seek(key(pool, "a")));
        }
    }

    // ---- prev ----

    @Test
    void prev_after_advance_returns_previous_item() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            it.next(); // a
            it.next(); // b
            assertTrue(it.prev(), "prev() must succeed after advancing");
            assertEquals("a", new String(new Tuple(it.key()).getField(0)));
        }
    }

    @Test
    void prev_on_fresh_iter_initializes_validity_from_start_position() {
        // First call (started==false) initializes from cursor position,
        // regardless of direction.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a", "b", "c");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atEnd(store, root), STRING_DESC, c -> false);
            assertTrue(it.prev(), "fresh iter's first prev() initializes validity");
            assertEquals(
                    "c",
                    new String(new Tuple(it.key()).getField(0)),
                    "prev() seeded at end → returns last item first");
        }
    }

    // ---- key()/value() proxies ----

    @Test
    void key_and_value_return_cursor_current() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a");
            TreeIter it =
                    new TreeIter(store, root, Cursor.atStart(store, root), STRING_DESC, c -> false);
            it.next();
            assertEquals("a", new String(new Tuple(it.key()).getField(0)));
            assertEquals(
                    "v-a", new String(it.value().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)));
        }
    }

    // ---- prefix-iterator constructor ----

    @Test
    void prefix_constructor_stop_predicate_terminates_iteration() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, "a-1", "a-2", "b-1", "b-2");
            Cursor c = Cursor.atStart(store, root);
            // Stop predicate: stop when key doesn't begin with "a".
            TreeIter it =
                    new TreeIter(
                            c,
                            cur -> {
                                byte[] k = new Tuple(cur.currentKey()).getField(0);
                                return k[0] != 'a';
                            });
            List<String> visited = new ArrayList<>();
            while (it.next()) visited.add(new String(new Tuple(it.key()).getField(0)));
            assertEquals(List.of("a-1", "a-2"), visited);
        }
    }
}
