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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 Step 3 of {@code plans/model-based-testing-rollout.md} — model-based <b>multi-branch</b>
 * simulation for {@link Database}, the commit/branch surface.
 *
 * <p><b>Why a seeded simulation, not a jqwik {@code ActionChain}, and why multi-branch:</b> the
 * rollout's D-1 instrument is {@code ActionChain}, but {@link Database} is RocksDB-backed (it needs
 * a {@code RocksManifest}; {@code deriveManifest} rejects an in-memory store), so a fresh {@code
 * ActionChain} model per chain would spin up a temp-dir RocksDB per chain. This module's
 * established model-based instrument for a RocksDB-backed class is instead a {@link
 * SplittableRandom}-seeded operation loop against a {@link TreeMap} oracle over one store — exactly
 * what {@code DeterministicSimulationTest} and {@code StressFuzzerTest} already do. Those two,
 * however, only ever drive the <b>single</b> {@code main} branch. The uncovered surface — and so
 * this test — is <b>multiple branches at once</b>: {@code createBranch} copies a branch's contents;
 * a commit to one branch must not leak into another (<b>isolation</b>); a stale-parent commit is
 * rejected (compare-and-set). Each is checked against a <b>per-branch</b> oracle after every
 * operation.
 *
 * <p><b>Scoped out (deliberately):</b> {@code merge} (3-way; covered by {@code DiffMergeTest} — a
 * random merge oracle is a separate effort) and {@code revert} (its own tests). The plan placed
 * {@code Database} under the engine module; it actually lived a layer up, so this test does too.
 * There is no {@code checkout} (the Database is branch-name-addressed).
 */
class DatabaseMultiBranchModelTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final String[] BRANCHES = {"main", "b1", "b2", "b3"};
    // Tiny key alphabet so commits to different branches churn the same keys → isolation is
    // genuinely tested.
    private static final String[] KEYS = {"a", "b", "c", "aa", "bb", "cc"};

    @Test
    void multiBranchOpsStayIsolatedAndMatchPerBranchOracle() throws Exception {
        for (long seed = 1; seed <= 6; seed++) {
            Path dir = Files.createTempDirectory("db-multibranch");
            try (DirectBufferPool pool = new DirectBufferPool();
                    RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString())) {
                runOneSeed(seed, store, pool);
            } finally {
                deleteTree(dir);
            }
        }
    }

    private void runOneSeed(long seed, RocksNodeStore store, DirectBufferPool pool) {
        Database db = new Database(store, "model-" + seed, DESC, pool);
        db.createBranch("main", "EMPTY");
        Map<String, TreeMap<String, String>> oracle = new HashMap<>();
        oracle.put("main", new TreeMap<>()); // only "main" exists at first

        SplittableRandom rnd = new SplittableRandom(seed);
        for (int round = 0; round < 250; round++) {
            switch (rnd.nextInt(3)) {
                case 0 -> commit(db, pool, oracle, rnd);
                case 1 -> createBranch(db, oracle, rnd);
                default -> staleCommitRejected(db, pool, oracle, rnd);
            }
            // Isolation invariant: EVERY branch still equals its own oracle after each op.
            for (Map.Entry<String, TreeMap<String, String>> e : oracle.entrySet()) {
                assertBranch(db, pool, e.getKey(), e.getValue(), seed, round);
            }
        }
    }

    /**
     * Commit a few random edits to a random existing branch; the parent is its current head (always
     * succeeds).
     */
    private void commit(
            Database db,
            DirectBufferPool pool,
            Map<String, TreeMap<String, String>> oracle,
            SplittableRandom rnd) {
        String branch = existing(oracle, rnd);
        MutableMap mm = new MutableMap(db.getBranch(branch), db.store(), DESC, pool);
        TreeMap<String, String> ref = oracle.get(branch);
        int edits = 1 + rnd.nextInt(4);
        TreeMap<String, String> pending = new TreeMap<>();
        for (int i = 0; i < edits; i++) {
            String k = KEYS[rnd.nextInt(KEYS.length)];
            if (rnd.nextInt(4) == 0) {
                mm.delete(key(pool, k));
                pending.put(k, null);
            } else {
                String v = "v" + rnd.nextInt(1000);
                mm.put(key(pool, k), val(v));
                pending.put(k, v);
            }
        }
        byte[] parent = db.getHeadHash(branch).orElse(null);
        assertTrue(
                db.commit(branch, mm, parent, "sim", "commit"),
                "commit to " + branch + " must succeed with the current head");
        pending.forEach(
                (k, v) -> {
                    if (v == null) ref.remove(k);
                    else ref.put(k, v);
                }); // apply to the oracle
    }

    /**
     * Create a not-yet-existing branch from an existing one; its contents start equal to the
     * source.
     */
    private void createBranch(
            Database db, Map<String, TreeMap<String, String>> oracle, SplittableRandom rnd) {
        String from = existing(oracle, rnd);
        if (db.getHeadHash(from).isEmpty())
            return; // can't branch a headless (never-committed) branch
        String fresh = null;
        for (String b : BRANCHES)
            if (!oracle.containsKey(b)) {
                fresh = b;
                break;
            }
        if (fresh == null) return; // all branches already exist this run
        db.createBranch(fresh, from);
        oracle.put(fresh, new TreeMap<>(oracle.get(from))); // branch starts == source
    }

    /**
     * A commit with a wrong expected-parent must be rejected (compare-and-set), leaving the branch
     * unchanged.
     */
    private void staleCommitRejected(
            Database db,
            DirectBufferPool pool,
            Map<String, TreeMap<String, String>> oracle,
            SplittableRandom rnd) {
        String branch = existing(oracle, rnd);
        Optional<byte[]> head = db.getHeadHash(branch);
        if (head.isEmpty()) return; // no head yet → no stale parent to forge
        byte[] stale = head.get().clone();
        stale[0] ^= (byte) 0xFF; // a valid-length but wrong parent
        MutableMap mm = new MutableMap(db.getBranch(branch), db.store(), DESC, pool);
        mm.put(key(pool, "a"), val("should-not-land"));
        assertFalse(
                db.commit(branch, mm, stale, "sim", "stale"),
                "commit with a stale parent must be rejected");
        // oracle unchanged; the per-round isolation check confirms the branch is untouched.
    }

    private static String existing(
            Map<String, TreeMap<String, String>> oracle, SplittableRandom rnd) {
        List<String> live = List.copyOf(oracle.keySet());
        return live.get(rnd.nextInt(live.size()));
    }

    private void assertBranch(
            Database db,
            DirectBufferPool pool,
            String branch,
            TreeMap<String, String> ref,
            long seed,
            int round) {
        String where = " [seed " + seed + " round " + round + " branch " + branch + "]";
        StaticMap sm = db.getBranch(branch);
        long count = (sm.root() == null) ? 0 : sm.root().treeCount();
        assertEquals(ref.size(), count, "entry count" + where);
        for (Map.Entry<String, String> e : ref.entrySet()) {
            Optional<MemorySegment> got = sm.get(key(pool, e.getKey()));
            assertTrue(got.isPresent(), "key " + e.getKey() + where);
            assertEquals(e.getValue(), str(got.get()), "value of " + e.getKey() + where);
        }
    }

    private static MemorySegment key(DirectBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    private static MemorySegment val(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(BYTE), StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                }
                            });
        }
    }
}
