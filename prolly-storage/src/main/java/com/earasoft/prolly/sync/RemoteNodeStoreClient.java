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
package com.earasoft.prolly.sync;

import com.dolthub.prolly.*;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * A {@link NodeStore} that stands in for a network-backed remote store — it wraps another store and
 * optionally injects latency.
 *
 * <p>It exists so the sync engine can be exercised against a "remote" without a real transport:
 * every {@code read} / {@code write} delegates to the wrapped store after an optional simulated
 * delay. A production deployment would replace this with a real gRPC or HTTP client speaking to a
 * remote node store; because the interface ({@link NodeStore}) is the same, {@link SyncEngine} does
 * not care which one it holds.
 *
 * @apiNote {@code setLatency(ms)} injects a per-call delay to model network round-trips in tests
 *     and benchmarks. This is a <b>simulation, not a network client</b> — there is no
 *     serialization, connection management, or failure handling; do not mistake it for the eventual
 *     transport.
 * @implNote <b>Collaborators:</b> the wrapped {@link NodeStore} (the actual backing store).
 *     <b>Dependents:</b> {@link SyncEngine} (uses it as the {@code remote} side) and the sync tests
 *     / benchmarks that need a controllable-latency remote.
 */
public class RemoteNodeStoreClient implements NodeStore {
    private final NodeStore remote;
    private long latencyMs = 0;

    public RemoteNodeStoreClient(NodeStore remote) {
        this.remote = remote;
    }

    public void setLatency(long ms) {
        this.latencyMs = ms;
    }

    @Override
    public Optional<MemorySegment> read(byte[] hash) {
        simulateLatency();
        return remote.read(hash);
    }

    @Override
    public byte[] write(MemorySegment data) {
        simulateLatency();
        return remote.write(data);
    }

    @Override
    public byte[] write(byte[] data) {
        simulateLatency();
        return remote.write(data);
    }

    private void simulateLatency() {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
            }
        }
    }
}
