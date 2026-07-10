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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDBException;

/**
 * Phase 1 Step 8 of the upstream multi-tenant hosting plan — pins the per-repo RocksDB factory's
 * open / close / lifecycle contract. Each test uses a fresh {@code @TempDir} so RocksDB's
 * directory-level lock is isolated.
 */
class PerRepoRocksDbFactoryTest {

    @Test
    void open_creates_directory_and_chunk_cf(@TempDir Path tempDir) throws Exception {
        try (OpenRepoDb open = PerRepoRocksDbFactory.open("alpha", tempDir)) {
            assertEquals("alpha", open.repoId());
            assertNotNull(open.db());
            assertNotNull(open.chunkColumnFamily());
            // The DB directory exists where the factory says it should.
            assertTrue(Files.isDirectory(PerRepoRocksDbFactory.repoDbDir(tempDir, "alpha")));
            // RocksDB writes a CURRENT file at the DB root.
            assertTrue(
                    Files.exists(
                            PerRepoRocksDbFactory.repoDbDir(tempDir, "alpha").resolve("CURRENT")));
            assertFalse(open.isClosed());
        }
    }

    @Test
    void write_and_read_chunk_round_trips(@TempDir Path tempDir) throws Exception {
        try (OpenRepoDb open = PerRepoRocksDbFactory.open("alpha", tempDir)) {
            byte[] key = "test-key".getBytes(StandardCharsets.UTF_8);
            byte[] value = "test-value".getBytes(StandardCharsets.UTF_8);
            open.db().put(open.chunkColumnFamily(), key, value);
            byte[] read = open.db().get(open.chunkColumnFamily(), key);
            assertArrayEquals(value, read, "chunk store round-trip on the default-CF handle");
        }
    }

    @Test
    void close_releases_lock_so_reopen_works(@TempDir Path tempDir) throws Exception {
        // First open + close → no lingering lock.
        try (OpenRepoDb open = PerRepoRocksDbFactory.open("alpha", tempDir)) {
            open.db()
                    .put(
                            open.chunkColumnFamily(),
                            "k".getBytes(StandardCharsets.UTF_8),
                            "v".getBytes(StandardCharsets.UTF_8));
        }
        // Reopen on the same path should succeed.
        try (OpenRepoDb reopen = PerRepoRocksDbFactory.open("alpha", tempDir)) {
            byte[] read =
                    reopen.db()
                            .get(reopen.chunkColumnFamily(), "k".getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(
                    "v".getBytes(StandardCharsets.UTF_8),
                    read,
                    "data persists across close + reopen");
        }
    }

    @Test
    void double_open_fails_with_lock_error(@TempDir Path tempDir) throws Exception {
        // RocksDB acquires an exclusive lock on the directory; a second
        // concurrent open must fail with RocksDBException.
        try (OpenRepoDb first = PerRepoRocksDbFactory.open("alpha", tempDir)) {
            assertThrows(
                    RocksDBException.class,
                    () -> PerRepoRocksDbFactory.open("alpha", tempDir),
                    "double-open of the same directory must fail with a RocksDB lock error");
        }
    }

    @Test
    void open_rejects_malformed_repoId_before_io(@TempDir Path tempDir) {
        assertThrows(
                RepoNameInvalidException.class,
                () -> PerRepoRocksDbFactory.open("Bad-Name", tempDir),
                "malformed repoId must fail before touching the filesystem");
        // Confirm no directory was created.
        assertFalse(
                Files.exists(tempDir.resolve("repos").resolve("Bad-Name")),
                "no I/O happens for a rejected repoId");
    }

    @Test
    void open_rejects_null_repoId() {
        assertThrows(
                RepoNameInvalidException.class,
                () -> PerRepoRocksDbFactory.open(null, Path.of("/tmp")));
    }

    @Test
    void open_rejects_null_storeRoot() {
        assertThrows(
                IllegalArgumentException.class, () -> PerRepoRocksDbFactory.open("alpha", null));
    }

    @Test
    void open_creates_parent_directories_lazily(@TempDir Path tempDir) throws Exception {
        // tempDir/repos/{repoId} doesn't exist yet — factory must mkdir.
        Path expectedDir = PerRepoRocksDbFactory.repoDbDir(tempDir, "alpha");
        assertFalse(Files.exists(expectedDir), "precondition: the per-repo dir is not yet created");
        try (OpenRepoDb open = PerRepoRocksDbFactory.open("alpha", tempDir)) {
            assertTrue(Files.isDirectory(expectedDir));
        }
    }

    @Test
    void close_is_idempotent(@TempDir Path tempDir) throws Exception {
        OpenRepoDb open = PerRepoRocksDbFactory.open("alpha", tempDir);
        open.close();
        assertTrue(open.isClosed());
        assertDoesNotThrow(
                open::close,
                "close must be a no-op when already closed (try-with-resources composes idempotency)");
    }

    @Test
    void repoDbDir_uses_documented_layout(@TempDir Path tempDir) {
        // D-2 + D-3 of the upstream multi-tenant hosting plan:
        //   <storeRoot>/repos/{repoId}/db/
        Path expected = tempDir.resolve("repos").resolve("alpha").resolve("db");
        assertEquals(expected, PerRepoRocksDbFactory.repoDbDir(tempDir, "alpha"));
    }

    @Test
    void repoDbDir_rejects_malformed_repoId(@TempDir Path tempDir) {
        assertThrows(
                RepoNameInvalidException.class,
                () -> PerRepoRocksDbFactory.repoDbDir(tempDir, "Bad-Name"),
                "even the path-resolver applies the validator — defense in depth");
    }

    @Test
    void config_constants_match_D11(@TempDir Path tempDir) {
        // D-11 of the plan pins these defaults. Pinning them in the test
        // catches accidental drift.
        assertEquals(
                8L * 1024 * 1024,
                PerRepoRocksDbFactory.WRITE_BUFFER_BYTES_DEFAULT,
                "D-11: 8MB per-CF write buffer");
        assertEquals(
                2,
                PerRepoRocksDbFactory.MAX_WRITE_BUFFER_NUMBER,
                "D-11: max 2 in-flight memtables");
        assertEquals(
                4,
                PerRepoRocksDbFactory.LEVEL0_FILE_NUM_COMPACTION_TRIGGER,
                "D-11: L0 compaction trigger at 4 files");
    }

    @Test
    void orgRepoDbDir_uses_documented_layout(@TempDir Path tempDir) {
        // D-5 of the upstream orgs plan:
        //   <storeRoot>/orgs/{orgId}/repos/{repoId}/db/
        Path expected =
                tempDir.resolve("orgs")
                        .resolve("biopharma")
                        .resolve("repos")
                        .resolve("alpha")
                        .resolve("db");
        assertEquals(expected, PerRepoRocksDbFactory.orgRepoDbDir(tempDir, "biopharma", "alpha"));
    }

    @Test
    void orgRepoDbDir_rejects_malformed_orgId(@TempDir Path tempDir) {
        assertThrows(
                RepoNameInvalidException.class,
                () -> PerRepoRocksDbFactory.orgRepoDbDir(tempDir, "Bad-Org", "alpha"));
    }

    @Test
    void orgRepoDbDir_rejects_malformed_repoId(@TempDir Path tempDir) {
        assertThrows(
                RepoNameInvalidException.class,
                () -> PerRepoRocksDbFactory.orgRepoDbDir(tempDir, "biopharma", "Bad-Name"));
    }

    @Test
    void openInOrg_creates_org_scoped_db_directory(@TempDir Path tempDir) throws Exception {
        try (OpenRepoDb db = PerRepoRocksDbFactory.openInOrg("biopharma", "alpha", tempDir)) {
            Path expectedDir =
                    tempDir.resolve("orgs")
                            .resolve("biopharma")
                            .resolve("repos")
                            .resolve("alpha")
                            .resolve("db");
            assertTrue(Files.isDirectory(expectedDir));
            byte[] key = "k".getBytes(StandardCharsets.UTF_8);
            db.db().put(db.chunkColumnFamily(), key, "org-val".getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(
                    "org-val".getBytes(StandardCharsets.UTF_8),
                    db.db().get(db.chunkColumnFamily(), key));
        }
    }

    @Test
    void personal_and_org_owned_same_name_are_independent(@TempDir Path tempDir) throws Exception {
        // Per-org-repo isolation: a personal "alpha" and an org-owned
        // "alpha" under biopharma are TWO distinct DBs.
        try (OpenRepoDb personal = PerRepoRocksDbFactory.open("alpha", tempDir);
                OpenRepoDb orgOwned =
                        PerRepoRocksDbFactory.openInOrg("biopharma", "alpha", tempDir)) {
            byte[] key = "k".getBytes(StandardCharsets.UTF_8);
            personal.db()
                    .put(
                            personal.chunkColumnFamily(),
                            key,
                            "personal-val".getBytes(StandardCharsets.UTF_8));
            orgOwned.db()
                    .put(
                            orgOwned.chunkColumnFamily(),
                            key,
                            "org-val".getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(
                    "personal-val".getBytes(StandardCharsets.UTF_8),
                    personal.db().get(personal.chunkColumnFamily(), key));
            assertArrayEquals(
                    "org-val".getBytes(StandardCharsets.UTF_8),
                    orgOwned.db().get(orgOwned.chunkColumnFamily(), key));
        }
    }

    @Test
    void two_repos_open_independent_databases(@TempDir Path tempDir) throws Exception {
        try (OpenRepoDb alpha = PerRepoRocksDbFactory.open("alpha", tempDir);
                OpenRepoDb beta = PerRepoRocksDbFactory.open("beta", tempDir)) {
            // Different repos write to different stores; no cross-repo
            // visibility.
            byte[] key = "k".getBytes(StandardCharsets.UTF_8);
            alpha.db()
                    .put(
                            alpha.chunkColumnFamily(),
                            key,
                            "alpha-val".getBytes(StandardCharsets.UTF_8));
            beta.db()
                    .put(
                            beta.chunkColumnFamily(),
                            key,
                            "beta-val".getBytes(StandardCharsets.UTF_8));

            assertArrayEquals(
                    "alpha-val".getBytes(StandardCharsets.UTF_8),
                    alpha.db().get(alpha.chunkColumnFamily(), key));
            assertArrayEquals(
                    "beta-val".getBytes(StandardCharsets.UTF_8),
                    beta.db().get(beta.chunkColumnFamily(), key));
        }
    }
}
