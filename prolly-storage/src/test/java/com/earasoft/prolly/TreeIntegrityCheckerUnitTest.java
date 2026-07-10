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
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.TreeMutator;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * JUnit coverage for {@link TreeIntegrityChecker} (existing main-method test covers the happy path;
 * this file pins the failure modes — missing nodes, planted hash mismatches — that the recovery
 * story depends on).
 */
class TreeIntegrityCheckerUnitTest {

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
    void valid_single_leaf_tree_passes() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 3);
            byte[] rootHash = HashUtils.hash(root.bytes());
            store.write(root.bytes());
            new TreeIntegrityChecker(store).verify(rootHash);
            // no exception → pass
        }
    }

    @Test
    void valid_multi_level_tree_passes() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            assertTrue(root.level() >= 1, "expected multi-level tree");
            byte[] rootHash = HashUtils.hash(root.bytes());
            assertDoesNotThrow(() -> new TreeIntegrityChecker(store).verify(rootHash));
        }
    }

    @Test
    void missing_root_throws() {
        try (InMemoryNodeStore store = new InMemoryNodeStore()) {
            byte[] phantom = new byte[20];
            phantom[0] = 0x42;
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () -> new TreeIntegrityChecker(store).verify(phantom));
            assertTrue(e.getMessage().contains("Missing node"));
        }
    }

    @Test
    void corrupted_root_throws_hash_mismatch() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 3);
            // Plant the wrong bytes under root's hash via a corrupting wrapper.
            byte[] rootHash = HashUtils.hash(root.bytes());

            // Wrap the store so reading rootHash returns tampered bytes.
            NodeStore corrupt =
                    new NodeStore() {
                        @Override
                        public Optional<MemorySegment> read(byte[] h) {
                            if (java.util.Arrays.equals(h, rootHash)) {
                                return Optional.of(
                                        MemorySegment.ofArray("tampered payload here".getBytes()));
                            }
                            return store.read(h);
                        }

                        @Override
                        public byte[] write(MemorySegment d) {
                            return store.write(d);
                        }

                        @Override
                        public byte[] write(byte[] d) {
                            return store.write(d);
                        }
                    };
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () -> new TreeIntegrityChecker(corrupt).verify(rootHash));
            assertTrue(e.getMessage().contains("Hash mismatch"));
        }
    }

    @Test
    void missing_child_throws() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = tree(pool, store, 2000);
            byte[] rootHash = HashUtils.hash(root.bytes());

            // Strip one child reference's underlying bytes by serving null for
            // a specific child hash. Pick the first child.
            byte[] childHash = root.getValue(0);
            NodeStore stripped =
                    new NodeStore() {
                        @Override
                        public Optional<MemorySegment> read(byte[] h) {
                            if (java.util.Arrays.equals(h, childHash)) return Optional.empty();
                            return store.read(h);
                        }

                        @Override
                        public byte[] write(MemorySegment d) {
                            return store.write(d);
                        }

                        @Override
                        public byte[] write(byte[] d) {
                            return store.write(d);
                        }
                    };
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () -> new TreeIntegrityChecker(stripped).verify(rootHash));
            assertTrue(
                    e.getMessage().contains("Missing node"),
                    "must propagate the missing-child failure up");
        }
    }
}
