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

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Cache;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.LRUCache;
import org.rocksdb.Statistics;
import org.rocksdb.WriteBufferManager;

/**
 * The {@code prolly.rocksdb.*} tuning knobs, read once and materialised into the native handles a
 * RocksDB open needs — usable by the <b>multi-column-family</b> opener as well as the single-family
 * one.
 *
 * <p><b>Why this exists.</b> {@link RocksNodeStore#RocksNodeStore(String)} honours five properties
 * (statistics, block-cache.mb, bloom.bits, write-buffer.mb, read-opt). {@link SharedRocksDb} built a
 * bare {@code new DBOptions()} and {@code new ColumnFamilyOptions()} and honoured none, so any
 * workload moved from a standalone store onto a shared database silently lost every knob and fell
 * back to RocksDB's implicit default cache. That asymmetry was the entire stated cost of ADR-0011's
 * option E (a column family for the class index) and the reason its open question 6 could not be
 * closed.
 *
 * <p><b>One budget per database, not per family.</b> A single {@link Cache} and a single {@link
 * BlockBasedTableConfig} are built here and applied to <em>every</em> column family, so N families
 * share one budget rather than reserving N. Reserving per family would understate memory by exactly
 * the family count — the shape {@code write-buffer.mb}'s memtable charging exists to prevent.
 *
 * <p><b>Ownership.</b> The native handles are owned by this object and released by {@link #close()},
 * which the opener must call after the database is closed (RocksDB's JNI bindings require handles
 * outlive the DB) or on a failed open.
 *
 * <p>Unset properties produce a tuning that applies nothing — {@link #isDefault()} is true and the
 * opener behaves byte-for-byte as it did before this class existed. That is the path every forge
 * deployment takes today.
 *
 * @implNote {@code RocksNodeStore(String)} predates this class and still builds its own handles
 *     inline, because its constructor interleaves them with two distinct failure-cleanup paths.
 *     {@code RocksTuningParityTest} pins the two against drift; migrating that constructor is parked
 *     rather than done here, since it is not on the path of the question this unblocks.
 */
public final class RocksTuning implements AutoCloseable {

    private final Statistics statistics;
    private final Cache blockCache;
    private final BloomFilter bloomFilter;
    private final WriteBufferManager writeBufferManager;
    private final BlockBasedTableConfig tableConfig;
    private final boolean bulk;

    private RocksTuning(
            Statistics statistics,
            Cache blockCache,
            BloomFilter bloomFilter,
            WriteBufferManager writeBufferManager,
            BlockBasedTableConfig tableConfig,
            boolean bulk) {
        this.statistics = statistics;
        this.blockCache = blockCache;
        this.bloomFilter = bloomFilter;
        this.writeBufferManager = writeBufferManager;
        this.tableConfig = tableConfig;
        this.bulk = bulk;
    }

    /**
     * Read the knobs from system properties and build the matching native handles. Mirrors {@code
     * RocksNodeStore(String)}'s interpretation exactly, including the derived cache size in bulk
     * mode.
     */
    public static RocksTuning fromSystemProperties() {
        Statistics statistics =
                Boolean.getBoolean("prolly.rocksdb.statistics") ? new Statistics() : null;
        int cacheMb = Integer.getInteger("prolly.rocksdb.block-cache.mb", 0);
        int bloomBits = Integer.getInteger("prolly.rocksdb.bloom.bits", 10);
        long writeBufMb = Long.getLong("prolly.rocksdb.write-buffer.mb", -1L);
        boolean bulk = writeBufMb > 0;

        if (cacheMb <= 0 && !bulk && !Boolean.getBoolean("prolly.rocksdb.read-opt")) {
            return new RocksTuning(statistics, null, null, null, null, false);
        }

        // Bulk mode needs a block cache to charge memtable memory against (the Java
        // WriteBufferManager requires one); default it to 4x the memtable cap so memtables are at
        // most a quarter of the budget when read-opt didn't size the cache explicitly.
        long cacheBytes = cacheMb > 0 ? (long) cacheMb << 20 : (bulk ? (writeBufMb << 20) * 4 : 0L);
        Cache blockCache = cacheBytes > 0 ? new LRUCache(cacheBytes) : null;
        BloomFilter bloomFilter = bloomBits > 0 ? new BloomFilter((double) bloomBits) : null;

        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();
        if (blockCache != null) tableConfig.setBlockCache(blockCache);
        if (bloomFilter != null) tableConfig.setFilterPolicy(bloomFilter);
        // index/filter live IN the bounded block cache. Pin L0's for read speed — but NOT in bulk
        // mode: a bulk load's compaction backlog piles up L0 files, and pinned (unevictable) L0
        // index/filter would then grow past the cache budget, which is the OOM this mode prevents.
        tableConfig.setCacheIndexAndFilterBlocks(true).setPinL0FilterAndIndexBlocksInCache(!bulk);

        WriteBufferManager wbm =
                bulk && blockCache != null
                        ? new WriteBufferManager(writeBufMb << 20, blockCache)
                        : null;

        return new RocksTuning(statistics, blockCache, bloomFilter, wbm, tableConfig, bulk);
    }

    /** True when no property was set — the opener should behave exactly as it did before. */
    public boolean isDefault() {
        return statistics == null && tableConfig == null && writeBufferManager == null;
    }

    /** Database-wide settings: statistics and the memtable budget. */
    public void applyTo(DBOptions dbOptions) {
        if (statistics != null) dbOptions.setStatistics(statistics);
        if (writeBufferManager != null) dbOptions.setWriteBufferManager(writeBufferManager);
    }

    /**
     * Per-family settings: the block-based table config carrying the ONE shared cache and bloom.
     * Applying the same config to every family is what makes the budget shared rather than
     * per-family.
     */
    public void applyTo(ColumnFamilyOptions cfOptions) {
        if (tableConfig != null) cfOptions.setTableFormatConfig(tableConfig);
    }

    /** The block cache, or {@code null} when unconfigured. Exposed for gauges and for tests. */
    public Cache blockCache() {
        return blockCache;
    }

    /** True when {@code write-buffer.mb} put this open in memory-bounded bulk-load mode. */
    public boolean bulk() {
        return bulk;
    }

    /**
     * Release every native handle. Call after the database is closed — RocksDB's JNI bindings
     * require the handles to outlive the DB — or on a failed open.
     */
    @Override
    public void close() {
        if (writeBufferManager != null) writeBufferManager.close();
        if (blockCache != null) blockCache.close();
        if (bloomFilter != null) bloomFilter.close();
        if (statistics != null) statistics.close();
    }
}
