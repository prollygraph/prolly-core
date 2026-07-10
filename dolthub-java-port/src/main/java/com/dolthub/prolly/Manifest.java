/*
 * Copyright 2026 Earasoft
 * Copyright 2021 Dolthub, Inc.
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

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The named-reference store — maps {@code (repoId, refName)} to a root hash. Everything else in the
 * prolly architecture is immutable content-addressed data; the manifest is <b>the only mutable
 * part</b>, the single place where "what is the current tip of branch X" changes.
 *
 * <p><b>Why it exists as its own interface:</b> the whole versioning model reduces every write to
 * one atomic pointer swing — build the new immutable tree, then compare-and-set the ref from the
 * root you read to the root you built. Concurrency control therefore lives entirely here, not in
 * the tree structures.
 *
 * @apiNote {@link #updateRef} is the compare-and-set: it succeeds only when the ref's current value
 *     equals {@code expectedHash}, making concurrent lost updates impossible. Two null sentinels
 *     are part of the contract (implementations null-check both): {@code newHash null} = delete the
 *     ref; {@code expectedHash null} = expect the ref <em>absent</em> (a create-only first write).
 *     Every method takes a {@code repoId} — per-repository isolation is the interface's own axis,
 *     not a caller convention.
 * @implNote <b>Collaborators / implementations:</b> the production impl is {@code RocksManifest}
 *     (prolly-storage; derived from a Rocks-backed store by {@code Database.deriveManifest}); the
 *     test double is the shared {@code InMemoryManifest} in this module's <b>test-jar</b> (one copy
 *     — two independent per-module copies were consolidated 2026-07-01 by the test-only-stand-in
 *     audit). The pair is registered in the upstream production-primitive parity registry and
 *     contract-tested across both implementations by {@code ManifestContractTest} (prolly-storage):
 *     compare-and-set lifecycle, per-repo isolation, defensive byte copies. <b>Dependents:</b>
 *     {@code Database} (prolly-storage) holds one for branch/tag resolution and the commit-time
 *     root swing.
 */
public interface Manifest {
    /**
     * The current root hash the ref points at, or empty when the ref does not exist.
     *
     * @param repoId the repository whose ref namespace to read
     * @param name the ref name (e.g. a branch or tag name)
     * @return a defensive copy of the hash — mutating it never corrupts the store
     */
    Optional<byte[]> getRef(String repoId, String name);

    /**
     * Atomically set {@code (repoId, name)} to {@code newHash} if and only if its current value is
     * {@code expectedHash} — the compare-and-set every commit's root swing rides on.
     *
     * @param newHash the new root, or null to <b>delete</b> the ref
     * @param expectedHash the value the ref must currently hold, or null to require the ref be
     *     <b>absent</b> (create-only)
     * @return true when the swap happened; false when the precondition failed (the caller re-reads
     *     and retries or reports the conflict)
     */
    boolean updateRef(
            String repoId, String name, byte @Nullable [] newHash, byte @Nullable [] expectedHash);

    /** Unconditionally remove the ref (no optimistic precondition — prefer {@link #updateRef}). */
    void deleteRef(String repoId, String name);

    /** The ref names existing under {@code repoId} (this repository only — isolation axis). */
    List<String> listRefs(String repoId);
}
