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

import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Direct storage-level coverage for {@link Database#cherryPick} and {@link Database#rebase} — the
 * substrate's commit-replay version-control operations.
 *
 * @implNote These two operations were exercised only by DOWNSTREAM tests (cherryPick by an upstream
 *     module's {@code DiffMergeTest}; rebase by {@code MVCCTest}/{@code SailCommitContractTest}),
 *     so prolly-storage's own module-local JaCoCo could not see them and counted ~20 of {@code
 *     Database}'s lines as missed — which is exactly why the storage {@code jacoco-check} gate
 *     (LINE ≥ 0.90) sat at 0.8977 and failed {@code mvn verify}. The fix is the architecturally
 *     correct one, not a threshold tweak: the substrate's core operations deserve a direct, focused
 *     storage-level test (faster + more targeted than the RDF-integration tests), and that
 *     legitimately lifts module-local coverage over the bar with margin. Mirrors the sibling {@link
 *     DatabaseRevertTest} harness (revert is cherryPick's inverse — apply the patch's valueA rather
 *     than valueB).
 */
class DatabaseCherryPickTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(DirectBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    /**
     * Commits a single key/value onto {@code branch}, CAS-chained off that branch's current head.
     */
    private static void commitPut(
            Database db,
            RocksNodeStore store,
            DirectBufferPool pool,
            String branch,
            String k,
            String v) {
        byte[] parent = db.getHeadHash(branch).orElse(null);
        MutableMap mm = new MutableMap(db.getBranch(branch), store, DESC, pool);
        mm.put(key(pool, k), MemorySegment.ofArray(v.getBytes()));
        db.commit(branch, mm.flush(), parent, "author", "put " + k);
    }

    @Test
    void cherryPick_replays_a_commits_changes_onto_another_branch(@TempDir Path dir)
            throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "cherrypick-test", DESC, pool);
            db.createBranch("main", "EMPTY");

            commitPut(db, store, pool, "main", "alpha", "1"); // commit A: {alpha}
            db.createBranch("topic", "main"); // topic forks at A (alpha only, no bravo)
            commitPut(db, store, pool, "main", "bravo", "2"); // commit B on main adds bravo
            byte[] commitB = db.getHeadHash("main").orElseThrow();

            // Cherry-pick B (which introduced bravo) onto topic: replays B's diff-vs-its-parent.
            db.cherryPick("topic", commitB, "picker");

            StaticMap topic = db.getBranch("topic");
            assertTrue(
                    topic.get(key(pool, "alpha")).isPresent(),
                    "alpha (topic's own history) remains");
            assertTrue(
                    topic.get(key(pool, "bravo")).isPresent(),
                    "bravo, replayed from commit B, is now on topic");

            // The cherry-pick onto topic leaves main untouched.
            StaticMap main = db.getBranch("main");
            assertTrue(main.get(key(pool, "alpha")).isPresent());
            assertTrue(main.get(key(pool, "bravo")).isPresent());
        }
    }

    @Test
    void rebase_replays_pending_edits_onto_a_new_base(@TempDir Path dir) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "rebase-test", DESC, pool);
            db.createBranch("main", "EMPTY");

            commitPut(db, store, pool, "main", "alpha", "1"); // base1 = {alpha}
            StaticMap base1 = db.getBranch("main");

            // Pending edits authored against base1: add bravo (not yet committed).
            MutableMap pending = new MutableMap(base1, store, DESC, pool);
            pending.put(key(pool, "bravo"), MemorySegment.ofArray("2".getBytes()));

            // The base moves on: a newer commit adds charlie.
            commitPut(db, store, pool, "main", "charlie", "3"); // base2 = {alpha, charlie}
            StaticMap base2 = db.getBranch("main");

            // Rebase the pending edits onto the newer base.
            MutableMap rebased = db.rebase(pending, base2);
            StaticMap result = rebased.flush();

            assertTrue(result.get(key(pool, "alpha")).isPresent(), "alpha from the new base");
            assertTrue(result.get(key(pool, "charlie")).isPresent(), "charlie from the new base");
            assertTrue(
                    result.get(key(pool, "bravo")).isPresent(),
                    "bravo, the pending edit, replayed onto the new base");
        }
    }
}
