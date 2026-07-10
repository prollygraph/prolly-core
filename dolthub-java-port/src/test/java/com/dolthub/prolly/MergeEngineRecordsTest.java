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
 * Coverage gap for {@link MergeEngine.MergeResult} and {@link MergeEngine.Conflict} record
 * contracts, plus the null-ancestor branch that the existing MergeEngineTest doesn't directly
 * exercise.
 */
class MergeEngineRecordsTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    // ---- MergeResult record ----

    @Test
    void merge_result_carries_root_and_conflicts() {
        MergeEngine.MergeResult r = new MergeEngine.MergeResult(null, List.of());
        assertNull(r.root());
        assertTrue(r.conflicts().isEmpty());
    }

    @Test
    void merge_result_record_equality_by_value() {
        // Same null root + same empty conflict list → equal.
        MergeEngine.MergeResult a = new MergeEngine.MergeResult(null, List.of());
        MergeEngine.MergeResult b = new MergeEngine.MergeResult(null, List.of());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ---- Conflict record ----

    @Test
    void conflict_carries_all_four_fields() {
        MemorySegment k = MemorySegment.ofArray("k".getBytes());
        MemorySegment baseVal = MemorySegment.ofArray("base".getBytes());
        MemorySegment ours = MemorySegment.ofArray("o".getBytes());
        MemorySegment theirs = MemorySegment.ofArray("t".getBytes());
        MergeEngine.Conflict c = new MergeEngine.Conflict(k, baseVal, ours, theirs);
        assertSame(k, c.key());
        assertSame(baseVal, c.baseVal());
        assertSame(ours, c.ourVal());
        assertSame(theirs, c.theirVal());
    }

    @Test
    void conflict_with_null_base_allowed() {
        // Add/add conflict: no common ancestor for the key — base is null.
        MergeEngine.Conflict c =
                new MergeEngine.Conflict(
                        MemorySegment.ofArray("k".getBytes()),
                        null,
                        MemorySegment.ofArray("o".getBytes()),
                        MemorySegment.ofArray("t".getBytes()));
        assertNull(c.baseVal(), "null baseVal must be permitted — represents an add/add conflict");
    }

    @Test
    void conflict_with_null_value_for_delete_side() {
        // Delete vs modify: the delete side's valueB is null.
        MergeEngine.Conflict c =
                new MergeEngine.Conflict(
                        MemorySegment.ofArray("k".getBytes()),
                        MemorySegment.ofArray("base".getBytes()),
                        null,
                        MemorySegment.ofArray("t".getBytes()));
        assertNull(
                c.ourVal(), "null ourVal represents the delete side of a delete/modify conflict");
    }

    @Test
    void conflict_record_equality() {
        MemorySegment k = MemorySegment.ofArray("k".getBytes());
        MemorySegment a = MemorySegment.ofArray("a".getBytes());
        MergeEngine.Conflict x = new MergeEngine.Conflict(k, a, a, a);
        MergeEngine.Conflict y = new MergeEngine.Conflict(k, a, a, a);
        assertEquals(x, y);
        assertEquals(x.hashCode(), y.hashCode());
    }

    // ---- null-ancestor branch ----

    @Test
    void null_ancestor_with_both_sides_compares_changes_not_fast_forward() {
        // When ancestor is null, the fast-forward path (lines 44-52) is skipped.
        // Both sides must be diffed against null and merged.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);

            List<TreeMutator.Mutation> ourEdits = new ArrayList<>();
            ourEdits.add(
                    new TreeMutator.Mutation(
                            key(pool, "a"), MemorySegment.ofArray("ours".getBytes())));
            Node ours = m.applyMutations(null, ourEdits.iterator());

            List<TreeMutator.Mutation> theirEdits = new ArrayList<>();
            theirEdits.add(
                    new TreeMutator.Mutation(
                            key(pool, "b"), MemorySegment.ofArray("theirs".getBytes())));
            Node theirs = m.applyMutations(null, theirEdits.iterator());

            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(null, ours, theirs);
            assertTrue(
                    r.conflicts().isEmpty(), "disjoint adds against null ancestor → clean merge");
            assertNotNull(
                    r.root(), "merged root must be non-null after combining two non-null sides");
        }
    }

    @Test
    void null_ancestor_with_same_key_add_yields_conflict_if_values_differ() {
        // Add/add against null ancestor with different values → conflict.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);

            Node ours =
                    m.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    key(pool, "k"),
                                                    MemorySegment.ofArray("ours-value".getBytes())))
                                    .iterator());
            Node theirs =
                    m.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    key(pool, "k"),
                                                    MemorySegment.ofArray(
                                                            "theirs-value".getBytes())))
                                    .iterator());

            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(null, ours, theirs);
            assertEquals(
                    1,
                    r.conflicts().size(),
                    "add/add with different values against null ancestor → conflict");
            MergeEngine.Conflict c = r.conflicts().get(0);
            assertNull(c.baseVal(), "null ancestor → conflict's baseVal is null (no prior value)");
        }
    }

    @Test
    void null_ancestor_with_same_key_add_same_value_no_conflict() {
        // Add/add with IDENTICAL values → not a conflict (isSameChange).
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);

            Node ours =
                    m.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    key(pool, "k"),
                                                    MemorySegment.ofArray("shared".getBytes())))
                                    .iterator());
            Node theirs =
                    m.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    key(pool, "k"),
                                                    MemorySegment.ofArray("shared".getBytes())))
                                    .iterator());

            MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
            MergeEngine.MergeResult r = engine.merge(null, ours, theirs);
            assertTrue(r.conflicts().isEmpty(), "add/add with IDENTICAL values must NOT conflict");
        }
    }
}
