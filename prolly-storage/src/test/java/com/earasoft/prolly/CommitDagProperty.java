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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.dolthub.prolly.Commit;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.gen.RdfGenerators;
import com.earasoft.prolly.gen.RdfGenerators.Edit;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * Phase 1 Step 6 of the upstream test-strategy plan — the R-1 versioning-algebra property. A
 * generated sequence of commit batches is replayed against a real {@link Database} on a temp
 * RocksDB, and three invariants are checked against a {@code TreeMap}-style oracle ({@link
 * RdfGenerators#applyOracle}):
 *
 * <ol>
 *   <li><b>Materialization == oracle</b> — after the sequence, the branch head's full key→value set
 *       equals replaying the same put/delete ops in memory.
 *   <li><b>Parents chain</b> — walking parent links from the head reaches the root in exactly one
 *       step per commit, matching the heads we recorded.
 *   <li><b>Reopen preserves heads</b> — closing the store and reopening a fresh {@code Database} on
 *       the same dir restores the head and the data.
 * </ol>
 *
 * <p>Single-column {@code String} keys; values are raw UTF-8 bytes. This drives the engine through
 * its real commit/branch API ({@code commit} OCC, {@code getHead}, the manifest-backed refs), not a
 * mock.
 */
class CommitDagProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private final List<Path> tempDirs = new ArrayList<>();

    @Provide
    Arbitrary<List<List<Edit>>> batches() {
        return RdfGenerators.editBatches();
    }

    @AfterTry
    void cleanup() {
        for (Path dir : tempDirs) deleteRecursively(dir);
        tempDirs.clear();
    }

    @Property(tries = 60)
    void commitSequenceMaterializesOracleAndSurvivesReopen(
            @ForAll @From("batches") List<List<Edit>> batches) throws Exception {
        Path dir = Files.createTempDirectory("rdf-r1-");
        tempDirs.add(dir);
        Map<String, String> oracle = RdfGenerators.applyOracle(batches);
        List<byte[]> recordedHeads = new ArrayList<>();
        byte[] lastHead;

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "r1-repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            TupleBuilder tb = new TupleBuilder(pool);

            byte[] parent = null;
            for (int c = 0; c < batches.size(); c++) {
                MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
                for (Edit e : batches.get(c)) {
                    tb.putField(0, e.key().getBytes());
                    MemorySegment key = tb.build().segment();
                    if (e.delete()) mm.delete(key);
                    else mm.put(key, MemorySegment.ofArray(e.value().getBytes()));
                }
                boolean ok = db.commit("main", mm.flush(), parent, "author", "c" + c);
                assertEquals(true, ok, "single-writer commit must succeed (no contention)");
                parent = db.getHeadHash("main").orElseThrow();
                recordedHeads.add(parent);
            }
            lastHead = parent;

            // (1) Materialization == oracle.
            assertEquals(oracle, scan(db), "branch head must materialize the oracle map");

            // (2) Parents chain: head -> ... -> root, one step per commit, and
            // the walked hashes (newest-first) equal the heads we recorded.
            List<byte[]> walked = walkHeads(db, store);
            assertEquals(
                    recordedHeads.size(),
                    walked.size(),
                    "one commit per batch on the parent chain");
            for (int i = 0; i < walked.size(); i++) {
                assertArrayEquals(
                        recordedHeads.get(recordedHeads.size() - 1 - i),
                        walked.get(i),
                        "parent chain must match the recorded commit order");
            }
        }

        // (3) Reopen on the same dir preserves the head + the data.
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "r1-repo", DESC, pool);
            byte[] headAfterReopen = db.getHeadHash("main").orElseThrow();
            assertArrayEquals(lastHead, headAfterReopen, "reopen must restore the branch head");
            assertEquals(oracle, scan(db), "reopen must restore the materialized data");
        }
    }

    /** Materialize a branch's key→value set by scanning the head's StaticMap. */
    private static Map<String, String> scan(Database db) {
        Map<String, String> out = new LinkedHashMap<>();
        StaticMap map = db.getBranch("main");
        MapIterator it = map.iter();
        while (it.next()) {
            String k = new String(new Tuple(it.key()).getField(0));
            String v = new String(it.value().toArray(ValueLayout.JAVA_BYTE));
            out.put(k, v);
        }
        return out;
    }

    /** Walk parent links from the head; newest-first list of commit hashes. */
    private static List<byte[]> walkHeads(Database db, RocksNodeStore store) {
        List<byte[]> out = new ArrayList<>();
        Commit current = db.getHead("main");
        byte[] currentHash = db.getHeadHash("main").orElse(null);
        while (current != null) {
            assertNotNull(currentHash);
            out.add(currentHash);
            if (current.getParents().isEmpty()) break;
            byte[] pHash = current.getParents().get(0);
            current =
                    store.read(pHash)
                            .map(seg -> Commit.deserialize(seg.toArray(ValueLayout.JAVA_BYTE)))
                            .orElse(null);
            currentHash = pHash;
        }
        return out;
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
        } catch (IOException ignored) {
        }
    }
}
