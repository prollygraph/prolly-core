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

import java.util.List;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Result of {@link PerRepoRocksDbFactory#open(String, java.nio.file.Path)} — holds the open {@link
 * RocksDB} instance for a single repo + the column family handle for its chunk store, plus the
 * option objects that need to be released alongside the DB on shutdown.
 *
 * <p>Native-handle lifecycle order matters: RocksDB's JNI requires column-family handles to close
 * BEFORE the DB instance, and option objects to close AFTER. {@link #close()} executes that order.
 *
 * <p>Phase 1 Step 8 of the upstream multi-tenant hosting plan.
 */
public final class OpenRepoDb implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OpenRepoDb.class);

    private final String repoId;
    private final RocksDB db;
    private final ColumnFamilyHandle chunkCf;
    private final List<ColumnFamilyOptions> cfOptions;
    private final DBOptions dbOptions;
    private volatile boolean closed = false;

    OpenRepoDb(
            String repoId,
            RocksDB db,
            ColumnFamilyHandle chunkCf,
            List<ColumnFamilyOptions> cfOptions,
            DBOptions dbOptions) {
        this.repoId = repoId;
        this.db = db;
        this.chunkCf = chunkCf;
        this.cfOptions = cfOptions;
        this.dbOptions = dbOptions;
    }

    /** RepoId this DB was opened for. */
    public String repoId() {
        return repoId;
    }

    /** The underlying RocksDB instance. */
    public RocksDB db() {
        return db;
    }

    /**
     * Column family handle for chunk-layer content-addressed storage — this repo's chunk store.
     * Currently mapped to RocksDB's default column family (the open-by-default CF every RocksDB
     * instance has); future steps may add named CFs alongside.
     */
    public ColumnFamilyHandle chunkColumnFamily() {
        return chunkCf;
    }

    /** Whether {@link #close} has been called. */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // Order: CF handles → DB → options. RocksDB JNI requires this.
        try {
            chunkCf.close();
        } catch (Exception e) {
            LOG.warn("repo={} chunk CF close failed", repoId, e);
        }
        try {
            db.close();
        } catch (Exception e) {
            LOG.warn("repo={} RocksDB close failed", repoId, e);
        }
        for (ColumnFamilyOptions opts : cfOptions) {
            try {
                opts.close();
            } catch (Exception e) {
                LOG.warn("repo={} ColumnFamilyOptions close failed", repoId, e);
            }
        }
        try {
            dbOptions.close();
        } catch (Exception e) {
            LOG.warn("repo={} DBOptions close failed", repoId, e);
        }
    }
}
