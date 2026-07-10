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

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jspecify.annotations.Nullable;
import org.rocksdb.*;

/**
 * The RocksDB-backed implementation of the content-addressed {@link NodeStore}.
 *
 * <p>An optional, caller-supplied {@link NodeCache} short-circuits repeated reads for hot nodes —
 * pass it to {@link #setNodeCache(NodeCache)} after construction. The cache is consulted on {@link
 * #read} and populated on a read-miss (and by writes). When {@link #setVerifyOnRead(boolean)
 * verify-on-read} is enabled (the production default), a node read from RocksDB is content-verified
 * — re-hashed and checked against the requested key — <b>before</b> it is cached or returned, so
 * each node is verified exactly once on its way in and cache hits are served trusted without
 * re-hashing (ADR-0064: verify below the cache, not as an outer decorator that would re-hash every
 * cache hit).
 *
 * <h3>Standalone vs. shared</h3>
 *
 * <p>Two constructors. {@link #RocksNodeStore(String)} opens — and owns — a dedicated
 * single-column-family RocksDB; this is the standalone mode used by almost every caller. {@link
 * #RocksNodeStore(RocksDB, ColumnFamilyHandle)} instead stores chunks in one column family of an
 * <em>externally</em>-opened RocksDB, so the chunk store can share a single engine (one write-ahead
 * log, one backup) with the unversioned flat Sail — see {@link SharedRocksDb}. In shared mode this
 * store does <em>not</em> own the database: {@link #close()} releases only its own {@code
 * WriteOptions} and pending batch, never the shared database.
 *
 * @implNote <b>Collaborators:</b> {@link RocksDB} + a {@link ColumnFamilyHandle} (the backing
 *     store), {@link NodeCache} (the optional read-through cache), and {@code HashUtils} (content
 *     hashing on write). <b>Dependents:</b> {@code Database}, {@code StaticMap}/ {@code
 *     TreeMutator} (read and write nodes), and {@code GarbageCollector} (iterate every key and
 *     delete the unreachable ones). In shared mode it is a non-owning co-tenant of {@link
 *     SharedRocksDb}.
 */
public class RocksNodeStore implements NodeStore, AutoCloseable {
    private final RocksDB db;

    /** Column family chunks live in — the default column family when standalone. */
    private final ColumnFamilyHandle cf;

    /** True only when this store opened the DB itself and must close it. */
    private final boolean ownsDb;

    private volatile @Nullable NodeCache cache; // optional; null = no caching

    // When true, read() re-hashes a disk-read node and checks it against the requested key before
    // caching/returning it — bit-rot fails closed. Verified once per node on the disk path; cache
    // hits are trusted (skip the re-hash). Off by default; the production boot turns it on via
    // config
    // (ADR-0064 — verify below the cache).
    private volatile boolean verifyOnRead;

    /**
     * Process-global registry of live stores, for the <b>aggregate</b> RocksDB-native gauges (the
     * multi-tenant footprint = N warm per-repo instances, each with its own memtables/cache/table-
     * readers). A store registers on successful construction and deregisters in {@link #close()}.
     * <b>Weak</b> keys so a store abandoned without {@code close()} drops at garbage collection
     * rather than leaking the registry forever; {@code synchronized} for concurrent
     * register/iterate.
     */
    private static final Set<RocksNodeStore> LIVE =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * Guards the native-handle lifecycle. Every operation that calls into the RocksDB {@code db} /
     * {@code cf} handles takes the READ lock and checks {@link #closed}; {@link #close()} takes the
     * WRITE lock, so it waits for all in-flight native calls to drain before it frees the handles,
     * then rejects any later call with a {@link StoreClosedException}. This converts a
     * use-after-close (a native {@code SIGSEGV}) into a catchable Java exception. A {@link
     * ReentrantReadWriteLock} (not a {@code StampedLock}) is used for reentrancy safety — its read
     * lock is uncontended in the common case and negligible next to the per-write content hash;
     * {@code close()} is the only writer. (plans/repo-teardown-quiesce.md D-3 — defense-in-depth
     * for the repo-drop race; the root fix is draining in-flight work before the directory is
     * deleted, D-2.)
     */
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    private volatile boolean closed = false;

    // Default WriteOptions — WAL enabled, so a batch write is exactly as
    // durable as the per-chunk db.put it replaces, just grouped. Constructed
    // in the constructor body, AFTER RocksDB.open has loaded the native
    // library (a field initializer would run too early → UnsatisfiedLinkError).
    private final WriteOptions writeOptions;

    // The Options used to open a standalone DB. RocksDB.open does NOT take ownership of the
    // Options,
    // so the opener must keep it alive and close it (here, in close(), after the DB). Null in
    // shared
    // mode (the owner supplies DBOptions/ColumnFamilyOptions). Closing it was previously omitted —
    // a one-time native (off-heap) leak per standalone store open.
    private final @Nullable Options options;

    /**
     * RocksDB's full statistics recorder — every ticker (block-cache hit/miss, bytes read/written,
     * bloom-filter, stalls, compaction) and histogram. Recording is a small per-op tax, so it is
     * <b>opt-in</b> via {@code -Dprolly.rocksdb.statistics=true} (default off — production
     * untouched); non-null only on a {@link #RocksNodeStore(String) standalone} store with the flag
     * set. Exposed by {@link #rocksDbFullStats()}; the property dump there works regardless of this
     * flag.
     */
    private final @Nullable Statistics statistics;

    /**
     * Opt-in read-path tuning for the chunk store's LSM {@code Get} cost — the bottleneck the
     * 2026-06-13 throughput probe attributed: the commit spine-walk's node reads grow ~9× in
     * latency as sorted-string-table files accumulate, because RocksDB's <b>stock</b> table config
     * (which this store used) has <b>no bloom filter</b> (every {@code Get} index-probes all
     * overlapping level-0 files), a <b>32 MiB block cache</b>, and {@code
     * cache_index_and_filter_blocks=false}. When enabled, a {@code Get} can skip non-matching files
     * (bloom) and finds blocks resident (larger pinned cache). Off by default (stock RocksDB) so
     * the cost/benefit is an explicit, measured choice; enable via {@code
     * -Dprolly.rocksdb.block-cache.mb=<N>} (LRU cache MiB; {@code >0} turns the tuned config on)
     * and {@code -Dprolly.rocksdb.bloom.bits=<B>} (bloom bits/key, default 10; {@code 0} = none),
     * or {@code -Dprolly.rocksdb.read-opt=true} for bloom-only. These native handles are owned here
     * and closed (after the DB) in {@link #close()}; null when off.
     */
    private final @Nullable Cache blockCache;

    private final @Nullable Filter bloomFilter;

    /**
     * Opt-in <b>bulk-load memory bound</b> — the structural fix that makes peak process memory a
     * constant independent of graph size (so a large bulk load cannot out-of-memory at the cap). A
     * 2026-06-13 acceptance proved the need: at 10.8M statements the batched path's memory is
     * dominated not by the live working set (~1.1 GiB, O(batch)) but by terms that scale with the
     * <i>store</i> — RocksDB memtables and the block cache (index/filter charged into it) — and a
     * compaction backlog that piles up level-0 files whose pinned index/filter would otherwise grow
     * past the cache budget. Enabling {@code -Dprolly.rocksdb.write-buffer.mb=<N>} ({@code >0}):
     *
     * <ul>
     *   <li>caps memtable memory at {@code N} MiB and <b>charges it to the block cache</b>, so
     *       memtables + data blocks + index/filter share <b>one</b> hard budget (the block cache
     *       size) instead of each growing independently with the store;
     *   <li>leaves level-0 index/filter blocks <b>evictable</b> (un-pinned), so a compaction
     *       backlog cannot grow pinned native memory without bound;
     *   <li>disables the write-ahead log on this store's writes (the bulk load is re-runnable on a
     *       crash — atomicity is per-commit, the whole load is replayable).
     * </ul>
     *
     * <p>So total RocksDB resident memory ≈ the block cache size; with a fixed {@code -Xmx} the
     * whole process footprint is bounded regardless of how many statements are loaded. Keep {@code
     * write-buffer.mb} a fraction of {@code block-cache.mb} (the memtables reserve that much
     * <i>of</i> the cache, leaving the rest for read blocks). This native handle is owned here and
     * closed (after the DB) in {@link #close()}; null when off. See {@code
     * plans/prolly-bulk-load.md} Phase 1.
     */
    private final @Nullable WriteBufferManager writeBufferManager;

    // Per-thread write batch. Non-null between beginWriteBatch/endWriteBatch on
    // that thread — write() buffers into it instead of an immediate db.put, so
    // a whole tree build commits as one RocksDB batch (one WAL record, one
    // memtable insertion run) rather than N individual puts. Thread-local
    // because the Sail commits its tables in parallel.
    private final ThreadLocal<WriteBatch> pendingBatch = new ThreadLocal<>();

    /**
     * Total in-flight {@link WriteBatch} chunk-bytes across ALL threads (the sum of every thread's
     * un-flushed {@link #pendingBatch}). The Sail builds its ~7 trees concurrently (dict + 4
     * indexes + namespaces + stats), each on its own thread with its own per-thread batch into this
     * <b>single column-family</b> store — so without a shared budget the native WriteBatch peak is
     * {@link #batchFlushBytes} × thread-count (~7×, the 2026-06-13 whole-file ingest's native
     * {@code std::bad_alloc}). This shared counter lets {@link #write} flush a thread's batch once
     * the <i>global</i> in-flight total reaches the budget, bounding total native WriteBatch memory
     * to ≈ {@code batchFlushBytes} <b>regardless of commit concurrency</b> — at the cost of
     * smaller, more frequent flushes under high concurrency (the no-OOM-over-throughput trade; the
     * commit is a small fraction of ingest time per the Step 4c-2 phase split). Correctness is
     * unchanged: a mid-build flush is the same content-addressed write {@link #write}/{@link
     * #endWriteBatch} already do, so the built root is byte-identical (pinned by {@code
     * RocksNodeStoreBatchFlushDifferentialTest}).
     */
    private final AtomicLong pendingBatchBytes = new AtomicLong();

    /**
     * Per-thread tally of {@link #pendingBatch}'s current chunk-bytes, feeding the global budget.
     */
    private final ThreadLocal<long[]> threadBatchBytes = ThreadLocal.withInitial(() -> new long[1]);

    /**
     * No-OOM safety net (the flush-window sibling; the upstream bulk-load plan D-7). A whole tree
     * build buffers every new chunk in one {@link WriteBatch} (above) until {@link #endWriteBatch};
     * for a huge bulk build that batch is <em>unbounded native memory</em> — the commit-build RSS
     * wall. When the batch grows past this many bytes, {@link #write} flushes and resets it
     * mid-build, bounding the batch to ≈ this size. Default 128 MiB; override via {@code
     * prolly.nodestore.batch.flush.bytes} ({@code 0} disables, restoring the one-batch-per-build
     * behavior). Safe to split a build's batch: {@code TreeMutator} never re-reads a chunk it just
     * wrote, and the new root is not referenced until the caller advances it — so a mid-build
     * failure leaves only harmless content-addressed orphans (collectible by GC), exactly as the
     * single-batch path already documented.
     */
    private static final long BATCH_FLUSH_BYTES_DEFAULT = resolveBatchFlushBytes();

    private volatile long batchFlushBytes = BATCH_FLUSH_BYTES_DEFAULT;

    private static long resolveBatchFlushBytes() {
        Long v = Long.getLong("prolly.nodestore.batch.flush.bytes");
        return v != null ? v : 128L * 1024 * 1024;
    }

    /**
     * Override the per-build WriteBatch flush threshold (bytes; {@code 0} disables). For tests +
     * tuning.
     */
    public void setBatchFlushBytes(long bytes) {
        this.batchFlushBytes = bytes;
    }

    /**
     * Test-only: the current global in-flight WriteBatch byte total (shared across commit threads).
     */
    long pendingBatchBytesForTest() {
        return pendingBatchBytes.get();
    }

    /**
     * Test-only: whether the bulk-load memory bound is active — a {@link WriteBufferManager} is
     * installed (memtable memory capped + charged to the block cache) and the write-ahead log is
     * disabled. See {@link #writeBufferManager}.
     */
    boolean bulkModeActiveForTest() {
        return writeBufferManager != null && writeOptions.disableWAL();
    }

    /**
     * Standalone: open (and own) a dedicated RocksDB at {@code path}. Chunks live in the default
     * column family, so a database created here is also readable as the chunk-store CF of a {@link
     * SharedRocksDb}.
     */
    public RocksNodeStore(String path) throws RocksDBException {
        Options options = new Options().setCreateIfMissing(true);
        this.statistics = Boolean.getBoolean("prolly.rocksdb.statistics") ? new Statistics() : null;
        if (statistics != null) options.setStatistics(statistics);
        int cacheMb = Integer.getInteger("prolly.rocksdb.block-cache.mb", 0);
        int bloomBits = Integer.getInteger("prolly.rocksdb.bloom.bits", 10);
        long writeBufMb = Long.getLong("prolly.rocksdb.write-buffer.mb", -1L);
        boolean bulk = writeBufMb > 0; // bulk-load memory-bounding mode (see #writeBufferManager)
        if (cacheMb > 0 || bulk || Boolean.getBoolean("prolly.rocksdb.read-opt")) {
            // Bulk mode needs a block cache to charge memtable memory against (the Java
            // WriteBufferManager requires one); default it to 4× the memtable cap so memtables are
            // at
            // most a quarter of the budget when read-opt didn't size the cache explicitly.
            long cacheBytes =
                    cacheMb > 0 ? (long) cacheMb << 20 : (bulk ? (writeBufMb << 20) * 4 : 0L);
            this.blockCache = cacheBytes > 0 ? new LRUCache(cacheBytes) : null;
            this.bloomFilter = bloomBits > 0 ? new BloomFilter((double) bloomBits) : null;
            BlockBasedTableConfig t = new BlockBasedTableConfig();
            if (blockCache != null) t.setBlockCache(blockCache);
            if (bloomFilter != null) t.setFilterPolicy(bloomFilter);
            // index/filter live IN the bounded block cache. Pin L0's for read speed — but NOT in
            // bulk
            // mode: a bulk load's compaction backlog piles up L0 files, and pinned (unevictable) L0
            // index/filter would then grow past the cache budget → the OOM this mode prevents.
            t.setCacheIndexAndFilterBlocks(true).setPinL0FilterAndIndexBlocksInCache(!bulk);
            options.setTableFormatConfig(t);
        } else {
            this.blockCache = null;
            this.bloomFilter = null;
        }
        if (bulk && blockCache != null) {
            // Cap memtable memory and charge it to the block cache → one bounded RocksDB RAM
            // budget.
            this.writeBufferManager = new WriteBufferManager(writeBufMb << 20, blockCache);
            options.setWriteBufferManager(writeBufferManager);
        } else {
            this.writeBufferManager = null;
        }
        try {
            this.db = RocksDB.open(options, path);
        } catch (RuntimeException | RocksDBException e) {
            // Failed open: close everything allocated above so a failed construction does not leak
            // native (off-heap) memory. These fields are all definitely-assigned before this point;
            // close() is never called on a constructor that threw, so this is the only cleanup
            // hook.
            if (writeBufferManager != null) writeBufferManager.close();
            if (blockCache != null) blockCache.close();
            if (bloomFilter != null) bloomFilter.close();
            if (statistics != null) statistics.close();
            options.close();
            throw e;
        }
        this.cf = db.getDefaultColumnFamily();
        this.ownsDb = true;
        this.writeOptions = new WriteOptions();
        if (bulk) writeOptions.setDisableWAL(true); // bulk load is re-runnable on crash
        this.options = options; // kept alive for the DB's lifetime; closed in close()
        try {
            verifyOrStampStoreFormat(db, cf);
        } catch (RuntimeException e) {
            // Incompatible/unversioned store: close everything this constructor opened (close()
            // never
            // runs on a constructor that threw) so a rejected open leaks no native memory.
            db.close();
            if (writeBufferManager != null) writeBufferManager.close();
            if (blockCache != null) blockCache.close();
            if (bloomFilter != null) bloomFilter.close();
            if (statistics != null) statistics.close();
            writeOptions.close();
            options.close();
            throw e;
        }
        LIVE.add(this); // register for the aggregate native gauges (deregistered in close())
    }

    /**
     * Shared: store chunks in {@code cf} of an externally-opened {@code db}. The caller (typically
     * {@link SharedRocksDb}) owns the database and the column-family handle and is responsible for
     * closing them; {@link #close()} here releases only this store's own {@code WriteOptions} and
     * pending batch.
     */
    public RocksNodeStore(RocksDB db, ColumnFamilyHandle cf) {
        this.db = db;
        this.cf = cf;
        this.ownsDb = false;
        this.writeOptions = new WriteOptions();
        this.statistics =
                null; // shared mode: the owner configures the engine, including statistics
        this.blockCache = null; // shared mode: the owner configures the table format / block cache
        this.bloomFilter = null;
        this.writeBufferManager = null; // shared mode: the owner configures memtable budgeting
        this.options = null; // shared mode: the owner owns DBOptions/ColumnFamilyOptions
        try {
            verifyOrStampStoreFormat(db, cf);
        } catch (RuntimeException e) {
            // Incompatible/unversioned per-repo store: release only our own WriteOptions (the owner
            // owns the shared db) and reject the construction.
            writeOptions.close();
            throw e;
        }
        LIVE.add(this); // register for the aggregate native gauges (deregistered in close())
    }

    // ----- store-level format markers (core-format-versioning Steps 1 + 4) -----

    /**
     * The store's format-version marker key. A non-20-byte key, so the {@code GarbageCollector}'s
     * "delete unreachable 20-byte hash keys" sweep skips it — the same length guard that already
     * protects the manifest's {@code ref:} keys. Lives in the chunk column family alongside chunks.
     */
    static final byte[] FORMAT_VERSION_KEY =
            "prolly_format_version".getBytes(StandardCharsets.UTF_8);

    /**
     * The store's hash-algorithm marker key ({@code core-format-versioning.md} Step 4). Records
     * {@link HashAlgorithm#id()} so a store written with a different content-address algorithm
     * fails closed on open — defense in depth against a hash change that did not also bump {@link
     * FormatVersion#CORE_FORMAT_VERSION}, and self-description for a future multi-algorithm engine.
     * Non-20-byte (garbage-collector-skipped), like the version key.
     */
    static final byte[] HASH_ALGORITHM_KEY =
            "prolly_hash_algorithm".getBytes(StandardCharsets.UTF_8);

    /**
     * Verify (or, for a fresh store, stamp) the store-level format markers on open — the broadest +
     * cheapest format guard ({@code core-format-versioning.md} D-3), run <i>before any chunk is
     * read</i>. A marker that disagrees with {@link FormatVersion#CORE_FORMAT_VERSION} / {@link
     * HashAlgorithm#CURRENT}, or data with no markers (a pre-versioning store), fails closed with
     * {@link UnsupportedFormatException}; a brand-new (empty) store is stamped with both.
     *
     * <p>Both markers are decided <b>together</b>: a fresh (empty) store is stamped with both; an
     * existing store must carry — and match — both. (Deciding them separately would let stamping
     * the first make the column family look non-empty to the second.)
     */
    static void verifyOrStampStoreFormat(RocksDB db, ColumnFamilyHandle cf) {
        try {
            byte[] ver = db.get(cf, FORMAT_VERSION_KEY);
            byte[] algo = db.get(cf, HASH_ALGORITHM_KEY);
            if (ver != null || algo != null) {
                // An existing (stamped) store: both markers must be present + match.
                verifyFormatVersion(ver);
                verifyHashAlgorithm(algo);
                return;
            }
            // Neither marker: a brand-new (empty) store is stamped with both; data with no markers
            // is a pre-versioning store and fails closed.
            if (cfHasAnyKey(db, cf)) {
                throw new UnsupportedFormatException(
                        "unversioned store format (written before format versioning, or by a"
                                + " different engine); back up the store and restore it with this"
                                + " engine version");
            }
            db.put(
                    cf,
                    FORMAT_VERSION_KEY,
                    Integer.toString(FormatVersion.CORE_FORMAT_VERSION)
                            .getBytes(StandardCharsets.UTF_8));
            db.put(
                    cf,
                    HASH_ALGORITHM_KEY,
                    Integer.toString(HashAlgorithm.CURRENT.id()).getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw rethrow("RocksNodeStore.verifyOrStampStoreFormat", e);
        }
    }

    private static void verifyFormatVersion(byte[] marker) {
        if (marker == null) {
            throw new UnsupportedFormatException(
                    "store format-version marker missing (a partially-initialised or pre-versioning"
                            + " store); back up the store and restore it with this engine version");
        }
        int found = parseMarker(marker, "format-version");
        if (found != FormatVersion.CORE_FORMAT_VERSION) {
            throw new UnsupportedFormatException(
                    "incompatible store format version "
                            + found
                            + " (this engine reads/writes version "
                            + FormatVersion.CORE_FORMAT_VERSION
                            + "); back up the store and restore it with a matching engine version");
        }
    }

    private static void verifyHashAlgorithm(byte[] marker) {
        if (marker == null) {
            throw new UnsupportedFormatException(
                    "store hash-algorithm marker missing (a partially-initialised or pre-versioning"
                            + " store); back up the store and restore it with this engine version");
        }
        int found = parseMarker(marker, "hash-algorithm");
        if (found != HashAlgorithm.CURRENT.id()) {
            throw new UnsupportedFormatException(
                    "incompatible store hash algorithm id "
                            + found
                            + " (this engine uses id "
                            + HashAlgorithm.CURRENT.id()
                            + " = "
                            + HashAlgorithm.CURRENT.messageDigestAlgorithm()
                            + "/"
                            + HashAlgorithm.CURRENT.length()
                            + "); back up the store and restore it with a matching engine version");
        }
    }

    private static int parseMarker(byte[] marker, String label) {
        try {
            return Integer.parseInt(new String(marker, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException nfe) {
            throw new UnsupportedFormatException(
                    "store "
                            + label
                            + " marker is unreadable; back up the store and restore it with a"
                            + " matching engine version");
        }
    }

    private static boolean cfHasAnyKey(RocksDB db, ColumnFamilyHandle cf) {
        try (RocksIterator it = db.newIterator(cf)) {
            it.seekToFirst();
            return it.isValid();
        }
    }

    public RocksDB db() {
        return db;
    }

    /**
     * Total on-disk sorted-string-table bytes for the chunk column family — telemetry for the
     * {@code prolly.store.bytes} gauge. Because the prolly tree is copy-on-write and garbage
     * collection is not yet wired, every commit adds node versions and this only grows: alert on
     * the <i>growth rate</i> to catch a disk filling before it does. Reads RocksDB's {@code
     * rocksdb.total-sst-files-size} property; {@code 0} if unavailable (e.g. the property is
     * disabled or the engine is closing).
     */
    public long totalSstBytes() {
        return prop("rocksdb.total-sst-files-size");
    }

    /** Number of live RocksDB stores currently registered (the breadth of the aggregate gauges). */
    public static int liveStoreCount() {
        synchronized (LIVE) {
            return LIVE.size();
        }
    }

    /**
     * Aggregate {@code estimate-table-readers-mem} across all live stores (index + bloom of open
     * files).
     */
    public static long aggregateTableReadersBytes() {
        return aggregate("rocksdb.estimate-table-readers-mem");
    }

    /**
     * Aggregate {@code size-all-mem-tables} across all live stores (active + unflushed write
     * buffers).
     */
    public static long aggregateMemTableBytes() {
        return aggregate("rocksdb.size-all-mem-tables");
    }

    /**
     * Aggregate {@code block-cache-usage} across all live stores (data/index blocks cached for
     * reads).
     */
    public static long aggregateBlockCacheBytes() {
        return aggregate("rocksdb.block-cache-usage");
    }

    /**
     * Sum a RocksDB integer property across every live store, the source for the aggregate
     * native-memory gauges — the multi-tenant footprint a single-instance reading misses. Closed
     * stores are skipped, and the sum is <b>deduped by the RocksDB handle</b> so a shared database
     * (a {@link SharedRocksDb} co-tenant) is counted once, not once per column-family store. (In
     * the per-repo design each store owns its own database, so dedup is a guard, not the common
     * case.)
     */
    private static long aggregate(String name) {
        long sum = 0;
        synchronized (LIVE) {
            Set<RocksDB> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (RocksNodeStore s : LIVE) {
                if (!s.closed && s.db != null && seen.add(s.db)) {
                    sum += s.prop(name);
                }
            }
        }
        return sum;
    }

    /**
     * A single integer RocksDB property on the chunk column family; {@code 0} if unavailable.
     *
     * @implNote Hardened 2026-07-03 (found by a SIGABRT in {@code RocksNativeMetersTest}): the
     *     aggregate gauges poll this OUTSIDE any request path, racing {@link #close} on another
     *     thread AND — for a co-tenant store over a {@code SharedRocksDb} — the shared handle being
     *     freed by its owner without this wrapper's {@code close()} ever running (the upstream
     *     co-tenant shape). {@code getProperty} on a freed native handle is a JVM-fatal SIGABRT,
     *     and a metrics scrape must never be able to kill the process — so this takes the lifecycle
     *     read lock (ordering against {@link #close}'s write lock) and checks the DATABASE handle's
     *     disposal flag before touching it. The column-family handle is deliberately NOT checked:
     *     RocksDB's default-CF handle reports {@code isOwningHandle()=false} even on a healthy open
     *     database (RocksDB owns it), so that check would zero every legitimate read on a
     *     default-CF store — and the CF dies with the database in both real teardown shapes.
     */
    private long prop(String name) {
        lifecycleLock.readLock().lock();
        try {
            if (closed || db == null || !db.isOwningHandle()) {
                return 0L;
            }
            String v = db.getProperty(cf, name);
            return v == null || v.isBlank() ? 0L : Long.parseLong(v.trim());
        } catch (Exception e) {
            return 0L;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * Native-memory attribution snapshot for the chunk store's RocksDB — the consumers that compete
     * with the Java heap for the process's resident set (the 2026-06-13 whole-file ingest's {@code
     * std::bad_alloc} wall). Returns a compact, sample-loggable line:
     *
     * <ul>
     *   <li>{@code tableReaders} — {@code estimate-table-readers-mem}: index + filter blocks pinned
     *       in memory for OPEN sorted-string-table files. With the default {@code
     *       max_open_files=-1} (unlimited) <em>every</em> file's metadata stays resident, so this
     *       grows with the copy-on-write store's file count — the prime suspect for the runaway.
     *   <li>{@code memTables} — {@code size-all-mem-tables}: active + unflushed write buffers.
     *   <li>{@code blockCache} / {@code blockCachePinned} — {@code block-cache-usage} / {@code
     *       block-cache-pinned-usage}: data/index blocks cached for reads.
     *   <li>{@code numKeys} — {@code estimate-num-keys}; {@code sstBytes} — on-disk file size.
     * </ul>
     *
     * <p>Purely diagnostic (no production caller wires it yet) — sampled by the ingest bench to
     * <em>name</em> which native consumer hits the wall before choosing a bound
     * (plans/prolly-bulk-load.md Step 4g). All values are bytes except {@code numKeys}.
     */
    public String memStatsLine() {
        return String.format(
                "rocksdb[tableReaders=%,dMiB memTables=%,dMiB blockCache=%,dMiB sst=%,dMiB(live=%,dMiB)"
                        + " numKeys=%,d L0files=%d pendCompact=%,dMiB runCompact=%d runFlush=%d"
                        + " delayedWriteRate=%,d]",
                prop("rocksdb.estimate-table-readers-mem") >> 20,
                prop("rocksdb.size-all-mem-tables") >> 20,
                prop("rocksdb.block-cache-usage") >> 20,
                prop("rocksdb.total-sst-files-size") >> 20,
                prop("rocksdb.live-sst-files-size") >> 20,
                prop("rocksdb.estimate-num-keys"),
                prop("rocksdb.num-files-at-level0"),
                prop("rocksdb.estimate-pending-compaction-bytes") >> 20,
                prop("rocksdb.num-running-compactions"),
                prop("rocksdb.num-running-flushes"),
                prop("rocksdb.actual-delayed-write-rate"));
    }

    /**
     * The <b>full</b> RocksDB statistics for the chunk store — everything RocksDB will tell us.
     * Three sources, concatenated: {@code rocksdb.stats} (the per-level compaction table — files,
     * sizes, read/write GB, <b>write-amplification</b>, stall counts/seconds — the single most
     * informative view for a write-heavy workload), {@code rocksdb.cfstats} (column-family
     * rollups), and, when {@code -Dprolly.rocksdb.statistics=true} is set, {@link
     * Statistics#toString()} (every ticker + histogram: block-cache hit/miss, bytes read/written,
     * bloom-filter, write stalls). Verbose by design — intended for end-of-run / checkpoint dumps,
     * not the per-batch line.
     */
    public String rocksDbFullStats() {
        StringBuilder sb = new StringBuilder();
        sb.append(strProp("rocksdb.stats"));
        sb.append('\n').append(strProp("rocksdb.cfstats"));
        sb.append('\n').append(strProp("rocksdb.levelstats"));
        if (statistics != null) {
            sb.append("\n=== rocksdb Statistics (all tickers + histograms) ===\n");
            sb.append(statistics);
        }
        return sb.toString();
    }

    /**
     * A string-valued RocksDB property on the chunk column family, labelled; {@code ""} if absent.
     */
    private String strProp(String name) {
        try {
            String v = db.getProperty(cf, name);
            return v == null || v.isBlank() ? "" : "=== " + name + " ===\n" + v;
        } catch (Exception e) {
            return "=== " + name + " === <unavailable: " + e.getMessage() + ">";
        }
    }

    /** Wires an LRU node cache onto this store; pass null to disable. */
    public void setNodeCache(NodeCache cache) {
        this.cache = cache;
    }

    /**
     * Enable/disable content-address verification on the disk-read path (ADR-0064). When on, a node
     * read from RocksDB is re-hashed and checked against the requested key before it is cached or
     * returned — silent bit-rot fails closed with a clear error. Cache hits are trusted (no
     * re-hash), so the hot path is unaffected. Off by default; the production boot enables it via
     * {@code prolly.rdf4j.verify-integrity}.
     */
    public void setVerifyOnRead(boolean verifyOnRead) {
        this.verifyOnRead = verifyOnRead;
    }

    @Override
    public void beginWriteBatch() {
        lifecycleLock.readLock().lock();
        try {
            if (closed) throw new StoreClosedException();
            WriteBatch existing = pendingBatch.get();
            long[] tb = threadBatchBytes.get();
            if (existing != null) { // defensive — begin/end are balanced
                pendingBatchBytes.addAndGet(-tb[0]); // un-count an abandoned batch
                existing.close();
            }
            tb[0] = 0;
            pendingBatch.set(new WriteBatch());
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public void endWriteBatch() {
        lifecycleLock.readLock().lock();
        try {
            if (closed) throw new StoreClosedException();
            WriteBatch b = pendingBatch.get();
            if (b == null) return;
            pendingBatch.remove();
            long[] tb = threadBatchBytes.get();
            try {
                db.write(writeOptions, b);
            } catch (RocksDBException e) {
                throw rethrow("RocksNodeStore.endWriteBatch", e);
            } finally {
                pendingBatchBytes.addAndGet(
                        -tb[0]); // drain this thread's share of the global budget
                tb[0] = 0;
                b.close();
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public Optional<MemorySegment> read(byte[] hash) {
        if (hash == null) throw new IllegalArgumentException("hash must not be null");
        lifecycleLock.readLock().lock();
        try {
            if (closed) throw new StoreClosedException();
            NodeCache c = this.cache;
            if (c != null) {
                Optional<Node> hit = c.get(hash);
                if (hit.isPresent()) return Optional.of(hit.get().segment());
            }
            try {
                byte[] data = db.get(cf, hash);
                if (data == null) return Optional.empty();
                if (verifyOnRead) {
                    // Verify the untrusted disk bytes BEFORE they enter the cache (ADR-0064). A
                    // cache
                    // hit above already skipped this — those bytes were verified on their way in.
                    byte[] actual = HashUtils.hash(data);
                    if (!Arrays.equals(hash, actual)) {
                        throw new ProllyCorruptionException(
                                "node integrity check failed at "
                                        + HashUtils.toHex(hash)
                                        + " — stored bytes hash to "
                                        + HashUtils.toHex(actual)
                                        + " (corruption / bit-rot on the disk read)");
                    }
                }
                MemorySegment seg = MemorySegment.ofArray(data);
                if (c != null) {
                    // The store is content-addressed and holds heterogeneous blobs keyed by hash —
                    // prolly nodes AND other records (a Commit is 'PCMT', a RootMetaTree 'PRMT';
                    // Database.getHead reads a Commit straight from here). Only nodes belong in the
                    // *node* cache. ADR-0072 made Node.fromBytes fail closed on non-node bytes,
                    // which
                    // surfaced this: the previously-removed SimpleNodeSerializer fallback had been
                    // silently caching a garbage Node for every commit / meta blob. Skip the
                    // non-nodes
                    // — their bytes still round-trip below (the integrity hash check already ran).
                    try {
                        // seg is non-null here, so fromBytes returns a real node or throws
                        // UnsupportedFormatException (caught below) — never null.
                        c.put(hash, Objects.requireNonNull(Node.fromBytes(seg)));
                    } catch (com.dolthub.prolly.UnsupportedFormatException notANode) {
                        // a commit / meta-tree / opaque blob — not a node; don't cache it as one.
                    }
                }
                return Optional.of(seg);
            } catch (RocksDBException e) {
                throw rethrow("RocksNodeStore.read", e);
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public byte[] write(MemorySegment segment) {
        lifecycleLock.readLock().lock();
        try {
            if (closed) throw new StoreClosedException();
            return writeLocked(segment);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** The write body, run under the lifecycle read lock by {@link #write(MemorySegment)}. */
    private byte[] writeLocked(MemorySegment segment) {
        byte[] hash = HashUtils.hash(segment.asByteBuffer());
        byte[] data = segment.toArray(ValueLayout.JAVA_BYTE);
        WriteBatch batch = pendingBatch.get();
        try {
            if (batch != null) {
                batch.put(cf, hash, data); // flushed by endWriteBatch
                long[] tb = threadBatchBytes.get();
                tb[0] += data.length;
                long global = pendingBatchBytes.addAndGet(data.length);
                // No-OOM safety net: flush + reset this thread's batch once EITHER it alone
                // OR the GLOBAL in-flight total (summed across the parallel commit's per-thread
                // batches into this single CF) reaches the budget — so total native WriteBatch
                // memory stays ≈ cap regardless of commit concurrency (was cap × threads, the
                // 2026-06-13 std::bad_alloc). Safe — the build never re-reads a chunk it just wrote
                // (root not referenced until the caller advances it; a mid-build failure leaves
                // only
                // collectible content-addressed orphans). See pendingBatchBytes + batchFlushBytes.
                long cap = batchFlushBytes;
                if (cap > 0 && (tb[0] >= cap || global >= cap)) {
                    db.write(writeOptions, batch);
                    batch.clear();
                    pendingBatchBytes.addAndGet(-tb[0]);
                    tb[0] = 0;
                }
            } else {
                db.put(cf, hash, data);
            }
            return hash;
        } catch (RocksDBException e) {
            throw rethrow("RocksNodeStore.write", e);
        }
    }

    /**
     * Wraps a {@link RocksDBException} as a typed, retryable {@link ProllyIoException} with
     * operational guidance. Detects the common case where the underlying filesystem (often {@code
     * tmpfs} at {@code /tmp}) has filled up — e.g. test runs that don't clean up their {@code
     * Files.createTempDirectory("prolly-...")} dirs — and points the caller at the cleanup recipe
     * in {@code operation_guide.md} instead of surfacing only the bare RocksDB stack trace.
     *
     * @apiNote Returns (does not throw) the exception so call sites read {@code throw rethrow(op,
     *     e)} — the typed {@link ProllyIoException} lets a caller branch on transient-io vs a
     *     non-retryable {@link ProllyCorruptionException} ({@code
     *     core-error-taxonomy-and-failpaths.md} D-1). The originating {@code RocksDBException} is
     *     preserved as the cause.
     * @implNote Package-private (not {@code private}) so the disk-quota detection can be
     *     unit-tested directly — it can't be reached through the public API without genuinely
     *     exhausting a filesystem.
     */
    static ProllyIoException rethrow(String op, RocksDBException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("Disk quota exceeded") || msg.contains("No space left on device")) {
            return new ProllyIoException(
                    op
                            + " failed: "
                            + msg
                            + "\n  Likely cause: the filesystem hosting this RocksDB store is full."
                            + "\n  If you've been running tests, /tmp/prolly-* probably accumulated."
                            + "\n  Quick fix: rm -rf /tmp/prolly-*"
                            + "\n  See operation_guide.md ('Disk full / quota exceeded') for details.",
                    e);
        }
        return new ProllyIoException(op + " failed: " + msg, e);
    }

    @Override
    public byte[] write(byte[] data) {
        return write(MemorySegment.ofArray(data));
    }

    /**
     * Forces all buffered writes to durable storage. Call before advancing the manifest in a commit
     * so the manifest can never reference chunks that survive only in volatile RocksDB memory.
     * Throws on flush failure rather than silently dropping the data.
     */
    public void flushDurable() {
        lifecycleLock.readLock().lock();
        try {
            if (closed) throw new StoreClosedException();
            try (FlushOptions fo = new FlushOptions().setWaitForFlush(true)) {
                db.flush(fo, cf);
            } catch (RocksDBException e) {
                throw new ProllyIoException("RocksDB durable flush failed", e);
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        // WRITE lock: waits for every in-flight read/write/batch (which hold the READ lock) to
        // drain before we free the native handles, so no native call can be mid-flight when the
        // db/cf pointers are freed. Idempotent under the lock.
        lifecycleLock.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            LIVE.remove(this); // deregister from the aggregate native gauges
            closeNative();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void closeNative() {
        WriteBatch pending = pendingBatch.get();
        if (pending != null) {
            pending.close();
            pendingBatch.remove();
        }
        writeOptions.close();
        // Shared mode: SharedRocksDb owns the DB and the column-family handle —
        // closing them here would yank the engine out from under a co-tenant.
        if (ownsDb) {
            db.close();
        }
        // Options is referenced by the live DB, so close it AFTER the DB — and before the
        // sub-objects
        // it referenced (statistics / block cache / writeBufferManager), which are closed below.
        if (options != null) options.close();
        if (statistics != null) statistics.close();
        // After the DB (which referenced them): release the owned table-format native handles. The
        // WriteBufferManager references the block cache, so close it before the cache.
        if (writeBufferManager != null) writeBufferManager.close();
        if (blockCache != null) blockCache.close();
        if (bloomFilter != null) bloomFilter.close();
    }
}
