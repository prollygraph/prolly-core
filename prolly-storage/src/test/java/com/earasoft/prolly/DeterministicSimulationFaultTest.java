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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.ProllyCorruptionException;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksManifest;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.rocksdb.RocksDB;

/**
 * I-7/I-4 deterministic-simulation FAULT seams (plans/core-engine-test-strategy.md Step 24) — the
 * richer fault modes the Step-23 {@link DeterministicSimulationTest} left for here:
 *
 * <ul>
 *   <li><b>crash-mid-write</b>: an {@link ErrorInjectingNodeStore} throws part- way through a
 *       commit's chunk writes (before the manifest CAS). After reopen the head is still the last
 *       <em>fully durable</em> commit and the content equals the oracle — a torn commit leaves
 *       orphan chunks but never advances the head or corrupts recovery.
 *   <li><b>single-bit-flip detection</b>: a committed chunk is overwritten with garbage on disk;
 *       reading it through an {@link IntegrityVerifyingNodeStore} re-hashes and <em>throws</em>
 *       (corruption detected, never silently served) — the I-4/I-8 integrity guarantee.
 * </ul>
 *
 * <p>Torn-write (truncating RocksDB's last write batch) is deliberately NOT here: it needs
 * RocksDB-internal WAL manipulation that's fragile to simulate portably; crash-mid-write covers the
 * same "partial commit doesn't corrupt recovery" invariant at the application boundary.
 */
class DeterministicSimulationFaultTest {
    static {
        RocksDB.loadLibrary();
    }

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Test
    void crashMidWriteNeverAdvancesTheHeadOrCorruptsRecovery() throws Exception {
        for (long seed : new long[] {7L, 101L, 5150L, 2026L}) {
            runCrashMidWrite(seed);
        }
    }

    private void runCrashMidWrite(long seed) throws Exception {
        Path dir = Files.createTempDirectory("prolly-dst-fault-" + seed);
        SplittableRandom rnd = new SplittableRandom(seed);
        TreeMap<String, String> oracle = new TreeMap<>();
        byte[] durableHead;

        try (DirectBufferPool pool = new DirectBufferPool()) {
            // Phase A — establish a durable head H + oracle via clean commits.
            try (RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
                Database db = new Database(rocks, "repo", DESC, pool);
                db.createBranch("main", "EMPTY");
                int commits = 2 + rnd.nextInt(3);
                for (int c = 0; c < commits; c++) {
                    MutableMap mm = new MutableMap(db.getBranch("main"), rocks, DESC, pool);
                    applyRandomEdits(mm, oracle, rnd, pool);
                    assertTrue(
                            db.commit(
                                    "main",
                                    mm.flush(),
                                    db.getHeadHash("main").orElse(null),
                                    "sim",
                                    "A" + c),
                            "clean commit must succeed");
                }
                durableHead = db.getHeadHash("main").orElseThrow();
            }

            // Phase B — a commit that throws part-way through its chunk writes.
            try (RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
                ErrorInjectingNodeStore failing = new ErrorInjectingNodeStore(rocks);
                Database db =
                        new Database(failing, new RocksManifest(rocks.db()), "repo", DESC, pool);
                MutableMap mm = new MutableMap(db.getBranch("main"), failing, DESC, pool);
                applyRandomEdits(
                        mm,
                        new TreeMap<>(),
                        rnd,
                        pool); // throwaway oracle — this commit must NOT land
                byte[] parent = db.getHeadHash("main").orElse(null);
                failing.injectErrorAfter(1); // throw on the first write of the flush/commit
                try {
                    db.commit("main", mm.flush(), parent, "sim", "B-doomed");
                    fail("seed=" + seed + ": expected the injected mid-write failure");
                } catch (RuntimeException e) {
                    assertTrue(
                            e.getMessage() != null
                                    && e.getMessage().contains("Injected IO Failure"),
                            "seed=" + seed + ": expected the injected failure, got " + e);
                }
            }

            // Phase C — reopen clean: the doomed commit never advanced the head.
            try (RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
                Database db = new Database(rocks, "repo", DESC, pool);
                assertArrayEquals(
                        durableHead,
                        db.getHeadHash("main").orElseThrow(),
                        "seed="
                                + seed
                                + ": head advanced past the last durable commit after a crashed write");
                assertEquals(
                        oracle,
                        readBranch(db),
                        "seed="
                                + seed
                                + ": content diverged from the oracle after a crashed write");
            }
        }
    }

    @Test
    void bitFlipInACommittedChunkIsDetectedNotServed() throws Exception {
        Path dir = Files.createTempDirectory("prolly-dst-bitflip");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
            Database db = new Database(rocks, "repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            MutableMap mm = new MutableMap(db.getBranch("main"), rocks, DESC, pool);
            for (int i = 0; i < 50; i++) {
                mm.put(
                        keyTuple(pool, String.format("k%02d", i)),
                        MemorySegment.ofArray(("v" + i).getBytes(StandardCharsets.UTF_8)));
            }
            assertTrue(db.commit("main", mm.flush(), null, "sim", "seed"), "commit must succeed");
            byte[] head = db.getHeadHash("main").orElseThrow();

            // Corrupt the committed head chunk in place (garbage that won't re-hash to `head`).
            byte[] original = rocks.read(head).orElseThrow().toArray(ValueLayout.JAVA_BYTE);
            byte[] corrupt = original.clone();
            corrupt[0] ^= 0x5A;
            rocks.db().put(head, corrupt);

            // A re-hashing read must DETECT the corruption, not serve the bytes.
            IntegrityVerifyingNodeStore verifying = new IntegrityVerifyingNodeStore(rocks);
            assertThrows(
                    ProllyCorruptionException.class,
                    () -> verifying.read(head),
                    "a bit-flip in a committed chunk must be detected on read, not silently served");
        }
    }

    // ---- helpers (mirrors DeterministicSimulationTest) ----

    private void applyRandomEdits(
            MutableMap mm,
            TreeMap<String, String> oracle,
            SplittableRandom rnd,
            DirectBufferPool pool) {
        int edits = 1 + rnd.nextInt(8);
        for (int i = 0; i < edits; i++) {
            String key = String.format("k%02d", rnd.nextInt(20));
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

    private TreeMap<String, String> readBranch(Database db) {
        TreeMap<String, String> out = new TreeMap<>();
        MapIterator it = db.getBranch("main").iter();
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
