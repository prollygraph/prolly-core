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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
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
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.rocksdb.RocksDB;

/**
 * I-7 durability/atomicity as Deterministic Simulation Testing (plans/core-engine-test-strategy.md
 * Step 23, first increment). Generalizes the fixed-scenario {@code CrashRecoveryAtomicityTest} to a
 * {@link SplittableRandom}-seeded schedule of {build random edit, commit, crash (close+reopen)}
 * over many rounds, checked against a {@link TreeMap} oracle.
 *
 * <p><b>Invariant (I-7):</b> after any crash+reopen, the recovered branch head is exactly the last
 * <em>durably committed</em> head and its content equals the oracle — committed work always
 * survives, and uncommitted work (chunks written but the manifest never advanced) never corrupts
 * recovery.
 *
 * <p>Two seeded crash modes per round:
 *
 * <ul>
 *   <li><b>durable-commit crash</b>: commit (which {@code flushDurable}s) then close+reopen → the
 *       commit survives, head + content match the oracle;
 *   <li><b>crash-before-manifest</b>: build + flush an edit (writes chunks) but DON'T commit, then
 *       close+reopen → head + content unchanged (the orphan chunks are ignored; a later GC reclaims
 *       them).
 * </ul>
 *
 * <p>Fixed seeds make any failure reproducible. Richer fault modes (crash mid-write via {@code
 * ErrorInjectingNodeStore}, torn-batch truncation, single bit-flip) are the Step-24 follow-up; this
 * increment pins the core commit→crash→recover loop over randomized schedules.
 */
class DeterministicSimulationTest {
    static {
        RocksDB.loadLibrary();
    }

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final int KEY_SPACE = 20; // small → puts/deletes overlap → real churn

    @Test
    void seededCommitsSurviveCrashAndReopen() throws Exception {
        for (long seed : new long[] {1L, 42L, 12345L, 2026L, 8675309L}) {
            runSimulation(seed);
        }
    }

    private void runSimulation(long seed) throws Exception {
        Path dir = Files.createTempDirectory("prolly-dst-" + seed);
        SplittableRandom rnd = new SplittableRandom(seed);
        TreeMap<String, String> oracle = new TreeMap<>(); // last durable committed content
        byte[] expectedHead = null; // last durable head

        int reopenRounds = 3 + rnd.nextInt(3); // 3..5 crash/reopen cycles
        try (DirectBufferPool pool = new DirectBufferPool()) {
            for (int round = 0; round < reopenRounds; round++) {
                try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                    Database db = new Database(store, "dst-repo", DESC, pool);
                    if (round == 0) db.createBranch("main", "EMPTY");

                    // On reopen: the recovered state must equal the last durable commit.
                    verifyRecovered(db, oracle, expectedHead, seed, round);

                    // A batch of durable commits, each mutating the oracle in lock-step.
                    int commits = 1 + rnd.nextInt(5);
                    for (int c = 0; c < commits; c++) {
                        MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
                        applyRandomEdits(mm, oracle, rnd, pool);
                        byte[] parent = db.getHeadHash("main").orElse(null);
                        assertTrue(
                                db.commit(
                                        "main",
                                        mm.flush(),
                                        parent,
                                        "sim",
                                        "s" + seed + "r" + round + "c" + c),
                                "commit must succeed (single writer, correct parent)");
                        expectedHead = db.getHeadHash("main").orElseThrow();
                    }

                    // Crash-before-manifest mode: write chunks but never commit.
                    if (rnd.nextInt(2) == 0) {
                        MutableMap orphan = new MutableMap(db.getBranch("main"), store, DESC, pool);
                        applyRandomEdits(
                                orphan, new TreeMap<>(), rnd, pool); // mutate a throwaway oracle
                        orphan.flush(); // writes chunks; manifest NOT advanced — must not survive
                    }
                } // close = simulated crash
            }
            // Final reopen: everything durably committed is still there.
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                Database db = new Database(store, "dst-repo", DESC, pool);
                verifyRecovered(db, oracle, expectedHead, seed, reopenRounds);
            }
        }
    }

    private void applyRandomEdits(
            MutableMap mm,
            TreeMap<String, String> oracle,
            SplittableRandom rnd,
            DirectBufferPool pool) {
        int edits = 1 + rnd.nextInt(8);
        for (int i = 0; i < edits; i++) {
            String key = String.format("k%02d", rnd.nextInt(KEY_SPACE));
            if (rnd.nextInt(10) < 7) { // 70% put
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

    private void verifyRecovered(
            Database db,
            TreeMap<String, String> oracle,
            byte[] expectedHead,
            long seed,
            int round) {
        Optional<byte[]> head = db.getHeadHash("main");
        String where = "seed=" + seed + " round=" + round;
        if (expectedHead == null) {
            assertTrue(head.isEmpty(), where + ": no commit yet, head must be empty");
            return;
        }
        assertTrue(head.isPresent(), where + ": recovered head missing after reopen");
        assertArrayEquals(
                expectedHead, head.get(), where + ": recovered head != last durable commit");
        assertEquals(oracle, readBranch(db), where + ": recovered content != oracle");
    }

    private TreeMap<String, String> readBranch(Database db) {
        TreeMap<String, String> out = new TreeMap<>();
        StaticMap sm = db.getBranch("main");
        MapIterator it = sm.iter();
        while (it.next()) {
            String key = new String(new Tuple(it.key()).getField(0), StandardCharsets.UTF_8);
            String val =
                    new String(it.value().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
            out.put(key, val);
        }
        return out;
    }

    private MemorySegment keyTuple(DirectBufferPool pool, String key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }
}
