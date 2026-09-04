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
package com.earasoft.prolly;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.ReachabilityWalker;
import com.dolthub.prolly.TreeMutator;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * JUnit unit-level coverage for {@link ParallelReachabilityWalker}. Existing {@code
 * ParallelReachabilityWalkerTest} (main-method) checks specific oracles; this file pins boundary
 * contracts: null-safe, idempotent re-walks, hex set format, and parity with the serial {@link
 * ReachabilityWalker} on the same trees.
 */
class ParallelReachabilityWalkerUnitTest {

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

    @Test
    void null_root_yields_empty_set() {
        try (InMemoryNodeStore store = new InMemoryNodeStore()) {
            ParallelReachabilityWalker w = new ParallelReachabilityWalker(store);
            assertDoesNotThrow(() -> w.walk(null));
            assertTrue(w.getReachableHashes().isEmpty());
        }
    }

    @Test
    void single_leaf_yields_one_hash() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 3);
            byte[] rootHash = HashUtils.hash(root.bytes());
            store.write(root.bytes());

            ParallelReachabilityWalker w = new ParallelReachabilityWalker(store);
            w.walk(rootHash);
            assertEquals(1, w.getReachableHashes().size(), "single-leaf tree has exactly one node");
        }
    }

    @Test
    void multi_level_tree_reaches_every_chunk() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            assertTrue(root.level() >= 1);
            byte[] rootHash = HashUtils.hash(root.bytes());
            store.write(root.bytes());

            long totalChunks = store.size();
            ParallelReachabilityWalker w = new ParallelReachabilityWalker(store);
            w.walk(rootHash);
            assertEquals(
                    totalChunks,
                    w.getReachableHashes().size(),
                    "parallel walker must reach EVERY chunk that the tree references");
        }
    }

    @Test
    void parity_with_serial_walker() {
        // Property: serial and parallel walkers must agree on the reachable set.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 1500);
            byte[] rootHash = HashUtils.hash(root.bytes());
            store.write(root.bytes());

            ReachabilityWalker serial = new ReachabilityWalker(store);
            serial.walk(rootHash);

            ParallelReachabilityWalker parallel = new ParallelReachabilityWalker(store);
            parallel.walk(rootHash);

            assertEquals(
                    serial.getReachableHashes().toHexSet(),
                    parallel.getReachableHashes().toHexSet(),
                    "serial and parallel walks must produce the SAME reachable set");
        }
    }

    @Test
    void idempotent_walk_does_not_double_count() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 50);
            byte[] rootHash = HashUtils.hash(root.bytes());
            store.write(root.bytes());

            ParallelReachabilityWalker w = new ParallelReachabilityWalker(store);
            w.walk(rootHash);
            int firstSize = w.getReachableHashes().size();
            w.walk(rootHash);
            assertEquals(
                    firstSize,
                    w.getReachableHashes().size(),
                    "re-walking the same root must not grow the reachable set");
        }
    }

    @Test
    void missing_chunks_do_not_throw() {
        // Walker must be tolerant of dangling references (GC pre-conditions
        // sometimes hold mid-flight).
        try (InMemoryNodeStore store = new InMemoryNodeStore()) {
            byte[] phantom = new byte[20];
            phantom[0] = (byte) 0x99;
            ParallelReachabilityWalker w = new ParallelReachabilityWalker(store);
            assertDoesNotThrow(() -> w.walk(phantom));
            assertEquals(
                    1,
                    w.getReachableHashes().size(),
                    "phantom hash is recorded even when chunk is missing");
        }
    }

    @Test
    void hex_format_lowercase_40_chars() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 3);
            byte[] rootHash = HashUtils.hash(root.bytes());
            store.write(root.bytes());
            ParallelReachabilityWalker w = new ParallelReachabilityWalker(store);
            w.walk(rootHash);
            for (String h : w.getReachableHashes().toHexSet()) {
                assertEquals(40, h.length());
                assertTrue(h.matches("[0-9a-f]{40}"), "hex must be lowercase: " + h);
            }
        }
    }

    @Test
    void two_disjoint_walks_union_reachable() {
        // Accumulator behavior: walking two different roots stacks their
        // reachable sets (GC's union semantics).
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node a = tree(pool, store, 30);
            // Build a separate disjoint subtree by reusing the store with diff keys.
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("z-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node b = m.applyMutations(null, edits.iterator());

            byte[] hA = HashUtils.hash(a.bytes());
            byte[] hB = HashUtils.hash(b.bytes());
            store.write(a.bytes());
            store.write(b.bytes());

            ParallelReachabilityWalker w = new ParallelReachabilityWalker(store);
            w.walk(hA);
            int afterA = w.getReachableHashes().size();
            w.walk(hB);
            assertTrue(
                    w.getReachableHashes().size() > afterA,
                    "walking a second disjoint root must add to the reachable set");
        }
    }
}
