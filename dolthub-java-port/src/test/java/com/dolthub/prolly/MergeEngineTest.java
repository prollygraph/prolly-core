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
 * SQLite-grade coverage for core {@link MergeEngine}. Three-way merge underpins every rebase,
 * squash-merge, and event-log promotion path. Silent merge bugs corrupt repository history without
 * warning.
 *
 * <p>Upstream consumers layer higher-level merge engines over this one; this file exercises the
 * lower-level byte-tree merge in isolation so the conflict-detection and fast-forward paths can be
 * pinned without the RDF layer's value materialization noise.
 */
class MergeEngineTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static Node applyMutations(
            HeapBufferPool pool,
            InMemoryNodeStore store,
            Node base,
            List<TreeMutator.Mutation> edits) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        return m.applyMutations(base, edits.iterator());
    }

    private static String valOf(
            MergeEngine.MergeResult r, HeapBufferPool pool, InMemoryNodeStore store, String k) {
        StaticMap map = new StaticMap(store, r.root(), STRING_DESC);
        return new String(
                map.get(key(pool, k))
                        .orElseThrow()
                        .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }

    private static Node mut(
            HeapBufferPool pool, InMemoryNodeStore store, Node base, String k, String v) {
        return applyMutations(
                pool,
                store,
                base,
                List.of(
                        new TreeMutator.Mutation(
                                key(pool, k),
                                v == null ? null : MemorySegment.ofArray(v.getBytes()))));
    }

    // ---- null-side fast paths ----

    @Test
    void null_ours_returns_theirs() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node theirs = mut(pool, store, null, "a", "1");
            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(null, null, theirs);
            assertSame(theirs, r.root());
            assertTrue(r.conflicts().isEmpty());
        }
    }

    @Test
    void null_theirs_returns_ours() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node ours = mut(pool, store, null, "a", "1");
            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(null, ours, null);
            assertSame(ours, r.root());
            assertTrue(r.conflicts().isEmpty());
        }
    }

    // ---- fast-forward ----

    @Test
    void ours_equal_ancestor_fast_forwards_to_theirs() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base = mut(pool, store, null, "a", "1");
            Node theirs = mut(pool, store, base, "b", "2");
            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(base, base, theirs);
            assertSame(theirs, r.root(), "ours == ancestor → fast-forward to theirs");
            assertTrue(r.conflicts().isEmpty());
        }
    }

    @Test
    void theirs_equal_ancestor_fast_forwards_to_ours() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base = mut(pool, store, null, "a", "1");
            Node ours = mut(pool, store, base, "c", "3");
            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(base, ours, base);
            assertSame(ours, r.root());
            assertTrue(r.conflicts().isEmpty());
        }
    }

    // ---- non-conflicting merge ----

    @Test
    void disjoint_changes_merge_cleanly() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base =
                    applyMutations(
                            pool,
                            store,
                            null,
                            List.of(
                                    new TreeMutator.Mutation(
                                            key(pool, "a"),
                                            MemorySegment.ofArray("base".getBytes()))));

            Node ours = mut(pool, store, base, "b", "ours");
            Node theirs = mut(pool, store, base, "c", "theirs");

            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(base, ours, theirs);
            assertTrue(r.conflicts().isEmpty(), "disjoint adds → no conflict");
            assertEquals("base", valOf(r, pool, store, "a"));
            assertEquals("ours", valOf(r, pool, store, "b"));
            assertEquals("theirs", valOf(r, pool, store, "c"));
        }
    }

    @Test
    void identical_changes_on_both_sides_merge_clean() {
        // Same key + same new value on both sides: not a conflict.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base = mut(pool, store, null, "a", "1");
            Node ours = mut(pool, store, base, "a", "2");
            Node theirs = mut(pool, store, base, "a", "2");
            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(base, ours, theirs);
            assertTrue(
                    r.conflicts().isEmpty(),
                    "same key with identical new value on both sides → no conflict");
            assertEquals("2", valOf(r, pool, store, "a"));
        }
    }

    // ---- conflicts ----

    @Test
    void same_key_different_values_yields_conflict() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base = mut(pool, store, null, "a", "1");
            Node ours = mut(pool, store, base, "a", "ours-value");
            Node theirs = mut(pool, store, base, "a", "theirs-value");
            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(base, ours, theirs);
            assertEquals(1, r.conflicts().size());
            MergeEngine.Conflict c = r.conflicts().get(0);
            assertEquals("a", new String(new Tuple(c.key()).getField(0)));
            assertEquals(
                    "1", new String(c.baseVal().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)));
            assertEquals(
                    "ours-value",
                    new String(c.ourVal().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)));
            assertEquals(
                    "theirs-value",
                    new String(c.theirVal().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)));
        }
    }

    @Test
    void one_side_delete_other_side_modify_yields_conflict() {
        // Need extra keys so neither side reduces to an empty tree (which would
        // trigger MergeEngine's "ours == null → return theirs" fast-path and
        // hide the conflict).
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base =
                    applyMutations(
                            pool,
                            store,
                            null,
                            List.of(
                                    new TreeMutator.Mutation(
                                            key(pool, "a"), MemorySegment.ofArray("1".getBytes())),
                                    new TreeMutator.Mutation(
                                            key(pool, "z"),
                                            MemorySegment.ofArray("9".getBytes()))));
            Node ours = mut(pool, store, base, "a", null); // delete on ours
            Node theirs = mut(pool, store, base, "a", "updated");
            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(base, ours, theirs);
            assertEquals(
                    1,
                    r.conflicts().size(),
                    "delete vs. modify on the same key must be reported as a conflict");
        }
    }

    @Test
    void both_sides_delete_merges_clean() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base =
                    applyMutations(
                            pool,
                            store,
                            null,
                            List.of(
                                    new TreeMutator.Mutation(
                                            key(pool, "a"), MemorySegment.ofArray("1".getBytes())),
                                    new TreeMutator.Mutation(
                                            key(pool, "b"),
                                            MemorySegment.ofArray("2".getBytes()))));
            Node ours = mut(pool, store, base, "a", null);
            Node theirs = mut(pool, store, base, "a", null);
            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(base, ours, theirs);
            assertTrue(r.conflicts().isEmpty(), "matched deletes are not a conflict");
            assertFalse(
                    new StaticMap(store, r.root(), STRING_DESC).get(key(pool, "a")).isPresent());
            // 'b' should still be present
            assertTrue(new StaticMap(store, r.root(), STRING_DESC).get(key(pool, "b")).isPresent());
        }
    }

    // ---- larger merge ----

    @Test
    void many_disjoint_adds_merge_correctly() {
        // Each side adds 100 distinct keys; result must contain all 200.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base =
                    applyMutations(
                            pool,
                            store,
                            null,
                            List.of(
                                    new TreeMutator.Mutation(
                                            key(pool, "0"),
                                            MemorySegment.ofArray("base".getBytes()))));

            List<TreeMutator.Mutation> ourEdits = new ArrayList<>();
            List<TreeMutator.Mutation> theirEdits = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                ourEdits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("a-%05d", i)),
                                MemorySegment.ofArray(("our-" + i).getBytes())));
                theirEdits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("b-%05d", i)),
                                MemorySegment.ofArray(("their-" + i).getBytes())));
            }
            Node ours = applyMutations(pool, store, base, ourEdits);
            Node theirs = applyMutations(pool, store, base, theirEdits);

            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(base, ours, theirs);
            assertTrue(r.conflicts().isEmpty());
            assertEquals(
                    201L,
                    r.root().treeCount(),
                    "merge of 1 base + 100 our + 100 their = 201 entries");
        }
    }
}
