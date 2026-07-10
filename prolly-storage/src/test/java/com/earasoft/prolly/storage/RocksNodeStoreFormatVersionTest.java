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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.FormatVersion;
import com.dolthub.prolly.UnsupportedFormatException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code core-format-versioning.md} Step 1 — the store-level {@code _format_version} marker. A
 * fresh store is stamped and reopens cleanly; a store whose marker is a future version, or that
 * holds data with no marker (a pre-versioning store), fails closed with {@link
 * UnsupportedFormatException} before any chunk is read. The marker is a non-20-byte key, so the
 * garbage collector's delete-unreachable-20-byte-keys sweep leaves it alone (asserted indirectly by
 * the clean reopen).
 */
class RocksNodeStoreFormatVersionTest {

    @Test
    void fresh_store_is_stamped_and_reopens(@TempDir Path dir) throws Exception {
        String path = dir.toString();
        byte[] chunkHash;
        try (RocksNodeStore store = new RocksNodeStore(path)) {
            byte[] marker =
                    store.db()
                            .get(
                                    store.db().getDefaultColumnFamily(),
                                    RocksNodeStore.FORMAT_VERSION_KEY);
            assertNotNull(marker, "a fresh store is stamped with the format version");
            assertEquals(
                    Integer.toString(FormatVersion.CORE_FORMAT_VERSION),
                    new String(marker, StandardCharsets.UTF_8),
                    "the marker carries the current version");
            chunkHash = store.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        // Reopen: the marker matches, so it verifies (no throw) and the chunk is still readable.
        try (RocksNodeStore reopened = new RocksNodeStore(path)) {
            assertTrue(reopened.read(chunkHash).isPresent(), "the chunk survives a clean reopen");
        }
    }

    @Test
    void future_version_marker_fails_closed(@TempDir Path dir) throws Exception {
        String path = dir.toString();
        try (RocksNodeStore store = new RocksNodeStore(path)) {
            store.write("x".getBytes(StandardCharsets.UTF_8));
            // Tamper the marker to a future version.
            store.db()
                    .put(
                            store.db().getDefaultColumnFamily(),
                            RocksNodeStore.FORMAT_VERSION_KEY,
                            "999".getBytes(StandardCharsets.UTF_8));
        }
        UnsupportedFormatException ex =
                assertThrows(UnsupportedFormatException.class, () -> new RocksNodeStore(path));
        assertTrue(
                ex.getMessage().contains("incompatible store format version 999"),
                "the error names the offending version: " + ex.getMessage());
    }

    @Test
    void unversioned_store_with_data_fails_closed(@TempDir Path dir) throws Exception {
        String path = dir.toString();
        try (RocksNodeStore store = new RocksNodeStore(path)) {
            store.write("x".getBytes(StandardCharsets.UTF_8));
            // Simulate a true pre-versioning store: data present, BOTH markers removed.
            store.db()
                    .delete(store.db().getDefaultColumnFamily(), RocksNodeStore.FORMAT_VERSION_KEY);
            store.db()
                    .delete(store.db().getDefaultColumnFamily(), RocksNodeStore.HASH_ALGORITHM_KEY);
        }
        UnsupportedFormatException ex =
                assertThrows(UnsupportedFormatException.class, () -> new RocksNodeStore(path));
        assertTrue(
                ex.getMessage().contains("unversioned store format"),
                "the error explains the pre-versioning store: " + ex.getMessage());
    }

    @Test
    void wrong_hash_algorithm_fails_closed(@TempDir Path dir) throws Exception {
        // Step 4: a store whose hash-algorithm marker is an unknown/future id fails closed —
        // defense
        // in depth against a content-hash change that did not also bump the format version.
        String path = dir.toString();
        try (RocksNodeStore store = new RocksNodeStore(path)) {
            store.write("x".getBytes(StandardCharsets.UTF_8));
            // Tamper only the hash-algorithm marker (the format-version marker stays valid).
            store.db()
                    .put(
                            store.db().getDefaultColumnFamily(),
                            RocksNodeStore.HASH_ALGORITHM_KEY,
                            "99".getBytes(StandardCharsets.UTF_8));
        }
        UnsupportedFormatException ex =
                assertThrows(UnsupportedFormatException.class, () -> new RocksNodeStore(path));
        assertTrue(
                ex.getMessage().contains("hash algorithm id 99"),
                "the error names the offending hash-algorithm id: " + ex.getMessage());
    }

    @Test
    void shared_co_tenant_store_rejects_a_future_version_marker(@TempDir Path dir)
            throws Exception {
        // The PRODUCTION path is the shared (co-tenant) constructor — verify it also fails closed.
        try (SharedRocksDb shared =
                SharedRocksDb.open(dir.toString(), java.util.List.of("dict-fwd"))) {
            // Stamp via a first co-tenant store, then tamper the chunk CF marker to a future
            // version.
            try (RocksNodeStore chunks =
                    new RocksNodeStore(shared.db(), shared.chunkStoreColumnFamily())) {
                chunks.write("x".getBytes(StandardCharsets.UTF_8));
            }
            shared.db()
                    .put(
                            shared.chunkStoreColumnFamily(),
                            RocksNodeStore.FORMAT_VERSION_KEY,
                            "999".getBytes(StandardCharsets.UTF_8));
            UnsupportedFormatException ex =
                    assertThrows(
                            UnsupportedFormatException.class,
                            () -> new RocksNodeStore(shared.db(), shared.chunkStoreColumnFamily()));
            assertTrue(
                    ex.getMessage().contains("incompatible store format version 999"),
                    "the production co-tenant constructor fails closed too: " + ex.getMessage());
        }
    }
}
