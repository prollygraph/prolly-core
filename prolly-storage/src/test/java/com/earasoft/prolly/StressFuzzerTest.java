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
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.rocksdb.RocksDB;

/**
 * Deterministic stress fuzzer for the versioned {@link Database} write path (formerly {@code
 * StressFuzzer}, a {@code main()}-only class that the MainMethodTests driver skipped because its
 * name didn't end in {@code Test} — so it never ran). Converted to a real {@code @Test} as part of
 * the "fix all dark tests" sweep (plans/core-engine-test-strategy.md Step 15).
 *
 * <p>Drives a fixed-seed sequence of random put/delete commits against a RocksDB-backed Database
 * and asserts the prolly tree's {@code treeCount} stays in lock-step with a {@link TreeMap} oracle.
 * The fixed seed makes a failure reproducible; a divergence means the commit/mutation path lost or
 * double-counted a key.
 */
class StressFuzzerTest {
    static {
        RocksDB.loadLibrary();
    }

    private static final int NUM_OPS = 1000;
    private static final int SEED = 12345;

    @Test
    void deterministicCommitSequenceKeepsTreeCountInSyncWithOracle() throws Exception {
        Path tempDir = Files.createTempDirectory("prolly-fuzz");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "fuzz-repo", desc, pool);
            db.createBranch("main", "EMPTY");
            Random rnd = new Random(SEED);
            Map<String, String> oracle = new TreeMap<>();
            TupleBuilder tb = new TupleBuilder(pool);
            for (int i = 0; i < NUM_OPS; i++) {
                byte[] parent = db.getHeadHash("main").orElse(null);
                StaticMap current = db.getBranch("main");
                MutableMap mm = new MutableMap(current, store, desc, pool);
                String keyStr = String.format("key-%06d", rnd.nextInt(20000));
                if (rnd.nextInt(10) < 8) {
                    String valStr = "val-" + rnd.nextInt(1000000);
                    oracle.put(keyStr, valStr);
                    tb.putField(0, keyStr.getBytes());
                    mm.put(tb.build().segment(), MemorySegment.ofArray(valStr.getBytes()));
                } else {
                    oracle.remove(keyStr);
                    tb.putField(0, keyStr.getBytes());
                    mm.delete(tb.build().segment());
                }
                db.commit("main", mm.flush(), parent, "author", "fuzz");
            }
            assertEquals(
                    oracle.size(),
                    db.getBranch("main").root().treeCount(),
                    "after "
                            + NUM_OPS
                            + " deterministic put/delete commits, the prolly tree's "
                            + "treeCount must equal the TreeMap oracle size");
        }
    }
}
