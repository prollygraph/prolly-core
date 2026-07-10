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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Foundational coverage for {@link InMemoryNodeStore}. Every chunk that crosses a
 * snapshot/staging/cold-tier boundary in the higher layers resolves through a NodeStore lookup —
 * the contract here is "write returns a stable content hash, read by that hash returns identical
 * bytes, no chunk ever disappears."
 */
class InMemoryNodeStoreTest {

    @Test
    void write_returns_content_address() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] hash = s.write(data);
            byte[] expected = HashUtils.hash(data);
            assertArrayEquals(expected, hash, "write must return the content-address (SHA-512/20)");
        }
    }

    @Test
    void read_null_hash_fails_fast() {
        // Step 3 fail-fast arg guard: a null hash is a caller bug, not a missing node — reject it
        // as
        // IllegalArgumentException (the NodeStore.read contract), not a deep NPE in toHex.
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            assertThrows(IllegalArgumentException.class, () -> s.read(null));
        }
    }

    @Test
    void write_then_read_roundtrip() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            byte[] data = "round-trip me".getBytes(StandardCharsets.UTF_8);
            byte[] hash = s.write(data);
            Optional<MemorySegment> got = s.read(hash);
            assertTrue(got.isPresent());
            assertArrayEquals(data, got.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void read_missing_hash_returns_empty() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            assertFalse(s.read(new byte[] {0x42}).isPresent());
            assertFalse(s.read(new byte[20]).isPresent()); // unwritten 20-byte hash
        }
    }

    @Test
    void write_is_idempotent_for_identical_input() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            byte[] data = "same".getBytes(StandardCharsets.UTF_8);
            byte[] h1 = s.write(data);
            byte[] h2 = s.write(data);
            assertArrayEquals(h1, h2);
            assertEquals(1, s.size(), "writing identical data twice must not duplicate the chunk");
        }
    }

    @Test
    void different_inputs_get_different_hashes() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            byte[] h1 = s.write("a".getBytes());
            byte[] h2 = s.write("b".getBytes());
            assertFalse(java.util.Arrays.equals(h1, h2));
            assertEquals(2, s.size());
        }
    }

    @Test
    void write_memory_segment_matches_byte_array_overload() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            byte[] data = "via segment".getBytes(StandardCharsets.UTF_8);
            byte[] viaBytes = s.write(data);
            // Re-write via MemorySegment overload — same hash.
            byte[] viaSeg = s.write(MemorySegment.ofArray(data));
            assertArrayEquals(viaBytes, viaSeg);
        }
    }

    @Test
    void write_empty_array_is_well_defined() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            byte[] hash = s.write(new byte[0]);
            assertNotNull(hash);
            assertEquals(20, hash.length);
            Optional<MemorySegment> got = s.read(hash);
            assertTrue(got.isPresent());
            assertEquals(0, got.get().byteSize());
        }
    }

    @Test
    void write_large_chunk_roundtrips() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            byte[] data = new byte[1024 * 1024]; // 1 MiB
            new java.util.Random(7).nextBytes(data);
            byte[] hash = s.write(data);
            byte[] readBack =
                    s.read(hash).orElseThrow().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            assertArrayEquals(data, readBack);
        }
    }

    @Test
    void clear_removes_all_chunks() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            s.write("a".getBytes());
            s.write("b".getBytes());
            s.write("c".getBytes());
            assertEquals(3, s.size());
            s.clear();
            assertEquals(0, s.size());
        }
    }

    @Test
    void close_drops_chunks() {
        InMemoryNodeStore s = new InMemoryNodeStore();
        s.write("data".getBytes());
        assertEquals(1, s.size());
        s.close();
        assertEquals(0, s.size());
    }

    @Test
    void size_zero_on_construction() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            assertEquals(0, s.size());
        }
    }

    @Test
    void content_addressing_dedupes_across_payloads() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            // 1000 writes of one payload, 1000 writes of another → 2 chunks.
            byte[] a = "payload a".getBytes();
            byte[] b = "payload b".getBytes();
            for (int i = 0; i < 1000; i++) {
                s.write(a);
                s.write(b);
            }
            assertEquals(2, s.size());
        }
    }

    @Test
    void chunk_with_high_bytes_roundtrips() {
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            byte[] data = new byte[256];
            for (int i = 0; i < 256; i++) data[i] = (byte) i;
            byte[] hash = s.write(data);
            byte[] readBack =
                    s.read(hash).orElseThrow().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            assertArrayEquals(data, readBack);
        }
    }

    @Test
    void concurrent_distinct_writes_each_round_trip() throws InterruptedException {
        // InMemoryNodeStore is backed by a ConcurrentHashMap so the Sail can
        // commit its independent per-transaction trees in parallel. Hammer it
        // from many threads with distinct payloads: every written hash must
        // read back its exact bytes, and the final chunk count must equal the
        // number of distinct payloads.
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            int threads = 8, perThread = 2000;
            Thread[] workers = new Thread[threads];
            java.util.concurrent.ConcurrentLinkedQueue<AssertionError> failures =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                workers[t] =
                        new Thread(
                                () -> {
                                    for (int i = 0; i < perThread; i++) {
                                        byte[] data =
                                                ("chunk-" + tid + "-" + i)
                                                        .getBytes(StandardCharsets.UTF_8);
                                        try {
                                            byte[] hash = s.write(data);
                                            byte[] back =
                                                    s.read(hash)
                                                            .orElseThrow()
                                                            .toArray(
                                                                    java.lang.foreign.ValueLayout
                                                                            .JAVA_BYTE);
                                            assertArrayEquals(data, back);
                                        } catch (AssertionError e) {
                                            failures.add(e);
                                        }
                                    }
                                });
            }
            for (Thread w : workers) w.start();
            for (Thread w : workers) w.join();
            assertTrue(
                    failures.isEmpty(),
                    () -> failures.size() + " round-trip failures, first: " + failures.peek());
            assertEquals(
                    threads * perThread,
                    s.size(),
                    "every distinct payload across all threads must be stored exactly once");
        }
    }

    @Test
    void write_batch_methods_are_harmless_no_ops() {
        // InMemoryNodeStore inherits NodeStore's default no-op beginWriteBatch/
        // endWriteBatch. TreeMutator now wraps every build in a begin/end pair,
        // so the no-op default must leave writes immediately visible.
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            s.beginWriteBatch();
            byte[] hash = s.write("batched-noop".getBytes(StandardCharsets.UTF_8));
            assertTrue(s.read(hash).isPresent(), "no-op batch must not defer visibility");
            s.endWriteBatch();
            assertTrue(s.read(hash).isPresent());
        }
    }

    @Test
    void concurrent_idempotent_writes_dedupe_to_one_chunk_each() throws InterruptedException {
        // All threads write the SAME payloads — a concurrent double-write of
        // the same content-addressed chunk is harmless (idempotent), so the
        // store must collapse them to one chunk per payload regardless of
        // interleaving. This is the property the parallel Sail commit relies on.
        try (InMemoryNodeStore s = new InMemoryNodeStore()) {
            int threads = 8;
            byte[][] payloads = new byte[50][];
            for (int i = 0; i < payloads.length; i++) {
                payloads[i] = ("shared-" + i).getBytes(StandardCharsets.UTF_8);
            }
            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                workers[t] =
                        new Thread(
                                () -> {
                                    for (int rep = 0; rep < 100; rep++) {
                                        for (byte[] p : payloads) s.write(p);
                                    }
                                });
            }
            for (Thread w : workers) w.start();
            for (Thread w : workers) w.join();
            assertEquals(payloads.length, s.size());
            // And every shared payload is still readable by its hash.
            for (byte[] p : payloads) {
                byte[] back =
                        s.read(HashUtils.hash(p))
                                .orElseThrow()
                                .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                assertArrayEquals(p, back);
            }
        }
    }
}
