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
package com.earasoft.prolly.sync;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link SyncEngine}. Pull semantics are load-bearing for clustering and
 * disaster recovery — a missed Merkle skip turns a small delta sync into a full-clone bandwidth
 * bomb, and a missing pull leaves dangling references.
 */
class SyncEngineUnitTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static Node buildTree(HeapBufferPool pool, InMemoryNodeStore store, int n) {
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

    // ---- null / no-op ----

    @Test
    void null_root_is_noop() {
        try (InMemoryNodeStore local = new InMemoryNodeStore();
                InMemoryNodeStore remote = new InMemoryNodeStore()) {
            SyncEngine engine = new SyncEngine(local, remote);
            assertDoesNotThrow(() -> engine.pull(null));
            assertEquals(0, local.size());
        }
    }

    // ---- happy path ----

    @Test
    void pull_copies_single_leaf_tree() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore local = new InMemoryNodeStore();
                InMemoryNodeStore remote = new InMemoryNodeStore()) {
            Node root = buildTree(pool, remote, 3);
            byte[] rootHash = HashUtils.hash(root.bytes());
            remote.write(root.bytes());

            new SyncEngine(local, remote).pull(rootHash);
            assertTrue(
                    local.read(rootHash).isPresent(),
                    "local store must hold the pulled root after sync");
        }
    }

    @Test
    void pull_copies_multi_level_tree() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore local = new InMemoryNodeStore();
                InMemoryNodeStore remote = new InMemoryNodeStore()) {
            Node root = buildTree(pool, remote, 2000);
            assertTrue(root.level() >= 1, "expected multi-level tree");
            byte[] rootHash = HashUtils.hash(root.bytes());
            remote.write(root.bytes());

            long remoteSize = remote.size();
            new SyncEngine(local, remote).pull(rootHash);
            assertEquals(
                    remoteSize,
                    local.size(),
                    "after full pull, local must hold every chunk that comprises the tree");
        }
    }

    // ---- Merkle skip property ----

    @Test
    void pull_skips_subtrees_already_present_locally() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore remote = new InMemoryNodeStore();
                InMemoryNodeStore local = new InMemoryNodeStore()) {
            // Build identical content in both stores → byte-identical chunks.
            Node remoteRoot = buildTree(pool, remote, 100);
            Node localRoot = buildTree(pool, local, 100);
            assertArrayEquals(remoteRoot.bytes(), localRoot.bytes());

            byte[] rootHash = HashUtils.hash(remoteRoot.bytes());
            remote.write(remoteRoot.bytes());
            local.write(localRoot.bytes());

            // Wrap remote so we can count read calls.
            AtomicInteger reads = new AtomicInteger();
            NodeStore countingRemote =
                    new NodeStore() {
                        @Override
                        public Optional<MemorySegment> read(byte[] h) {
                            reads.incrementAndGet();
                            return remote.read(h);
                        }

                        @Override
                        public byte[] write(MemorySegment d) {
                            return remote.write(d);
                        }

                        @Override
                        public byte[] write(byte[] d) {
                            return remote.write(d);
                        }
                    };

            new SyncEngine(local, countingRemote).pull(rootHash);
            assertEquals(
                    0,
                    reads.get(),
                    "if local already has the root, Merkle skip avoids ALL remote reads");
        }
    }

    // ---- error handling ----

    @Test
    void missing_remote_node_throws_with_hash_in_message() {
        try (InMemoryNodeStore local = new InMemoryNodeStore();
                InMemoryNodeStore remote = new InMemoryNodeStore()) {
            byte[] phantom = new byte[20];
            phantom[0] = 0x42;
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () -> new SyncEngine(local, remote).pull(phantom));
            assertTrue(
                    e.getMessage().toLowerCase().contains("missing"),
                    "error must describe the missing-node condition");
            assertTrue(
                    e.getMessage().contains("42"),
                    "error must include the hash in hex for forensics");
        }
    }

    @Test
    void missing_descendant_aborts_pull() {
        // Build a multi-level tree on remote, then strip one descendant.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore local = new InMemoryNodeStore();
                InMemoryNodeStore remote = new InMemoryNodeStore()) {
            Node root = buildTree(pool, remote, 2000);
            byte[] rootHash = HashUtils.hash(root.bytes());
            remote.write(root.bytes());

            // Pick a leaf-level child and remove from remote.
            byte[] missingChild = root.getValue(0);
            NodeStore broken =
                    new NodeStore() {
                        @Override
                        public Optional<MemorySegment> read(byte[] h) {
                            if (java.util.Arrays.equals(h, missingChild)) return Optional.empty();
                            return remote.read(h);
                        }

                        @Override
                        public byte[] write(MemorySegment d) {
                            return remote.write(d);
                        }

                        @Override
                        public byte[] write(byte[] d) {
                            return remote.write(d);
                        }
                    };

            assertThrows(
                    RuntimeException.class,
                    () -> new SyncEngine(local, broken).pull(rootHash),
                    "missing descendant in the DAG must propagate as RuntimeException");
        }
    }

    // ---- bottom-up integrity property ----

    @Test
    void parent_written_locally_only_after_children() {
        // Property test: if a partial failure happens mid-pull, the local
        // store must not hold a parent whose children are absent. We verify
        // this indirectly: track local writes in order, ensure leaves precede
        // their parent (for a balanced tree, the root is written last).
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore remote = new InMemoryNodeStore()) {
            Node root = buildTree(pool, remote, 2000);
            byte[] rootHash = HashUtils.hash(root.bytes());
            remote.write(root.bytes());

            List<byte[]> writeOrder = new ArrayList<>();
            InMemoryNodeStore localInner = new InMemoryNodeStore();
            NodeStore local =
                    new NodeStore() {
                        @Override
                        public Optional<MemorySegment> read(byte[] h) {
                            return localInner.read(h);
                        }

                        @Override
                        public byte[] write(byte[] d) {
                            byte[] hash = localInner.write(d);
                            writeOrder.add(hash);
                            return hash;
                        }

                        @Override
                        public byte[] write(MemorySegment d) {
                            byte[] hash = localInner.write(d);
                            writeOrder.add(hash);
                            return hash;
                        }
                    };

            new SyncEngine(local, remote).pull(rootHash);

            // Root must be the last hash written — children precede parents.
            byte[] lastWritten = writeOrder.get(writeOrder.size() - 1);
            assertArrayEquals(
                    rootHash,
                    lastWritten,
                    "bottom-up integrity: root is written AFTER all its children");
        }
    }
}
