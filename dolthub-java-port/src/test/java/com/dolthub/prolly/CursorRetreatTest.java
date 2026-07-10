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
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Focused coverage for {@link Cursor#retreat()} and the {@code fetchNodeFromParent}
 * dangling-reference path.
 *
 * <p>{@code CursorEdgeTest} exercises {@code retreat} only within a single leaf. This file pins
 * backward navigation <em>across internal-node boundaries</em> on a multi-level tree — the mirror
 * of {@code advance_across_leaf_boundary} — plus the {@link IllegalStateException} thrown when a
 * cursor steps into a child chunk that is missing from the store.
 */
class CursorRetreatTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static Node tree(HeapBufferPool pool, InMemoryNodeStore store, int n) {
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

    // ---- retreat across internal-node boundaries ----

    @Test
    void retreat_from_end_visits_every_key_in_reverse() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            int n = 2000;
            Node root = tree(pool, store, n);
            assertTrue(root.level() >= 1, "expected multi-level tree");

            Cursor c = Cursor.atEnd(store, root);
            int seen = 0;
            String prev = null;
            do {
                String k = new String(new Tuple(c.currentKey()).getField(0));
                if (prev != null) {
                    assertTrue(
                            k.compareTo(prev) < 0,
                            "retreat must yield strictly descending keys: " + k + " !< " + prev);
                }
                prev = k;
                seen++;
            } while (c.retreat());

            assertEquals(n, seen, "retreat from atEnd must visit every entry exactly once");
            assertEquals("k-00000", prev, "the final retreat position is the smallest key");
        }
    }

    @Test
    void retreat_past_start_returns_false_and_sets_index_negative() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            Cursor c = Cursor.atStart(store, root);
            assertFalse(c.retreat(), "retreat at the first entry must return false");
            assertEquals(-1, c.index(), "exhausted-backward cursor parks index at -1");
        }
    }

    @Test
    void retreat_from_leaf_index_zero_hops_into_previous_leaf() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);

            // Walk forward to the first index-0 position of a non-leftmost
            // leaf, then retreat once: it must hop back into the prior leaf.
            Cursor c = Cursor.atStart(store, root);
            while (!(c.isLeaf()
                    && c.index() == 0
                    && new String(new Tuple(c.currentKey()).getField(0)).compareTo("k-00000")
                            > 0)) {
                assertTrue(c.advance(), "tree must contain more than one leaf");
            }
            String before = new String(new Tuple(c.currentKey()).getField(0));
            assertTrue(c.retreat(), "retreat across the leaf boundary must succeed");
            String after = new String(new Tuple(c.currentKey()).getField(0));
            assertTrue(
                    after.compareTo(before) < 0,
                    "retreat across a leaf boundary must land on the predecessor key");
        }
    }

    @Test
    void advance_then_retreat_round_trips_across_boundary() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            Cursor c = Cursor.atRawKey(store, root, key(pool, "k-00999"));
            String start = new String(new Tuple(c.currentKey()).getField(0));

            assertTrue(c.advance(), "advance off the leaf's last entry");
            assertTrue(c.retreat(), "retreat must undo the boundary-crossing advance");
            assertEquals(
                    start,
                    new String(new Tuple(c.currentKey()).getField(0)),
                    "advance followed by retreat must return to the exact start key");
        }
    }

    @Test
    void atEnd_then_single_retreat_is_second_to_last_key() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            Cursor c = Cursor.atEnd(store, root);
            assertEquals("k-01999", new String(new Tuple(c.currentKey()).getField(0)));
            assertTrue(c.retreat());
            assertEquals(
                    "k-01998",
                    new String(new Tuple(c.currentKey()).getField(0)),
                    "one retreat from atEnd is the second-to-last key");
        }
    }

    // ---- dangling-reference path: fetchNodeFromParent ----

    /**
     * A {@link NodeStore} wrapper that hides exactly one chunk, simulating a corrupt store with a
     * dangling child reference.
     */
    private static final class HoleStore implements NodeStore {
        private final NodeStore delegate;
        private final String hiddenHex;

        HoleStore(NodeStore delegate, byte[] hidden) {
            this.delegate = delegate;
            this.hiddenHex = HashUtils.toHex(hidden);
        }

        @Override
        public Optional<MemorySegment> read(byte[] hash) {
            if (HashUtils.toHex(hash).equals(hiddenHex)) return Optional.empty();
            return delegate.read(hash);
        }

        @Override
        public byte[] write(MemorySegment data) {
            return delegate.write(data);
        }

        @Override
        public byte[] write(byte[] data) {
            return delegate.write(data);
        }
    }

    @Test
    void advancing_into_a_missing_child_chunk_throws_illegal_state() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            assertTrue(root.level() >= 1, "need an internal root with child hashes");

            // Hide the root's second child. atStart descends the LEFTMOST
            // path, so construction still succeeds — the hole is only hit
            // once the cursor advances past the first subtree's last entry.
            byte[] missingChild = root.getValue(1);
            assertNotNull(missingChild, "root's second child reference must exist");
            HoleStore holed = new HoleStore(store, missingChild);

            Cursor c = Cursor.atStart(holed, root);
            IllegalStateException ex =
                    assertThrows(
                            IllegalStateException.class,
                            () -> {
                                while (c.advance()) {
                                    /* drain until the hole */
                                }
                            });
            assertTrue(
                    ex.getMessage().contains("missing from store"),
                    "the dangling-reference error must name the missing chunk: " + ex.getMessage());
        }
    }

    @Test
    void retreating_into_a_missing_child_chunk_throws_illegal_state() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            int last = root.count() - 1;
            assertTrue(last >= 1, "need an internal root with at least two children");

            // Hide the root's second-to-last child. atEnd descends the
            // RIGHTMOST path, so the hole is only reached by retreating
            // backward out of the last subtree.
            byte[] missingChild = root.getValue(last - 1);
            assertNotNull(missingChild);
            HoleStore holed = new HoleStore(store, missingChild);

            Cursor c = Cursor.atEnd(holed, root);
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        while (c.retreat()) {
                            /* drain backward until the hole */
                        }
                    },
                    "retreating into a dangling child reference must fail loudly");
        }
    }
}
