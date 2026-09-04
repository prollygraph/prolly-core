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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Commit;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.InMemoryManifest;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.ReachabilityWalker;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TreeMutator;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.FileNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@link GarbageCollector} reachability contract (invariant R-4) enforced over a {@link
 * FileNodeStore}, the filesystem analogue of {@link GcRootReachabilityTest} (which pins it over
 * {@code RocksNodeStore}). The production {@code GarbageCollector} is RocksDB-coupled (it sweeps
 * via a {@code RocksIterator} + {@code db().delete}), so this test drives the <em>same</em>
 * mark→sweep algorithm using {@code FileNodeStore}'s own sweep surface — {@link
 * FileNodeStore#hashes()} to enumerate and {@link FileNodeStore#delete(byte[])} to unlink — proving
 * that surface supports the contract: a commit-reachable data tree <b>survives</b>, while an
 * <b>out-of-band root</b> (a real tree written to the store but referenced by no commit) and a
 * <b>stray loose chunk</b> are <b>swept</b>. It also asserts the lazy fan-out prune leaves no empty
 * {@code <xx>/} directory behind.
 *
 * <p>Marking reuses {@link ReachabilityWalker} (itself {@code NodeStore}-generic); only the sweep
 * is store-specific, which is exactly the code Step 8 added.
 */
final class FileNodeStoreGcReachabilityTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final int ROWS = 300; // multi-level tree -> interior + leaf chunks to preserve.

    @Test
    void gcSweepsUnreachableFilesAndKeepsCommitReachableOnes(@TempDir Path root) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            FileNodeStore store = new FileNodeStore(root);
            Database db = new Database(store, new InMemoryManifest(), "gc-repo", DESC, pool);
            db.createBranch("main", "EMPTY");

            // Commit-reachable data — a multi-level tree that must survive GC in full.
            MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
            for (int i = 0; i < ROWS; i++) {
                mm.put(key(pool, "k" + i), val("v" + i));
            }
            assertTrue(
                    db.commit("main", mm, db.getHeadHash("main").orElse(null), "t", "c0"),
                    "the commit must succeed");

            // An out-of-band root: a real multi-node tree written to the store, referenced by NO
            // commit (the shape of an aux index root GC's commit-DAG walk never marks).
            byte[] outOfBand = writeOutOfBandTree(store, pool);
            // And a single stray loose chunk — the simplest unreachable object.
            byte[] stray = store.write("orphan chunk".getBytes(StandardCharsets.UTF_8));
            assertTrue(store.read(outOfBand).isPresent(), "out-of-band root exists before GC");
            assertTrue(store.read(stray).isPresent(), "stray chunk exists before GC");

            // MARK: reachable = commit chunks + every chunk of each commit's data tree.
            Set<String> reachable = mark(db, store);

            // SWEEP: over FileNodeStore's own enumeration, delete every chunk not marked.
            int swept = 0;
            for (byte[] h : store.hashes()) {
                if (!reachable.contains(HashUtils.toHex(h))) {
                    assertTrue(store.delete(h), "sweep must remove an unreachable chunk file");
                    swept++;
                }
            }
            assertTrue(
                    swept >= 2,
                    "at least the out-of-band tree + stray must be swept; was " + swept);

            // Safety: every commit-reachable row still reads back through the store.
            StaticMap branch = db.getBranch("main");
            for (int i = 0; i < ROWS; i++) {
                Optional<MemorySegment> got = branch.get(key(pool, "k" + i));
                assertArrayEquals(
                        val("v" + i).toArray(ValueLayout.JAVA_BYTE),
                        got.orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                        "reachable row k" + i + " must survive the sweep");
            }
            // Liveness: the unreachable objects are gone.
            assertFalse(store.read(outOfBand).isPresent(), "out-of-band root must be swept");
            assertFalse(store.read(stray).isPresent(), "stray chunk must be swept");

            // Lazy prune: a fully-swept fan-out directory is removed — none left empty.
            assertNoEmptyDirBelow(root);
        }
    }

    /**
     * GC's mark phase over any {@link com.dolthub.prolly.NodeStore}: commit DAG + each data tree.
     */
    private static Set<String> mark(Database db, FileNodeStore store) {
        ReachabilityWalker walker = new ReachabilityWalker(store);
        Set<String> reachable = new HashSet<>();
        for (String branch : db.listBranches()) {
            Optional<byte[]> head = db.getHeadHash(branch);
            if (head.isEmpty()) {
                continue;
            }
            reachable.add(HashUtils.toHex(head.get())); // the commit chunk itself is reachable.
            Commit commit = db.getHead(branch);
            if (commit != null && commit.getRootValueHash() != null) {
                walker.walk(commit.getRootValueHash());
            }
        }
        reachable.addAll(walker.getReachableHashes().toHexSet());
        return reachable;
    }

    private static byte[] writeOutOfBandTree(FileNodeStore store, DirectBufferPool pool) {
        List<TreeMutator.Mutation> muts = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            muts.add(
                    new TreeMutator.Mutation(
                            key(pool, String.format("aux%05d", i)),
                            MemorySegment.ofArray(("a" + i).getBytes(StandardCharsets.UTF_8))));
        }
        Node treeRoot = new TreeMutator(store, DESC, pool).applyMutations(null, muts.iterator());
        return store.write(treeRoot.segment()); // written, referenced by no commit.
    }

    private static void assertNoEmptyDirBelow(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> emptyDirs =
                    walk.filter(Files::isDirectory)
                            .filter(d -> !d.equals(root))
                            .filter(FileNodeStoreGcReachabilityTest::isEmptyDir)
                            .toList();
            assertTrue(
                    emptyDirs.isEmpty(),
                    "lazy prune must leave no empty fan-out dir: " + emptyDirs);
        }
    }

    private static boolean isEmptyDir(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static MemorySegment key(DirectBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    private static MemorySegment val(String v) {
        return MemorySegment.ofArray(v.getBytes(StandardCharsets.UTF_8));
    }
}
