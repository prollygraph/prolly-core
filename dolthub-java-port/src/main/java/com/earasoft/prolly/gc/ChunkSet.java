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
package com.earasoft.prolly.gc;

import java.util.function.Consumer;

/**
 * A set of 20-byte chunk hashes, addressed by their bytes rather than by a hex rendering.
 *
 * @apiNote The contract is deliberately three methods. {@link #add} is a <b>test-and-set</b> — it
 *     returns {@code true} only when the hash was not already present — because that is the single
 *     operation a reachability walk needs to terminate on a shared subtree. Implementations MUST
 *     compare the FULL 20 bytes: a truncated or hashed key makes a collision indistinguishable from
 *     a repeat visit, which prunes a subtree that was never walked and leaves live chunks unmarked.
 *     That error is silent and unrecoverable, so it is not a tuning choice.
 *     <p>Implementations are NOT required to be thread-safe; {@link ConcurrentChunkSet} is the
 *     variant that is, and only the parallel walker needs it.
 * @implNote <b>Why this exists at all:</b> the walks used to traffic in {@code Set<String>} of hex.
 *     A 20-byte hash cost ~117 bytes to store that way (24 B {@code String} + 56 B LATIN1 payload +
 *     32 B {@code HashMap.Node} + table slot) and every visit allocated two objects — a {@code
 *     char[40]} inside {@link com.dolthub.prolly.HashUtils#toHex} and the {@code String} wrapping
 *     it. On a 4.08M-chunk store a single collection churned roughly 12M such pairs across the mark
 *     and the sweep, to re-encode bytes that were already uniform random. Keying on the bytes
 *     removes the allocation entirely and lets the slot index come straight from the hash's leading
 *     bytes, with no mixing function.
 *     <p><b>Collaborators:</b> {@link GcReachabilityContributor} returns one; {@code
 *     DataTreeReachability} and {@code ReachabilityWalker} fill one; {@code GarbageCollector.sweep}
 *     tests every store key against one.
 *     <p><b>Why an interface rather than the packed class:</b> a differential test that compares
 *     two walks must not have both arms share a set implementation — a bug in the structure would
 *     corrupt both identically and the comparison would report agreement. The interface lets such a
 *     test supply an independent, obviously-correct implementation as its oracle.
 */
public interface ChunkSet {

    /** The width of every key. SHA-512/20. */
    int HASH_LEN = 20;

    /**
     * An immutable empty set — for a walk with nothing to exclude.
     *
     * @apiNote {@link #add} throws rather than silently no-opping: an exclusion set is never the
     *     thing a walk accumulates into, so a call here is a wiring mistake worth hearing about.
     */
    ChunkSet EMPTY =
            new ChunkSet() {
                @Override
                public boolean add(byte[] hash) {
                    throw new UnsupportedOperationException("ChunkSet.EMPTY is immutable");
                }

                @Override
                public boolean contains(byte[] hash) {
                    return false;
                }

                @Override
                public int size() {
                    return 0;
                }

                @Override
                public void forEach(Consumer<byte[]> sink) {}
            };

    /**
     * Adds {@code hash}, reporting whether it was absent.
     *
     * @param hash exactly {@link #HASH_LEN} bytes; never retained by reference
     * @return {@code true} if this call added it, {@code false} if it was already present
     */
    boolean add(byte[] hash);

    /** Whether {@code hash} is present, comparing all {@link #HASH_LEN} bytes. */
    boolean contains(byte[] hash);

    /** How many distinct hashes are present. */
    int size();

    /** Whether no hash is present. */
    default boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Feeds every present hash to {@code sink}, in unspecified order.
     *
     * @param sink receives a fresh array per call; it may keep it
     */
    void forEach(Consumer<byte[]> sink);

    /**
     * A hex rendering of every member, for diagnostics and for equality assertions between two
     * sets.
     *
     * @apiNote Allocates a {@code String} per element — the very cost this type exists to avoid.
     *     Never call it on a walk or a sweep; it is for an error message, a report, or a test
     *     oracle.
     */
    default java.util.Set<String> toHexSet() {
        java.util.Set<String> out = new java.util.HashSet<>(Math.max(16, size() * 2));
        forEach(h -> out.add(com.dolthub.prolly.HashUtils.toHex(h)));
        return out;
    }

    /** Adds every hash in {@code other}. */
    default void addAll(ChunkSet other) {
        other.forEach(this::add);
    }
}
