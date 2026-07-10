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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

/**
 * The RocksDB-backed {@link Manifest}: the branch-and-tag reference table.
 *
 * <p>Persists named references under the {@code ref:{repoId}/{name}} key prefix. This is the
 * mutable layer above the immutable chunk store — where a commit's optimistic compare-and-set
 * actually lands.
 *
 * <p><b>Concurrency:</b> {@link #updateRef} synchronizes within this Java process only. RocksDB
 * itself is single-writer-process at the file-system level by default, so opening the same database
 * from a second Java process will fail. Cross-process coordination (e.g. multiple containers
 * writing to a shared mount) is out of scope for this implementation.
 *
 * @implNote <b>Collaborators:</b> {@link RocksDB} (the reference key-values). <b>Dependents:</b>
 *     {@code Database} — every branch-head read/advance, and the optimistic compare-and-set ({@link
 *     #updateRef}) that serializes concurrent commits.
 */
public class RocksManifest implements Manifest {
    private static final String REF_PREFIX = "ref:";
    private final RocksDB db;

    public RocksManifest(RocksDB db) {
        this.db = db;
    }

    private byte[] refKey(String repoId, String name) {
        return (REF_PREFIX + repoId + "/" + name).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Optional<byte[]> getRef(String repoId, String name) {
        try {
            return Optional.ofNullable(db.get(refKey(repoId, name)));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized boolean updateRef(
            String repoId, String name, byte @Nullable [] newHash, byte @Nullable [] expectedHash) {
        try {
            byte[] key = refKey(repoId, name);
            byte[] current = db.get(key);

            if (expectedHash == null) {
                if (current != null) return false;
            } else {
                if (current == null || !Arrays.equals(current, expectedHash)) {
                    return false;
                }
            }

            if (newHash == null) {
                db.delete(key);
            } else {
                db.put(key, newHash);
            }
            return true;
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteRef(String repoId, String name) {
        try {
            db.delete(refKey(repoId, name));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> listRefs(String repoId) {
        List<String> refs = new ArrayList<>();
        String prefix = REF_PREFIX + repoId + "/";
        try (RocksIterator it = db.newIterator()) {
            it.seek(prefix.getBytes(StandardCharsets.UTF_8));
            while (it.isValid()) {
                String key = new String(it.key(), StandardCharsets.UTF_8);
                if (!key.startsWith(prefix)) break;
                refs.add(key.substring(prefix.length()));
                it.next();
            }
        }
        return refs;
    }
}
