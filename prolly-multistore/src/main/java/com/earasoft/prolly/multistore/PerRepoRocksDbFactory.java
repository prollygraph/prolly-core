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

import com.earasoft.prolly.storage.SharedRocksResources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a per-repo RocksDB instance at {@code <storeRoot>/repos/{repoId}/db/}. Each repo gets its
 * own independent RocksDB with its own WAL, memtable, compaction threads, and chunk store — the v2
 * design (D-2 of the upstream multi-tenant hosting plan) that flipped from "shared DB + per-repo
 * CFs" after review verified that per-repo dictionaries break cross-repo chunk dedup anyway (there
 * is nothing to share).
 *
 * <p>D-11 config applied per-instance:
 *
 * <ul>
 *   <li>{@code write_buffer_size = 8MB} — bounded memtable allocation per CF; multi-tenant
 *       deployments override via the caller-supplied write-buffer size
 *   <li>{@code max_write_buffer_number = 2} — caps in-flight memtable count to bound memory under
 *       concurrent writes
 *   <li>{@code level0_file_num_compaction_trigger = 4} — keeps L0 file count modest so reads stay
 *       cheap
 * </ul>
 *
 * <p>Phase 1 Step 8 of the upstream multi-tenant hosting plan. Used by downstream products'
 * per-repo {@code RepoRegistry} resource factories (each store type grows a constructor that takes
 * a per-repo backing).
 */
public final class PerRepoRocksDbFactory {

    private static final Logger LOG = LoggerFactory.getLogger(PerRepoRocksDbFactory.class);

    /** D-11 per-CF write buffer cap (8MB). */
    public static final long WRITE_BUFFER_BYTES_DEFAULT = 8L * 1024 * 1024;

    /** D-11 max in-flight memtable count. */
    public static final int MAX_WRITE_BUFFER_NUMBER = 2;

    /** D-11 L0 compaction trigger. */
    public static final int LEVEL0_FILE_NUM_COMPACTION_TRIGGER = 4;

    /** RocksDB's default-CF name (every DB has one). The chunk store lives here. */
    static final String DEFAULT_CF_NAME =
            new String(RocksDB.DEFAULT_COLUMN_FAMILY, StandardCharsets.UTF_8);

    private PerRepoRocksDbFactory() {}

    /**
     * Open the per-repo RocksDB for {@code repoId} under {@code storeRoot}. Creates the directory
     * tree if it doesn't exist.
     *
     * @param repoId well-formed repo identifier (see {@link RepoNameValidator#validate})
     * @param storeRoot the deployment's storage root
     * @return a handle containing the open DB + chunk CF + cleanup-owned option objects. Caller
     *     MUST call {@link OpenRepoDb#close} to release native handles.
     * @throws RepoNameInvalidException if {@code repoId} is malformed
     * @throws IOException on filesystem failure creating the storage directory
     * @throws RocksDBException on RocksDB-level failure
     */
    public static OpenRepoDb open(String repoId, Path storeRoot)
            throws IOException, RocksDBException {
        return open(repoId, storeRoot, null);
    }

    /**
     * As {@link #open(String, Path)}, but wiring the per-repo RocksDB to a process-shared native
     * budget when {@code shared} is non-null (the multi-tenant {@link SharedRocksResources}: one
     * block cache + write-buffer-manager across every per-repo store, so aggregate native is
     * bounded regardless of warm-store count). {@code null} = per-instance RocksDB defaults (the
     * prior behaviour).
     */
    public static OpenRepoDb open(
            String repoId, Path storeRoot, @Nullable SharedRocksResources shared)
            throws IOException, RocksDBException {
        RepoNameValidator.validate(repoId);
        if (storeRoot == null) {
            throw new IllegalArgumentException("storeRoot must not be null");
        }
        return openAt(repoId, repoDbDir(storeRoot, repoId), shared);
    }

    /**
     * Open variant that takes an explicit DB directory — useful for tests that want to point at
     * {@code @TempDir} without composing the {@code repos/{repoId}/db/} suffix.
     */
    public static OpenRepoDb openAt(String repoId, Path dbDir)
            throws IOException, RocksDBException {
        return openAt(repoId, dbDir, null);
    }

    /**
     * As {@link #openAt(String, Path)}, wiring the per-repo RocksDB to the process-shared native
     * budget when {@code shared} is non-null.
     */
    public static OpenRepoDb openAt(
            String repoId, Path dbDir, @Nullable SharedRocksResources shared)
            throws IOException, RocksDBException {
        RepoNameValidator.validate(repoId);
        if (dbDir == null) {
            throw new IllegalArgumentException("dbDir must not be null");
        }
        Files.createDirectories(dbDir);

        // Discover any existing CFs — RocksDB refuses to open a directory
        // that holds CFs the caller didn't enumerate. A fresh dir yields
        // just "default"; an existing one may yield more.
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(DEFAULT_CF_NAME);
        try (org.rocksdb.Options probe = new org.rocksdb.Options()) {
            for (byte[] existing : RocksDB.listColumnFamilies(probe, dbDir.toString())) {
                names.add(new String(existing, StandardCharsets.UTF_8));
            }
        }

        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>(names.size());
        List<ColumnFamilyOptions> cfOptions = new ArrayList<>(names.size());
        for (String name : names) {
            ColumnFamilyOptions opts =
                    new ColumnFamilyOptions()
                            .setWriteBufferSize(WRITE_BUFFER_BYTES_DEFAULT)
                            .setMaxWriteBufferNumber(MAX_WRITE_BUFFER_NUMBER)
                            .setLevel0FileNumCompactionTrigger(LEVEL0_FILE_NUM_COMPACTION_TRIGGER);
            if (shared != null) {
                shared.applyTo(
                        opts); // shared block cache + bloom (index/filter in the bounded cache)
            }
            cfOptions.add(opts);
            descriptors.add(
                    new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8), opts));
        }

        DBOptions dbOptions =
                new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        if (shared != null) {
            shared.applyTo(dbOptions); // shared write-buffer-manager + max-open-files (one budget)
        }

        List<ColumnFamilyHandle> handleList = new ArrayList<>(names.size());
        try {
            RocksDB db = RocksDB.open(dbOptions, dbDir.toString(), descriptors, handleList);
            // The default CF is always the first descriptor → first handle.
            ColumnFamilyHandle chunkCf = handleList.get(0);
            LOG.debug("repo={} RocksDB open at {} ({} CFs)", repoId, dbDir, handleList.size());
            return new OpenRepoDb(repoId, db, chunkCf, cfOptions, dbOptions);
        } catch (RocksDBException e) {
            // Open failed — release native option objects we just allocated.
            dbOptions.close();
            for (ColumnFamilyOptions opts : cfOptions) {
                opts.close();
            }
            throw e;
        }
    }

    /**
     * Resolve the on-disk DB directory for a personal-namespace repo under a deployment's storage
     * root. {@code <storeRoot>/repos/{repoId}/db/} — see D-2 of the upstream multi-tenant hosting
     * plan for the storage-layout decision.
     */
    public static Path repoDbDir(Path storeRoot, String repoId) {
        RepoNameValidator.validate(repoId);
        return storeRoot.resolve("repos").resolve(repoId).resolve("db");
    }

    /**
     * Open the per-org-repo RocksDB. Phase 0 Step 4 of the upstream orgs plan. Storage path: {@code
     * <storeRoot>/orgs/{orgId}/repos/{repoId}/db/} (per D-5 of the orgs plan). Same per-repo
     * isolation properties as personal repos — each DB has its own WAL, memtables, chunk store, and
     * dictionary.
     *
     * @param orgId well-formed org identifier
     * @param repoId well-formed repo identifier under that org
     * @param storeRoot deployment storage root
     */
    public static OpenRepoDb openInOrg(String orgId, String repoId, Path storeRoot)
            throws IOException, RocksDBException {
        return openInOrg(orgId, repoId, storeRoot, null);
    }

    /**
     * As {@link #openInOrg(String, String, Path)}, wiring the per-org-repo RocksDB to the
     * process-shared native budget when {@code shared} is non-null.
     */
    public static OpenRepoDb openInOrg(
            String orgId, String repoId, Path storeRoot, @Nullable SharedRocksResources shared)
            throws IOException, RocksDBException {
        RepoNameValidator.validate(orgId);
        RepoNameValidator.validate(repoId);
        if (storeRoot == null) {
            throw new IllegalArgumentException("storeRoot must not be null");
        }
        return openAt(repoId, orgRepoDbDir(storeRoot, orgId, repoId), shared);
    }

    /**
     * Resolve the on-disk DB directory for an org-owned repo under a deployment's storage root.
     * {@code <storeRoot>/orgs/{orgId}/repos/{repoId}/db/} — see D-5 of the upstream orgs plan.
     */
    public static Path orgRepoDbDir(Path storeRoot, String orgId, String repoId) {
        RepoNameValidator.validate(orgId);
        RepoNameValidator.validate(repoId);
        return storeRoot
                .resolve("orgs")
                .resolve(orgId)
                .resolve("repos")
                .resolve(repoId)
                .resolve("db");
    }
}
