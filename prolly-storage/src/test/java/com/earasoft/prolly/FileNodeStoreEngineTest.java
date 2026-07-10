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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryManifest;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.Manifest;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.FileNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The prolly engine over a {@link FileNodeStore}. A {@code NodeStore} is a {@code NodeStore}, so
 * the cardinal invariants (I-1..I-8) must hold regardless of backend: the same {@code Database}
 * operations must produce the <b>same data tree root</b> over the filesystem store as over the
 * in-memory reference (content-addressing makes the whole engine backend-independent), and the
 * engine must read every committed row back through the filesystem store's chunks. We compare the
 * tree root, not the commit head: the differential test showed the prolly-storage {@code Database}
 * commit <em>id</em> differs run-to-run for identical data/author/message, i.e. it includes the
 * wall-clock timestamp — ADR-0071's timestamp-free id is the higher RDF4J {@code CommitLog} layer,
 * a distinct scheme. The tree root has no such time-varying component.
 *
 * <p>Note the split between planes: {@code FileNodeStore} is only the <b>chunk store</b> (data
 * plane); the engine's branch/tag refs are a separate <b>control plane</b> — a {@link Manifest} —
 * which the single-arg {@code Database} ctor can only auto-derive from a RocksDB-backed store. So a
 * non-RocksDB {@code NodeStore} must inject a {@code Manifest} (the full ctor exists exactly for
 * this); this test uses the shared {@link InMemoryManifest}. A real filesystem-backed deployment
 * would pair {@code FileNodeStore} with a file-based {@code Manifest} — a separate concern, not
 * this plan.
 */
final class FileNodeStoreEngineTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final int ROWS = 300; // enough to force a multi-level tree (internal nodes).

    private static MemorySegment key(DirectBufferPool pool, int i) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, ("key-" + i).getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    private static byte[] value(int i) {
        return ("val-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildAndCommit(NodeStore store) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            Database db = new Database(store, new InMemoryManifest(), "engine-repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
            for (int i = 0; i < ROWS; i++) {
                mm.put(key(pool, i), MemorySegment.ofArray(value(i)));
            }
            assertTrue(
                    db.commit(
                            "main",
                            mm.flush(),
                            db.getHeadHash("main").orElse(null),
                            "author",
                            "add rows"),
                    "the commit must succeed over " + store.getClass().getSimpleName());
            // Return the DATA tree root, not the commit head: the prolly-storage Database commit id
            // includes the wall-clock timestamp (ADR-0071's timestamp-free id is the RDF4J
            // CommitLog
            // layer), so two runs get different commit ids. The tree root is a pure content-address
            // of the data — timestamp-free and backend-independent, the right axis here.
            return db.getHead("main").getRootValueHash();
        }
    }

    @Test
    void sameOperationsProduceTheSameDataTreeRootOverFileAndInMemory(@TempDir Path root) {
        byte[] fileRoot = buildAndCommit(new FileNodeStore(root.resolve("file")));
        byte[] memRoot = buildAndCommit(new InMemoryNodeStore());
        assertArrayEquals(
                memRoot,
                fileRoot,
                "the engine is backend-independent: identical ops -> identical data tree root (I-1..I-8)");
    }

    @Test
    void theEngineReadsBackEveryRowOverFileNodeStore(@TempDir Path root) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            FileNodeStore store = new FileNodeStore(root);
            Database db = new Database(store, new InMemoryManifest(), "engine-repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
            for (int i = 0; i < ROWS; i++) {
                mm.put(key(pool, i), MemorySegment.ofArray(value(i)));
            }
            assertTrue(
                    db.commit(
                            "main",
                            mm.flush(),
                            db.getHeadHash("main").orElse(null),
                            "author",
                            "add rows"));

            StaticMap branch = db.getBranch("main");
            for (int i = 0; i < ROWS; i++) {
                Optional<MemorySegment> read = branch.get(key(pool, i));
                assertArrayEquals(
                        value(i),
                        read.orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                        "row " + i + " must read back through the filesystem store");
            }
        }
    }
}
