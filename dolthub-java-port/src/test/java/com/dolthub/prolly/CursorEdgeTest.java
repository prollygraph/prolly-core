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
 * Edge-case coverage for {@link Cursor}, complementing {@code CursorAdvanceInvariantTest}'s
 * scan-invariant property tests.
 *
 * <p>These cover the static factory paths ({@code atStart}, {@code atEnd}, {@code atKey}, {@code
 * atRawKey}), boundary advance/retreat, and the multi-level descent that {@code Cursor.clone()}
 * must preserve.
 */
class CursorEdgeTest {

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
    void atStart_on_single_leaf_points_at_index_zero() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 3);
            Cursor c = Cursor.atStart(store, root);
            assertTrue(c.isValid());
            assertEquals(0, c.index());
            assertEquals("k-00000", new String(new Tuple(c.currentKey()).getField(0)));
        }
    }

    @Test
    void atEnd_on_single_leaf_points_at_last_index() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 5);
            Cursor c = Cursor.atEnd(store, root);
            assertTrue(c.isValid());
            assertEquals(4, c.index());
            assertEquals("k-00004", new String(new Tuple(c.currentKey()).getField(0)));
        }
    }

    @Test
    void advance_to_end_returns_false() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 3);
            Cursor c = Cursor.atStart(store, root);
            assertTrue(c.advance()); // → index 1
            assertTrue(c.advance()); // → index 2
            assertFalse(c.advance(), "advance past last element must return false");
            assertFalse(c.isValid(), "cursor must be invalid after advancing past end");
        }
    }

    @Test
    void retreat_to_start_returns_false() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 3);
            Cursor c = Cursor.atEnd(store, root);
            assertTrue(c.retreat()); // → 1
            assertTrue(c.retreat()); // → 0
            assertFalse(c.retreat(), "retreat before first element must return false");
            assertFalse(c.isValid());
        }
    }

    @Test
    void advance_then_retreat_round_trip() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 4);
            Cursor c = Cursor.atStart(store, root);
            c.advance(); // k-00001
            String mid = new String(new Tuple(c.currentKey()).getField(0));
            c.advance();
            c.retreat();
            assertEquals(
                    mid,
                    new String(new Tuple(c.currentKey()).getField(0)),
                    "advance + retreat must restore the cursor position");
        }
    }

    @Test
    void atKey_exact_match_finds_key() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 10);
            Cursor c = Cursor.atKey(store, root, key(pool, "k-00005"), STRING_DESC);
            assertTrue(c.isValid());
            assertEquals("k-00005", new String(new Tuple(c.currentKey()).getField(0)));
        }
    }

    @Test
    void atKey_missing_key_lands_at_successor() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 10);
            // Insert a gap: searching for a key between existing ones should
            // land at the first key >= search target.
            Cursor c = Cursor.atKey(store, root, key(pool, "k-00004a"), STRING_DESC);
            assertTrue(c.isValid());
            assertEquals(
                    "k-00005",
                    new String(new Tuple(c.currentKey()).getField(0)),
                    "atKey on a missing key must land at the successor");
        }
    }

    @Test
    void atKey_past_end_invalid_or_at_last() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 3);
            Cursor c = Cursor.atKey(store, root, key(pool, "z-99999"), STRING_DESC);
            // Past-end semantics: searchInNode returns count, leaving the
            // cursor positioned just past the end. isValid() must reflect that.
            if (c.isValid()) {
                // Accept either behavior — if valid, must be at last item.
                assertEquals(2, c.index());
            } else {
                assertEquals(root.count(), c.index());
            }
        }
    }

    @Test
    void atRawKey_exact_match_finds_key() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 10);
            Cursor c = Cursor.atRawKey(store, root, key(pool, "k-00003"));
            assertTrue(c.isValid());
            assertEquals("k-00003", new String(new Tuple(c.currentKey()).getField(0)));
        }
    }

    @Test
    void multi_level_descent_to_leaf() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 2000);
            assertTrue(root.level() >= 1);
            Cursor c = Cursor.atStart(store, root);
            assertTrue(c.isLeaf(), "atStart must descend all the way to a leaf");
            assertEquals(0, c.index());
        }
    }

    @Test
    void advance_across_leaf_boundary() {
        // 2000 items → multi-leaf tree; advancing past one leaf's last
        // element must walk up the parent and back down into the next leaf.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 2000);
            Cursor c = Cursor.atStart(store, root);
            int count = 1;
            String last = new String(new Tuple(c.currentKey()).getField(0));
            while (c.advance()) {
                String now = new String(new Tuple(c.currentKey()).getField(0));
                assertTrue(
                        now.compareTo(last) > 0,
                        "key order must be monotonic across leaf boundaries");
                last = now;
                count++;
            }
            assertEquals(2000, count, "advance must visit every leaf item");
        }
    }

    @Test
    void clone_independently_navigable() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 10);
            Cursor a = Cursor.atStart(store, root);
            a.advance();
            a.advance(); // a at index 2
            Cursor b = a.clone();
            b.advance();
            b.advance(); // b at index 4
            // a should still be at index 2.
            assertEquals(2, a.index());
            assertEquals(4, b.index());
            assertEquals("k-00002", new String(new Tuple(a.currentKey()).getField(0)));
            assertEquals("k-00004", new String(new Tuple(b.currentKey()).getField(0)));
        }
    }

    @Test
    void node_and_index_accessors() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = smallTree(pool, store, 3);
            Cursor c = Cursor.atStart(store, root);
            assertNotNull(c.node());
            assertEquals(0, c.index());
            assertNull(c.parent(), "single-leaf cursor has no parent");
        }
    }
}
