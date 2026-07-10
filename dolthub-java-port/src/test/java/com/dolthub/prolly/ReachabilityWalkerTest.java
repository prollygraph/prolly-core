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
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link ReachabilityWalker}. This is GC's load-bearing class — a single
 * missed child hash would mark a live chunk as garbage and silently corrupt the store on the next
 * prune.
 *
 * <p>Building real trees via {@link TreeMutator} so the walker traverses actual
 * Flatbuffer-serialized internal/leaf nodes. Pure-unit tests on a mocked NodeStore would miss real
 * serialization bugs.
 */
class ReachabilityWalkerTest {

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    @Test
    void null_root_walks_to_empty_set() {
        try (InMemoryNodeStore store = new InMemoryNodeStore()) {
            ReachabilityWalker w = new ReachabilityWalker(store);
            w.walk(null);
            assertTrue(w.getReachableHashes().isEmpty());
        }
    }

    @Test
    void single_leaf_root_reachable() throws Exception {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator m = new TreeMutator(store, desc, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, "k1"), MemorySegment.ofArray("v1".getBytes())));
            Node root = m.applyMutations(null, edits.iterator());

            ReachabilityWalker w = new ReachabilityWalker(store);
            w.walk(HashUtils.hash(root.bytes()));
            assertEquals(1, w.getReachableHashes().size(), "single-leaf tree has exactly one node");
        }
    }

    @Test
    void multi_level_tree_walks_every_chunk() throws Exception {
        // Force at least one internal node by inserting enough entries.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator m = new TreeMutator(store, desc, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node root = m.applyMutations(null, edits.iterator());
            assertTrue(root.level() >= 1, "expected internal-node tree");

            // Snapshot the store's pre-walk size as ground truth.
            long totalChunks = store.size();

            ReachabilityWalker w = new ReachabilityWalker(store);
            w.walk(HashUtils.hash(root.bytes()));

            // Every chunk that was written for THIS tree should be reachable
            // from the root. Since the store only holds this tree, totalChunks
            // is the upper bound, and the walker must hit them all.
            assertEquals(
                    totalChunks,
                    w.getReachableHashes().size(),
                    "walker must reach every chunk that makes up the tree");
        }
    }

    @Test
    void missing_chunks_do_not_throw() throws Exception {
        // Walker should be tolerant of dangling references — never throw.
        try (InMemoryNodeStore store = new InMemoryNodeStore()) {
            byte[] fake = new byte[20];
            fake[0] = 0x42;
            ReachabilityWalker w = new ReachabilityWalker(store);
            assertDoesNotThrow(() -> w.walk(fake));
            // The hash itself is still marked reachable (recorded on push, not on read).
            assertEquals(1, w.getReachableHashes().size());
        }
    }

    @Test
    void cycle_safety_repeated_pushes_dedup() throws Exception {
        // Walker uses a Set — repeated walks of the same root must not
        // double-count nor loop forever.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator m = new TreeMutator(store, desc, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node root = m.applyMutations(null, edits.iterator());

            ReachabilityWalker w = new ReachabilityWalker(store);
            byte[] rh = HashUtils.hash(root.bytes());
            w.walk(rh);
            int firstSize = w.getReachableHashes().size();
            w.walk(rh); // walk again — should be idempotent
            assertEquals(
                    firstSize,
                    w.getReachableHashes().size(),
                    "repeated walks of the same root must not grow the set");
        }
    }

    @Test
    void hash_set_is_hex_encoded() throws Exception {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator m = new TreeMutator(store, desc, pool);
            Node root =
                    m.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    key(pool, "k"),
                                                    MemorySegment.ofArray("v".getBytes())))
                                    .iterator());

            ReachabilityWalker w = new ReachabilityWalker(store);
            w.walk(HashUtils.hash(root.bytes()));
            Set<String> got = w.getReachableHashes();
            for (String h : got) {
                assertEquals(40, h.length(), "20-byte hash → 40 hex chars");
                assertTrue(h.matches("[0-9a-f]{40}"), "hex set must be lowercase: " + h);
            }
        }
    }

    @Test
    void empty_store_with_nonexistent_root_records_hash_only() {
        try (InMemoryNodeStore store = new InMemoryNodeStore()) {
            byte[] phantom = new byte[20];
            for (int i = 0; i < 20; i++) phantom[i] = (byte) (i + 1);
            ReachabilityWalker w = new ReachabilityWalker(store);
            w.walk(phantom);
            assertEquals(1, w.getReachableHashes().size());
        }
    }

    @Test
    void two_independent_walks_share_reachable_set() throws Exception {
        // The walker accumulates across walk() calls (no reset). Verify the
        // union semantics that GC relies on: walk each branch head, accumulate.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator m = new TreeMutator(store, desc, pool);

            List<TreeMutator.Mutation> a = new ArrayList<>();
            for (int i = 0; i < 30; i++)
                a.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("a-%05d", i)),
                                MemorySegment.ofArray(("va-" + i).getBytes())));
            Node rootA = m.applyMutations(null, a.iterator());

            List<TreeMutator.Mutation> b = new ArrayList<>();
            for (int i = 0; i < 30; i++)
                b.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("b-%05d", i)),
                                MemorySegment.ofArray(("vb-" + i).getBytes())));
            Node rootB = m.applyMutations(null, b.iterator());

            ReachabilityWalker w = new ReachabilityWalker(store);
            w.walk(HashUtils.hash(rootA.bytes()));
            int afterA = w.getReachableHashes().size();
            w.walk(HashUtils.hash(rootB.bytes()));
            int afterAB = w.getReachableHashes().size();
            assertTrue(
                    afterAB > afterA,
                    "walking a second, disjoint root must extend the reachable set");
        }
    }
}
