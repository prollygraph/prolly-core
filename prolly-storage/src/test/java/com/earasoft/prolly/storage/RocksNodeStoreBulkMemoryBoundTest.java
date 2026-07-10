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
package com.earasoft.prolly.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@link RocksNodeStore} <b>bulk-load memory bound</b> — the structural fix that makes
 * peak process memory a constant independent of graph size, so a large bulk load cannot
 * out-of-memory at the cap (plans/prolly-bulk-load.md Phase 1). The 2026-06-13 acceptance proved
 * the need: at 10.8M statements the killer terms scale with the <em>store</em> (RocksDB memtables +
 * block cache + pinned level-0 index/filter), not the batch.
 *
 * <p>Enabling {@code -Dprolly.rocksdb.write-buffer.mb=<N>} installs a {@link
 * org.rocksdb.WriteBufferManager} that caps memtable memory at {@code N} MiB and charges it to the
 * block cache (one bounded RocksDB RAM budget), leaves level-0 index/filter evictable (so a
 * compaction backlog cannot grow pinned native memory), and disables the write-ahead log (the bulk
 * load is re-runnable on crash). This test pins the load-bearing contract: <b>the memory-bounding
 * machinery does not corrupt data</b> — every chunk written under bulk mode, across many forced
 * memtable flushes, reads back verbatim — plus that the mode actually activated.
 *
 * <p>The <i>memory</i> bound itself (RSS becomes a constant) is an integration property validated
 * by the capped acceptance bench, not assertable in a unit test; here we pin correctness +
 * activation.
 */
class RocksNodeStoreBulkMemoryBoundTest {

    /**
     * Deterministic, content-unique chunk (the 4-byte seed prefix defeats content-addressed dedup).
     */
    private static MemorySegment chunk(int seed, int size) {
        byte[] b = new byte[size];
        b[0] = (byte) (seed >>> 24);
        b[1] = (byte) (seed >>> 16);
        b[2] = (byte) (seed >>> 8);
        b[3] = (byte) seed;
        for (int i = 4; i < size; i++) b[i] = (byte) (seed * 31 + i);
        return MemorySegment.ofArray(b);
    }

    /**
     * Set a system property, returning the prior value (or null) so a finally block can restore it.
     */
    private static String swap(String key, String value) {
        String prior = System.getProperty(key);
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
        return prior;
    }

    @Test
    void bulkMode_preservesChunkRoundTrip_acrossForcedFlushes(@TempDir Path dir) throws Exception {
        // 1 MiB memtable cap charged to a 16 MiB block cache → writing ~2 MiB of chunks forces
        // several mid-load memtable flushes through the bounded WriteBufferManager.
        String priorWb = swap("prolly.rocksdb.write-buffer.mb", "1");
        String priorBc = swap("prolly.rocksdb.block-cache.mb", "16");
        RocksNodeStore store;
        try {
            store = new RocksNodeStore(dir.resolve("bulk").toString());
        } finally {
            // The store read the props in its constructor; restore immediately to keep the global
            // window tight (other tests construct stores without these props).
            swap("prolly.rocksdb.write-buffer.mb", priorWb);
            swap("prolly.rocksdb.block-cache.mb", priorBc);
        }
        try (store) {
            assertTrue(
                    store.bulkModeActiveForTest(),
                    "write-buffer.mb > 0 must install the WriteBufferManager + disable the WAL");

            int n = 8000,
                    chunkSz = 256; // ~2 MiB total → exceeds the 1 MiB memtable cap several times
            List<byte[]> hashes = new ArrayList<>(n);
            store.beginWriteBatch();
            for (int i = 0; i < n; i++) {
                hashes.add(store.write(chunk(i, chunkSz)));
            }
            store.endWriteBatch();

            // Every chunk round-trips verbatim despite the flushes, the un-pinned L0, and the
            // disabled WAL — the memory-bounding machinery is transparent to correctness.
            for (int i = 0; i < n; i++) {
                MemorySegment read =
                        store.read(hashes.get(i))
                                .orElseThrow(
                                        () -> new AssertionError("chunk missing under bulk mode"));
                MemorySegment expected = chunk(i, chunkSz);
                assertTrue(
                        read.byteSize() == expected.byteSize() && expected.mismatch(read) == -1,
                        "chunk " + i + " must read back byte-identical under bulk mode");
            }
        }
    }

    @Test
    void withoutBulkProp_modeIsOff(@TempDir Path dir) throws Exception {
        // Default construction (no write-buffer.mb) leaves bulk mode off: WAL on, no
        // WriteBufferManager.
        String priorWb = swap("prolly.rocksdb.write-buffer.mb", null);
        try (RocksNodeStore store = new RocksNodeStore(dir.resolve("plain").toString())) {
            assertFalse(
                    store.bulkModeActiveForTest(),
                    "bulk mode must be off when write-buffer.mb is unset");
        } finally {
            swap("prolly.rocksdb.write-buffer.mb", priorWb);
        }
    }
}
