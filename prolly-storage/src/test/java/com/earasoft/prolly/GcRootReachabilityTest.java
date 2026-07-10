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
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.TreeMutator;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.gc.GcReachabilityContributor;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 of docs/write-ups/gc-concurrent-write-flush-window.md (invariant R-4): pins {@link
 * GarbageCollector}'s <b>reachability contract</b>, in its ADR-0074 shape.
 *
 * <p>GC marks the branch-head commit DAG → each commit's data tree, unions every registered {@link
 * GcReachabilityContributor}'s claimed closure, then sweeps every other 20-byte chunk. This test
 * pins THREE halves of that contract: a commit-reachable tree <b>survives</b>; an out-of-band tree
 * claimed by a contributor <b>survives</b> (the ADR-0074 fix — this arm was the documented GAP
 * before 2026-07-16); and an out-of-band tree claimed by NO contributor is <b>swept</b> (the wiring
 * rule stays sharp: an absent contributor means that substrate's chunks are deleted — the reason
 * the RDF face may not run garbage collection until its contributor ships).
 */
class GcRootReachabilityTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Test
    void unclaimedOutOfBandRoot_isSwept_commitReachableDataSurvives() throws Exception {
        Path dir = Files.createTempDirectory("prolly-gc-root-reach");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "gc-root-reach-repo", DESC, pool);
            db.createBranch("main", "EMPTY");

            // Commit-reachable data — must survive GC.
            MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
            TreeMap<String, String> oracle = new TreeMap<>();
            for (int i = 0; i < 5; i++) {
                mm.put(key(pool, "k" + i), val("v" + i));
                oracle.put("k" + i, "v" + i);
            }
            db.commit("main", mm, db.getHeadHash("main").orElse(null), "t", "c0");

            // An out-of-band root: a real (multi-node) tree written to the store but referenced by
            // NO commit — the shape of an aux index root (provenance / RootMetaTree) that GC's
            // commit-DAG walk never marks.
            byte[] outOfBand = writeOutOfBandTree(store, pool);
            assertTrue(store.read(outOfBand).isPresent(), "out-of-band root must exist before GC");

            new GarbageCollector(db, store).collect();

            // Contract: commit-reachable data survives; the UNCLAIMED out-of-band root is swept.
            assertEquals(
                    oracle,
                    read(db.getBranch("main")),
                    "GC must preserve every commit-reachable chunk (safety)");
            assertFalse(
                    store.read(outOfBand).isPresent(),
                    "WIRING RULE (ADR-0074): an out-of-band root claimed by NO contributor is "
                            + "swept. A substrate holding such roots must register its "
                            + "GcReachabilityContributor before garbage collection runs.");
        }
    }

    @Test
    void contributorClaimedOutOfBandTree_survives_everyChunkOfIt() throws Exception {
        Path dir = Files.createTempDirectory("prolly-gc-root-claimed");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "gc-root-claimed-repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
            mm.put(key(pool, "k0"), val("v0"));
            db.commit("main", mm, db.getHeadHash("main").orElse(null), "t", "c0");

            byte[] outOfBand = writeOutOfBandTree(store, pool);

            // The substrate's contributor: claims the tree's FULL closure (the contributor owns
            // its closure logic — here the engine's own ReachabilityWalker plays that role).
            GcReachabilityContributor contributor =
                    s -> {
                        com.dolthub.prolly.ReachabilityWalker walker =
                                new com.dolthub.prolly.ReachabilityWalker((RocksNodeStore) s);
                        walker.walk(outOfBand);
                        return walker.getReachableHashes();
                    };

            new GarbageCollector(db, store, List.of(contributor)).collect();

            // The ADR-0074 fix: every chunk of the claimed tree survives — the whole multi-node
            // tree is still readable, not just its root.
            assertTrue(
                    store.read(outOfBand).isPresent(),
                    "a contributor-claimed out-of-band root must survive collection");
            TreeMap<String, String> auxRead = new TreeMap<>();
            com.dolthub.prolly.StaticMap aux =
                    new com.dolthub.prolly.StaticMap(
                            store, store.read(outOfBand).map(Node::fromBytes).orElseThrow(), DESC);
            MapIterator it = aux.iter();
            while (it.next()) {
                auxRead.put(
                        new String(new Tuple(it.key()).getField(0), StandardCharsets.UTF_8),
                        new String(
                                it.value().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
            }
            assertEquals(200, auxRead.size(), "the claimed tree's every entry must still read");
        }
    }

    @Test
    void collectExclusive_databaseFree_claimedSurvives_unclaimedSwept_countsReported()
            throws Exception {
        Path dir = Files.createTempDirectory("prolly-gc-exclusive");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            // NO engine Database at all — the collectExclusive contract (D-3 of the
            // productionization plan): the store's only roots are the substrate's.
            byte[] claimed = writeOutOfBandTree(store, pool);
            byte[] junk =
                    store.write(MemorySegment.ofArray("orphan".getBytes(StandardCharsets.UTF_8)));

            GcReachabilityContributor contributor =
                    s2 -> {
                        com.dolthub.prolly.ReachabilityWalker walker =
                                new com.dolthub.prolly.ReachabilityWalker((RocksNodeStore) s2);
                        walker.walk(claimed);
                        return walker.getReachableHashes();
                    };

            GcResult result = GarbageCollector.collectExclusive(store, List.of(contributor));

            assertTrue(store.read(claimed).isPresent(), "claimed tree survives");
            assertFalse(store.read(junk).isPresent(), "unclaimed orphan swept");
            assertTrue(result.reachableChunks() > 1, "multi-node tree: several chunks claimed");
            assertTrue(result.sweptChunks() >= 1, "the orphan is counted in the sweep");
        }
    }

    // --- helpers ---

    private static byte[] writeOutOfBandTree(RocksNodeStore store, DirectBufferPool pool) {
        List<TreeMutator.Mutation> muts = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            muts.add(
                    new TreeMutator.Mutation(
                            key(pool, String.format("aux%05d", i)),
                            MemorySegment.ofArray(("a" + i).getBytes(StandardCharsets.UTF_8))));
        }
        Node root = new TreeMutator(store, DESC, pool).applyMutations(null, muts.iterator());
        return store.write(root.segment()); // written, referenced by no commit
    }

    private static TreeMap<String, String> read(com.dolthub.prolly.StaticMap sm) {
        TreeMap<String, String> out = new TreeMap<>();
        MapIterator it = sm.iter();
        while (it.next()) {
            out.put(
                    new String(new Tuple(it.key()).getField(0), StandardCharsets.UTF_8),
                    new String(it.value().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
        }
        return out;
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
