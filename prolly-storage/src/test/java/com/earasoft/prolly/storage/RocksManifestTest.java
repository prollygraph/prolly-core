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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.rocksdb.RocksDB;

/**
 *
 *
 * <h3>RocksManifest CAS + Multi-Tenancy Test</h3>
 *
 * <p>Pins the contract of {@link com.earasoft.prolly.storage.RocksManifest}:
 *
 * <ol>
 *   <li>{@code updateRef(repoId, name, newHash, expectedHash=null)} succeeds only when no current
 *       value exists; second call with {@code expected=null} on the same key must return false.
 *   <li>{@code updateRef} with a non-null {@code expectedHash} succeeds only when the current value
 *       byte-equals expected (compare-and-swap).
 *   <li>{@code listRefs} returns only refs from the requested repo — multi-tenancy isolation is
 *       enforced.
 *   <li>{@code deleteRef} and {@code updateRef(newHash=null)} both remove the ref.
 *   <li>UTF-8 bytes round-trip cleanly through repo IDs and ref names — regression guard for the
 *       {@code String.getBytes()} fix made in the production-review pass.
 * </ol>
 *
 * <p><b>The Gap:</b> {@code RocksManifest} had zero direct test references before this. CAS +
 * multi-tenancy is the foundation of {@code Database.commit}; a regression in either silently
 * corrupts branch heads or leaks refs across repos.
 */
public class RocksManifestTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- RocksManifest CAS + Multi-Tenancy Test ---");
        Path tempDir = Files.createTempDirectory("prolly-rocks-manifest");
        RocksDB.loadLibrary();

        try (org.rocksdb.Options opts = new org.rocksdb.Options().setCreateIfMissing(true);
                RocksDB db = RocksDB.open(opts, tempDir.toString())) {

            RocksManifest m = new RocksManifest(db);
            byte[] h1 = bytes(20, 0x11);
            byte[] h2 = bytes(20, 0x22);
            byte[] h3 = bytes(20, 0x33);
            byte[] hWrong = bytes(20, 0xAA);

            // Oracle 1: first creation with expected=null succeeds; second fails.
            if (!m.updateRef("repo", "main", h1, null)) {
                throw new RuntimeException("first updateRef should have succeeded");
            }
            if (m.updateRef("repo", "main", h2, null)) {
                throw new RuntimeException(
                        "second updateRef with expected=null should fail (current is non-null)");
            }
            byte[] got = m.getRef("repo", "main").orElseThrow();
            if (!Arrays.equals(got, h1)) {
                throw new RuntimeException("getRef returned wrong head after creation");
            }
            System.out.println("Initial-create CAS semantics work. (1/5)");

            // Oracle 2: CAS — succeeds only when expected == current.
            if (!m.updateRef("repo", "main", h2, h1)) {
                throw new RuntimeException("CAS with correct expected should succeed");
            }
            if (m.updateRef("repo", "main", h3, hWrong)) {
                throw new RuntimeException("CAS with wrong expected should fail");
            }
            byte[] after = m.getRef("repo", "main").orElseThrow();
            if (!Arrays.equals(after, h2)) {
                throw new RuntimeException("Failed CAS should not have changed head");
            }
            System.out.println("CAS update semantics work. (2/5)");

            // Oracle 3: listRefs respects the repoId — refs in repoA are not
            // visible in repoB.
            m.updateRef("alpha", "main", h1, null);
            m.updateRef("alpha", "feature/x", h2, null);
            m.updateRef("beta", "main", h3, null);

            List<String> alphaRefs = m.listRefs("alpha");
            if (!setEquals(alphaRefs, List.of("main", "feature/x"))) {
                throw new RuntimeException("alpha refs wrong: " + alphaRefs);
            }
            List<String> betaRefs = m.listRefs("beta");
            if (!setEquals(betaRefs, List.of("main"))) {
                throw new RuntimeException("beta refs wrong: " + betaRefs);
            }
            // The "repo" entries from earlier oracles must remain isolated too.
            List<String> repoRefs = m.listRefs("repo");
            if (!setEquals(repoRefs, List.of("main"))) {
                throw new RuntimeException("repo refs wrong: " + repoRefs);
            }
            System.out.println("Multi-tenancy isolation works. (3/5)");

            // Oracle 4: deleteRef and updateRef(null) both remove.
            m.deleteRef("alpha", "feature/x");
            if (m.getRef("alpha", "feature/x").isPresent()) {
                throw new RuntimeException("deleteRef did not remove");
            }

            m.updateRef("alpha", "main", null, h1);
            if (m.getRef("alpha", "main").isPresent()) {
                throw new RuntimeException("updateRef(newHash=null) did not delete");
            }
            System.out.println("Removal via deleteRef and updateRef(null) work. (4/5)");

            // Oracle 5: UTF-8 round-trip on non-ASCII repo + ref names.
            String repoCyr = "репо-1";
            String branchCyr = "ветка/мастер";
            if (!m.updateRef(repoCyr, branchCyr, h1, null)) {
                throw new RuntimeException("UTF-8 repo/branch updateRef failed");
            }
            Optional<byte[]> retrieved = m.getRef(repoCyr, branchCyr);
            if (retrieved.isEmpty() || !Arrays.equals(retrieved.get(), h1)) {
                throw new RuntimeException("UTF-8 repo/branch round-trip lost data");
            }
            List<String> cyrRefs = m.listRefs(repoCyr);
            if (cyrRefs.size() != 1 || !cyrRefs.get(0).equals(branchCyr)) {
                throw new RuntimeException("UTF-8 listRefs returned: " + cyrRefs);
            }
            System.out.println("UTF-8 round-trip preserved. (5/5)");

            System.out.println("--- RocksManifest CAS + Multi-Tenancy Test PASSED ---");
        }
    }

    private static byte[] bytes(int n, int fill) {
        byte[] out = new byte[n];
        Arrays.fill(out, (byte) fill);
        return out;
    }

    private static <T> boolean setEquals(List<T> a, List<T> b) {
        return a.size() == b.size() && a.containsAll(b) && b.containsAll(a);
    }
}
