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
package com.dolthub.prolly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The ONE shared in-memory {@link Manifest} test double — same compare-and-set contract as the
 * production {@code RocksManifest} (create-only on a {@code null} expected, delete on a {@code
 * null} newHash, atomic get-then-put), with defensive byte-array copies on both read and write.
 *
 * <p>Published via this module's test-jar so every module uses <b>this</b> copy. History: the
 * test-only-stand-in audit (plans/prepublic/test-only-standin-audit.md, 2026-07-01) found TWO
 * independent copies — prolly-concurrency's (2026-06-25, this implementation) and prolly-storage's
 * (2026-07-01, written in ignorance of the first, and subtly divergent: no read-side defensive
 * copy) — consolidated here, with the contract pinned across {@code RocksManifest |
 * InMemoryManifest} by {@code ManifestContractTest} (prolly-storage) so the double can never drift
 * from production semantics unnoticed again.
 *
 * <p>{@code updateRef} succeeds IFF the current ref equals {@code expectedHash} ({@code null}
 * expected = create-only; {@code null} newHash = delete) — the atomic compare-and-set the {@code
 * Database} commit path relies on for optimistic concurrency control.
 */
public final class InMemoryManifest implements Manifest {

    private final Map<String, byte[]> refs = new ConcurrentHashMap<>();

    private static String key(String repoId, String name) {
        return repoId + " " + name;
    }

    @Override
    public Optional<byte[]> getRef(String repoId, String name) {
        byte[] v = refs.get(key(repoId, name));
        return v == null ? Optional.empty() : Optional.of(v.clone());
    }

    @Override
    public synchronized boolean updateRef(
            String repoId, String name, byte @Nullable [] newHash, byte @Nullable [] expectedHash) {
        String k = key(repoId, name);
        byte[] current = refs.get(k);
        if (expectedHash == null) {
            if (current != null) return false; // create-only
        } else {
            if (current == null || !Arrays.equals(current, expectedHash)) return false; // CAS
        }
        if (newHash == null) {
            refs.remove(k);
        } else {
            refs.put(k, newHash.clone());
        }
        return true;
    }

    @Override
    public synchronized void deleteRef(String repoId, String name) {
        refs.remove(key(repoId, name));
    }

    @Override
    public List<String> listRefs(String repoId) {
        String prefix = repoId + " ";
        List<String> out = new ArrayList<>();
        for (String k : refs.keySet()) {
            if (k.startsWith(prefix)) out.add(k.substring(prefix.length()));
        }
        return out;
    }
}
