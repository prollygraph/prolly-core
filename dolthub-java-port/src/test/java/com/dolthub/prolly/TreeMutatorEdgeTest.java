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
 * Edge-case coverage for {@link TreeMutator}. Builds on top of {@code TreeMutationAdvancedTest}
 * (correctness on large workloads) by pinning the boundary contracts: sorted-stream guard,
 * duplicate-key "last write wins", null = delete, empty edit stream.
 */
class TreeMutatorEdgeTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }

    private static Optional<MemorySegment> get(
            InMemoryNodeStore store, Node root, HeapBufferPool pool, String k) {
        if (root == null) return Optional.empty();
        return new StaticMap(store, root, STRING_DESC).get(key(pool, k));
    }

    // ---- sorted-stream guard ----

    @Test
    void unsorted_edit_stream_throws() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits =
                    List.of(
                            new TreeMutator.Mutation(
                                    key(pool, "b"), MemorySegment.ofArray("1".getBytes())),
                            new TreeMutator.Mutation(
                                    key(pool, "a"), MemorySegment.ofArray("2".getBytes())));
            IllegalArgumentException e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> m.applyMutations(null, edits.iterator()));
            assertTrue(
                    e.getMessage().contains("sorted"), "error must mention the sorting contract");
        }
    }

    // ---- duplicate-key resolution ----

    @Test
    void duplicate_keys_take_last_value() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits =
                    List.of(
                            new TreeMutator.Mutation(
                                    key(pool, "k"), MemorySegment.ofArray("first".getBytes())),
                            new TreeMutator.Mutation(
                                    key(pool, "k"), MemorySegment.ofArray("second".getBytes())),
                            new TreeMutator.Mutation(
                                    key(pool, "k"), MemorySegment.ofArray("third".getBytes())));
            Node root = m.applyMutations(null, edits.iterator());
            assertEquals(
                    "third",
                    str(get(store, root, pool, "k").orElseThrow()),
                    "last write wins for duplicate keys");
        }
    }

    @Test
    void duplicate_key_with_final_delete_removes_entry() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits =
                    List.of(
                            new TreeMutator.Mutation(
                                    key(pool, "x"), MemorySegment.ofArray("first".getBytes())),
                            new TreeMutator.Mutation(key(pool, "x"), null));
            Node root = m.applyMutations(null, edits.iterator());
            assertFalse(
                    get(store, root, pool, "x").isPresent(),
                    "put-then-delete in same batch → key absent");
        }
    }

    // ---- empty / null cases ----

    @Test
    void empty_edit_stream_returns_null() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            assertNull(
                    m.applyMutations(null, List.<TreeMutator.Mutation>of().iterator()),
                    "no edits + no root → no tree");
        }
    }

    @Test
    void delete_of_nonexistent_key_is_noop() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node base =
                    m.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    key(pool, "a"),
                                                    MemorySegment.ofArray("1".getBytes())))
                                    .iterator());

            Node updated =
                    m.applyMutations(
                            base,
                            List.of(new TreeMutator.Mutation(key(pool, "z"), null)).iterator());

            assertEquals(
                    "1",
                    str(get(store, updated, pool, "a").orElseThrow()),
                    "deleting a missing key must not affect other entries");
            assertFalse(get(store, updated, pool, "z").isPresent());
        }
    }

    // ---- mixed put/delete batches ----

    @Test
    void mixed_put_delete_batch_applies_in_sorted_order() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node base =
                    m.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    key(pool, "a"),
                                                    MemorySegment.ofArray("A".getBytes())),
                                            new TreeMutator.Mutation(
                                                    key(pool, "b"),
                                                    MemorySegment.ofArray("B".getBytes())),
                                            new TreeMutator.Mutation(
                                                    key(pool, "c"),
                                                    MemorySegment.ofArray("C".getBytes())))
                                    .iterator());

            List<TreeMutator.Mutation> mixed = new ArrayList<>();
            mixed.add(
                    new TreeMutator.Mutation(
                            key(pool, "a"), MemorySegment.ofArray("A2".getBytes())));
            mixed.add(new TreeMutator.Mutation(key(pool, "b"), null)); // delete b
            mixed.add(
                    new TreeMutator.Mutation(
                            key(pool, "d"), MemorySegment.ofArray("D".getBytes())));
            Node updated = m.applyMutations(base, mixed.iterator());

            assertEquals("A2", str(get(store, updated, pool, "a").orElseThrow()));
            assertFalse(get(store, updated, pool, "b").isPresent());
            assertEquals("C", str(get(store, updated, pool, "c").orElseThrow()));
            assertEquals("D", str(get(store, updated, pool, "d").orElseThrow()));
        }
    }

    // ---- determinism ----

    @Test
    void same_edits_against_same_base_yield_identical_root_hash() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore s1 = new InMemoryNodeStore();
                InMemoryNodeStore s2 = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node r1 =
                    new TreeMutator(s1, STRING_DESC, pool)
                            .applyMutations(null, new ArrayList<>(edits).iterator());
            Node r2 =
                    new TreeMutator(s2, STRING_DESC, pool)
                            .applyMutations(null, new ArrayList<>(edits).iterator());
            assertArrayEquals(
                    HashUtils.hash(r1.bytes()),
                    HashUtils.hash(r2.bytes()),
                    "deterministic root-hash for identical input streams");
        }
    }

    @Test
    void incremental_and_batch_paths_converge() {
        // Property: applying N edits one at a time vs. as a single batch
        // must produce the same root hash. This is the structural-sharing
        // property; any divergence indicates a chunker/splitter bug.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore s1 = new InMemoryNodeStore();
                InMemoryNodeStore s2 = new InMemoryNodeStore()) {
            TreeMutator m1 = new TreeMutator(s1, STRING_DESC, pool);
            TreeMutator m2 = new TreeMutator(s2, STRING_DESC, pool);

            // Batch path.
            List<TreeMutator.Mutation> batch = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                batch.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node batchRoot = m1.applyMutations(null, batch.iterator());

            // Incremental path.
            Node incRoot = null;
            for (int i = 0; i < 200; i++) {
                incRoot =
                        m2.applyMutations(
                                incRoot,
                                List.of(
                                                new TreeMutator.Mutation(
                                                        key(pool, String.format("k-%05d", i)),
                                                        MemorySegment.ofArray(
                                                                ("v-" + i).getBytes())))
                                        .iterator());
            }
            assertArrayEquals(
                    HashUtils.hash(batchRoot.bytes()),
                    HashUtils.hash(incRoot.bytes()),
                    "batch and incremental builds must converge to the same root hash");
        }
    }

    @Test
    void applying_no_edits_to_existing_tree_returns_equivalent_tree() {
        // Re-applying zero edits should produce a tree with the same content.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node base =
                    m.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    key(pool, "k"),
                                                    MemorySegment.ofArray("v".getBytes())))
                                    .iterator());

            Node noEdit = m.applyMutations(base, List.<TreeMutator.Mutation>of().iterator());
            assertNotNull(noEdit);
            assertEquals("v", str(get(store, noEdit, pool, "k").orElseThrow()));
        }
    }
}
