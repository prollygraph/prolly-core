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
package com.earasoft.prolly.playground;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link NodeStore} decorator over {@link InMemoryNodeStore} that remembers every write —
 * insertion-ordered, so the playground can render the content-addressed store as bands — and tracks
 * the hashes written since the last {@link #drainWrites()}, which is exactly the set a single
 * operation minted (the write's spine, measured on the real engine rather than claimed).
 *
 * <p>Content addressing makes re-writing an existing chunk idempotent; a re-write of a known hash
 * is NOT recorded as new. Synchronized throughout — the playground is a single-user tool.
 */
public class RecordingNodeStore implements NodeStore, AutoCloseable {

    private final NodeStore delegate;
    private final Map<String, Integer> insertionOrder = new LinkedHashMap<>();
    private final Set<String> sinceDrain = new LinkedHashSet<>();

    /** Records over the ephemeral in-memory store (the default playground mode). */
    public RecordingNodeStore() {
        this(new InMemoryNodeStore());
    }

    /**
     * Records over any store — the disk engines pass a {@code FileNodeStore}/{@code
     * RocksNodeStore}.
     */
    public RecordingNodeStore(NodeStore delegate) {
        this.delegate = delegate;
    }

    /**
     * Prime the recording with hashes that already exist in a reopened disk store, so {@code
     * allHashes()} reflects the whole store, not just this process's writes. Order is the store's
     * enumeration order — "first-write order" is only exact within one process lifetime.
     */
    public synchronized void seed(Iterable<byte[]> existing) {
        for (byte[] h : existing)
            insertionOrder.putIfAbsent(HashUtils.toHex(h), insertionOrder.size());
    }

    @Override
    public synchronized void close() {
        if (delegate instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception e) {
                throw new IllegalStateException("closing the delegate store failed", e);
            }
        }
    }

    private final Set<String> readsSinceDrain = new LinkedHashSet<>();

    @Override
    public synchronized Optional<MemorySegment> read(byte[] hash) {
        Optional<MemorySegment> r = delegate.read(hash);
        if (r.isPresent()) readsSinceDrain.add(HashUtils.toHex(hash));
        return r;
    }

    /** Hashes read since the previous drain, in first-read order — one operation's real descent. */
    public synchronized Set<String> drainReads() {
        Set<String> out = new LinkedHashSet<>(readsSinceDrain);
        readsSinceDrain.clear();
        return out;
    }

    @Override
    public synchronized byte[] write(MemorySegment data) {
        byte[] hash = delegate.write(data);
        record(HashUtils.toHex(hash));
        return hash;
    }

    @Override
    public synchronized byte[] write(byte[] data) {
        byte[] hash = delegate.write(data);
        record(HashUtils.toHex(hash));
        return hash;
    }

    private void record(String hex) {
        if (insertionOrder.putIfAbsent(hex, insertionOrder.size()) == null) {
            sinceDrain.add(hex);
        }
    }

    /** Hashes minted since the previous drain — one operation's real write set. */
    public synchronized Set<String> drainWrites() {
        Set<String> out = new LinkedHashSet<>(sinceDrain);
        sinceDrain.clear();
        return out;
    }

    /** Every hash ever stored, in first-write order. */
    public synchronized Set<String> allHashes() {
        return new LinkedHashSet<>(insertionOrder.keySet());
    }
}
