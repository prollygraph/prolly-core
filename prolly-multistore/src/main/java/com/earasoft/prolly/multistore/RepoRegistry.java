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

import java.time.Duration;
import java.util.Set;

/**
 * Per-repo lifecycle registry for multi-tenant deployments. Tracks the registered set of repos
 * (eagerly loaded from {@code _repo_metadata} on boot in Step 14 of the upstream multi-tenant
 * hosting plan) and lazily opens per-repo resources of type {@code R} on first access, with LRU
 * eviction of cold repos to bound memory.
 *
 * <p>Phase 0 Step 1 of the upstream multi-tenant hosting plan. The interface ships here; Spring
 * wiring lands in Step 2; the per-repo RocksDB factory that powers the {@code resourceFactory}
 * injection lands in Step 8.
 *
 * <p><b>Type parameter:</b> {@code R} is the per-repo resource (an RDF Sail, a document store, a
 * raw database handle). Unbounded — closing on eviction goes through a {@code Consumer<R>} close
 * callback injected at construction (an RDF4J Sail uses {@code shutDown()} not {@code
 * AutoCloseable.close()}, so a type bound would over-constrain).
 *
 * <p>Genericized 2026-05-27 per the upstream registry-generification plan so the registry contract
 * is face-agnostic — necessary to move the routing layer in {@code the product's RDF REST layer}
 * into {@code its platform REST layer}.
 *
 * <p>Thread-safety contract: all methods are safe for concurrent callers. Implementations are free
 * to choose the locking discipline; the default {@link LruRepoRegistry} synchronizes the whole
 * instance because resolve misses (which trigger an open) are rare and resolve hits are cheap.
 */
public interface RepoRegistry<R> {

    /**
     * Resolve a repo by id, opening its underlying storage on first access if not already warm.
     *
     * @throws RepoNotFoundException when {@code repoId} is not in the registered set (i.e. no
     *     matching {@link #register} call has ever happened, or {@link #unregister} removed it).
     * @throws IllegalArgumentException when {@code repoId} is {@code null}.
     */
    R resolve(String repoId);

    /** Registered repo ids (regardless of warm/cold state). */
    Set<String> listRepoIds();

    /**
     * Register a new repo for resolution. Does <em>not</em> open the underlying Sail — opens happen
     * lazily on first {@link #resolve}. The lifecycle state is initialized to {@link
     * RepoLifecycleState#ACTIVE}. The registry tracks only the id + lifecycle; repo metadata
     * (description, visibility, …) belongs to the caller's own metadata store — the registry never
     * reads it (the pre-2026-07-16 {@code RepoMetadata} parameter was stored but never consumed).
     *
     * @throws IllegalStateException when the {@code repoId} is already registered. Callers that
     *     want create-or-replace semantics must {@link #unregister} first.
     */
    void register(String repoId);

    /**
     * Remove a repo from the registered set. Closes the underlying Sail if currently warm. A no-op
     * when {@code repoId} is not registered.
     */
    void unregister(String repoId);

    /**
     * Begin the quiesce protocol for a repo in {@link RepoLifecycleState#ACTIVE}. CAS-transitions
     * to {@link RepoLifecycleState#QUIESCING}; future {@link #resolve} calls see the transitional
     * state.
     *
     * <p>Step 1 ships a stub: CAS to QUIESCING then immediately unregister + close. Step 12 of the
     * multi-tenant plan extends this with real in-flight request drain via a per-repo {@link
     * java.util.concurrent.Phaser}; the {@code timeout} parameter is the bounded wait for that
     * drain.
     *
     * @throws RepoNotFoundException when {@code repoId} is not registered.
     * @throws IllegalStateException when the repo is not currently {@link
     *     RepoLifecycleState#ACTIVE}.
     */
    void quiesce(String repoId, Duration timeout);
}
