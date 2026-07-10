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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/**
 * Opens one {@link RocksDB} instance hosting several column families, so the versioned chunk store
 * ({@link RocksNodeStore}) and an upstream unversioned flat Sail can share a single engine — one
 * write-ahead log, one backup, one set of open files — rather than each opening its own database.
 *
 * <p>The versioned chunk store lives in the <strong>default</strong> column family, so a database
 * created by the standalone {@code RocksNodeStore(String)} is byte-for-byte readable here as the
 * chunk-store column family. Every extra named column family is created on first open ({@code
 * createMissingColumnFamilies}); reopening an existing database also picks up whatever families it
 * already contains, so an older single-family database upgrades transparently.
 *
 * <h3>Ownership &amp; close order</h3>
 *
 * <p>This object <strong>owns</strong> the {@code RocksDB} and every column-family handle. Any
 * {@code RocksNodeStore} or flat store built on its handles is a non-owning co-tenant — those do
 * <em>not</em> close the shared DB. Close this {@code SharedRocksDb} <em>last</em>, after every
 * co-tenant: {@link #close()} disposes the column-family handles first, then the database, then the
 * option objects, which is the order RocksDB's native binding requires.
 *
 * <p>Not thread-safe to construct or close concurrently; once open, the handed- out handles and the
 * {@code RocksDB} are safe for the usual concurrent single-writer / many-reader RocksDB access.
 *
 * @implNote <b>Collaborators:</b> one {@link RocksDB} engine and its {@link
 *     org.rocksdb.ColumnFamilyHandle column-family handles}. <b>Dependents (non-owning
 *     co-tenants):</b> {@code RocksNodeStore} (the versioned chunk store, in the default column
 *     family) and the unversioned flat Sail (its own families) — both built on the handed-out
 *     handles, neither closing the engine.
 */
public final class SharedRocksDb implements AutoCloseable {

    /** Name of the column family the versioned chunk store uses. */
    public static final String CHUNK_STORE_CF = "default";

    private final RocksDB db;
    private final DBOptions dbOptions;
    private final Map<String, ColumnFamilyHandle> handles;
    private final List<ColumnFamilyOptions> cfOptions;
    private boolean closed;

    private SharedRocksDb(
            RocksDB db,
            DBOptions dbOptions,
            Map<String, ColumnFamilyHandle> handles,
            List<ColumnFamilyOptions> cfOptions) {
        this.db = db;
        this.dbOptions = dbOptions;
        this.handles = handles;
        this.cfOptions = cfOptions;
    }

    /**
     * Open the database at {@code path}, ensuring the default column family plus each name in
     * {@code extraColumnFamilies} exists. Any column family already present in an existing database
     * is also reopened.
     *
     * @param path filesystem directory for the RocksDB
     * @param extraColumnFamilies named CFs to ensure exist (e.g. the flat Sail's {@code spoc},
     *     {@code posc}, …)
     */
    public static SharedRocksDb open(String path, Collection<String> extraColumnFamilies)
            throws RocksDBException {
        // Discover whatever column families the database already holds. A fresh
        // directory yields just "default"; an existing one must be reopened
        // with descriptors for every CF it contains or RocksDB refuses to open.
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(CHUNK_STORE_CF);
        try (Options probe = new Options()) {
            for (byte[] existing : RocksDB.listColumnFamilies(probe, path)) {
                names.add(new String(existing, StandardCharsets.UTF_8));
            }
        }
        names.addAll(extraColumnFamilies);

        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>(names.size());
        List<ColumnFamilyOptions> cfOptions = new ArrayList<>(names.size());
        for (String name : names) {
            ColumnFamilyOptions opts = new ColumnFamilyOptions();
            cfOptions.add(opts);
            descriptors.add(
                    new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8), opts));
        }

        DBOptions dbOptions =
                new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        List<ColumnFamilyHandle> handleList = new ArrayList<>(names.size());
        try {
            RocksDB db = RocksDB.open(dbOptions, path, descriptors, handleList);
            Map<String, ColumnFamilyHandle> handles = new LinkedHashMap<>();
            int i = 0;
            for (String name : names) {
                handles.put(name, handleList.get(i++));
            }
            return new SharedRocksDb(db, dbOptions, handles, cfOptions);
        } catch (RocksDBException e) {
            // Open failed — release the option objects we just allocated so a
            // failed open does not leak native handles.
            dbOptions.close();
            for (ColumnFamilyOptions opts : cfOptions) {
                opts.close();
            }
            throw e;
        }
    }

    /** The shared database — for callers that need raw RocksDB access. */
    public RocksDB db() {
        return db;
    }

    /** The default column family — where the versioned chunk store lives. */
    public ColumnFamilyHandle chunkStoreColumnFamily() {
        // Delegate to the null-guarding lookup: the chunk-store CF is always ensured present by
        // open(), so this never returns null (and fails loudly with a clear message if it somehow
        // weren't) — rather than handing back a nullable Map.get result.
        return columnFamily(CHUNK_STORE_CF);
    }

    /** Handle for a named column family ensured present by {@link #open}. */
    public ColumnFamilyHandle columnFamily(String name) {
        ColumnFamilyHandle handle = handles.get(name);
        if (handle == null) {
            throw new IllegalArgumentException(
                    "no such column family: " + name + " (opened: " + handles.keySet() + ")");
        }
        return handle;
    }

    // Convenience factory {@code openChunkStore()} removed 2026-05-27 when
    // SharedRocksDb moved to its own module (prolly-storage). The factory's
    // return type referenced RocksNodeStore before it moved into this module — a
    // then-backwards dep. Upstream callers construct
    // {@code new RocksNodeStore(shared.db(), shared.chunkStoreColumnFamily())}
    // directly; the same two-argument constructor the factory wrapped.

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Column-family handles before the DB, then options last — the order
        // RocksDB's JNI bindings require to avoid use-after-free on native peers.
        for (ColumnFamilyHandle handle : handles.values()) {
            handle.close();
        }
        db.close();
        dbOptions.close();
        for (ColumnFamilyOptions opts : cfOptions) {
            opts.close();
        }
    }
}
