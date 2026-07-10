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
 * Focused coverage for {@link Cursor#atRawKey} — the byte-lex search path used by prefix lookups.
 * Distinct from {@link Cursor#atKey}, which uses {@link TupleDescriptor#compare}; the raw-key path
 * bypasses the descriptor entirely and compares on physical bytes.
 *
 * <p>Existing {@code CursorEdgeTest} has one exact-match check; this file pins the missing-key
 * successor, multi-level descent, past-end landing, and the deep clone of a parent chain.
 */
class CursorAtRawKeyTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment keyTuple(HeapBufferPool pool, String s) {
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
                            keyTuple(pool, String.format("k-%05d", i)),
                            MemorySegment.ofArray(("v-" + i).getBytes())));
        }
        return m.applyMutations(null, edits.iterator());
    }

    // ---- exact + missing ----

    @Test
    void atRawKey_missing_key_lands_at_successor() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 10);
            Cursor c = Cursor.atRawKey(store, root, keyTuple(pool, "k-00004a"));
            assertTrue(c.isValid());
            assertEquals(
                    "k-00005",
                    new String(new Tuple(c.currentKey()).getField(0)),
                    "atRawKey on missing key must land at successor in byte-lex order");
        }
    }

    @Test
    void atRawKey_below_smallest_lands_at_first() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 5);
            // A tuple of a single byte less than the first key's first byte.
            Cursor c = Cursor.atRawKey(store, root, keyTuple(pool, "0"));
            assertTrue(c.isValid());
            assertEquals(
                    "k-00000",
                    new String(new Tuple(c.currentKey()).getField(0)),
                    "search before the smallest key must land at the first entry");
        }
    }

    @Test
    void atRawKey_past_end_invalid_or_at_last() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 3);
            Cursor c = Cursor.atRawKey(store, root, keyTuple(pool, "z-zzz"));
            // Past-end: searchInNodeRaw returns count; cursor is positioned
            // just past the end. Accept either invalid or at-last.
            if (c.isValid()) {
                assertEquals(2, c.index());
            } else {
                assertEquals(root.count(), c.index());
            }
        }
    }

    // ---- multi-level ----

    @Test
    void atRawKey_descends_to_leaf_on_multi_level_tree() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            assertTrue(root.level() >= 1, "expected multi-level tree");

            Cursor c = Cursor.atRawKey(store, root, keyTuple(pool, "k-01000"));
            assertTrue(c.isLeaf(), "atRawKey must descend all the way to a leaf");
            assertEquals("k-01000", new String(new Tuple(c.currentKey()).getField(0)));
        }
    }

    @Test
    void atRawKey_missing_key_in_multi_level_finds_successor() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            Cursor c = Cursor.atRawKey(store, root, keyTuple(pool, "k-00999z"));
            assertTrue(c.isValid());
            assertEquals(
                    "k-01000",
                    new String(new Tuple(c.currentKey()).getField(0)),
                    "raw-key successor lookup must work across internal-node boundaries");
        }
    }

    // ---- clone with deep parent chain ----

    @Test
    void clone_preserves_multi_level_parent_chain() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            Cursor a = Cursor.atRawKey(store, root, keyTuple(pool, "k-01500"));
            assertTrue(a.isLeaf());

            Cursor b = a.clone();
            // Both cursors must navigate independently.
            assertTrue(a.advance());
            // a moved forward; b should still be at its original position.
            assertEquals("k-01500", new String(new Tuple(b.currentKey()).getField(0)));
        }
    }

    @Test
    void clone_independently_reaches_end() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 200);
            Cursor a = Cursor.atStart(store, root);
            Cursor b = a.clone();
            // Advance b to end; a must remain at start.
            while (b.advance()) {
                /* drain */
            }
            assertEquals(0, a.index());
            assertEquals("k-00000", new String(new Tuple(a.currentKey()).getField(0)));
        }
    }

    // ---- atRawKey vs atKey divergence ----

    @Test
    void atRawKey_and_atKey_both_find_exact_match() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 100);
            Cursor raw = Cursor.atRawKey(store, root, keyTuple(pool, "k-00050"));
            Cursor typed = Cursor.atKey(store, root, keyTuple(pool, "k-00050"), STRING_DESC);
            // For tuples whose physical bytes happen to lex-compare the same
            // way as the descriptor compares, both paths find the same entry.
            assertEquals(typed.index(), raw.index());
            assertArrayEquals(
                    typed.currentKey().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                    raw.currentKey().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }
    }
}
