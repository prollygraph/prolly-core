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
package com.earasoft.prolly.multistore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.storage.SharedRocksResources;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step 2b of {@code core-resource-bounds-and-metrics.md}: {@code PerRepoRocksDbFactory.openAt(...,
 * shared)} wires the <b>shared</b> block cache + write-buffer-manager into each per-repo RocksDB's
 * options, so N warm per-repo databases share <b>one</b> bounded native budget (the multi-tenant
 * aggregate fix) instead of N independent default caches. The single most important production
 * invariant: two repos opened with one holder both report the shared cache capacity (the ceiling,
 * not RocksDB's small default); passing {@code null} preserves the prior per-instance behaviour.
 */
class PerRepoRocksDbFactorySharedBudgetTest {

    private static final long CACHE_BYTES = 64L << 20;

    private static long blockCacheCapacity(OpenRepoDb db) throws Exception {
        return db.db().getLongProperty(db.chunkColumnFamily(), "rocksdb.block-cache-capacity");
    }

    @Test
    void perRepoDatabasesShareOneBudget(@TempDir Path dir) throws Exception {
        try (SharedRocksResources shared =
                new SharedRocksResources(CACHE_BYTES, 16L << 20, 300, 10)) {
            OpenRepoDb a = PerRepoRocksDbFactory.openAt("repo-a", dir.resolve("a"), shared);
            OpenRepoDb b = PerRepoRocksDbFactory.openAt("repo-b", dir.resolve("b"), shared);
            try {
                assertEquals(
                        CACHE_BYTES,
                        blockCacheCapacity(a),
                        "the per-repo RocksDB wires the shared cache (the ceiling, not RocksDB's default)");
                assertEquals(
                        blockCacheCapacity(a),
                        blockCacheCapacity(b),
                        "both per-repo databases share the one budget");
            } finally {
                a.close();
                b.close();
            }
        } // holder closes the shared cache once, after both per-repo databases are closed
    }

    @Test
    void nullSharedKeepsPerInstanceDefaults(@TempDir Path dir) throws Exception {
        OpenRepoDb a = PerRepoRocksDbFactory.openAt("repo-a", dir.resolve("a"), null);
        try {
            assertTrue(
                    blockCacheCapacity(a) < CACHE_BYTES,
                    "null shared → RocksDB's per-instance default cache, not the shared ceiling");
        } finally {
            a.close();
        }
    }
}
