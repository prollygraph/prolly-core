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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The heap-backed {@link NodeStore} — a {@link ConcurrentHashMap} of hex-hash to bytes. No
 * persistence: everything is dropped when the JVM exits (or on {@link #close}).
 *
 * <p><b>Why it exists (and why it is main-source, not a test double):</b> it is the
 * <b>production-reachable ephemeral mode</b> — the store a server boots with when no store
 * directory is configured (upstream server defaults construct it) — as well as the unit- /
 * microbenchmark-store that keeps storage input/output from dominating engine measurements, and the
 * reference arm of the backend-independence differentials. It is <em>not</em> a durable production
 * alternative to {@code RocksNodeStore}; the parity registry records exactly that rationale (rule-1
 * relaxed with reason).
 *
 * @apiNote Thread-safe: concurrent {@code read}/{@code write} is safe, and because
 *     content-addressed writes are idempotent a concurrent double-write of the same chunk is
 *     harmless — this is what lets the Sail commit independent per-transaction trees in parallel.
 *     {@link #close} (and {@link #clear}) drop every chunk; {@link #size} reports unique chunks
 *     (useful for dedup assertions in tests).
 * @implNote <b>Collaborators:</b> {@link HashUtils} (content hash + the hex key the map is keyed
 *     by), {@link NodeStore} (the contract — pinned across backends by {@code
 *     NodeStoreContractTest}'s kinds). <b>Dependents:</b> the no-store-dir wiring named above, the
 *     engine microbenchmarks, and the differential oracles that compare a real backend against this
 *     reference. Keys are hex strings (not {@code byte[]}) because array equality is identity-based
 *     — a {@code byte[]} key would break map lookups.
 */
public final class InMemoryNodeStore implements NodeStore, AutoCloseable {
    @Override
    public void close() {
        chunks.clear();
    }

    private final Map<String, byte[]> chunks = new ConcurrentHashMap<>();

    @Override
    public Optional<MemorySegment> read(byte[] hash) {
        if (hash == null) throw new IllegalArgumentException("hash must not be null");
        byte[] data = chunks.get(HashUtils.toHex(hash));
        return data == null ? Optional.empty() : Optional.of(MemorySegment.ofArray(data));
    }

    @Override
    public byte[] write(MemorySegment segment) {
        return write(segment.toArray(ValueLayout.JAVA_BYTE));
    }

    @Override
    public byte[] write(byte[] data) {
        byte[] hash = HashUtils.hash(data);
        chunks.put(HashUtils.toHex(hash), data);
        return hash;
    }

    /** Number of unique chunks currently stored. */
    public int size() {
        return chunks.size();
    }

    /** Wipe all chunks. */
    public void clear() {
        chunks.clear();
    }
}
