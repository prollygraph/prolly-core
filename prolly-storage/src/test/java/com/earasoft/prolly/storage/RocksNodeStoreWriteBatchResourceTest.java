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

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step 2 of {@code plans/prepublic/production-primitive-parity-gate.md} — the production {@code
 * RocksNodeStore}'s standing resource net (NodeStore CHECK-A made real).
 *
 * <p><b>Why this invariant, and why deterministic.</b> The production store batches writes into a
 * per-thread native {@link org.rocksdb.WriteBatch} ({@code beginWriteBatch} opens it, {@code write}
 * buffers into it, {@code endWriteBatch} flushes via {@code db.write} and {@code close()}s it — the
 * native off-heap is freed there). A leaked batch (begin without a disposing end) is the
 * native-memory-leak analog of the {@code DirectBufferPool} off-heap leak. Following that leak
 * test's lesson — assert the resource invariant <em>deterministically</em>, not via
 * resident-set-size or file-descriptor sampling ("flaky, GC- and output-dependent") — this uses the
 * deterministic {@link RocksNodeStore#pendingBatchBytesForTest()} accounting: in-flight WriteBatch
 * bytes grow while a batch buffers and must drain to <b>0</b> when it ends, with no accumulation
 * across churn.
 *
 * <p>Drives the store directly (this is `RocksNodeStore`'s own module, below the upstream Sail);
 * the full-Sail Rocks write path is exercised by {@code ChurnHeapBoundTest} (heap axis) — this is
 * the complementary native-WriteBatch axis.
 */
class RocksNodeStoreWriteBatchResourceTest {

    @Test
    void writeBatchBuffersThenDrainsToZero_andChurnLeavesNoNativeLeak(@TempDir Path dir)
            throws Exception {
        try (RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString())) {
            assertEquals(
                    0L,
                    store.pendingBatchBytesForTest(),
                    "no batch is buffering before the first begin");

            // 1. Lifecycle: writes between begin/end buffer into the native batch; end drains it.
            store.beginWriteBatch();
            for (int i = 0; i < 500; i++) {
                store.write(("chunk-A-" + i).getBytes());
            }
            assertTrue(
                    store.pendingBatchBytesForTest() > 0,
                    "writes between begin/end must buffer into the native WriteBatch (in-flight bytes > 0)");
            store.endWriteBatch();
            assertEquals(
                    0L,
                    store.pendingBatchBytesForTest(),
                    "endWriteBatch must flush + dispose the batch, draining in-flight bytes to 0");

            // 2. Churn: many begin/write/end cycles must leave no accumulation (no leaked batch).
            int rounds = 20;
            for (int r = 0; r < rounds; r++) {
                store.beginWriteBatch();
                for (int i = 0; i < 500; i++) {
                    store.write(("chunk-" + r + "-" + i).getBytes());
                }
                store.endWriteBatch();
                assertEquals(
                        0L,
                        store.pendingBatchBytesForTest(),
                        "each commit's WriteBatch must drain to 0 — round " + r);
            }
            assertEquals(
                    0L,
                    store.pendingBatchBytesForTest(),
                    "no leaked native WriteBatch bytes after " + rounds + " churn rounds");
        }
    }
}
