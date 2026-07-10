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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MergeEngine;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Criss-cross merge content correctness ({@code plans/prepublic/criss-cross-merge-correctness.md})
 * — the Step-1 divergence-frontier characterization AND the Step-3 content oracle for the {@code
 * --recursive} fix (ADR-0070). Builds criss-cross histories through the real {@link Database#merge}
 * and asserts the merged content / conflict behavior.
 *
 * @apiNote Two paths, both now correct after Step 3:
 *     <ul>
 *       <li><b>Auto-merge API ({@link Database#merge}): safe.</b> Only conflict-free (disjoint)
 *           criss-crosses auto-commit, and for those single-base {@code resolve} already equalled
 *           {@code --recursive}; the disagreeing shape can't form here (the cross-merge surfaces a
 *           conflict, does not auto-commit). {@code --recursive} keeps disjoint content correct
 *           ({@link #disjoint_criss_cross_merge_content_equals_recursive_oracle}).
 *       <li><b>Public 2-parent {@link Database#commitMerge} (manual resolution): the silent
 *           divergence Step 1 measured, now FIXED.</b> A human resolves the cross-merge conflict
 *           and commits M1=K1 / M2=K2 (both parented by a1,b1). Step 1 measured the final merge
 *           <i>silently</i> auto-resolving K (0 conflicts, base-pick dependent). Step 3's {@code
 *           --recursive} (ADR-0070) builds a virtual base = merge(M, a1, b1) that is contested on
 *           K, so the merge now <b>surfaces a conflict</b> rather than silently picking ({@link
 *           #manually_resolved_disagreeing_criss_cross_now_surfaces_a_conflict}).
 *     </ul>
 */
class CrissCrossMergeContentProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private final List<Path> tempDirs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Path dir : tempDirs) deleteRecursively(dir);
        tempDirs.clear();
    }

    /**
     * Foundational probe: when both branches change the <i>same</i> key to <i>different</i> values
     * from a common base, the three-way merge must <b>surface a conflict</b> (not silently
     * auto-resolve). This is what prevents a disagreeing criss-cross from forming through the API.
     */
    @Test
    void both_branches_change_same_key_differently_surfaces_a_conflict() throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "cc-conflict", DESC, pool);
            db.createBranch("main", "EMPTY");
            commitEdits(db, store, pool, "main", List.of(new Edit("K", "0", false)));
            db.createBranch("a", "main");
            db.createBranch("b", "main");
            commitEdits(db, store, pool, "a", List.of(new Edit("K", "1", false)));
            commitEdits(db, store, pool, "b", List.of(new Edit("K", "2", false)));

            MergeEngine.MergeResult r = db.merge("a", "b", "author", "m");
            assertFalse(
                    r.conflicts().isEmpty(),
                    "both-changed-same-key-differently must surface a conflict, not auto-resolve");
        }
    }

    /**
     * The dangerous criss-cross — two minimal ancestors that <i>disagree</i> on a key both later
     * touch — <b>cannot form silently</b>: the cross-merge that would create it conflicts, so it
     * never auto-commits (the head does not move), and {@code findLCA} therefore never has to
     * choose between disagreeing bases for a silently-formed history.
     */
    @Test
    void disagreeing_cross_merge_conflicts_so_dangerous_criss_cross_cannot_form_silently()
            throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "cc-dangerous", DESC, pool);
            db.createBranch("main", "EMPTY");
            commitEdits(db, store, pool, "main", List.of(new Edit("K", "0", false)));
            db.createBranch("a", "main");
            db.createBranch("b", "main");
            commitEdits(db, store, pool, "a", List.of(new Edit("K", "1", false))); // a1: K=1
            commitEdits(db, store, pool, "b", List.of(new Edit("K", "2", false))); // b1: K=2
            db.createBranch("a2", "a");
            db.createBranch("b2", "b");

            byte[] aHeadBefore = db.getHeadHash("a").orElseThrow();
            MergeEngine.MergeResult c1 = db.merge("a", "b", "author", "c1");

            assertFalse(c1.conflicts().isEmpty(), "the disagreeing cross-merge must conflict");
            assertArrayEquals(
                    aHeadBefore,
                    db.getHeadHash("a").orElseThrow(),
                    "a conflicted merge must NOT auto-commit — the head must not move, so the "
                            + "disagreeing criss-cross cannot form through the API");
        }
    }

    /**
     * The criss-crosses that <i>do</i> form through the API are conflict-free (disjoint): a1 and b1
     * touch different keys, so the two minimal ancestors do not disagree on any key. For these the
     * single-base {@code resolve} merge content equals the {@code --recursive} result — the
     * independent oracle is just every branch's edits applied (order-independent because disjoint).
     */
    @Test
    void disjoint_criss_cross_merge_content_equals_recursive_oracle() throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "cc-disjoint", DESC, pool);
            db.createBranch("main", "EMPTY");
            commitEdits(db, store, pool, "main", List.of(new Edit("base", "v", false))); // M
            db.createBranch("a", "main");
            db.createBranch("b", "main");
            commitEdits(db, store, pool, "a", List.of(new Edit("ka", "1", false))); // a1
            Thread.sleep(1100);
            commitEdits(db, store, pool, "b", List.of(new Edit("kb", "1", false))); // b1 (later)
            db.createBranch("a2", "a");
            db.createBranch("b2", "b");

            // The two cross-merges (conflict-free → they auto-commit) form the criss-cross.
            MergeEngine.MergeResult c1 = db.merge("a", "b", "author", "c1");
            MergeEngine.MergeResult c2 = db.merge("b2", "a2", "author", "c2");
            assertTrue(
                    c1.conflicts().isEmpty() && c2.conflicts().isEmpty(),
                    "disjoint cross-merges are conflict-free");

            // The final merge of the two criss-cross heads (single-base resolve under the hood).
            MergeEngine.MergeResult fin = db.merge("a", "b2", "author", "final");
            assertTrue(fin.conflicts().isEmpty(), "disjoint final merge is conflict-free");

            // Independent --recursive oracle: with disjoint edits the recursive and resolve results
            // coincide and equal {base ∪ all edits}.
            Map<String, String> oracle = new LinkedHashMap<>();
            oracle.put("base", "v");
            oracle.put("ka", "1");
            oracle.put("kb", "1");
            assertEquals(
                    oracle,
                    scan(db, "a"),
                    "disjoint criss-cross merge content must equal the recursive oracle (no divergence)");
        }
    }

    /**
     * The disagreeing criss-cross formed through the <b>public</b> {@link Database#commitMerge}: a1
     * and b1 disagree on K; M1 (on "a") resolves K=1, M2 (on "b2") resolves K=2, both parented by
     * {a1,b1}. Step 1 measured single-base {@code resolve} <i>silently</i> auto-resolving this (0
     * conflicts, K=1, base-pick dependent). <b>Step 3 fix (ADR-0070 {@code --recursive}):</b> the
     * virtual base = merge(M, a1=K1, b1=K2) is contested on K, and our/their disagree on K, so the
     * merge now <b>surfaces a conflict on K</b> instead of silently auto-resolving — this pins the
     * fix.
     */
    @Test
    void manually_resolved_disagreeing_criss_cross_now_surfaces_a_conflict() throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "cc-manual", DESC, pool);
            db.createBranch("main", "EMPTY");
            commitEdits(db, store, pool, "main", List.of(new Edit("K", "0", false)));
            db.createBranch("a", "main");
            db.createBranch("b", "main");
            commitEdits(db, store, pool, "a", List.of(new Edit("K", "1", false))); // a1: K=1
            Thread.sleep(1100);
            commitEdits(
                    db, store, pool, "b", List.of(new Edit("K", "2", false))); // b1: K=2 (later)
            byte[] a1 = db.getHeadHash("a").orElseThrow();
            byte[] b1 = db.getHeadHash("b").orElseThrow();
            db.createBranch("b2", "b");

            // Manually resolve the (conflicting) cross-merges via the PUBLIC 2-parent commitMerge:
            // M1 on "a" keeps K=1; M2 on "b2" keeps K=2. Each carries both a1 and b1 as parents.
            db.commitMerge(
                    "a", branchContent(db, store, pool, "a"), List.of(a1, b1), "author", "M1");
            db.commitMerge(
                    "b2", branchContent(db, store, pool, "b2"), List.of(b1, a1), "author", "M2");

            MergeEngine.MergeResult fin = db.merge("a", "b2", "author", "final");

            // ADR-0070 --recursive: the virtual base = merge(M, a1=K1, b1=K2) is contested on K,
            // and
            // our/their disagree on K, so the merge SURFACES a conflict on K instead of silently
            // auto-resolving by base-pick (the Step-1 verdict-(c) silent divergence — now fixed).
            assertFalse(
                    fin.conflicts().isEmpty(),
                    "the disagreeing criss-cross must surface a conflict (no silent auto-resolve)");
            boolean conflictOnK =
                    fin.conflicts().stream()
                            .anyMatch(c -> "K".equals(new String(new Tuple(c.key()).getField(0))));
            assertTrue(conflictOnK, "the surfaced conflict must be on the contested key K");
        }
    }

    /**
     * The branch's current content as a fresh StaticMap — the "resolved" merge content for
     * commitMerge.
     */
    private static StaticMap branchContent(
            Database db, RocksNodeStore store, DirectBufferPool pool, String branch) {
        return new MutableMap(db.getBranch(branch), store, DESC, pool).flush();
    }

    // ---- harness (mirrors LcaCorrectnessProperty's) ----------------------

    private RocksNodeStore openStore() throws Exception {
        Path dir = Files.createTempDirectory("cc-content-");
        tempDirs.add(dir);
        return new RocksNodeStore(dir.toString());
    }

    private void commitEdits(
            Database db,
            RocksNodeStore store,
            DirectBufferPool pool,
            String branch,
            List<Edit> edits) {
        byte[] parent = db.getHeadHash(branch).orElse(null);
        MutableMap mm = new MutableMap(db.getBranch(branch), store, DESC, pool);
        TupleBuilder tb = new TupleBuilder(pool);
        for (Edit e : edits) {
            tb.putField(0, e.key().getBytes());
            MemorySegment key = tb.build().segment();
            if (e.delete()) mm.delete(key);
            else mm.put(key, MemorySegment.ofArray(e.value().getBytes()));
        }
        db.commit(branch, mm.flush(), parent, "author", "c");
    }

    private static Map<String, String> scan(Database db, String branch) {
        Map<String, String> out = new LinkedHashMap<>();
        StaticMap map = db.getBranch(branch);
        MapIterator it = map.iter();
        while (it.next()) {
            out.put(
                    new String(new Tuple(it.key()).getField(0)),
                    new String(it.value().toArray(ValueLayout.JAVA_BYTE)));
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
