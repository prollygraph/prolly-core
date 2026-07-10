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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A pinned, self-reopening, LRU-bounded warm registry of per-repo resources of type {@code R} (Step
 * 0.2c / R-1..R-5 of the upstream versioning-service plan).
 *
 * <p>It differs from {@link LruRepoRegistry} in one load-bearing way: a caller does not merely
 * {@code resolve()} a resource (and then use it, unprotected, while a concurrent resolve of another
 * repo evicts and closes it) — it {@link #acquire} a {@link Pinned} handle, which <b>pins</b> the
 * resource for as long as the handle is open. <b>A pinned resource is never evicted</b> (R-4). That
 * closes the evict-in-use window, which for a Sail over RocksDB is a data-corruption-class bug
 * (closing the store under a live query). Resources open lazily on first acquire (R-5), are closed
 * on eviction (R-2), and re-open from the factory on a later acquire after eviction (R-3).
 *
 * @param <R> the per-repo resource type (e.g. a {@code PerRepoSail} bundle).
 * @apiNote Always use try-with-resources:
 *     {@snippet : try (var pin = registry.acquire(repoId)) { use(pin.resource()); } }
 *     The handle's {@link Pinned#close()} releases the pin; failing to close it leaks a pin and
 *     makes the entry permanently un-evictable. Pins stack: N acquires of the same repo need N
 *     closes before it is evictable again.
 * @implNote Every method synchronizes on the registry instance, so a pin and the eviction decision
 *     are taken atomically — the invariant "never evict a pinned entry" cannot be raced. The cap is
 *     <em>soft under pin pressure</em>: when every warm entry is pinned, the warm set may exceed
 *     {@code warmSetSize} (you cannot close what is in use); the excess is reclaimed as entries
 *     unpin. Eviction scans the access-ordered warm map for the eldest <em>unpinned</em> victim
 *     rather than relying on {@link LinkedHashMap#removeEldestEntry} (which can only judge the
 *     eldest, and the eldest may be pinned).
 */
public final class PinnedLruRegistry<R> {

    private static final Logger LOG = LoggerFactory.getLogger(PinnedLruRegistry.class);

    /**
     * Registered repo ids — the set {@link #acquire} is allowed to open. The factory owns how to
     * (re)open each (including its sail type); the registry only needs to know which ids exist, so
     * it does not retain the metadata.
     */
    private final Set<String> registered = new HashSet<>();

    /**
     * Warm resources, access-ordered (eldest first). Not capped via removeEldestEntry — see {@link
     * #evictIfNeeded()} (pinned entries must survive over-cap).
     */
    private final LinkedHashMap<String, R> warm = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Active pin counts per warm repoId. Absent ⇒ zero. An entry with count &gt; 0 is never
     * evicted.
     */
    private final Map<String, Integer> pinCounts = new HashMap<>();

    private final Function<String, R> factory;
    private final Consumer<R> closeCallback;
    private final int warmSetSize;

    private final LongAdder evictions = new LongAdder();

    /**
     * @param factory opens a resource for a repoId — called only on an acquire miss (cold or
     *     post-eviction). For the sail registry this re-opens from disk by the repo's {@code
     *     sailType} (R-3).
     * @param closeCallback closes an evicted resource. Orphan-tolerant: thrown {@link
     *     RuntimeException}s are swallowed + logged so eviction never throws.
     * @param warmSetSize soft cap on warm resources; eviction order is LRU among <em>unpinned</em>
     *     entries. Must be ≥ 1.
     */
    public PinnedLruRegistry(
            Function<String, R> factory, Consumer<R> closeCallback, int warmSetSize) {
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        if (closeCallback == null) {
            throw new IllegalArgumentException("closeCallback must not be null");
        }
        if (warmSetSize < 1) {
            throw new IllegalArgumentException("warmSetSize must be >= 1");
        }
        this.factory = factory;
        this.closeCallback = closeCallback;
        this.warmSetSize = warmSetSize;
    }

    /** Register a repo as eligible for {@link #acquire}. Idempotent on the id. */
    public synchronized void register(String repoId) {
        if (repoId == null) {
            throw new IllegalArgumentException("repoId must not be null");
        }
        registered.add(repoId);
    }

    /**
     * Acquire a pinned handle to {@code repoId}'s resource, opening it lazily on a miss. The
     * resource is pinned (never evicted) until the returned handle is {@link Pinned#close()
     * closed}.
     *
     * @throws RepoNotFoundException if {@code repoId} is not registered.
     */
    public synchronized Pinned acquire(String repoId) {
        if (repoId == null) {
            throw new IllegalArgumentException("repoId must not be null");
        }
        if (!registered.contains(repoId)) {
            throw new RepoNotFoundException(repoId);
        }
        R resource = warm.get(repoId); // access-order touch ⇒ MRU
        if (resource == null) {
            LOG.debug("opening resource for repo={} (cold)", repoId);
            resource = factory.apply(repoId);
            warm.put(repoId, resource);
        }
        pinCounts.merge(repoId, 1, Integer::sum); // PIN before any eviction can run
        evictIfNeeded();
        return new Pinned(repoId, resource);
    }

    /** Drop one pin. Called only by {@link Pinned#close()} under the monitor. */
    private void release(String repoId) {
        Integer count = pinCounts.get(repoId);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            pinCounts.remove(repoId);
        } else {
            pinCounts.put(repoId, count - 1);
        }
        evictIfNeeded(); // reclaim soft over-cap now that one unpinned
    }

    /**
     * Evict eldest <em>unpinned</em> entries until at cap, or stop when all remaining are pinned.
     */
    private void evictIfNeeded() {
        while (warm.size() > warmSetSize) {
            String victim = null;
            for (String id : warm.keySet()) { // access order: eldest first
                if (pinCounts.getOrDefault(id, 0) == 0) {
                    victim = id;
                    break;
                }
            }
            if (victim == null) {
                return; // every warm entry is pinned — soft over-cap
            }
            R resource = warm.remove(victim);
            closeQuietly(victim, resource);
            evictions.increment();
        }
    }

    private void closeQuietly(String repoId, R resource) {
        try {
            closeCallback.accept(resource);
        } catch (RuntimeException e) {
            LOG.warn("repo={} close callback failed; orphan logged", repoId, e);
        }
    }

    /**
     * Close every warm resource and empty the warm set — <b>shutdown semantics</b>, added for the
     * registry's first production consumer (a document-store multi-repo wiring): a Spring {@code
     * destroyMethod} needs a way to release every per-repo store at context shutdown.
     *
     * <p>Live pins are logged (WARN) but do <em>not</em> block the close: at shutdown the server
     * has stopped serving, so a remaining pin is a leak being cleaned up, not an in-flight request.
     * The registered-id set is untouched — a later {@link #acquire} re-opens from the factory, so
     * the instance stays usable (which is also what makes this testable without reconstructing).
     */
    public synchronized void closeAll() {
        for (Map.Entry<String, R> entry : warm.entrySet()) {
            int pins = pinCounts.getOrDefault(entry.getKey(), 0);
            if (pins > 0) {
                LOG.warn(
                        "repo={} closed by closeAll() with {} live pin(s) — pin leak cleaned at shutdown",
                        entry.getKey(),
                        pins);
            }
            closeQuietly(entry.getKey(), entry.getValue());
        }
        warm.clear();
        pinCounts.clear();
    }

    // ---- introspection (visible for testing + telemetry) ----------------

    /** Whether the repo is currently warm. */
    public synchronized boolean isWarm(String repoId) {
        return warm.containsKey(repoId);
    }

    /** Current warm count (may exceed {@code warmSetSize} when entries are pinned). */
    public synchronized int warmSize() {
        return warm.size();
    }

    /** Current pin count for a repo (0 when unpinned or not warm). */
    public synchronized int pinCount(String repoId) {
        return pinCounts.getOrDefault(repoId, 0);
    }

    /** Total evictions since construction (telemetry). */
    public long evictionCount() {
        return evictions.sum();
    }

    /** Configured soft warm-set cap. */
    public int warmSetSize() {
        return warmSetSize;
    }

    /**
     * A held pin on a warm resource. {@link #close()} releases the pin; the resource becomes
     * evictable again only when every pin on it is released.
     */
    public final class Pinned implements AutoCloseable {
        private final String repoId;
        private final R resource;
        private boolean released = false;

        Pinned(String repoId, R resource) {
            this.repoId = repoId;
            this.resource = resource;
        }

        /** The pinned resource — safe to use until this handle is closed. */
        public R resource() {
            return resource;
        }

        /** Release the pin. Idempotent. */
        @Override
        public void close() {
            synchronized (PinnedLruRegistry.this) {
                if (released) {
                    return;
                }
                released = true;
                release(repoId);
            }
        }
    }
}
