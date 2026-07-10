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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.NodeCache;
import com.dolthub.prolly.ProllyCorruptionException;
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
 * Step 4 of {@code core-read-integrity-default.md} — the end-to-end corruption test. Where {@link
 * RocksNodeStoreVerifyOnReadTest} pins the mechanism at the bare store, this pins the guarantee
 * <i>through the production stack</i>: a real {@link Database} over a {@link RocksNodeStore}
 * carrying the production read config (verify-on + a {@link NodeCache}), where a node corrupted on
 * disk is read back <b>cache-cold after a restart</b> — the realistic bit-rot scenario (server
 * down, bytes rot, server up, first read goes to disk).
 *
 * <p><b>Design (control arm).</b> The first test is the control: good data must round-trip through
 * the same restart + verify + cold-cache path, so the corruption test's failure is attributable to
 * the corruption and not to a broken reopen. The second corrupts the <i>data-tree root node</i> (so
 * a normal data read traverses into it) and asserts the read fails closed with the clear,
 * hash-mismatch error — not wrong data, not a generic {@code NullPointerException}. Verification
 * fires <i>before</i> the bytes are parsed (re-hash vs key, then {@code Node.fromBytes}), so even
 * unparseable tampered bytes surface as the integrity error.
 */
class DatabaseReadIntegrityEndToEndTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final String REPO = "read-integrity-end-to-end";
    private static final long CACHE_BYTES = 8L * 1024 * 1024;

    private static MemorySegment stringKey(DirectBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    /**
     * Commit one row through a real Database on {@code dir}, then close (release the RocksDB lock).
     */
    private static void writeOneRow(
            Path dir, DirectBufferPool pool, MemorySegment key, String value) throws Exception {
        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            store.setVerifyOnRead(true);
            store.setNodeCache(new NodeCache(CACHE_BYTES));
            Database db = new Database(store, REPO, DESC, pool);
            db.createBranch("main", "EMPTY");
            MutableMap mm = new MutableMap(db.getBranch("main"), store, DESC, pool);
            mm.put(key, MemorySegment.ofArray(value.getBytes()));
            assertTrue(
                    db.commit(
                            "main",
                            mm.flush(),
                            db.getHeadHash("main").orElse(null),
                            "author",
                            "add a row"),
                    "the seed commit must succeed");
        }
    }

    @Test
    void good_data_round_trips_through_a_restart_with_verification_on(@TempDir Path dir)
            throws Exception {
        // CONTROL: the verify-on + cold-cache restart read path serves correct data. Without this,
        // the corruption test could pass for the wrong reason (a broken reopen also throws).
        try (DirectBufferPool pool = new DirectBufferPool()) {
            MemorySegment key = stringKey(pool, "alpha");
            writeOneRow(dir, pool, key, "the-value");

            try (RocksNodeStore store2 = new RocksNodeStore(dir.toString())) {
                store2.setVerifyOnRead(true);
                store2.setNodeCache(new NodeCache(CACHE_BYTES)); // fresh = cold; the read hits disk
                Database db2 = new Database(store2, REPO, DESC, pool);
                assertTrue(
                        db2.getBranch("main").get(key).isPresent(),
                        "good data must read back after a restart with verification on");
            }
        }
    }

    @Test
    void a_corrupted_data_node_fails_closed_on_a_cache_cold_read(@TempDir Path dir)
            throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            MemorySegment key = stringKey(pool, "alpha");
            byte[] rootHash;

            // Phase 1 — write + commit through the real Database, capture the data-tree root hash,
            // corrupt that node's bytes on disk (bit-rot under the key), then close to release the
            // RocksDB lock so the restart can reopen.
            try (RocksNodeStore store1 = new RocksNodeStore(dir.toString())) {
                store1.setVerifyOnRead(true);
                store1.setNodeCache(new NodeCache(CACHE_BYTES));
                Database db1 = new Database(store1, REPO, DESC, pool);
                db1.createBranch("main", "EMPTY");
                MutableMap mm = new MutableMap(db1.getBranch("main"), store1, DESC, pool);
                mm.put(key, MemorySegment.ofArray("the-value".getBytes()));
                assertTrue(
                        db1.commit(
                                "main",
                                mm.flush(),
                                db1.getHeadHash("main").orElse(null),
                                "author",
                                "add a row"));
                rootHash = db1.getHead("main").getRootValueHash();
                assertNotNull(rootHash, "a one-row data tree has a non-null root");
                store1.db().put(rootHash, "tampered-bytes".getBytes()); // simulate on-disk bit-rot
            }

            // Phase 2 — restart over the SAME dir with a fresh (cold) cache, then read the data.
            // The
            // cold read goes to disk, re-hashes the tampered bytes, finds the mismatch, fails
            // closed.
            try (RocksNodeStore store2 = new RocksNodeStore(dir.toString())) {
                store2.setVerifyOnRead(true);
                store2.setNodeCache(
                        new NodeCache(CACHE_BYTES)); // cold: the corrupted node is on disk
                Database db2 = new Database(store2, REPO, DESC, pool);
                ProllyCorruptionException ex =
                        assertThrows(
                                ProllyCorruptionException.class,
                                () -> db2.getBranch("main").get(key),
                                "a cache-cold read of a corrupted node must fail closed");
                assertTrue(
                        ex.getMessage().contains("integrity check failed"),
                        "clear corruption error (expected vs actual hash), not wrong data or a"
                                + " generic NPE: "
                                + ex.getMessage());
            }
        }
    }
}
