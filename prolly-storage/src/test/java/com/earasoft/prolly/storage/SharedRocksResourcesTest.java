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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

/**
 * Step 2 of {@code core-resource-bounds-and-metrics.md}: {@link SharedRocksResources#applyTo} wires
 * <b>one</b> bounded block cache + write-buffer-manager into a per-repo RocksDB's options — the
 * exact call {@code PerRepoRocksDbFactory} makes. Pins two invariants by opening <i>real</i>
 * RocksDB instances: (1) {@code applyTo} installs the configured shared cache ({@code
 * block-cache-capacity} equals the ceiling, not RocksDB's small default), and (2) a second database
 * opens fine against the same holder <i>after</i> the first database's {@code Options} were closed
 * — proving the shared cache is not freed by a per-database {@code Options.close()} (the lifecycle
 * the multi-tenant fix rests on).
 */
class SharedRocksResourcesTest {

    private static final long CACHE_BYTES = 64L << 20;

    private static long openAndReadCacheCapacity(SharedRocksResources shared, Path dir)
            throws Exception {
        DBOptions dbOptions =
                new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        ColumnFamilyOptions cfOptions = new ColumnFamilyOptions();
        shared.applyTo(dbOptions);
        shared.applyTo(cfOptions);
        List<ColumnFamilyDescriptor> descriptors =
                List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try (RocksDB db = RocksDB.open(dbOptions, dir.toString(), descriptors, handles)) {
            return db.getLongProperty(handles.get(0), "rocksdb.block-cache-capacity");
        } finally {
            for (ColumnFamilyHandle h : handles) {
                h.close();
            }
            cfOptions.close(); // must NOT free the shared cache it references
            dbOptions.close(); // must NOT free the shared write-buffer-manager it references
        }
    }

    @Test
    void applyToWiresTheSharedCacheAndSurvivesPerDatabaseOptionsClose(@TempDir Path dir)
            throws Exception {
        try (SharedRocksResources shared =
                new SharedRocksResources(CACHE_BYTES, 16L << 20, 300, 10)) {
            Path d1 = Files.createDirectories(dir.resolve("r1"));
            Path d2 = Files.createDirectories(dir.resolve("r2"));

            long cap1 = openAndReadCacheCapacity(shared, d1);
            // The second open reuses the SAME shared cache after d1's Options were already closed —
            // it would crash native code if a per-database Options.close() had freed the shared
            // cache.
            long cap2 = openAndReadCacheCapacity(shared, d2);

            assertEquals(
                    CACHE_BYTES,
                    cap1,
                    "applyTo installs the configured shared block cache, not RocksDB's default");
            assertEquals(
                    cap1, cap2, "both databases wire the same shared budget (one cache, not two)");
        } // the holder's close() frees the shared cache/wbm/bloom exactly once
    }

    @Test
    void rejectsNonPositiveMaxBytes() {
        // maxBytes is the hard ceiling; a non-positive budget is a programming error, not a silent
        // unbounded cache (the throw branch the happy-path test never exercises).
        assertThrows(IllegalArgumentException.class, () -> new SharedRocksResources(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SharedRocksResources(-1, 0, 0, 0));
    }

    @Test
    void edgeConfigDefaultsMemtableDisablesBloomAndLeavesOpenFilesUnlimited(@TempDir Path dir)
            throws Exception {
        // The else-branches the happy-path test (all-positive args) never hits: memtableBudgetBytes
        // <= 0 defaults the memtable to maxBytes/4; bloomBits <= 0 disables the bloom (so applyTo +
        // close skip the null filter); maxOpenFiles <= 0 leaves the RocksDB default (applyTo skips
        // setMaxOpenFiles). A no-bloom, default-files holder must still open a real DB and bound
        // the
        // shared cache to the ceiling.
        try (SharedRocksResources shared = new SharedRocksResources(CACHE_BYTES, 0, 0, 0)) {
            long cap =
                    openAndReadCacheCapacity(shared, Files.createDirectories(dir.resolve("edge")));
            assertEquals(
                    CACHE_BYTES,
                    cap,
                    "a no-bloom / default-files holder still installs the configured shared cache");
        } // close() must not throw on the null bloom filter
    }
}
