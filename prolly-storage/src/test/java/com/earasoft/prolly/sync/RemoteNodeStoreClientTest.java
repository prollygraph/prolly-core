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

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.InMemoryNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link RemoteNodeStoreClient} — currently a thin delegating wrapper with optional
 * latency injection. Pin delegation, write idempotence (content-addressing), and latency behavior
 * so that any future migration to a real RPC client preserves these contracts.
 */
class RemoteNodeStoreClientTest {

    @Test
    void delegates_read_to_remote() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        byte[] hash = inner.write("hello".getBytes());

        RemoteNodeStoreClient client = new RemoteNodeStoreClient(inner);
        Optional<MemorySegment> got = client.read(hash);
        assertTrue(got.isPresent());
        assertArrayEquals("hello".getBytes(), got.get().toArray(ValueLayout.JAVA_BYTE));
    }

    @Test
    void delegates_write_byte_array_to_remote() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        RemoteNodeStoreClient client = new RemoteNodeStoreClient(inner);
        byte[] hash = client.write("via client".getBytes());
        // Reading directly from inner must also succeed.
        assertTrue(
                inner.read(hash).isPresent(),
                "client.write must populate the underlying inner store");
    }

    @Test
    void delegates_write_memory_segment_to_remote() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        RemoteNodeStoreClient client = new RemoteNodeStoreClient(inner);
        byte[] data = "via segment".getBytes();
        byte[] h1 = client.write(data);
        byte[] h2 = client.write(MemorySegment.ofArray(data));
        assertArrayEquals(h1, h2);
    }

    @Test
    void missing_hash_returns_empty() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        RemoteNodeStoreClient client = new RemoteNodeStoreClient(inner);
        byte[] phantom = new byte[20];
        phantom[0] = 0x42;
        assertFalse(client.read(phantom).isPresent());
    }

    // ---- latency simulation ----

    @Test
    void zero_latency_is_default() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        RemoteNodeStoreClient client = new RemoteNodeStoreClient(inner);
        long t0 = System.nanoTime();
        client.write("fast".getBytes());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(
                elapsedMs < 100,
                "default latency=0 must not delay calls (elapsed=" + elapsedMs + "ms)");
    }

    @Test
    void positive_latency_delays_calls() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        RemoteNodeStoreClient client = new RemoteNodeStoreClient(inner);
        client.setLatency(50);
        byte[] hash = inner.write("delayed".getBytes());

        long t0 = System.nanoTime();
        client.read(hash);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(
                elapsedMs >= 50,
                "50ms latency setting must produce at least 50ms delay (got " + elapsedMs + "ms)");
    }

    @Test
    void latency_applies_to_each_call_independently() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        RemoteNodeStoreClient client = new RemoteNodeStoreClient(inner);
        client.setLatency(20);
        byte[] hash = inner.write("d".getBytes());

        long t0 = System.nanoTime();
        client.read(hash);
        client.read(hash);
        client.read(hash);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs >= 60, "3 reads × 20ms must total ≥60ms (got " + elapsedMs + "ms)");
    }

    @Test
    void setLatency_can_be_disabled_back_to_zero() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        RemoteNodeStoreClient client = new RemoteNodeStoreClient(inner);
        client.setLatency(50);
        client.setLatency(0); // re-disable
        byte[] hash = inner.write("fast again".getBytes());
        long t0 = System.nanoTime();
        client.read(hash);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 30, "latency=0 must disable delays (got " + elapsedMs + "ms)");
    }

    @Test
    void write_returns_content_address_unchanged() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        RemoteNodeStoreClient client = new RemoteNodeStoreClient(inner);
        byte[] data = "addressed".getBytes();
        byte[] viaClient = client.write(data);
        byte[] viaInner = inner.write(data);
        assertArrayEquals(
                viaInner,
                viaClient,
                "client must not alter the content hash returned by the inner store");
    }
}
