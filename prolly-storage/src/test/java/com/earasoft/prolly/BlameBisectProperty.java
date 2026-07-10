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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.dolthub.prolly.Commit;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.StaticMap;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * Phase 1 Step 9 of the upstream test-strategy plan — blame + bisect properties (R-1), through the
 * real {@link VCUtils}.
 *
 * <ul>
 *   <li><b>blame</b>: over a generated linear history, {@code blame(HEAD,key)} names the commit
 *       that introduced the key's <i>current</i> value. Oracle = replay the batches and record, per
 *       key, the most recent commit at which its value changed (to its final value).
 *   <li><b>bisect</b>: with a <b>monotone</b> {@code isBad} (a "tripwire" key that, once set,
 *       persists), {@code bisect} returns the first bad commit. Oracle = the threshold index where
 *       the tripwire was introduced. The monotonicity precondition is pinned by construction
 *       (tripwire is never removed) — bisect is only correct for monotone predicates.
 * </ul>
 */
class BlameBisectProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final String TRIPWIRE = "tripwire";

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

    @Property(tries = 30)
    void blameNamesTheIntroducingCommit(@ForAll @From("batches") List<List<Edit>> batches)
            throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "blame-repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            VCUtils vc = new VCUtils(db, store, DESC);

            // Replay the batches, recording each commit's hash and computing the
            // oracle: per key, the index of the most recent commit that changed
            // its value (add/overwrite/delete-then-readd all count as a change).
            Map<String, String> cum = new LinkedHashMap<>();
            Map<String, Integer> lastChange = new HashMap<>();
            List<byte[]> commitHashes = new ArrayList<>();
            byte[] parent = null;
            for (int i = 0; i < batches.size(); i++) {
                Map<String, String> before = new LinkedHashMap<>(cum);
                applyInPlace(cum, batches.get(i));
                Set<String> touched = new HashSet<>(before.keySet());
                touched.addAll(cum.keySet());
                for (String k : touched) {
                    if (!Objects.equals(before.get(k), cum.get(k))) lastChange.put(k, i);
                }
                parent = commitBatch(db, store, pool, "main", batches.get(i), parent);
                commitHashes.add(parent);
            }
            Assume.that(!cum.isEmpty()); // need at least one key present at HEAD to blame

            for (String key : cum.keySet()) {
                Commit blamed = vc.blame("main", buildKey(pool, key));
                assertNotNull(blamed, "blame must name a commit for a key present at HEAD: " + key);
                byte[] blamedHash = store.write(blamed.serialize());
                assertArrayEquals(
                        commitHashes.get(lastChange.get(key)),
                        blamedHash,
                        "blame(" + key + ") must be the commit that introduced its current value");
            }
        }
    }

    @Property(tries = 30)
    void bisectFindsFirstBadUnderMonotonePredicate(
            @ForAll @IntRange(min = 3, max = 8) int n,
            @ForAll @IntRange(min = 1, max = 7) int threshold)
            throws Exception {
        Assume.that(threshold < n); // a real good→bad transition inside the history
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = openStore()) {
            Database db = new Database(store, "bisect-repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            VCUtils vc = new VCUtils(db, store, DESC);

            List<byte[]> hashes = new ArrayList<>();
            byte[] parent = null;
            for (int i = 0; i < n; i++) {
                List<Edit> edits = new ArrayList<>();
                edits.add(new Edit("k" + i, "v" + i, false));
                // Monotone fault: from `threshold` on, set the tripwire and never
                // remove it — so isBad is false…false,true…true along the history.
                if (i >= threshold) edits.add(new Edit(TRIPWIRE, "1", false));
                parent = commitBatch(db, store, pool, "main", edits, parent);
                hashes.add(parent);
            }

            byte[] good = hashes.get(threshold - 1); // last good commit
            byte[] bad = hashes.get(n - 1); // HEAD, definitely bad
            Commit firstBad = vc.bisect(good, bad, c -> commitContains(store, c, TRIPWIRE));
            assertNotNull(firstBad);
            byte[] firstBadHash = store.write(firstBad.serialize());
            assertArrayEquals(
                    hashes.get(threshold),
                    firstBadHash,
                    "bisect must return the first commit that set the tripwire");
        }
    }

    // ---- harness ---------------------------------------------------------

    private boolean commitContains(RocksNodeStore store, Commit c, String key) {
        byte[] rootHash = c.getRootValueHash();
        Node root =
                rootHash == null ? null : store.read(rootHash).map(Node::fromBytes).orElse(null);
        StaticMap sm = new StaticMap(store, root, DESC);
        return sm.get(buildKeyHeap(key)).isPresent();
    }

    /** Commit one batch onto a branch; returns the new head hash. */
    private byte[] commitBatch(
            Database db,
            RocksNodeStore store,
            DirectBufferPool pool,
            String branch,
            List<Edit> edits,
            byte[] parent) {
        MutableMap mm = new MutableMap(db.getBranch(branch), store, DESC, pool);
        TupleBuilder tb = new TupleBuilder(pool);
        for (Edit e : edits) {
            tb.putField(0, e.key().getBytes());
            MemorySegment key = tb.build().segment();
            if (e.delete()) mm.delete(key);
            else mm.put(key, MemorySegment.ofArray(e.value().getBytes()));
        }
        db.commit(branch, mm.flush(), parent, "author", "c");
        return db.getHeadHash(branch).orElseThrow();
    }

    private static MemorySegment buildKey(DirectBufferPool pool, String key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes());
        return tb.build().segment();
    }

    /** Heap-backed key for read-only get() (no pool lifecycle to manage). */
    private static MemorySegment buildKeyHeap(String key) {
        try (DirectBufferPool p = new DirectBufferPool()) {
            byte[] tuple = buildKey(p, key).toArray(ValueLayout.JAVA_BYTE);
            return MemorySegment.ofArray(tuple);
        }
    }

    private static void applyInPlace(Map<String, String> m, List<Edit> edits) {
        for (Edit e : edits) {
            if (e.delete()) m.remove(e.key());
            else m.put(e.key(), e.value());
        }
    }

    private RocksNodeStore openStore() throws Exception {
        Path dir = Files.createTempDirectory("rdf-blame-");
        tempDirs.add(dir);
        return new RocksNodeStore(dir.toString());
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
