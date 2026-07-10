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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link RepoRegistry} implementation: an LRU-bounded warm cache of per-repo resources of
 * type {@code R}, over a registered-set of repo ids. Repos are eagerly registered on boot from the
 * shared admin DB's {@code _repo_metadata} CF (Step 14 of the multi-tenant plan); the underlying
 * resource is opened lazily on first {@link #resolve} and closed via the injected {@code
 * closeCallback} when the LRU evicts it.
 *
 * <p>Genericized 2026-05-27 per the upstream registry-generification plan — Phase 0 Step 2. The
 * previous shape returned the RDF face's Sail class directly; the {@code R} type plus a {@link
 * Consumer Consumer&lt;R&gt;} close callback let the JSON + BOM + future faces parameterize this
 * registry against their own resource types without touching the platform module. an RDF4J Sail
 * isn't itself {@code AutoCloseable} (it uses {@code shutDown()} instead) so the close path goes
 * through a {@code Consumer<R>} rather than a type bound.
 *
 * <p>Thread-safety: every method synchronizes on the registry. Resolve misses (which open a
 * resource) are rare; resolve hits are a single map lookup. The hot-path cost is acceptable under
 * this lock.
 *
 * <p>The resource factory + close callback are injected via the constructor so this class doesn't
 * depend on any face's resource type — Step 8 of the multi-tenant plan wires the per-repo RocksDB
 * factory in for the RDF face; future faces wire their own factories.
 */
public final class LruRepoRegistry<R> implements RepoRegistry<R> {

    private static final Logger LOG = LoggerFactory.getLogger(LruRepoRegistry.class);

    /** Registered repo ids. Metadata lives in the caller's own store — never read here. */
    private final Set<String> registered = new HashSet<>();

    /** Lifecycle state per registered repo. */
    private final Map<String, RepoLifecycleState> states = new HashMap<>();

    /**
     * Warm resource instances, access-ordered (eldest = least-recently used). Capped at {@link
     * #warmSetSize}; on overflow, {@code removeEldestEntry} closes the evicted resource via {@link
     * #closeCallback}.
     */
    private final LinkedHashMap<String, R> warm;

    private final Function<String, R> resourceFactory;
    private final Consumer<R> closeCallback;
    private final int warmSetSize;

    /**
     * Count of LRU evictions (a warm resource closed to make room) — telemetry for {@code
     * prolly.repo.warmset.evictions}. A rising rate means more than {@link #warmSetSize} repos are
     * hot, so resolves keep re-opening cold (raise the warm-set size). Plain {@code LongAdder} — no
     * Micrometer in the hosting product; a rest-layer binder surfaces it.
     */
    private final java.util.concurrent.atomic.LongAdder evictions =
            new java.util.concurrent.atomic.LongAdder();

    /** Total warm-set evictions since construction (telemetry). */
    public long evictionCount() {
        return evictions.sum();
    }

    /** Current number of warm resources held (telemetry; ≤ {@link #warmSetSize}). */
    public synchronized int warmSize() {
        return warm.size();
    }

    /**
     * @param resourceFactory function that opens (or returns) a resource of type {@code R} for a
     *     given repoId. Called only on resolve-miss; must be thread-safe under the registry's lock.
     * @param closeCallback close hook invoked on LRU eviction and on {@link #unregister} when a
     *     warm resource exists. Receives the resource instance. Implementations swallow checked
     *     exceptions and log warnings (orphan-tolerant — the registry never throws during
     *     eviction).
     * @param warmSetSize maximum number of warm resource instances to keep in memory. Eviction
     *     order is LRU.
     */
    public LruRepoRegistry(
            Function<String, R> resourceFactory, Consumer<R> closeCallback, int warmSetSize) {
        if (resourceFactory == null) {
            throw new IllegalArgumentException("resourceFactory must not be null");
        }
        if (closeCallback == null) {
            throw new IllegalArgumentException("closeCallback must not be null");
        }
        if (warmSetSize < 1) {
            throw new IllegalArgumentException("warmSetSize must be >= 1");
        }
        this.resourceFactory = resourceFactory;
        this.closeCallback = closeCallback;
        this.warmSetSize = warmSetSize;
        // accessOrder=true → get() and put(existingKey, ...) reorder to MRU.
        this.warm =
                new LinkedHashMap<>(warmSetSize + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, R> eldest) {
                        if (size() > warmSetSize) {
                            closeQuietly(eldest.getKey(), eldest.getValue());
                            evictions.increment();
                            return true;
                        }
                        return false;
                    }
                };
    }

    @Override
    public synchronized R resolve(String repoId) {
        if (repoId == null) {
            throw new IllegalArgumentException("repoId must not be null");
        }
        if (!registered.contains(repoId)) {
            throw new RepoNotFoundException(repoId);
        }
        R resource = warm.get(repoId);
        if (resource == null) {
            LOG.debug("opening resource for repo={} (cold)", repoId);
            resource = resourceFactory.apply(repoId);
            // LinkedHashMap calls removeEldestEntry after put.
            warm.put(repoId, resource);
        }
        return resource;
    }

    @Override
    public synchronized Set<String> listRepoIds() {
        return Set.copyOf(registered);
    }

    @Override
    public synchronized void register(String repoId) {
        if (repoId == null) {
            throw new IllegalArgumentException("repoId must not be null");
        }
        if (registered.contains(repoId)) {
            throw new IllegalStateException("repo already registered: " + repoId);
        }
        registered.add(repoId);
        states.put(repoId, RepoLifecycleState.ACTIVE);
    }

    @Override
    public synchronized void unregister(String repoId) {
        if (!registered.contains(repoId)) {
            return;
        }
        registered.remove(repoId);
        states.remove(repoId);
        R resource = warm.remove(repoId);
        if (resource != null) {
            closeQuietly(repoId, resource);
        }
    }

    @Override
    public synchronized void quiesce(String repoId, Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("timeout must not be null");
        }
        if (!registered.contains(repoId)) {
            throw new RepoNotFoundException(repoId);
        }
        RepoLifecycleState current = states.get(repoId);
        if (current != RepoLifecycleState.ACTIVE) {
            throw new IllegalStateException("repo not ACTIVE: " + repoId + " is " + current);
        }
        // Step 12 extends this with in-flight request drain via Phaser.
        // Step 1 ships the CAS + immediate unregister.
        states.put(repoId, RepoLifecycleState.QUIESCING);
        unregister(repoId);
    }

    // ---- introspection (visible for testing) ---------------------------
    // public (not package-private) so downstream products' tests can
    // inspect lifecycle state too, not just this module's own tests.

    /** Current lifecycle state of a repo, or {@code null} if not registered. */
    public synchronized @Nullable RepoLifecycleState stateOf(String repoId) {
        return states.get(repoId);
    }

    /** Whether the repo is currently warm in the LRU. */
    public synchronized boolean isWarm(String repoId) {
        return warm.containsKey(repoId);
    }

    /** Configured warm-set capacity. */
    public int warmSetSize() {
        return warmSetSize;
    }

    private void closeQuietly(String repoId, R resource) {
        try {
            closeCallback.accept(resource);
        } catch (RuntimeException e) {
            LOG.warn("repo={} close callback failed; orphan logged", repoId, e);
        }
    }
}
