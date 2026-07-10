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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * Phase 1 Step 8 of the upstream test-strategy plan — LCA (merge-base) correctness. {@code
 * MergeBaseTest} already pins the diamond + linear cases deterministically; this adds (a) a
 * <b>generative diamond property</b> — over many shapes, the LCA of two branches is their fork
 * commit (the <i>latest</i> shared ancestor, not an earlier one) and the merge content is
 * oracle-correct — and (b) a <b>criss-cross</b> case with two minimal common ancestors, pinning the
 * documented tiebreak: pick the latest-timestamp minimal ancestor.
 *
 * <p>{@code Database#findLCA} is private and returns the LCA commit's <i>data-root</i> hash, so it
 * is invoked by reflection (as {@code MergeBaseTest} does) and compared against the relevant
 * commit's {@code getRootValueHash()}.
 */
class LcaCorrectnessProperty {

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
    void diamondLcaIsForkPointAndMergeIsCorrect(@ForAll @From("scenarios") ThreeWay tw)
            throws Exception {
        // Need ≥2 base entries to build a two-commit trunk (M0 -> M1) so the
        // LCA must distinguish the fork point (M1) from an earlier ancestor (M0).
        List<Map.Entry<String, String>> entries = new ArrayList<>(tw.base().entrySet());
        Assume.that(entries.size() >= 2);
        Map<String, String> half0 = new LinkedHashMap<>();
        Map<String, String> half1 = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            (i % 2 == 0 ? half0 : half1).put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        Assume.that(!half1.isEmpty());

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "lca-repo", DESC, pool);
            db.createBranch("trunk", "EMPTY");
            commitEdits(db, store, pool, "trunk", mapEdits(half0)); // M0
            commitEdits(db, store, pool, "trunk", mapEdits(half1)); // M1 = fork point

            byte[] forkRoot = db.getHead("trunk").getRootValueHash();

            db.createBranch("left", "trunk");
            db.createBranch("right", "trunk");
            commitEdits(db, store, pool, "left", tw.left());
            commitEdits(db, store, pool, "right", tw.right());

            byte[] leftHead = db.getHeadHash("left").orElseThrow();
            byte[] rightHead = db.getHeadHash("right").orElseThrow();

            byte[] lcaRoot = invokeFindLca(db, leftHead, rightHead);
            assertArrayEquals(
                    forkRoot,
                    lcaRoot,
                    "LCA of two branches must be their fork commit (M1), not an earlier ancestor");

            MergeEngine.MergeResult r = db.merge("left", "right", "author", "merge");
            assertTrue(r.conflicts().isEmpty(), "disjoint diamond merge has no conflict");
            Map<String, String> oracle = applyEdits(applyEdits(tw.base(), tw.left()), tw.right());
            assertEquals(oracle, scan(db, "left"), "diamond merge content must match the oracle");
        }
    }

    /**
     * Criss-cross: two branches each merge the other, producing two minimal common ancestors (a1,
     * b1). {@code findLCA} must pick the latest-timestamp one. Built deterministically with a sleep
     * so a1 and b1 have distinct, ordered commit timestamps (b1 later).
     */
    @Example
    void crissCrossPicksLatestTimestampMinimalAncestor() throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "lca-cc-repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            commitEdits(db, store, pool, "main", List.of(new Edit("base", "v", false))); // M

            db.createBranch("a", "main");
            db.createBranch("b", "main");
            commitEdits(db, store, pool, "a", List.of(new Edit("a", "1", false))); // a1 (earlier)
            Thread.sleep(1100);
            commitEdits(db, store, pool, "b", List.of(new Edit("b", "1", false))); // b1 (later)

            byte[] b1Root = db.getHead("b").getRootValueHash();

            // Snapshot a1 and b1 into fresh branches BEFORE the cross-merges:
            // merge() auto-commits to "ours" and moves that head, so without
            // snapshots the second merge would see the first merge commit (C1)
            // as "theirs" — making C1 an ancestor of C2 (a diamond, not a
            // criss-cross). a2==a1, b2==b1 keep the two cross-merges independent.
            db.createBranch("a2", "a");
            db.createBranch("b2", "b");

            // C1 on 'a' = merge(a, b); C2 on 'b2' = merge(b2, a2). Now a1 and b1
            // are both minimal common ancestors of C1 and C2 (M is below both).
            MergeEngine.MergeResult mc1 = db.merge("a", "b", "author", "c1");
            MergeEngine.MergeResult mc2 = db.merge("b2", "a2", "author", "c2");
            assertTrue(
                    mc1.conflicts().isEmpty() && mc2.conflicts().isEmpty(),
                    "disjoint criss-cross merges are conflict-free");

            byte[] c1 = db.getHeadHash("a").orElseThrow();
            byte[] c2 = db.getHeadHash("b2").orElseThrow();
            byte[] lcaRoot = invokeFindLca(db, c1, c2);

            assertArrayEquals(
                    b1Root,
                    lcaRoot,
                    "criss-cross LCA must be the latest-timestamp minimal ancestor (b1)");
        }
    }

    // ---- harness ---------------------------------------------------------

    private static byte[] invokeFindLca(Database db, byte[] a, byte[] b) throws Exception {
        Method m = Database.class.getDeclaredMethod("findLCA", byte[].class, byte[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(db, a, b);
    }

    private RocksNodeStore openStore() throws Exception {
        Path dir = Files.createTempDirectory("rdf-lca-");
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
