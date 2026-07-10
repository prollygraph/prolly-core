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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@link RocksNodeStore} native-memory attribution gauges (the resource-bounds-and-metrics
 * work): the per-store {@code totalSstBytes} / {@code memStatsLine} / {@code rocksDbFullStats}
 * readouts and the <b>static</b> cross-store {@code aggregate*Bytes} / {@code liveStoreCount}
 * rollups. These name <i>which</i> native consumer competes with the Java heap for the process's
 * resident set — the observability the project's "attribute a measured memory anomaly before you
 * file it" discipline rests on (the 2026-06-13 whole-file ingest {@code std::bad_alloc} wall) — yet
 * they shipped untested. This exercises them against <b>real</b> RocksDB instances, because a
 * property read on a fake proves nothing about the actual native counters.
 *
 * <p><b>Why two stores.</b> The aggregate gauges sum across every live store (deduped by RocksDB
 * handle) by walking a process-wide {@code LIVE} registry; a single-store reading would never
 * traverse that loop. Closing one store then proves it drops out of the live count — the {@code
 * LIVE.remove} in {@code close()} plus the {@code !s.closed} guard in {@code aggregate}.
 */
class RocksNodeStoreNativeStatsTest {

    private static MemorySegment chunk(int seed) {
        byte[] b = new byte[256];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (seed * 31 + i);
        }
        return MemorySegment.ofArray(b);
    }

    @Test
    void perStoreAndAggregateNativeGaugesReadRealRocksProperties(@TempDir Path dir)
            throws Exception {
        int before = RocksNodeStore.liveStoreCount();
        try (RocksNodeStore a = new RocksNodeStore(dir.resolve("a").toString());
                RocksNodeStore b = new RocksNodeStore(dir.resolve("b").toString())) {
            a.write(chunk(1));
            b.write(chunk(2));
            a.flushDurable(); // materialize an SST so the size gauges have a real file to measure

            // Both freshly-opened stores register for the aggregate gauges (others may be live
            // too).
            assertTrue(
                    RocksNodeStore.liveStoreCount() >= before + 2,
                    "both freshly-opened stores register in the live aggregate set");

            // Per-store gauge: a real RocksDB integer property, never negative, never throwing.
            assertTrue(
                    a.totalSstBytes() >= 0,
                    "total-sst-files-size is a real, non-negative property");

            // memStatsLine formats ~11 native-memory properties into one sample-loggable line.
            String line = a.memStatsLine();
            assertTrue(
                    line.startsWith("rocksdb["), "memStatsLine is the compact rocksdb[...] form");
            assertTrue(line.contains("numKeys="), "memStatsLine names each native consumer");

            // rocksDbFullStats concatenates the verbose per-level tables (the strProp path).
            assertTrue(
                    a.rocksDbFullStats().contains("rocksdb.stats"),
                    "full stats include the per-level compaction-table label");

            // The static rollups walk the LIVE registry and sum the property across stores.
            assertTrue(
                    RocksNodeStore.aggregateTableReadersBytes() >= 0,
                    "table-readers rollup is non-negative");
            assertTrue(
                    RocksNodeStore.aggregateMemTableBytes() >= 0,
                    "mem-tables rollup is non-negative");
            assertTrue(
                    RocksNodeStore.aggregateBlockCacheBytes() >= 0,
                    "block-cache rollup is non-negative");

            // A closed store drops out of the live count (LIVE.remove in close()); Surefire runs
            // test classes sequentially, so nothing else mutates the registry between these reads.
            int liveWithBoth = RocksNodeStore.liveStoreCount();
            b.close();
            assertEquals(
                    liveWithBoth - 1,
                    RocksNodeStore.liveStoreCount(),
                    "closing a store deregisters it from the live aggregate set");
        }
    }
}
