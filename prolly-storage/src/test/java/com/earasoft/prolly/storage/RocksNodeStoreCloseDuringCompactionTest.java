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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Root-cause + regression for the RocksDB close-while-compacting crash ({@code
 * prolly-storage/plans/rocksdb-graceful-shutdown.md}). The web-Google bulk-load verification hit a
 * {@code SIGSEGV} in a RocksDB background-compaction thread at the abrupt timeout-kill boundary;
 * this pins whether a <b>normal</b> (in-process, un-killed) close during an active compaction
 * crashes — the D-2 question that decides the fix.
 *
 * <p><b>Verified 2026-06-14: a normal close does NOT crash, and flushed data survives.</b> This
 * test passes — {@code db.close()} drains background compactions before {@code close()} frees the
 * block cache, so the sequential close is safe. So no change to {@code RocksNodeStore.close()} was
 * warranted; this test pins that verified-safe behaviour as a crash-safety regression.
 *
 * <p><b>This is NOT the whole story of the web-Google crash.</b> That {@code SIGSEGV} is a
 * <b>real</b> bug — but it lives in the <i>shutdown wiring</i>, not in {@code close()}: a
 * plain-{@code main} bench closes RocksDB only on normal completion, so a {@code timeout} SIGTERM
 * never runs {@code close()} and native compaction threads race process teardown. The fix is a
 * cooperative shutdown hook (an upstream bench-sources shutdown helper) that makes this
 * verified-safe {@code close()} actually run on SIGTERM. Reproduced 3/3, fixed 0/4 — see {@code
 * prolly-storage/plans/rocksdb-graceful-shutdown.md}.
 *
 * <p>Bulk mode ({@code write-buffer.mb=1}) gives a 1 MiB memtable, so a few MiB of writes forces
 * several flushes → level-0 files → a compaction backlog that is still draining when {@code
 * close()} runs. If {@code RocksNodeStore.close()} freed the block cache before a compaction thread
 * drained, this would {@code SIGSEGV} (crashing the test fork). It also verifies crash-safety:
 * flushed data survives the close + reopen.
 */
class RocksNodeStoreCloseDuringCompactionTest {

    @Test
    void normalClose_duringActiveCompaction_neitherCrashesNorLosesFlushedData(@TempDir Path dir)
            throws Exception {
        System.setProperty(
                "prolly.rocksdb.write-buffer.mb", "1"); // tiny memtable → flush+compaction churn
        try {
            for (int round = 0; round < 6; round++) {
                Path db = dir.resolve("db-" + round);
                byte[] earlyHash = null; // a hash written early enough to be flushed to an SST
                try (RocksNodeStore store = new RocksNodeStore(db.toString())) {
                    for (int i = 0; i < 20_000; i++) {
                        MemorySegment seg = MemorySegment.ofArray(new byte[256]);
                        seg.set(
                                ValueLayout.JAVA_INT_UNALIGNED,
                                0,
                                round * 20_000 + i); // distinct content → distinct hash
                        byte[] h = store.write(seg);
                        if (i == 1_000) earlyHash = h;
                    }
                    // close() fires here (try-with-resources), with a compaction backlog in flight.
                }
                // Reopen: a clean close must have flushed + drained; early (SST-resident) data
                // survives.
                try (RocksNodeStore store = new RocksNodeStore(db.toString())) {
                    assertTrue(
                            store.read(earlyHash).isPresent(),
                            "flushed data must survive a close during compaction (round "
                                    + round
                                    + ")");
                }
            }
        } finally {
            System.clearProperty("prolly.rocksdb.write-buffer.mb");
        }
    }
}
