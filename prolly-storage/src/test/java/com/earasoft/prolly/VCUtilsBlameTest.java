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

import com.dolthub.prolly.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 *
 *
 * <h3>VCUtils.blame Test</h3>
 *
 * <p>Pins the semantics of {@link com.earasoft.prolly.VCUtils#blame}: given a branch and a key,
 * return the commit that <b>introduced</b> the value currently at that key — i.e. walking backward
 * from HEAD, the oldest commit in the contiguous run of "value unchanged from HEAD".
 *
 * <p><b>The Gap:</b> {@code VCUtils} had a single test reference (an end-to-end smoke), but no
 * targeted blame oracle. This test exercises the canonical 3-commit history C0 → C1 → C2 with
 * values v0, v1, v1 and asserts {@code blame(HEAD, key) == C1}.
 *
 * <p><b>Bug expected:</b> reading the implementation, the {@code lastCommit} variable is updated
 * only on the first iteration; subsequent iterations with a matching {@code lastVal} leave {@code
 * lastCommit} pinned at HEAD. The expected return is therefore HEAD instead of the introducing
 * commit. If this test fails on first run, fix {@code blame} to advance {@code lastCommit} on every
 * matching iteration.
 */
public class VCUtilsBlameTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- VCUtils.blame Test ---");
        Path tempDir = Files.createTempDirectory("prolly-vc-blame");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "blame-repo", desc, pool);
            db.createBranch("main", "EMPTY");

            // Build C0: key=v0
            commit(db, store, desc, pool, "main", "key", "v0", null);
            byte[] c0Hash = db.getHeadHash("main").orElseThrow();
            sleep(2);

            // Build C1: key=v1 (the introducing commit)
            commit(db, store, desc, pool, "main", "key", "v1", c0Hash);
            byte[] c1Hash = db.getHeadHash("main").orElseThrow();
            sleep(2);

            // Build C2: key=v1 (no change — blame should NOT return C2)
            commit(db, store, desc, pool, "main", "other", "different", c1Hash);
            byte[] c2Hash = db.getHeadHash("main").orElseThrow();

            // blame(main, "key") should return C1 (the commit that introduced v1).
            MemorySegment keySeg = buildKey(pool, "key");
            VCUtils vc = new VCUtils(db, store, desc);
            Commit blamed = vc.blame("main", keySeg);
            if (blamed == null) {
                throw new RuntimeException("blame returned null");
            }
            byte[] blamedHash = store.write(blamed.serialize());
            String got = HashUtils.toHex(blamedHash);
            String expected = HashUtils.toHex(c1Hash);
            String headHex = HashUtils.toHex(c2Hash);
            System.out.println("HEAD=C2: " + headHex);
            System.out.println("Expected (C1, introducing): " + expected);
            System.out.println("Got: " + got);
            if (!got.equals(expected)) {
                throw new RuntimeException(
                        "blame returned wrong commit: got "
                                + got
                                + " expected "
                                + expected
                                + ". If got=HEAD, the bug is that VCUtils.blame fails to update "
                                + "lastCommit on each matching iteration — should advance "
                                + "lastCommit (and lastVal) every time the current commit's value "
                                + "matches lastVal, returning lastCommit only when a divergence is "
                                + "found.");
            }
            System.out.println("blame returned the introducing commit. (1/1)");
            System.out.println("--- VCUtils.blame Test PASSED ---");
        }
    }

    private static void commit(
            Database db,
            RocksNodeStore store,
            TupleDescriptor desc,
            DirectBufferPool pool,
            String branch,
            String key,
            String value,
            byte[] parent) {
        MutableMap mm = new MutableMap(db.getBranch(branch), store, desc, pool);
        mm.put(buildKey(pool, key), MemorySegment.ofArray(value.getBytes()));
        if (!db.commit(branch, mm.flush(), parent, "tester", "msg-" + key + "-" + value)) {
            throw new RuntimeException("commit failed on " + branch);
        }
    }

    private static MemorySegment buildKey(DirectBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        return tb.build().segment();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
