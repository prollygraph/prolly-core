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
 * Multi-level convergence: an incremental edit applied to an existing tree must produce the
 * <em>byte-identical root</em> that a from-scratch batch build of the same final key set would.
 *
 * <p>This is the load-bearing guarantee behind {@code Chunker.advanceTo}'s "re-emit every existing
 * entry through the splitter" comment — structural fast-forwarding was removed precisely so the
 * splitter sees the same byte sequence on both paths and the chunk boundaries (hence the root hash)
 * converge. {@code TreeMutatorEdgeTest.incremental_and_batch_paths_converge} checks this on a small
 * tree; this file pins it at <em>multi-level</em> scale, where the rightmost-spine force-flush at
 * every level is in play.
 */
class TreeMutatorConvergenceTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static TreeMutator.Mutation put(HeapBufferPool pool, int i, String v) {
        return new TreeMutator.Mutation(
                key(pool, String.format("k-%05d", i)),
                v == null ? null : MemorySegment.ofArray(v.getBytes()));
    }

    /** Batch-build a tree from a value function over keys [0, n). */
    private static Node batch(
            HeapBufferPool pool,
            InMemoryNodeStore store,
            int n,
            java.util.function.IntFunction<String> valueOf) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String v = valueOf.apply(i);
            if (v != null) edits.add(put(pool, i, v));
        }
        return m.applyMutations(null, edits.iterator());
    }

    private static byte[] rootHash(Node n) {
        return HashUtils.hash(n.bytes());
    }

    @Test
    void incremental_modify_converges_with_batch_build_on_multi_level_tree() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base = batch(pool, store, 2000, i -> "v-" + i);
            assertTrue(base.level() >= 1, "base must be multi-level");

            // Path A: incrementally modify k-01234 on the existing tree.
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node incremental =
                    m.applyMutations(base, List.of(put(pool, 1234, "CHANGED")).iterator());

            // Path B: batch-build the same final key set from scratch.
            Node batched = batch(pool, store, 2000, i -> i == 1234 ? "CHANGED" : "v-" + i);

            assertArrayEquals(
                    rootHash(batched),
                    rootHash(incremental),
                    "incremental modify must converge to the batch-built root hash");
        }
    }

    @Test
    void incremental_delete_converges_with_batch_build() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base = batch(pool, store, 2000, i -> "v-" + i);

            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node incremental =
                    m.applyMutations(
                            base, List.of(put(pool, 1500, null)).iterator()); // delete k-01500

            Node batched = batch(pool, store, 2000, i -> i == 1500 ? null : "v-" + i);

            assertArrayEquals(
                    rootHash(batched),
                    rootHash(incremental),
                    "incremental delete must converge to the batch-built root hash");
        }
    }

    @Test
    void incremental_interior_insert_converges_with_batch_build() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base = batch(pool, store, 2000, i -> "v-" + i);

            // Insert a key that sorts inside the existing range.
            MemorySegment newKey = key(pool, "k-00777x");
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node incremental =
                    m.applyMutations(
                            base,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    newKey,
                                                    MemorySegment.ofArray("inserted".getBytes())))
                                    .iterator());

            // Batch build the union, in sorted order.
            TreeMutator mb = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> all = new ArrayList<>();
            for (int i = 0; i < 778; i++) all.add(put(pool, i, "v-" + i));
            all.add(new TreeMutator.Mutation(newKey, MemorySegment.ofArray("inserted".getBytes())));
            for (int i = 778; i < 2000; i++) all.add(put(pool, i, "v-" + i));
            Node batched = mb.applyMutations(null, all.iterator());

            assertArrayEquals(
                    rootHash(batched),
                    rootHash(incremental),
                    "incremental interior insert must converge to the batch-built root");
        }
    }

    @Test
    void several_scattered_incremental_modifies_converge() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base = batch(pool, store, 2000, i -> "v-" + i);

            int[] touched = {3, 500, 999, 1000, 1001, 1999};
            java.util.Set<Integer> set = new java.util.HashSet<>();
            for (int t : touched) set.add(t);

            // Incremental: apply all six modifies in one sorted batch.
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int t : touched) edits.add(put(pool, t, "mod-" + t));
            Node incremental = m.applyMutations(base, edits.iterator());

            Node batched = batch(pool, store, 2000, i -> set.contains(i) ? "mod-" + i : "v-" + i);

            assertArrayEquals(
                    rootHash(batched),
                    rootHash(incremental),
                    "a scattered multi-key incremental modify must still converge");
        }
    }

    @Test
    void no_op_edit_round_trips_to_the_same_root() {
        // Re-applying a key's existing value must not change the tree.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node base = batch(pool, store, 2000, i -> "v-" + i);
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node rewritten =
                    m.applyMutations(
                            base, List.of(put(pool, 1234, "v-1234")).iterator()); // same value
            assertArrayEquals(
                    rootHash(base),
                    rootHash(rewritten),
                    "writing a key's existing value back must yield an identical root");
        }
    }
}
