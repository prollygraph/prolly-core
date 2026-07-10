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
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MergeEngine;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.gen.RdfGenerators;
import com.earasoft.prolly.gen.RdfGenerators.Edit;
import com.earasoft.prolly.gen.RdfGenerators.ThreeWay;
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
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * Phase 1 Step 7 of the upstream test-strategy plan — the merge laws (R-1, R-7), exercised through
 * {@link Database#merge}, which runs the real {@link MergeEngine} over LCA-based three-way merge
 * and auto-commits on a clean result. Each law is a property over generated three-way scenarios
 * with a {@code TreeMap} oracle:
 *
 * <ul>
 *   <li><b>Commutativity + correctness (disjoint edits):</b> merging two key-disjoint branches
 *       yields the base with both diffs applied, and the result is identical whichever branch is
 *       "ours".
 *   <li><b>Identity:</b> merging a branch with itself is a conflict-free no-op.
 *   <li><b>Idempotent re-merge:</b> merging an already-merged branch again changes nothing and
 *       raises no conflict.
 *   <li><b>Conflict detection == divergent overlap:</b> when both sides set the same key to
 *       different values, the merge reports exactly that key as a conflict and does not
 *       auto-commit.
 * </ul>
 *
 * <p>(Associativity over three disjoint branches is deferred — see the plan wrap-up;
 * commutativity+correctness already pins order-independence.)
 */
class CommitMergeLawsProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private final List<Path> tempDirs = new ArrayList<>();

    @Provide
    Arbitrary<ThreeWay> scenarios() {
        return RdfGenerators.threeWay();
    }

    @AfterTry
    void cleanup() {
        for (Path dir : tempDirs) deleteRecursively(dir);
        tempDirs.clear();
    }

    @Property(tries = 40)
    void disjointMergeIsCommutativeAndCorrect(@ForAll @From("scenarios") ThreeWay tw)
            throws Exception {
        Map<String, String> oracle = applyEdits(applyEdits(tw.base(), tw.left()), tw.right());

        Map<String, String> leftOurs = runMerge(tw, /* leftIsOurs= */ true);
        Map<String, String> rightOurs = runMerge(tw, /* leftIsOurs= */ false);

        assertEquals(oracle, leftOurs, "merge(left,right) must equal base + both diffs");
        assertEquals(oracle, rightOurs, "merge(right,left) must equal base + both diffs");
        assertEquals(leftOurs, rightOurs, "merge of disjoint branches is commutative");
    }

    @Property(tries = 40)
    void mergeWithSelfIsConflictFreeNoOp(@ForAll @From("scenarios") ThreeWay tw) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "merge-repo", DESC, pool);
            seedBranch(db, store, pool, "main", null, mapEdits(tw.base()));
            Map<String, String> before = scan(db, "main");

            MergeEngine.MergeResult r = db.merge("main", "main", "author", "self");
            assertTrue(r.conflicts().isEmpty(), "self-merge raises no conflict");
            assertEquals(before, scan(db, "main"), "self-merge is a no-op");
        }
    }

    @Property(tries = 30)
    void reMergeIsIdempotent(@ForAll @From("scenarios") ThreeWay tw) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "merge-repo", DESC, pool);
            seedBranch(db, store, pool, "main", null, mapEdits(tw.base()));
            db.createBranch("left", "main");
            db.createBranch("right", "main");
            commitEdits(db, store, pool, "left", tw.left());
            commitEdits(db, store, pool, "right", tw.right());

            MergeEngine.MergeResult first = db.merge("left", "right", "author", "m1");
            Assume.that(first.conflicts().isEmpty()); // disjoint → expected clean
            Map<String, String> afterFirst = scan(db, "left");

            MergeEngine.MergeResult second = db.merge("left", "right", "author", "m2");
            assertTrue(second.conflicts().isEmpty(), "re-merge raises no conflict");
            assertEquals(
                    afterFirst, scan(db, "left"), "re-merging an already-merged branch is a no-op");
        }
    }

    @Property(tries = 40)
    void conflictDetectionMatchesDivergentOverlap(
            @ForAll @From("scenarios") ThreeWay tw,
            @ForAll @AlphaChars @StringLength(min = 1, max = 6) String a,
            @ForAll @AlphaChars @StringLength(min = 1, max = 6) String b)
            throws Exception {
        Assume.that(!a.equals(b)); // an actual divergence
        String key = "conflict-key";
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "merge-repo", DESC, pool);
            // Base has the key; both branches change it to DIFFERENT values.
            Map<String, String> base = new LinkedHashMap<>(tw.base());
            base.put(key, "base");
            seedBranch(db, store, pool, "main", null, mapEdits(base));
            db.createBranch("left", "main");
            db.createBranch("right", "main");
            commitEdits(db, store, pool, "left", List.of(new Edit(key, a, false)));
            commitEdits(db, store, pool, "right", List.of(new Edit(key, b, false)));

            Map<String, String> leftBefore = scan(db, "left");
            MergeEngine.MergeResult r = db.merge("left", "right", "author", "conflict");

            assertFalse(
                    r.conflicts().isEmpty(), "divergent overlap must be reported as a conflict");
            boolean keyConflicted =
                    r.conflicts().stream()
                            .anyMatch(
                                    c -> {
                                        String ck = new String(new Tuple(c.key()).getField(0));
                                        return ck.equals(key);
                                    });
            assertTrue(keyConflicted, "the divergent key must be among the conflicts");
            assertEquals(
                    leftBefore,
                    scan(db, "left"),
                    "a conflicting merge must NOT auto-commit (ours unchanged)");
        }
    }

    // ---- harness ---------------------------------------------------------

    /**
     * Build base→left/right branches and merge, returning the merged content of the "ours" branch.
     * Each call uses a fresh store so the two orders in the commutativity check don't share state.
     */
    private Map<String, String> runMerge(ThreeWay tw, boolean leftIsOurs) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "merge-repo", DESC, pool);
            seedBranch(db, store, pool, "main", null, mapEdits(tw.base()));
            db.createBranch("left", "main");
            db.createBranch("right", "main");
            commitEdits(db, store, pool, "left", tw.left());
            commitEdits(db, store, pool, "right", tw.right());

            String ours = leftIsOurs ? "left" : "right";
            String theirs = leftIsOurs ? "right" : "left";
            MergeEngine.MergeResult r = db.merge(ours, theirs, "author", "merge");
            assertTrue(
                    r.conflicts().isEmpty(),
                    "disjoint-key branches must merge without conflict; got "
                            + r.conflicts().size());
            return scan(db, ours);
        }
    }

    private RocksNodeStore openStore() throws Exception {
        Path dir = Files.createTempDirectory("rdf-r7-");
        tempDirs.add(dir);
        return new RocksNodeStore(dir.toString());
    }

    /** Seed a branch from scratch with one commit holding {@code edits}. */
    private void seedBranch(
            Database db,
            RocksNodeStore store,
            DirectBufferPool pool,
            String branch,
            String from,
            List<Edit> edits) {
        if (from == null) db.createBranch(branch, "EMPTY");
        else db.createBranch(branch, from);
        commitEdits(db, store, pool, branch, edits);
    }

    /** Commit one batch of edits onto an existing branch. */
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
        db.commit(branch, mm.flush(), parent, "author", "seed");
    }

    private static List<Edit> mapEdits(Map<String, String> m) {
        List<Edit> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Edit(k, v, false)));
        return out;
    }

    private static Map<String, String> applyEdits(Map<String, String> base, List<Edit> edits) {
        Map<String, String> m = new LinkedHashMap<>(base);
        for (Edit e : edits) {
            if (e.delete()) m.remove(e.key());
            else m.put(e.key(), e.value());
        }
        return m;
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
