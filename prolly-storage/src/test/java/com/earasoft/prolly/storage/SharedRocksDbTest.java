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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * Coverage for {@link SharedRocksDb} — the single-RocksDB-instance opener that lets the versioned
 * chunk store and the unversioned flat Sail share one engine. Pins three contracts: the chunk store
 * reading/writing through the shared default CF, isolation between named column families, and
 * persistence across a close/reopen.
 */
class SharedRocksDbTest {
    static {
        RocksDB.loadLibrary();
    }

    private static final List<String> FLAT_CFS =
            List.of("dict-fwd", "dict-rev", "spoc", "posc", "ospc", "cspo", "ns");

    @Test
    void chunk_store_on_shared_default_cf_roundtrips(@TempDir Path dir) throws Exception {
        try (SharedRocksDb shared = SharedRocksDb.open(dir.toString(), FLAT_CFS);
                RocksNodeStore chunks =
                        new RocksNodeStore(shared.db(), shared.chunkStoreColumnFamily())) {
            byte[] data = "shared-engine chunk".getBytes(StandardCharsets.UTF_8);
            byte[] hash = chunks.write(data);
            Optional<MemorySegment> got = chunks.read(hash);
            assertTrue(got.isPresent(), "chunk written via the shared CF must read back");
            assertArrayEquals(data, got.get().toArray(ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void co_tenant_chunk_store_does_not_close_the_shared_db(@TempDir Path dir) throws Exception {
        try (SharedRocksDb shared = SharedRocksDb.open(dir.toString(), FLAT_CFS)) {
            byte[] hash;
            byte[] data = "before co-tenant close".getBytes(StandardCharsets.UTF_8);
            try (RocksNodeStore chunks =
                    new RocksNodeStore(shared.db(), shared.chunkStoreColumnFamily())) {
                hash = chunks.write(data);
            }
            // The co-tenant store closed, but it does not own the DB — the
            // shared engine is still open, so a fresh store still reads.
            try (RocksNodeStore reopened =
                    new RocksNodeStore(shared.db(), shared.chunkStoreColumnFamily())) {
                Optional<MemorySegment> got = reopened.read(hash);
                assertTrue(
                        got.isPresent(),
                        "closing a non-owning chunk store must not close the shared DB");
                assertArrayEquals(data, got.get().toArray(ValueLayout.JAVA_BYTE));
            }
        }
    }

    @Test
    void named_column_families_are_isolated(@TempDir Path dir) throws Exception {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        byte[] val = "v".getBytes(StandardCharsets.UTF_8);
        try (SharedRocksDb shared = SharedRocksDb.open(dir.toString(), FLAT_CFS)) {
            shared.db().put(shared.columnFamily("spoc"), key, val);
            assertArrayEquals(
                    val,
                    shared.db().get(shared.columnFamily("spoc"), key),
                    "the value must be visible in the CF it was written to");
            assertNull(
                    shared.db().get(shared.columnFamily("posc"), key),
                    "a sibling CF must not see another CF's key");
            assertNull(
                    shared.db().get(shared.chunkStoreColumnFamily(), key),
                    "the chunk-store CF must not see a flat-index CF's key");
        }
    }

    @Test
    void data_survives_close_and_reopen(@TempDir Path dir) throws Exception {
        byte[] hash;
        byte[] chunk = "persist me".getBytes(StandardCharsets.UTF_8);
        byte[] nsKey = "http://ex/".getBytes(StandardCharsets.UTF_8);
        byte[] nsVal = "ex".getBytes(StandardCharsets.UTF_8);
        try (SharedRocksDb shared = SharedRocksDb.open(dir.toString(), FLAT_CFS);
                RocksNodeStore chunks =
                        new RocksNodeStore(shared.db(), shared.chunkStoreColumnFamily())) {
            hash = chunks.write(chunk);
            chunks.flushDurable();
            shared.db().put(shared.columnFamily("ns"), nsKey, nsVal);
        }
        // Reopen the same directory — listColumnFamilies must rediscover the
        // flat CFs created on the first open, and all data must still be there.
        try (SharedRocksDb shared = SharedRocksDb.open(dir.toString(), FLAT_CFS);
                RocksNodeStore chunks =
                        new RocksNodeStore(shared.db(), shared.chunkStoreColumnFamily())) {
            assertTrue(chunks.read(hash).isPresent(), "chunk must survive reopen");
            assertArrayEquals(
                    nsVal,
                    shared.db().get(shared.columnFamily("ns"), nsKey),
                    "named-CF value must survive reopen");
        }
    }

    @Test
    void reopening_with_no_extra_cfs_still_finds_existing_ones(@TempDir Path dir) throws Exception {
        try (SharedRocksDb shared = SharedRocksDb.open(dir.toString(), FLAT_CFS)) {
            shared.db().put(shared.columnFamily("cspo"), new byte[] {1}, new byte[] {2});
        }
        // Even with an empty extra-CF list, an existing DB's CFs must be
        // rediscovered via listColumnFamilies, or the open would fail.
        try (SharedRocksDb shared = SharedRocksDb.open(dir.toString(), List.of())) {
            assertArrayEquals(
                    new byte[] {2}, shared.db().get(shared.columnFamily("cspo"), new byte[] {1}));
        }
    }

    @Test
    void unknown_column_family_is_rejected(@TempDir Path dir) throws Exception {
        try (SharedRocksDb shared = SharedRocksDb.open(dir.toString(), FLAT_CFS)) {
            assertThrows(IllegalArgumentException.class, () -> shared.columnFamily("no-such-cf"));
        }
    }
}
