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
package com.earasoft.prolly.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeCache;
import com.dolthub.prolly.ProllyIoException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * SQLite-grade coverage for {@link RocksNodeStore}. This is the durable CAS backing every persisted
 * commit — write/read drift here corrupts every persisted tree, and a missing flushDurable() makes
 * commits lose data on crash.
 *
 * <p>Pre-existing usage in {@code GCTest}, {@code FaultInjectionTest}, etc. exercises
 * RocksNodeStore as a transitive dependency; this file pins its contract directly so regressions
 * don't slip through gaps in the larger end-to-end paths.
 */
class RocksNodeStoreTest {
    static {
        RocksDB.loadLibrary();
    }

    // ---- CAS semantics ----

    @Test
    void write_returns_content_address(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] hash = s.write(data);
            byte[] expected = HashUtils.hash(data);
            assertArrayEquals(expected, hash, "write must return the SHA-512/20 content hash");
        }
    }

    @Test
    void write_then_read_roundtrip(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            byte[] data = "round-trip me".getBytes(StandardCharsets.UTF_8);
            byte[] hash = s.write(data);
            Optional<MemorySegment> got = s.read(hash);
            assertTrue(got.isPresent());
            assertArrayEquals(data, got.get().toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void read_missing_hash_returns_empty(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            byte[] nonexistent = new byte[20];
            nonexistent[0] = 0x42;
            assertFalse(s.read(nonexistent).isPresent());
        }
    }

    @Test
    void write_is_idempotent(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            byte[] data = "idempotent".getBytes();
            byte[] h1 = s.write(data);
            byte[] h2 = s.write(data);
            assertArrayEquals(h1, h2, "content-addressing: identical data → identical hash");
        }
    }

    @Test
    void write_memory_segment_overload_matches_byte_array(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            byte[] data = "via segment".getBytes();
            byte[] viaBytes = s.write(data);
            byte[] viaSeg = s.write(MemorySegment.ofArray(data));
            assertArrayEquals(viaBytes, viaSeg);
        }
    }

    @Test
    void large_chunk_roundtrips(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            byte[] data = new byte[1024 * 1024]; // 1 MiB
            new Random(7).nextBytes(data);
            byte[] hash = s.write(data);
            byte[] back = s.read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE);
            assertArrayEquals(data, back);
        }
    }

    @Test
    void empty_chunk_roundtrips(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            byte[] hash = s.write(new byte[0]);
            assertNotNull(hash);
            assertEquals(20, hash.length);
            assertEquals(0, s.read(hash).orElseThrow().byteSize());
        }
    }

    @Test
    void many_chunks_all_readable(@TempDir Path dir) throws Exception {
        // Functional equivalent of an in-memory dedup test, but proves
        // chunks land in RocksDB and survive read-back.
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            byte[][] hashes = new byte[100][];
            for (int i = 0; i < 100; i++) {
                hashes[i] = s.write(("payload-" + i).getBytes());
            }
            for (int i = 0; i < 100; i++) {
                Optional<MemorySegment> got = s.read(hashes[i]);
                assertTrue(got.isPresent(), "chunk " + i + " missing after batch write");
                assertEquals("payload-" + i, new String(got.get().toArray(ValueLayout.JAVA_BYTE)));
            }
        }
    }

    // ---- persistence across close+reopen ----

    @Test
    void chunks_survive_close_and_reopen(@TempDir Path dir) throws Exception {
        byte[] hash;
        byte[] data = "survive me".getBytes();
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            hash = s.write(data);
            s.flushDurable();
        }
        // Re-open the same dir.
        try (RocksNodeStore s2 = new RocksNodeStore(dir.toString())) {
            Optional<MemorySegment> got = s2.read(hash);
            assertTrue(got.isPresent(), "data written + flushed must survive close + reopen");
            assertArrayEquals(data, got.get().toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void flushDurable_idempotent(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            s.write("a".getBytes());
            assertDoesNotThrow(s::flushDurable);
            assertDoesNotThrow(s::flushDurable, "flushDurable must be safe to call repeatedly");
        }
    }

    // ---- cache integration ----

    @Test
    void cache_populated_on_read(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            NodeCache cache = new NodeCache(1 << 20); // 1 MiB byte budget
            s.setNodeCache(cache);

            // Write a real PNOD-headered node (ADR-0072) — the node cache only holds blobs that
            // Node.fromBytes accepts as nodes, so a SimpleNodeSerializer TLV blob would no longer
            // populate the cache (it round-trips as bytes but isn't a node).
            byte[] payload = makeRealNodeBytes();
            byte[] hash = s.write(payload);

            // First read populates the cache; subsequent reads hit it.
            assertTrue(s.read(hash).isPresent());
            assertTrue(
                    cache.get(hash).isPresent(),
                    "cache must be populated after a miss-then-fetch read");

            // Re-read returns the same bytes.
            assertArrayEquals(payload, s.read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void null_cache_disables_caching(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            NodeCache cache = new NodeCache(1 << 20); // 1 MiB byte budget
            s.setNodeCache(cache);
            s.setNodeCache(null); // disable
            byte[] data = makeSimpleNodeBytes();
            byte[] hash = s.write(data);
            assertTrue(s.read(hash).isPresent());
            assertFalse(
                    cache.get(hash).isPresent(),
                    "after cache=null, reads must NOT populate the disconnected cache");
        }
    }

    // ---- db() accessor ----

    @Test
    void db_accessor_returns_open_handle(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            RocksDB db = s.db();
            assertNotNull(db);
            // Direct DB ops should succeed.
            byte[] hash = s.write("via store".getBytes());
            assertNotNull(
                    db.get(hash),
                    "underlying RocksDB must hold what RocksNodeStore.write put there");
        }
    }

    // ---- determinism cross-check vs in-memory store ----

    @Test
    void rocks_and_in_memory_produce_same_hash(@TempDir Path dir) throws Exception {
        // RocksNodeStore.write and InMemoryNodeStore.write must derive
        // hashes from the same source: HashUtils.hash. Drift here would
        // mean a chunk written to disk reads back under a different hash
        // than the same chunk in memory — silent corruption.
        try (RocksNodeStore rocks = new RocksNodeStore(dir.toString());
                InMemoryNodeStore mem = new InMemoryNodeStore()) {
            byte[] data = "consistency".getBytes();
            byte[] hRocks = rocks.write(data);
            byte[] hMem = mem.write(data);
            assertArrayEquals(
                    hRocks,
                    hMem,
                    "rocks and in-memory stores must produce identical content hashes");
        }
    }

    // ---- write-batch lifecycle ----

    @Test
    void batched_write_returns_content_hash_immediately(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            s.beginWriteBatch();
            byte[] data = "batched".getBytes(StandardCharsets.UTF_8);
            byte[] hash = s.write(data);
            s.endWriteBatch();
            assertArrayEquals(
                    HashUtils.hash(data),
                    hash,
                    "write must return the content hash even while batching");
        }
    }

    @Test
    void batched_write_not_visible_until_end(@TempDir Path dir) throws Exception {
        // Documented contract: a chunk buffered in an open batch is NOT
        // guaranteed readable until endWriteBatch. (TreeMutator never reads a
        // chunk it just wrote, so this is safe for the engine.)
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            s.beginWriteBatch();
            byte[] hash = s.write("invisible".getBytes(StandardCharsets.UTF_8));
            assertTrue(
                    s.read(hash).isEmpty(),
                    "a chunk buffered in an open batch must not be readable yet");
            s.endWriteBatch();
            assertTrue(s.read(hash).isPresent(), "after endWriteBatch the chunk must be readable");
        }
    }

    @Test
    void endWriteBatch_persists_every_buffered_write(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            s.beginWriteBatch();
            java.util.List<byte[]> hashes = new java.util.ArrayList<>();
            for (int i = 0; i < 500; i++) {
                hashes.add(s.write(("chunk-" + i).getBytes(StandardCharsets.UTF_8)));
            }
            s.endWriteBatch();
            for (int i = 0; i < 500; i++) {
                Optional<MemorySegment> got = s.read(hashes.get(i));
                assertTrue(got.isPresent(), "buffered chunk " + i + " must persist");
                assertArrayEquals(
                        ("chunk-" + i).getBytes(StandardCharsets.UTF_8),
                        got.get().toArray(ValueLayout.JAVA_BYTE));
            }
        }
    }

    @Test
    void endWriteBatch_without_active_batch_is_a_safe_noop(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            assertDoesNotThrow(s::endWriteBatch);
            byte[] hash = s.write("after".getBytes(StandardCharsets.UTF_8));
            assertTrue(s.read(hash).isPresent(), "normal writes still work");
        }
    }

    @Test
    void writes_outside_a_batch_persist_immediately(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            byte[] hash = s.write("direct".getBytes(StandardCharsets.UTF_8));
            assertTrue(s.read(hash).isPresent(), "a non-batched write must be readable at once");
        }
    }

    @Test
    void store_returns_to_write_through_after_a_batch(@TempDir Path dir) throws Exception {
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            s.beginWriteBatch();
            byte[] inBatch = s.write("in-batch".getBytes(StandardCharsets.UTF_8));
            s.endWriteBatch();
            byte[] afterBatch = s.write("after-batch".getBytes(StandardCharsets.UTF_8));
            assertTrue(s.read(inBatch).isPresent());
            assertTrue(
                    s.read(afterBatch).isPresent(),
                    "writes after the batch ends must persist immediately again");
        }
    }

    @Test
    void batched_writes_survive_close_and_reopen(@TempDir Path dir) throws Exception {
        // endWriteBatch writes with WAL-on WriteOptions, so batched chunks are
        // exactly as durable as direct puts — they survive a store reopen.
        byte[] hash;
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            s.beginWriteBatch();
            hash = s.write("durable-batch".getBytes(StandardCharsets.UTF_8));
            s.endWriteBatch();
        }
        try (RocksNodeStore reopened = new RocksNodeStore(dir.toString())) {
            Optional<MemorySegment> got = reopened.read(hash);
            assertTrue(got.isPresent(), "batched write must survive reopen (WAL kept)");
            assertArrayEquals(
                    "durable-batch".getBytes(StandardCharsets.UTF_8),
                    got.get().toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void write_batch_is_per_thread(@TempDir Path dir) throws Exception {
        // beginWriteBatch is thread-scoped. A batch open on this thread must
        // not capture a write made on another thread — that one writes through.
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            s.beginWriteBatch(); // batch on the test thread
            byte[][] otherHash = new byte[1][];
            Thread other =
                    new Thread(
                            () ->
                                    otherHash[0] =
                                            s.write(
                                                    "other-thread"
                                                            .getBytes(StandardCharsets.UTF_8)));
            other.start();
            other.join();
            assertTrue(
                    s.read(otherHash[0]).isPresent(),
                    "a write on a batch-free thread must persist immediately");
            s.endWriteBatch();
        }
    }

    @Test
    void beginWriteBatch_twice_replaces_the_open_batch_without_leaking(@TempDir Path dir)
            throws Exception {
        // beginWriteBatch is documented non-reentrant, but a stray double-begin
        // must not leak the first batch's native handle — the second begin
        // closes it. The subsequent write lands in the live (second) batch.
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            s.beginWriteBatch();
            s.beginWriteBatch(); // discards the first batch
            byte[] hash = s.write("second-batch".getBytes(StandardCharsets.UTF_8));
            s.endWriteBatch();
            assertTrue(
                    s.read(hash).isPresent(),
                    "write after a double-begin must persist via the live batch");
        }
    }

    @Test
    void close_with_an_open_batch_drops_the_unflushed_writes(@TempDir Path dir) throws Exception {
        // Closing the store with a batch still open must not throw or leak —
        // and the buffered writes, never endWriteBatch'd, were never committed,
        // so they must NOT survive. (Crash-equivalent: uncommitted = lost.)
        byte[] hash = HashUtils.hash("orphan".getBytes(StandardCharsets.UTF_8));
        try (RocksNodeStore s = new RocksNodeStore(dir.toString())) {
            s.beginWriteBatch();
            s.write("orphan".getBytes(StandardCharsets.UTF_8)); // buffered, never ended
            // close() runs here via try-with-resources — must handle the open batch.
        }
        try (RocksNodeStore reopened = new RocksNodeStore(dir.toString())) {
            assertTrue(
                    reopened.read(hash).isEmpty(),
                    "a write buffered in a never-ended batch must not survive close");
        }
    }

    // ---- rethrow: disk-quota / disk-full detection ----

    @Test
    void rethrow_detects_disk_quota_exceeded() {
        RuntimeException wrapped =
                RocksNodeStore.rethrow(
                        "RocksNodeStore.write",
                        new org.rocksdb.RocksDBException("Disk quota exceeded: /tmp/x/000004.log"));
        assertInstanceOf(
                ProllyIoException.class,
                wrapped,
                "a RocksDB failure is a typed, retryable io error (core-error-taxonomy D-1)");
        String msg = wrapped.getMessage();
        assertTrue(msg.contains("RocksNodeStore.write failed"));
        assertTrue(msg.contains("Disk quota exceeded"), "keeps the underlying message");
        assertTrue(
                msg.contains("rm -rf /tmp/prolly-*"),
                "a quota error must surface the cleanup recipe");
        assertTrue(msg.contains("operation_guide.md"));
        assertInstanceOf(org.rocksdb.RocksDBException.class, wrapped.getCause());
    }

    @Test
    void rethrow_detects_no_space_left_on_device() {
        RuntimeException wrapped =
                RocksNodeStore.rethrow(
                        "RocksNodeStore.endWriteBatch",
                        new org.rocksdb.RocksDBException("No space left on device"));
        assertInstanceOf(ProllyIoException.class, wrapped, "disk-full is a typed io error");
        assertTrue(
                wrapped.getMessage().contains("operation_guide.md"),
                "a disk-full error must point at the operations guide");
        assertTrue(wrapped.getMessage().contains("filesystem hosting this RocksDB store is full"));
    }

    @Test
    void read_null_hash_fails_fast(@TempDir Path dir) throws Exception {
        // Step 3 fail-fast arg guard on the PRODUCTION store: null hash → IllegalArgumentException
        // before the lifecycle lock, not a deep failure inside db.get.
        try (RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
            assertThrows(IllegalArgumentException.class, () -> rocks.read(null));
        }
    }

    @Test
    void rethrow_wraps_an_ordinary_error_plainly() {
        // A non-disk failure must NOT carry the disk-cleanup recipe — that
        // would be a misleading diagnosis.
        RuntimeException wrapped =
                RocksNodeStore.rethrow(
                        "RocksNodeStore.read",
                        new org.rocksdb.RocksDBException("Corruption: bad block in sst"));
        String msg = wrapped.getMessage();
        assertTrue(msg.contains("RocksNodeStore.read failed"));
        assertTrue(msg.contains("Corruption: bad block in sst"));
        assertFalse(
                msg.contains("rm -rf"),
                "a non-disk error must not be misdiagnosed as a full filesystem");
    }

    // ---- helper: build a minimal valid chunk that Node.fromBytes accepts ----

    private static byte[] makeSimpleNodeBytes() {
        // Opaque payload bytes in the retired TLV layout (the deleted test-only
        // SimpleNodeSerializer's format — historical): 1-byte level + 4-byte count
        // + per-item (4-byte keyLen + key + 4-byte valLen + val + 8-byte sc).
        // The store round-trips arbitrary bytes, so the content is incidental here.
        // Single item: key="k", value="v", sc=1.
        java.nio.ByteBuffer bb =
                java.nio.ByteBuffer.allocate(5 + 4 + 1 + 4 + 1 + 8)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 0).putInt(1);
        bb.putInt(1).put((byte) 'k').putInt(1).put((byte) 'v').putLong(1L);
        return bb.array();
    }

    /**
     * A real PNOD-headered leaf node (ADR-0072) that {@code Node.fromBytes} accepts → is cacheable.
     */
    private static byte[] makeRealNodeBytes() {
        try (com.dolthub.prolly.HeapBufferPool pool = new com.dolthub.prolly.HeapBufferPool()) {
            return new com.dolthub.prolly.FlatbufferNodeSerializer()
                    .serialize(
                            0,
                            java.util.List.of(
                                    new com.dolthub.prolly.TreeMutator.PendingItem(
                                            MemorySegment.ofArray("k".getBytes()),
                                            MemorySegment.ofArray("v".getBytes()),
                                            1L)));
        }
    }

    @org.junit.jupiter.api.Test
    void aggregateGauges_surviveAWrapperOverAFreedSharedHandle(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // The 2026-07-03 SIGABRT shape (RocksNativeMetersTest crash): a co-tenant
        // RocksNodeStore over a SharedRocksDb stays in the LIVE registry after the
        // SHARED handle's owner closes it — the wrapper's own close() never ran, so
        // closed=false and the next aggregate gauge poll called getProperty on a
        // freed native handle, killing the JVM. prop()'s disposal-flag guard must
        // make the poll return instead of crash.
        SharedRocksDb shared = SharedRocksDb.open(dir.toString(), java.util.List.of());
        RocksNodeStore wrapper = new RocksNodeStore(shared.db(), shared.chunkStoreColumnFamily());
        try {
            // Owner frees the shared handle; the wrapper is now dangling-but-registered.
            shared.close();
            // A metrics scrape over the aggregate gauges must NOT crash the JVM —
            // the dangling wrapper contributes 0.
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    RocksNodeStore::aggregateMemTableBytes);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    RocksNodeStore::aggregateTableReadersBytes);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    RocksNodeStore::aggregateBlockCacheBytes);
        } finally {
            wrapper.close(); // deregister from LIVE so later tests see a clean registry
        }
    }

    @org.junit.jupiter.api.Test
    void rocksDbOwnershipSemantics_theFactsTheGaugeGuardDependsOn(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // Pins the RocksDB 8.10.0 semantics the prop() guard is built on — and the
        // trap the FIRST (wrong) fix fell into (the upstream owning-handle-overguard
        // bug write-up): isOwningHandle() is an OWNERSHIP bit ("responsible to free
        // the underlying C++ object"), NOT a liveness bit. RocksDB's
        // makeDefaultColumnFamilyHandle() explicitly disOwnNativeHandle()s the
        // default-CF handle, so on a HEALTHY open database the CF flag is false —
        // guarding on it zeroes every legitimate read. The db handle from
        // RocksDB.open IS owner-created, so its flag is a usable liveness proxy.
        // If a RocksDB upgrade changes any of this, the guard's assumptions break
        // here, loudly, with the reason in this comment.
        org.rocksdb.RocksDB.loadLibrary();
        try (var opts = new org.rocksdb.Options().setCreateIfMissing(true)) {
            org.rocksdb.RocksDB db = org.rocksdb.RocksDB.open(opts, dir.toString());
            org.junit.jupiter.api.Assertions.assertTrue(
                    db.isOwningHandle(), "an open owner-created db reports owning");
            org.junit.jupiter.api.Assertions.assertFalse(
                    db.getDefaultColumnFamily().isOwningHandle(),
                    "the default-CF handle reports NON-owning on a HEALTHY db"
                            + " (RocksDB owns it) — never guard liveness on it");
            db.close();
            org.junit.jupiter.api.Assertions.assertFalse(
                    db.isOwningHandle(), "after close the db flag drops — the usable signal");
        }
    }

    @org.junit.jupiter.api.Test
    void aggregateMemTableBytes_readsRealBytesOnALiveStore(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // The POSITIVE direction of the gauge contract, pinned module-locally.
        // The first (wrong) hardening fix zeroed every healthy read and STILL
        // passed this module's whole suite — the only positive-direction
        // assertion lived downstream in an upstream REST module's native-meters test
        // (the cross-module-test-metric trap). A guard that fails toward 0 is
        // self-concealing; this test is the module-local tripwire.
        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            for (int i = 0; i < 50; i++) {
                store.write(("chunk-" + i + "-" + "x".repeat(500)).getBytes());
            }
            org.junit.jupiter.api.Assertions.assertTrue(
                    RocksNodeStore.aggregateMemTableBytes() > 0,
                    "a live store's memtable aggregate must read real bytes, not a"
                            + " guard-swallowed zero");
        }
    }

    @org.junit.jupiter.api.Test
    void prop_onAClosedStore_returnsZeroInsteadOfTouchingTheHandle(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        RocksNodeStore store = new RocksNodeStore(dir.toString());
        store.close();
        // The closed guard, not the freed-handle guard: a normally-closed store's
        // property reads answer 0 (the single-instance meters poll these too).
        org.junit.jupiter.api.Assertions.assertEquals(0L, store.totalSstBytes());
    }
}
