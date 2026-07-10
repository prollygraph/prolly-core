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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.TreeMutator;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.rocksdb.RocksDB;

/**
 * I-4 GC safety + liveness as Deterministic Simulation Testing (plans/core-engine-test-strategy.md
 * Step 26). Generalizes the fixed-scenario {@code GCReachabilitySafetyTest} to a {@link
 * SplittableRandom}-seeded schedule of {commit to a random branch, plant an orphan tree, run GC}
 * across multiple branches, checked against a per-branch {@link TreeMap} oracle.
 *
 * <p>After every {@code GarbageCollector.collect()}:
 *
 * <ul>
 *   <li><b>Safety (the data-loss guard):</b> every branch still reads back exactly its oracle —
 *       i.e. GC never collected a chunk reachable from any live branch/commit. A wrongly-swept
 *       reachable node would make {@code getBranch} degrade and the content diverge.
 *   <li><b>Liveness:</b> every orphan-tree root planted since the last GC (a root nothing
 *       references, with unique content so it can't dedup with live data) is gone from the store.
 * </ul>
 *
 * <p>(GC and commits don't truly interleave — GC holds the gcLock write lock, commits the read lock
 * — so this is a randomized functional schedule, not a thread-race; that race is the GC-vs-write
 * boundary test in the upstream test-strategy plan's Phase 4.)
 */
class GcReachabilitySimulationTest {
    static {
        RocksDB.loadLibrary();
    }

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final String[] BRANCHES = {"A", "B", "C"};

    @Test
    void seededCommitGcSchedulesPreserveReachableAndCollectOrphans() throws Exception {
        for (long seed : new long[] {3L, 77L, 1984L, 31415L}) {
            runGcSimulation(seed);
        }
    }

    private void runGcSimulation(long seed) throws Exception {
        Path dir = Files.createTempDirectory("prolly-gc-dst-" + seed);
        SplittableRandom rnd = new SplittableRandom(seed);
        Map<String, TreeMap<String, String>> oracles = new LinkedHashMap<>();
        for (String b : BRANCHES) oracles.put(b, new TreeMap<>());
        Set<String> orphanRootsSinceGc = new HashSet<>();

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "gc-dst-repo", DESC, pool);
            for (String b : BRANCHES) db.createBranch(b, "EMPTY");

            int ops = 14 + rnd.nextInt(8);
            for (int op = 0; op < ops; op++) {
                int roll = rnd.nextInt(10);
                if (roll < 6) { // commit to a random branch
                    String b = BRANCHES[rnd.nextInt(BRANCHES.length)];
                    MutableMap mm = new MutableMap(db.getBranch(b), store, DESC, pool);
                    applyRandomEdits(mm, oracles.get(b), rnd, pool);
                    db.commit(b, mm.flush(), db.getHeadHash(b).orElse(null), "sim", "op" + op);
                } else if (roll < 8) { // plant an unreachable orphan tree
                    orphanRootsSinceGc.add(plantOrphan(store, pool, seed, op, rnd));
                } else { // GC + verify
                    new GarbageCollector(db, store).collect();
                    verifyGc(db, store, oracles, orphanRootsSinceGc, seed, op);
                    orphanRootsSinceGc.clear();
                }
            }
            // Final GC + verify.
            new GarbageCollector(db, store).collect();
            verifyGc(db, store, oracles, orphanRootsSinceGc, seed, ops);
        }
    }

    private void verifyGc(
            Database db,
            RocksNodeStore store,
            Map<String, TreeMap<String, String>> oracles,
            Set<String> orphanRoots,
            long seed,
            int op) {
        String where = "seed=" + seed + " op=" + op;
        // SAFETY: no reachable chunk collected → every branch reads back its oracle.
        for (Map.Entry<String, TreeMap<String, String>> e : oracles.entrySet()) {
            assertEquals(
                    e.getValue(),
                    readBranch(db, e.getKey()),
                    where
                            + " branch "
                            + e.getKey()
                            + ": GC collected a reachable chunk (DATA LOSS)");
        }
        // LIVENESS: orphan roots planted since the last GC are gone.
        for (String rootHex : orphanRoots) {
            assertTrue(
                    store.read(HashUtils.fromHex(rootHex)).isEmpty(),
                    where + ": orphan root " + rootHex + " survived GC (not collected)");
        }
    }

    private String plantOrphan(
            RocksNodeStore store, DirectBufferPool pool, long seed, int op, SplittableRandom rnd) {
        List<TreeMutator.Mutation> muts = new ArrayList<>();
        int n = 5 + rnd.nextInt(40);
        for (int i = 0; i < n; i++) {
            // Unique content (seed+op) so the orphan can't content-dedup with live data.
            String key = "orphan-" + seed + "-" + op + "-" + String.format("%04d", i);
            String val = "x" + seed + "-" + op + "-" + i;
            muts.add(
                    new TreeMutator.Mutation(
                            keyTuple(pool, key),
                            MemorySegment.ofArray(val.getBytes(StandardCharsets.UTF_8))));
        }
        Node root = new TreeMutator(store, DESC, pool).applyMutations(null, muts.iterator());
        return HashUtils.toHex(store.write(root.segment())); // written but referenced by nothing
    }

    private void applyRandomEdits(
            MutableMap mm,
            TreeMap<String, String> oracle,
            SplittableRandom rnd,
            DirectBufferPool pool) {
        int edits = 1 + rnd.nextInt(10);
        for (int i = 0; i < edits; i++) {
            String key = String.format("k%02d", rnd.nextInt(25));
            if (rnd.nextInt(10) < 7) {
                String val = "v" + rnd.nextInt(1_000_000);
                oracle.put(key, val);
                mm.put(
                        keyTuple(pool, key),
                        MemorySegment.ofArray(val.getBytes(StandardCharsets.UTF_8)));
            } else {
                oracle.remove(key);
                mm.delete(keyTuple(pool, key));
            }
        }
    }

    private TreeMap<String, String> readBranch(Database db, String branch) {
        TreeMap<String, String> out = new TreeMap<>();
        MapIterator it = db.getBranch(branch).iter();
        while (it.next()) {
            out.put(
                    new String(new Tuple(it.key()).getField(0), StandardCharsets.UTF_8),
                    new String(it.value().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
        }
        return out;
    }

    private MemorySegment keyTuple(DirectBufferPool pool, String key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }
}
