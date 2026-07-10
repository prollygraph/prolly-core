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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A bounded, <b>lock-free</b> cache of parsed {@link Node} objects, keyed by content hash and
 * bounded by a <b>byte budget</b> — the sum of cached nodes' segment sizes — not an entry count.
 *
 * <p>It removes the cost of repeatedly reading and re-parsing the same hot node from the {@link
 * NodeStore}. It is <b>correct by construction</b>: nodes are content-addressed and therefore
 * immutable, so a cached entry can never go stale — the hard part of caching (invalidation) does
 * not arise. The same property makes lock-free reads safe: an evicted-but-still-referenced {@link
 * Node} stays alive via the garbage collector, so there is no use-after-evict hazard.
 *
 * @apiNote Thread-safe and lock-free on the read path — Sail connections share one instance across
 *     threads, and reads do not contend (no monitor, no write-on-read relink). {@code put} inserts
 *     and lets the eviction policy keep total held bytes within the budget; a budget of {@code 0}
 *     (or negative) disables caching (every {@code put} is a no-op, every {@code get} misses).
 *     {@code get} returns the cached {@link Node} or empty.
 * @implNote Backed by <b>Caffeine</b> (ADR-0040): {@code maximumWeight} = the byte budget, the
 *     {@code weigher} = {@code node.segment().byteSize()}, eviction is Window-TinyLFU. Why Caffeine
 *     rather than the original {@code synchronized} {@code LinkedHashMap} LRU: the read-path
 *     experiment series measured the synchronized monitor <i>negatively</i> scaling under
 *     concurrency (lock-free recovers ~3×) and W-TinyLFU resisting scan pollution that evicts an
 *     LRU's hot set (real-Sail hit rate 62%→87% under scans). The cache is shared across every
 *     tenant + connection thread, so that high-contention, scan-mixed regime is the default. The
 *     bound is bytes, not entries (read-path plan D-7): a {@link Node} carries a 4–16 KiB segment,
 *     so an entry count silently means hundreds of MiB across the warm Sail set. Eviction is async
 *     + approximate (not exact LRU) — tests pin invariants (bounded weight, roundtrip, counters),
 *     not eviction order. Wired onto a store via {@code RocksNodeStore.setNodeCache} (off by
 *     default). On-heap (it retains the {@code Node} objects). Collaborators: {@link Node} (the
 *     cached value, supplies its byte size), {@code RocksNodeStore} (the sole caller).
 */
public class NodeCache {

    // null when disabled (maxBytes <= 0): a null cache IS the disabled state, so the methods below
    // branch on `cache == null` rather than a separate flag (the redundant `enabled` boolean is
    // gone).
    private final @Nullable Cache<HashKey, Node> cache;

    public NodeCache(long maxBytes) {
        this.cache =
                maxBytes > 0
                        ? Caffeine.newBuilder()
                                .maximumWeight(maxBytes)
                                .weigher((HashKey k, Node n) -> (int) n.segment().byteSize())
                                .recordStats()
                                .build()
                        : null;
    }

    public Optional<Node> get(byte[] hash) {
        if (cache == null) return Optional.empty();
        return Optional.ofNullable(cache.getIfPresent(new HashKey(hash)));
    }

    public void put(byte[] hash, Node node) {
        if (cache == null) return;
        cache.put(new HashKey(hash), node);
    }

    /**
     * Total bytes currently held (forces pending eviction maintenance first) — tests + footprint
     * readout.
     */
    public long bytes() {
        if (cache == null) return 0L;
        cache.cleanUp();
        return cache.policy().eviction().map(e -> e.weightedSize().orElse(0L)).orElse(0L);
    }

    /**
     * Cumulative cache hits since construction — read-path telemetry (hit rate =
     * hits/(hits+misses)).
     */
    public long hits() {
        return cache != null ? cache.stats().hitCount() : 0L;
    }

    /** Cumulative cache misses since construction — read-path telemetry. */
    public long misses() {
        return cache != null ? cache.stats().missCount() : 0L;
    }

    /**
     * Map key over the raw content-hash bytes — content equality via {@link Arrays#equals}, hash
     * code precomputed once. Holds the hash array by reference (content-addressed hashes are
     * immutable, so no copy is needed); cheaper per access than a hex {@code String}.
     */
    private static final class HashKey {
        private final byte[] h;
        private final int hash;

        HashKey(byte[] h) {
            this.h = h;
            this.hash = Arrays.hashCode(h);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof HashKey k && Arrays.equals(h, k.h);
        }
    }
}
