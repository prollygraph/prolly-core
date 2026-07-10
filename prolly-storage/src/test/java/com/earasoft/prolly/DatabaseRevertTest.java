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

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for {@link Database#revert}, {@link Database#close}, and the {@code pool}/{@code
 * descriptor} accessors — paths the {@code main()}-style Database smoke tests don't reach.
 */
class DatabaseRevertTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(DirectBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    /** Commits a single key/value onto "main", CAS-chained off the current head. */
    private static void commitPut(
            Database db, RocksNodeStore store, DirectBufferPool pool, String k, String v) {
        byte[] parent = db.getHeadHash("main").orElse(null);
        MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
        mm.put(key(pool, k), MemorySegment.ofArray(v.getBytes()));
        db.commit("main", mm.flush(), parent, "author", "put " + k);
    }

    @Test
    void revert_undoes_the_changes_introduced_by_a_commit(@TempDir Path dir) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "revert-test", DESC, pool);
            db.createBranch("main", "EMPTY");

            commitPut(db, store, pool, "alpha", "1");
            commitPut(db, store, pool, "bravo", "2");
            byte[] commitB = db.getHeadHash("main").orElseThrow();

            // Revert commit B — it introduced "bravo".
            db.revert("main", commitB, "reverter");

            StaticMap after = db.getBranch("main");
            assertTrue(
                    after.get(key(pool, "alpha")).isPresent(),
                    "alpha, from the un-reverted commit, survives");
            assertFalse(
                    after.get(key(pool, "bravo")).isPresent(),
                    "bravo was introduced by the reverted commit and is gone again");
        }
    }

    @Test
    void pool_and_descriptor_accessors_return_the_constructor_arguments(@TempDir Path dir)
            throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "accessor-test", DESC, pool);
            assertSame(pool, db.pool());
            assertSame(DESC, db.descriptor());
        }
    }

    @Test
    void close_closes_the_backing_node_store(@TempDir Path dir) throws Exception {
        DirectBufferPool pool = new DirectBufferPool();
        RocksNodeStore store = new RocksNodeStore(dir.toString());
        Database db = new Database(store, "close-test", DESC, pool);
        // close() must close the AutoCloseable NodeStore without throwing.
        assertDoesNotThrow(db::close);
        pool.close();
    }
}
