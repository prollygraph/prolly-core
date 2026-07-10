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

import org.jspecify.annotations.Nullable;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Cache;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Filter;
import org.rocksdb.LRUCache;
import org.rocksdb.WriteBufferManager;

/**
 * One bounded RocksDB native-memory budget <b>shared across many RocksDB instances</b> — the
 * multi-tenant native-memory fix ({@code core-resource-bounds-and-metrics.md} Step 2). A single
 * block cache + a single {@link WriteBufferManager} (memtables charged to that cache) are wired,
 * via {@link #applyTo(DBOptions)} / {@link #applyTo(ColumnFamilyOptions)}, into the {@code Options}
 * of every per-repo RocksDB (each opened by {@code PerRepoRocksDbFactory}). So the process's
 * aggregate RocksDB resident memory is bounded by this cache size <i>regardless of how many tenants
 * warm up</i> — instead of growing with the warm-store count (the measured vector: step 0 found a
 * single instance bounded, the aggregate unbounded).
 *
 * @apiNote Create one per process and {@code applyTo} every per-repo RocksDB's {@code DBOptions} +
 *     {@code ColumnFamilyOptions} before {@code RocksDB.open}. The opened databases reference these
 *     handles but do <b>not</b> own them — neither {@code DBOptions.close()} nor {@code
 *     ColumnFamilyOptions.close()} frees the cache/wbm/bloom. The cache/wbm therefore outlive every
 *     store; treat this holder as process-global (reclaimed at exit) or {@link #close()} it once,
 *     after every database using it has been closed (closing it while a database still reads
 *     through it would crash native code).
 * @implNote {@code maxBytes} is the hard ceiling: the block cache holds data blocks + (via {@code
 *     cacheIndexAndFilterBlocks}) index/filter blocks, and the {@code WriteBufferManager} charges
 *     memtable memory to the <i>same</i> cache, so total RocksDB native ≈ {@code maxBytes}. Level-0
 *     index/filter is left <b>un-pinned</b> so one busy tenant's compaction backlog cannot pin
 *     native memory past the shared budget.
 */
public final class SharedRocksResources implements AutoCloseable {

    private final Cache blockCache;
    private final WriteBufferManager writeBufferManager;
    private final @Nullable Filter bloomFilter; // null when bloomBits <= 0 (filter disabled)
    private final int maxOpenFiles;

    /**
     * @param maxBytes block-cache size — the shared hard ceiling on aggregate RocksDB native
     *     memory.
     * @param memtableBudgetBytes memtable cap charged to the cache; {@code <= 0} defaults to a
     *     quarter of {@code maxBytes}.
     * @param maxOpenFiles cap on open table readers per database ({@code <= 0} leaves the RocksDB
     *     default of unlimited — not recommended for many-tenant deployments).
     * @param bloomBits bloom-filter bits per key ({@code <= 0} disables the bloom filter).
     */
    public SharedRocksResources(
            long maxBytes, long memtableBudgetBytes, int maxOpenFiles, int bloomBits) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be > 0: " + maxBytes);
        }
        this.blockCache = new LRUCache(maxBytes);
        long memtable = memtableBudgetBytes > 0 ? memtableBudgetBytes : maxBytes / 4;
        this.writeBufferManager = new WriteBufferManager(memtable, blockCache);
        this.bloomFilter = bloomBits > 0 ? new BloomFilter((double) bloomBits) : null;
        this.maxOpenFiles = maxOpenFiles;
    }

    /** Wire the shared write-buffer-manager + {@code max_open_files} onto a database's options. */
    public void applyTo(DBOptions dbOptions) {
        dbOptions.setWriteBufferManager(writeBufferManager);
        if (maxOpenFiles > 0) {
            dbOptions.setMaxOpenFiles(maxOpenFiles);
        }
    }

    /**
     * Wire the shared block cache (with index/filter in-cache, L0 un-pinned) + bloom filter onto a
     * column family's options. Each column family gets its own {@link BlockBasedTableConfig}, but
     * they all reference the one shared {@link Cache}.
     */
    public void applyTo(ColumnFamilyOptions cfOptions) {
        BlockBasedTableConfig table = new BlockBasedTableConfig();
        table.setBlockCache(blockCache);
        if (bloomFilter != null) {
            table.setFilterPolicy(bloomFilter);
        }
        table.setCacheIndexAndFilterBlocks(true).setPinL0FilterAndIndexBlocksInCache(false);
        cfOptions.setTableFormatConfig(table);
    }

    /** Close the shared native handles. Call once, after every database using them is closed. */
    @Override
    public void close() {
        // The WriteBufferManager references the cache, so close it before the cache.
        writeBufferManager.close();
        blockCache.close();
        if (bloomFilter != null) {
            bloomFilter.close();
        }
    }
}
