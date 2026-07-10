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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.NodeCache;
import com.dolthub.prolly.ProllyCorruptionException;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0064: {@link RocksNodeStore#setVerifyOnRead} content-verifies a node read from disk before it
 * is cached or returned — the in-store, below-the-cache replacement for the {@link
 * IntegrityVerifyingNodeStore} outer decorator. Pins the four invariants the redesign rests on:
 * valid data is untouched; a corrupted disk node fails closed (only when verification is on); and a
 * <b>cache hit serves trusted bytes without re-verifying</b> (the property that makes the hot path
 * free — disk corruption after caching does not affect a cached read).
 */
class RocksNodeStoreVerifyOnReadTest {

    private static byte[] writeNode(RocksNodeStore rocks, String text) {
        return rocks.write(MemorySegment.ofArray(text.getBytes()));
    }

    private static String readNode(RocksNodeStore rocks, byte[] hash) {
        return new String(rocks.read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE));
    }

    /**
     * A real PNOD-headered leaf node (ADR-0072) that {@code Node.fromBytes} accepts → is cacheable.
     */
    private static byte[] realNodeBytes() {
        try (com.dolthub.prolly.HeapBufferPool pool = new com.dolthub.prolly.HeapBufferPool()) {
            return new com.dolthub.prolly.FlatbufferNodeSerializer()
                    .serialize(
                            0,
                            java.util.List.of(
                                    new com.dolthub.prolly.TreeMutator.PendingItem(
                                            MemorySegment.ofArray("k".getBytes()),
                                            MemorySegment.ofArray("v".getBytes()),
                                            1L)));
        }
    }

    @Test
    void verify_on_passes_a_valid_node(@TempDir Path dir) throws Exception {
        try (RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
            byte[] hash = writeNode(rocks, "a-valid-node");
            rocks.setVerifyOnRead(true);
            assertEquals(
                    "a-valid-node",
                    readNode(rocks, hash),
                    "verification must be transparent for good data (no false positive)");
        }
    }

    @Test
    void verify_on_detects_a_corrupted_disk_node(@TempDir Path dir) throws Exception {
        try (RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
            byte[] hash = writeNode(rocks, "the-real-bytes");
            rocks.db()
                    .put(hash, "tampered-bytes".getBytes()); // simulate disk bit-rot under the key
            rocks.setVerifyOnRead(true);
            ProllyCorruptionException ex =
                    assertThrows(ProllyCorruptionException.class, () -> rocks.read(hash));
            assertTrue(
                    ex.getMessage().contains("integrity check failed"),
                    "fail closed with a clear, typed corruption error: " + ex.getMessage());
        }
    }

    @Test
    void verify_off_serves_corrupted_bytes_unchecked(@TempDir Path dir) throws Exception {
        // Documents the gate: off (the store-level default), a corrupt node is served silently —
        // the
        // status quo that production opts OUT of by leaving verification on.
        try (RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
            byte[] hash = writeNode(rocks, "the-real-bytes");
            rocks.db().put(hash, "tampered-bytes".getBytes());
            assertEquals(
                    "tampered-bytes",
                    readNode(rocks, hash),
                    "verifyOnRead defaults off — the corrupt bytes are served unchecked");
        }
    }

    @Test
    void a_cache_hit_skips_verification_and_serves_trusted_bytes(@TempDir Path dir)
            throws Exception {
        // The redesign's load-bearing property: verification is BELOW the cache. A first read
        // verifies
        // the disk bytes and caches them; corrupting the disk afterwards does not affect a cache
        // hit —
        // the cached (trusted) bytes are served without re-hashing. This is what frees the hot
        // path.
        try (RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
            rocks.setNodeCache(new NodeCache(8L * 1024 * 1024));
            rocks.setVerifyOnRead(true);
            // A real PNOD node (ADR-0072): only nodes enter the node cache, so this test needs a
            // genuine node for the first read to populate the cache (a text/TLV blob round-trips as
            // bytes but is not cached).
            byte[] node = realNodeBytes();
            byte[] hash = rocks.write(MemorySegment.ofArray(node));
            assertArrayEquals(
                    node,
                    rocks.read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    "first read verifies + caches");
            rocks.db()
                    .put(hash, "tampered-bytes".getBytes()); // corrupt the disk copy after caching
            assertArrayEquals(
                    node,
                    rocks.read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    "a cache hit serves the trusted cached bytes and skips re-verification");
        }
    }
}
