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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.BufferPool;
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
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compile-pins the "hello world" in {@code prolly-storage/USAGE.md}: open a {@link Database} over a
 * durable {@link RocksNodeStore}, commit a change set to a branch with the compare-and-set guard,
 * and read it back. If this stops compiling or passing, the usage guide has drifted from the real
 * API — fix both together.
 */
class UsageExampleTest {

    @Test
    void usage_guide_hello_world_commits_and_reads(@TempDir Path dir) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "myrepo", desc, pool);

            // a fresh Database has no branches — create "main" from the empty base (cf.
            // JsonLeafStore)
            db.createBranch("main", "EMPTY");

            // build a change set against the branch's current state, then commit it
            // (compare-and-set)
            StaticMap base = db.getBranch("main");
            MutableMap mm = new MutableMap(base, store, desc, pool);
            mm.put(key(pool, "alice"), MemorySegment.ofArray("v1".getBytes()));

            byte[] parent = db.getHeadHash("main").orElse(null);
            boolean ok = db.commit("main", mm, parent, "me", "first write");
            assertTrue(ok, "commit should land: HEAD did not move");

            // read it back at the new HEAD
            StaticMap now = db.getBranch("main");
            byte[] v = now.get(key(pool, "alice")).orElseThrow().toArray(ValueLayout.JAVA_BYTE);
            assertEquals("v1", new String(v));
        }
    }

    private static MemorySegment key(BufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }
}
