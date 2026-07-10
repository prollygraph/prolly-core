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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDBException;

/**
 * Covers the {@link RocksNodeStore} <b>construction-config</b> branches added by the bulk-load +
 * read-path-tuning work (the storage branch-coverage regression — {@code
 * prolly-storage/plans/storage-branch-coverage-gate.md}). The stock and bulk ({@code
 * write-buffer.mb}) paths are already exercised ({@code RocksNodeStoreBulkMemoryBoundTest}, {@code
 * RocksNodeStoreCloseDuringCompactionTest}); this pins the rest of the constructor's config
 * branches by constructing a store under each relevant system property and round-tripping a chunk,
 * plus the failed-{@code open} cleanup catch.
 *
 * <p>Each test sets the property, constructs (which selects the branch), round-trips, and clears
 * the property in a {@code finally} so the JVM-global property does not leak into other tests.
 */
class RocksNodeStoreConstructionConfigTest {

    /**
     * Read-path tuning via the explicit block cache: {@code cacheMb>0} → LRU cache + bloom + pinned
     * L0.
     */
    @Test
    void readOptBlockCacheConfig_roundTrips(@TempDir Path dir) throws Exception {
        withProps(
                () -> roundTrip(dir.resolve("read-opt").toString()),
                "prolly.rocksdb.block-cache.mb",
                "16");
    }

    /** Bloom explicitly disabled ({@code bloom.bits=0}) while a block cache is configured. */
    @Test
    void blockCacheWithBloomDisabled_roundTrips(@TempDir Path dir) throws Exception {
        withProps(
                () -> roundTrip(dir.resolve("no-bloom").toString()),
                "prolly.rocksdb.block-cache.mb",
                "16",
                "prolly.rocksdb.bloom.bits",
                "0");
    }

    /**
     * The bare {@code read-opt=true} flag with no explicit cache → table-format block, null cache.
     */
    @Test
    void readOptFlagWithoutCache_roundTrips(@TempDir Path dir) throws Exception {
        withProps(
                () -> roundTrip(dir.resolve("read-opt-flag").toString()),
                "prolly.rocksdb.read-opt",
                "true");
    }

    /**
     * Statistics recorder on → the {@code statistics != null} branch + the full-stats dump path.
     */
    @Test
    void statisticsConfig_roundTripsAndDumpsStats(@TempDir Path dir) throws Exception {
        withProps(
                () -> {
                    try (RocksNodeStore store =
                            new RocksNodeStore(dir.resolve("stats").toString())) {
                        byte[] h = store.write(MemorySegment.ofArray(new byte[] {1, 2, 3, 4}));
                        assertTrue(store.read(h).isPresent(), "round-trip under statistics config");
                        // Exercises the statistics-enabled branch of the full-stats dump.
                        assertTrue(
                                store.rocksDbFullStats().contains("rocksdb"),
                                "full stats should include the rocksdb stats sections");
                    }
                },
                "prolly.rocksdb.statistics",
                "true");
    }

    /**
     * Failed {@code open} with sub-objects allocated → the constructor's cleanup catch closes them
     * and rethrows. A second open at a path already locked by an open store throws {@link
     * RocksDBException}; bulk mode means the block cache / write-buffer-manager / bloom /
     * statistics are non-null, so the catch's null-guarded closes all execute.
     */
    @Test
    void failedOpenAtLockedPath_closesAllocatedHandlesAndRethrows(@TempDir Path dir)
            throws Exception {
        System.setProperty("prolly.rocksdb.write-buffer.mb", "1"); // bulk → sub-objects allocated
        System.setProperty("prolly.rocksdb.statistics", "true"); // statistics also allocated
        String path = dir.resolve("locked").toString();
        try (RocksNodeStore holder = new RocksNodeStore(path)) {
            // Second open at the same (locked) path must fail through the cleanup catch.
            assertThrows(RocksDBException.class, () -> new RocksNodeStore(path));
        } finally {
            System.clearProperty("prolly.rocksdb.write-buffer.mb");
            System.clearProperty("prolly.rocksdb.statistics");
        }
    }

    // ---- helpers ----

    private static void roundTrip(String path) throws Exception {
        try (RocksNodeStore store = new RocksNodeStore(path)) {
            byte[] h = store.write(MemorySegment.ofArray(new byte[] {9, 8, 7, 6, 5}));
            assertTrue(store.read(h).isPresent(), "written chunk must read back");
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Sets the given key/value system properties, runs the body, then clears them. */
    private static void withProps(ThrowingRunnable body, String... kv) throws Exception {
        for (int i = 0; i < kv.length; i += 2) System.setProperty(kv[i], kv[i + 1]);
        try {
            body.run();
        } finally {
            for (int i = 0; i < kv.length; i += 2) System.clearProperty(kv[i]);
        }
    }
}
