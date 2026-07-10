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
package com.earasoft.prolly;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.ProllyCorruptionException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link IntegrityVerifyingNodeStore}. This decorator is the only
 * protection against silent corruption between the storage layer and the tree code — a missed
 * verification path means a flipped bit on disk poisons the Merkle DAG without warning.
 */
class IntegrityVerifyingNodeStoreTest {

    /** A corrupting store: returns hash-mismatched bytes for a planted key. */
    static class CorruptingStore implements NodeStore {
        final InMemoryNodeStore inner = new InMemoryNodeStore();
        byte[] planted; // hash to lie about
        byte[] lie; // bytes to return instead

        @Override
        public Optional<MemorySegment> read(byte[] hash) {
            if (planted != null && java.util.Arrays.equals(hash, planted)) {
                return Optional.of(MemorySegment.ofArray(lie));
            }
            return inner.read(hash);
        }

        @Override
        public byte[] write(MemorySegment data) {
            return inner.write(data);
        }

        @Override
        public byte[] write(byte[] data) {
            return inner.write(data);
        }
    }

    // ---- happy path ----

    @Test
    void valid_data_passes_through() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        IntegrityVerifyingNodeStore wrap = new IntegrityVerifyingNodeStore(inner);
        byte[] data = "hello".getBytes();
        byte[] hash = wrap.write(data);
        Optional<MemorySegment> got = wrap.read(hash);
        assertTrue(got.isPresent());
        assertArrayEquals(data, got.get().toArray(ValueLayout.JAVA_BYTE));
    }

    @Test
    void missing_hash_returns_empty() {
        IntegrityVerifyingNodeStore wrap = new IntegrityVerifyingNodeStore(new InMemoryNodeStore());
        byte[] phantom = new byte[20];
        phantom[0] = 0x42;
        assertFalse(
                wrap.read(phantom).isPresent(),
                "missing hash → empty Optional, not a corruption error");
    }

    // ---- corruption detection ----

    @Test
    void corruption_throws_with_diagnostic_message() {
        CorruptingStore corrupt = new CorruptingStore();
        IntegrityVerifyingNodeStore wrap = new IntegrityVerifyingNodeStore(corrupt);

        byte[] data = "real".getBytes();
        byte[] hash = wrap.write(data);
        corrupt.planted = hash;
        corrupt.lie = "TAMPERED".getBytes();

        ProllyCorruptionException e =
                assertThrows(ProllyCorruptionException.class, () -> wrap.read(hash));
        assertTrue(
                e.getMessage().contains("DATA CORRUPTION DETECTED"),
                "error must contain the diagnostic marker");
        assertTrue(
                e.getMessage().contains(toHex(hash)),
                "error must contain the requested hash for forensic context");
    }

    @Test
    void corruption_detection_works_with_empty_payload_lie() {
        CorruptingStore corrupt = new CorruptingStore();
        IntegrityVerifyingNodeStore wrap = new IntegrityVerifyingNodeStore(corrupt);
        byte[] hash = wrap.write("real".getBytes());
        corrupt.planted = hash;
        corrupt.lie = new byte[0]; // empty lie has a different hash than "real"
        assertThrows(ProllyCorruptionException.class, () -> wrap.read(hash));
    }

    @Test
    void single_bit_flip_detected() {
        // The whole point of content-addressing: any byte change → different hash.
        CorruptingStore corrupt = new CorruptingStore();
        IntegrityVerifyingNodeStore wrap = new IntegrityVerifyingNodeStore(corrupt);
        byte[] data = "abcdefgh".getBytes();
        byte[] hash = wrap.write(data);
        byte[] flipped = data.clone();
        flipped[0] ^= 0x01;
        corrupt.planted = hash;
        corrupt.lie = flipped;
        assertThrows(ProllyCorruptionException.class, () -> wrap.read(hash));
    }

    // ---- write passthrough ----

    @Test
    void write_delegates_to_inner_store() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        IntegrityVerifyingNodeStore wrap = new IntegrityVerifyingNodeStore(inner);
        byte[] hash = wrap.write("via wrap".getBytes());
        // Reading from the inner store directly must also return the data.
        assertTrue(inner.read(hash).isPresent(), "wrap.write must populate the inner store");
        // And reading through wrap must succeed too.
        assertArrayEquals(HashUtils.hash("via wrap".getBytes()), hash);
    }

    @Test
    void write_memory_segment_overload_passes_through() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        IntegrityVerifyingNodeStore wrap = new IntegrityVerifyingNodeStore(inner);
        byte[] data = "via segment".getBytes();
        byte[] h1 = wrap.write(data);
        byte[] h2 = wrap.write(MemorySegment.ofArray(data));
        assertArrayEquals(h1, h2, "byte[] and MemorySegment overloads must produce the same hash");
    }

    // ---- unwrap ----

    @Test
    void unwrap_returns_inner_store() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        IntegrityVerifyingNodeStore wrap = new IntegrityVerifyingNodeStore(inner);
        assertSame(
                inner,
                wrap.unwrap(),
                "unwrap must expose the underlying store for decorator-chain walks");
    }

    @Test
    void nested_wrappers_unwrap_one_layer() {
        // GC and other tools sometimes need to drill through decorator chains.
        InMemoryNodeStore inner = new InMemoryNodeStore();
        IntegrityVerifyingNodeStore outer =
                new IntegrityVerifyingNodeStore(new IntegrityVerifyingNodeStore(inner));
        NodeStore middle = outer.unwrap();
        assertInstanceOf(IntegrityVerifyingNodeStore.class, middle);
        assertSame(inner, ((IntegrityVerifyingNodeStore) middle).unwrap());
    }

    // ---- multiple reads ----

    @Test
    void corruption_detected_on_every_read() {
        // The verifier must NOT cache — each read independently re-hashes.
        CorruptingStore corrupt = new CorruptingStore();
        IntegrityVerifyingNodeStore wrap = new IntegrityVerifyingNodeStore(corrupt);
        byte[] hash = wrap.write("real".getBytes());
        corrupt.planted = hash;
        corrupt.lie = "fake".getBytes();
        assertThrows(ProllyCorruptionException.class, () -> wrap.read(hash));
        assertThrows(
                ProllyCorruptionException.class,
                () -> wrap.read(hash),
                "verifier must NOT memoize corruption status — repeated reads re-verify");
    }

    // ---- helper ----
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
