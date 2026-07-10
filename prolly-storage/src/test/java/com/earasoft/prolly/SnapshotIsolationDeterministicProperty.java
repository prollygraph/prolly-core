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
import java.util.TreeMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Step 24 of the upstream test-strategy plan (invariant R-2, snapshot isolation) — the
 * <b>deterministic-scheduler upgrade</b> of the sleep-based {@code ConcurrentReadDuringMutateTest}.
 *
 * <p>A reader pins an <i>immutable</i> {@link StaticMap} root R (content-addressed; commits only
 * ever add new roots, never mutate R, and R's chunks stay reachable until GC). So the
 * snapshot-isolation invariant is structural — which is exactly what lets a <i>deterministic</i>
 * driver replace sleeps: the test generates an interleaving of reader-reads and writer-commits and
 * drives them in order (the "scheduler"), asserting that at <b>every</b> reader step the pinned
 * root still yields R's exact key set, no matter how many writers committed in between. jqwik
 * generation enumerates the interleavings; every run is replayable from its seed.
 *
 * <p>This is the deterministic complement to the Lincheck linearizability proof ({@code
 * DatabaseCommitOccTest}, Step 23) and the genuinely-threaded (sleep-based) {@code
 * ConcurrentReadDuringMutateTest}; because the pinned root is immutable, thread interleavings
 * cannot affect it, so an explicit ordered drive is the stronger (replayable) realization.
 */
class SnapshotIsolationDeterministicProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Property(tries = 60)
    void pinnedReaderSeesItsRootDespiteInterleavedWriters(@ForAll("schedules") List<Boolean> ops)
            throws Exception {
        Path dir = Files.createTempDirectory("prolly-snap-iso");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "snap-iso-repo", DESC, pool);
            db.createBranch("main", "EMPTY");

            // Seed R and pin it.
            MutableMap init = new MutableMap(db.getBranch("main"), store, DESC, pool);
            TreeMap<String, String> pinnedOracle = new TreeMap<>();
            for (int i = 0; i < 5; i++) {
                init.put(key(pool, "r" + i), val("rv" + i));
                pinnedOracle.put("r" + i, "rv" + i);
            }
            db.commit("main", init.flush(), db.getHeadHash("main").orElse(null), "t", "init");

            StaticMap pinned = db.getBranch("main"); // pin root R (immutable snapshot)
            long pinnedCount = pinned.root().treeCount();

            TreeMap<String, String> liveOracle = new TreeMap<>(pinnedOracle);
            int w = 0;
            for (boolean isWrite : ops) {
                if (isWrite) { // writer step: commit a new key on main
                    String k = "w" + (w++);
                    MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
                    mm.put(key(pool, k), val("wv"));
                    db.commit("main", mm.flush(), db.getHeadHash("main").orElse(null), "t", "w");
                    liveOracle.put(k, "wv");
                } else { // reader step: pinned root must be UNCHANGED
                    assertEquals(
                            pinnedOracle,
                            read(pinned),
                            "pinned snapshot drifted under concurrent writes (R-2 violated)");
                    assertEquals(
                            pinnedCount,
                            pinned.root().treeCount(),
                            "pinned root treeCount changed (snapshot is not immutable)");
                }
            }
            // The pin still sees exactly R; the live head reflects every write (test isn't
            // vacuous).
            assertEquals(pinnedOracle, read(pinned), "pinned snapshot drifted (final)");
            assertEquals(
                    liveOracle, read(db.getBranch("main")), "live head must reflect the writes");
        }
    }

    @Provide
    Arbitrary<List<Boolean>> schedules() {
        return Arbitraries.of(true, false).list().ofMinSize(2).ofMaxSize(30);
    }

    private static TreeMap<String, String> read(StaticMap sm) {
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
