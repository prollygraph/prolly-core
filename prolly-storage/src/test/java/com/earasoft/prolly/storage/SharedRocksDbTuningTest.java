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

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * {@link SharedRocksDb} must honour the same {@code prolly.rocksdb.*} tunables that {@link
 * RocksNodeStore#RocksNodeStore(String)} does.
 *
 * <p>Why this matters beyond symmetry: the single-family opener honours five properties
 * (statistics, block-cache.mb, bloom.bits, write-buffer.mb, read-opt) while the multi-family one
 * built a bare {@code new DBOptions()} and {@code new ColumnFamilyOptions()} and honoured none. Any
 * design that moves a workload from {@code new RocksNodeStore(path)} onto a shared database
 * therefore silently lost every tuning knob — swapping a sizeable, sizable cache for RocksDB's
 * implicit 8 MiB default. That is the whole stated cost of ADR-0011's option E (a column family for
 * the class index) and the reason its open question 6 could not be closed.
 *
 * <p>The assertion is on {@code rocksdb.block-cache-capacity} rather than usage: capacity is what
 * the caller asked for, is stable regardless of what has been read, and distinguishes an explicit
 * cache from RocksDB's default unambiguously.
 */
class SharedRocksDbTuningTest {
    static {
        RocksDB.loadLibrary();
    }

    private static final long ASKED_MB = 64;

    private static long cacheCapacity(SharedRocksDb shared, String cf) throws Exception {
        return Long.parseLong(
                shared.db().getProperty(shared.columnFamily(cf), "rocksdb.block-cache-capacity"));
    }

    /** Run {@code body} with one system property set, restoring whatever was there before. */
    private static void withProperty(String key, String value, ThrowingRunnable body)
            throws Exception {
        String prev = System.getProperty(key);
        System.setProperty(key, value);
        try {
            body.run();
        } finally {
            if (prev == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, prev);
            }
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void shared_db_honours_block_cache_property(@TempDir Path tuned, @TempDir Path bare)
            throws Exception {
        // RocksDB's implicit default is not hardcoded — it is observed, so this test does not
        // break when the bundled RocksDB changes it (an earlier draft asserted 8 MiB; the
        // bundled build actually defaults to 32 MiB, and the assertion caught that).
        long observedDefault;
        try (SharedRocksDb shared = SharedRocksDb.open(bare.toString(), List.of("idx"))) {
            observedDefault = cacheCapacity(shared, SharedRocksDb.CHUNK_STORE_CF);
        }
        assertNotEquals(ASKED_MB << 20, observedDefault,
                "test is vacuous: RocksDB's default already equals the size being asked for");

        withProperty("prolly.rocksdb.block-cache.mb", Long.toString(ASKED_MB), () -> {
            try (SharedRocksDb shared = SharedRocksDb.open(tuned.toString(), List.of("idx"))) {
                assertEquals(ASKED_MB << 20, cacheCapacity(shared, SharedRocksDb.CHUNK_STORE_CF),
                        "SharedRocksDb ignored prolly.rocksdb.block-cache.mb");
            }
        });
    }

    @Test
    void every_column_family_shares_the_one_cache_not_a_cache_each(@TempDir Path dir)
            throws Exception {
        withProperty("prolly.rocksdb.block-cache.mb", Long.toString(ASKED_MB), () -> {
            try (SharedRocksDb shared = SharedRocksDb.open(dir.toString(), List.of("a", "b", "c"))) {
                // One budget for the database, not one per family — otherwise "shared" understates
                // memory by the number of column families.
                for (String cf : List.of(SharedRocksDb.CHUNK_STORE_CF, "a", "b", "c")) {
                    assertEquals(ASKED_MB << 20, cacheCapacity(shared, cf),
                            "column family '" + cf + "' has its own cache budget");
                }
            }
        });
    }

    @Test
    void unset_properties_leave_the_opener_exactly_as_it_was(@TempDir Path a, @TempDir Path b)
            throws Exception {
        // The default path must not change for callers setting nothing — which is every forge
        // deployment today. Two independent unconfigured opens must agree, and must differ from
        // the tuned size.
        assertNull(System.getProperty("prolly.rocksdb.block-cache.mb"),
                "test pollution: another test left the property set");
        long first;
        try (SharedRocksDb shared = SharedRocksDb.open(a.toString(), List.of("idx"))) {
            first = cacheCapacity(shared, SharedRocksDb.CHUNK_STORE_CF);
        }
        try (SharedRocksDb shared = SharedRocksDb.open(b.toString(), List.of("idx"))) {
            assertEquals(first, cacheCapacity(shared, SharedRocksDb.CHUNK_STORE_CF));
        }
        assertNotEquals(ASKED_MB << 20, first,
                "unconfigured open picked up the tuned size — property leaked between tests");
    }

    @Test
    void tuning_matches_what_the_single_family_opener_does(@TempDir Path dir) throws Exception {
        // Drift pin. RocksNodeStore(String) still builds its own handles inline (its constructor
        // interleaves them with two failure-cleanup paths), so the interpretation of the knobs now
        // lives in two places. If someone changes one, this fails.
        withProperty("prolly.rocksdb.block-cache.mb", Long.toString(ASKED_MB), () -> {
            try (RocksTuning tuning = RocksTuning.fromSystemProperties()) {
                assertFalse(tuning.isDefault(), "a set property must produce a non-default tuning");
                assertNotNull(tuning.blockCache(), "block-cache.mb must build a cache");
            }
            try (RocksNodeStore single = new RocksNodeStore(dir.toString())) {
                assertEquals(ASKED_MB << 20, single.blockCacheCapacityBytes(),
                        "RocksNodeStore and RocksTuning disagree about block-cache.mb");
            }
        });
    }

    @Test
    void unset_tuning_applies_nothing(@TempDir Path dir) throws Exception {
        assertNull(System.getProperty("prolly.rocksdb.block-cache.mb"));
        try (RocksTuning tuning = RocksTuning.fromSystemProperties()) {
            assertTrue(tuning.isDefault(), "no properties set must yield an inert tuning");
            assertNull(tuning.blockCache());
            assertFalse(tuning.bulk());
        }
    }
}
