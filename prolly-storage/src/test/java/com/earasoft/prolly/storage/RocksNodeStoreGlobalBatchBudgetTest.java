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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@link RocksNodeStore} <b>global</b> WriteBatch byte budget — the fix for the 2026-06-13
 * whole-file ingest's native {@code std::bad_alloc} (plans/prolly-bulk-load.md Step 4g).
 *
 * <p>The Sail builds its ~7 trees concurrently into one chunk-store column family, each tree on its
 * own thread with its own per-thread {@link org.rocksdb.WriteBatch}. The pre-fix flush was
 * <em>per-thread</em>, so the native WriteBatch peak was {@code batchFlushBytes} × thread-count
 * (~7×). The budget caps the <em>sum</em> of the in-flight batches to ≈ {@code batchFlushBytes}
 * regardless of concurrency. Two contracts:
 *
 * <ol>
 *   <li><b>Single thread:</b> the in-flight total never exceeds cap + one chunk, and drains to 0 on
 *       {@code endWriteBatch} — and every chunk is durably stored.
 *   <li><b>Concurrent:</b> the peak stays far below the per-thread sum a budget-less store would
 *       hit (the regression guard), the counter drains to 0, and every chunk is stored.
 * </ol>
 *
 * <p>Root-identity under mid-build flushing (correctness) is pinned separately by {@code
 * RocksNodeStoreBatchFlushDifferentialTest}; this test pins the <i>memory bound</i>.
 */
class RocksNodeStoreGlobalBatchBudgetTest {

    /**
     * Deterministic, content-unique chunk (the 4-byte seed prefix prevents content-addressed
     * dedup).
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

    @Test
    void singleThread_inFlightBoundedByCap_andDrains(@TempDir Path dir) throws Exception {
        long cap = 64 * 1024; // 64 KiB
        int n = 5000,
                chunkSz = 256; // ~1.25 MiB total → forces many mid-build flushes under a 64 KiB cap
        try (RocksNodeStore store = new RocksNodeStore(dir.resolve("s1").toString())) {
            store.setBatchFlushBytes(cap);
            store.beginWriteBatch();
            List<byte[]> hashes = new ArrayList<>(n);
            long maxSeen = 0;
            for (int i = 0; i < n; i++) {
                hashes.add(store.write(chunk(i, chunkSz)));
                maxSeen = Math.max(maxSeen, store.pendingBatchBytesForTest());
            }
            assertTrue(
                    maxSeen <= cap + chunkSz,
                    "in-flight WriteBatch bytes must stay <= cap + one chunk; was " + maxSeen);
            store.endWriteBatch();
            assertEquals(
                    0L,
                    store.pendingBatchBytesForTest(),
                    "budget must drain to 0 on endWriteBatch");
            for (byte[] h : hashes) {
                assertTrue(store.read(h).isPresent(), "every chunk must be durably stored");
            }
        }
    }

    @Test
    void concurrent_sharedBudget_capsBelowPerThreadSum(@TempDir Path dir) throws Exception {
        long cap = 256 * 1024; // 256 KiB shared budget
        int threads = 4, perThread = 4000, chunkSz = 256;
        try (RocksNodeStore store = new RocksNodeStore(dir.resolve("s2").toString())) {
            store.setBatchFlushBytes(cap);
            AtomicLong maxSeen = new AtomicLong();
            CountDownLatch start = new CountDownLatch(1);
            List<List<byte[]>> perThreadHashes = new ArrayList<>();
            List<Thread> ts = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                List<byte[]> hs = Collections.synchronizedList(new ArrayList<>(perThread));
                perThreadHashes.add(hs);
                final int tid = t;
                Thread th =
                        new Thread(
                                () -> {
                                    try {
                                        start.await();
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                    store.beginWriteBatch();
                                    for (int i = 0; i < perThread; i++) {
                                        hs.add(store.write(chunk(tid * perThread + i, chunkSz)));
                                        maxSeen.accumulateAndGet(
                                                store.pendingBatchBytesForTest(), Math::max);
                                    }
                                    store.endWriteBatch();
                                });
                ts.add(th);
                th.start();
            }
            start.countDown();
            for (Thread th : ts) th.join();
            // A per-thread-only (budget-less) store would peak ≈ threads × cap; the shared budget
            // keeps the in-flight total far below that — the regression guard for the fix.
            assertTrue(
                    maxSeen.get() < (long) threads * cap,
                    "shared budget must cap the in-flight total below the per-thread sum ("
                            + ((long) threads * cap)
                            + "); peak was "
                            + maxSeen.get());
            assertEquals(
                    0L,
                    store.pendingBatchBytesForTest(),
                    "budget must drain to 0 after all threads end");
            for (List<byte[]> hs : perThreadHashes) {
                for (byte[] h : hs) {
                    assertTrue(store.read(h).isPresent(), "every chunk must be durably stored");
                }
            }
        }
    }
}
