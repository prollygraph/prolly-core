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
 * Regression test for the empty-tree commit NPE (fixed 2026-05-16).
 *
 * <p>Committing a branch whose data tree is empty — every row deleted — used to crash: {@code
 * Database.commitInternal} passes a {@code null} {@code rootValueHash} to {@code
 * Commit.serialize()}, which NPE'd. This exercises commit → persist → read-back of an emptied
 * branch.
 */
class DatabaseEmptyTreeCommitTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Test
    void committing_an_emptied_branch_succeeds_and_reads_back_empty(@TempDir Path dir)
            throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "empty-tree-test", DESC, pool);
            db.createBranch("main", "EMPTY");

            // Commit one row.
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, "only-row".getBytes());
            MemorySegment key = tb.build().segment();
            MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
            mm.put(key, MemorySegment.ofArray("v".getBytes()));
            assertTrue(
                    db.commit(
                            "main",
                            mm.flush(),
                            db.getHeadHash("main").orElse(null),
                            "author",
                            "add the row"));

            // Commit an empty data tree on top — the delete-everything case.
            // Regression: this used to NPE in Commit.serialize on a null root.
            byte[] headA = db.getHeadHash("main").orElseThrow();
            assertTrue(
                    db.commit(
                            "main",
                            new StaticMap(store, null, DESC),
                            headA,
                            "author",
                            "empty the tree"),
                    "committing an empty data tree must succeed, not NPE");

            // The branch reads back as empty.
            StaticMap branch = db.getBranch("main");
            assertNull(branch.root(), "the emptied branch has a null root");
            assertFalse(branch.get(key).isPresent(), "the row is gone");

            // The persisted head commit carries a null root and reloads cleanly.
            assertNull(
                    db.getHead("main").getRootValueHash(),
                    "the empty-tree commit round-trips a null rootValueHash");
        }
    }

    @Test
    void commit_rejects_null_branch_or_tree_fast(@TempDir Path dir) throws Exception {
        // Step 3 fail-fast arg guards on the commit entry points: a null branch or null tree is a
        // caller bug → IllegalArgumentException, not a deep NPE in the commit body. A null
        // expectedParentHash is legitimate (no parent), so it is NOT guarded.
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "commit-arg-guard", DESC, pool);
            db.createBranch("main", "EMPTY");
            StaticMap tree = new StaticMap(store, null, DESC);
            MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
            byte[] head = db.getHeadHash("main").orElse(null);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> db.commit(null, tree, head, "a", "m"),
                    "commit(StaticMap): null branch");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> db.commit("main", (StaticMap) null, head, "a", "m"),
                    "commit(StaticMap): null tree");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> db.commit(null, mm, head, "a", "m"),
                    "commit(MutableMap): null branch");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> db.commit("main", (MutableMap) null, head, "a", "m"),
                    "commit(MutableMap): null tree");
        }
    }
}
