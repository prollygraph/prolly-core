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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * One block-cache budget shared by MANY separate databases.
 *
 * <p>{@code RocksNodeStore(String)} builds its own cache and closes it, which is right for a single
 * store and wrong for a host that opens one database <b>per tenant</b>: a 512 MiB budget then
 * reserves 512 MiB of off-heap memory <i>per database</i>, unbounded in the number of tenants. That
 * is the shape blocking a measured 2.2x collection win downstream — the sizing is worth having and
 * cannot be switched on until the budget is per-process rather than per-store.
 *
 * <p><b>What these tests do and do not discriminate.</b> Sharing itself is proven: giving each store
 * its own equally-sized tuning turns
 * {@code storesShareONECacheRatherThanOneEachOfTheSameSize} red (usage 88 -> 88, no movement).
 * CAPACITY alone would not have — two stores each holding their own 64 MiB cache report exactly what
 * two sharing one report, which is why an earlier version of this test was vacuous. The
 * {@code ownsTuning} guard in {@code RocksNodeStore.closeNative}, by contrast, is <b>defensive and
 * not discriminated here</b>: making a shared-tuning store free the handles anyway leaves all three
 * tests green, because RocksDB's Java wrappers are refcounted and idempotently closeable, so a
 * premature close neither frees the live cache nor double-frees. The guard is kept because not
 * freeing what you do not own is correct by construction and costs nothing, not because a test
 * forces it.
 *
 * <p>So this constructor takes a <b>prepared</b> {@link RocksTuning} the caller owns: every store
 * shares its cache, bloom filter, statistics and memtable budget, and none of them closes those
 * handles. Only the caller does, after every store is closed — RocksDB's JNI bindings require the
 * handles to outlive the databases referencing them.
 */
class SharedTuningAcrossStoresTest {
    static {
        RocksDB.loadLibrary();
    }

    private static final long ASKED_MB = 64;

    private static void withCacheProperty(Runnable body) {
        String prev = System.getProperty("prolly.rocksdb.block-cache.mb");
        System.setProperty("prolly.rocksdb.block-cache.mb", Long.toString(ASKED_MB));
        try {
            body.run();
        } finally {
            if (prev == null) {
                System.clearProperty("prolly.rocksdb.block-cache.mb");
            } else {
                System.setProperty("prolly.rocksdb.block-cache.mb", prev);
            }
        }
    }

    @Test
    void storesShareONECacheRatherThanOneEachOfTheSameSize(@TempDir Path dir) {
        withCacheProperty(() -> {
            try (RocksTuning shared = RocksTuning.fromSystemProperties();
                    RocksNodeStore a = new RocksNodeStore(dir.resolve("a").toString(), shared);
                    RocksNodeStore b = new RocksNodeStore(dir.resolve("b").toString(), shared)) {

                // CAPACITY cannot prove sharing: two stores each with their own 64 MiB cache report
                // exactly what two stores sharing one report. USAGE can — a shared cache is one
                // pool, so blocks that store A's reads pull in are visible from store B.
                assertEquals(ASKED_MB << 20, a.blockCacheCapacityBytes());
                assertEquals(ASKED_MB << 20, b.blockCacheCapacityBytes());

                long before = b.blockCacheUsageBytes();
                byte[] h = null;
                for (int i = 0; i < 400; i++) {
                    byte[] payload = new byte[512];
                    payload[0] = (byte) i;
                    payload[1] = (byte) (i >> 8);
                    h = a.write(MemorySegment.ofArray(payload));
                }
                a.flushDurable();
                for (int i = 0; i < 3; i++) {
                    assertTrue(a.read(h).isPresent());   // pull blocks into the cache
                }
                long after = b.blockCacheUsageBytes();

                assertTrue(after > before,
                        "store B's cache usage did not move when store A read (" + before + " -> "
                                + after + ") — the stores are NOT sharing one cache");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void closingOneStoreLeavesTheOthersUsable(@TempDir Path dir) {
        withCacheProperty(() -> {
            try (RocksTuning shared = RocksTuning.fromSystemProperties()) {
                RocksNodeStore a = new RocksNodeStore(dir.resolve("a").toString(), shared);
                RocksNodeStore b = new RocksNodeStore(dir.resolve("b").toString(), shared);

                byte[] hash = b.write(MemorySegment.ofArray(new byte[] {1, 2, 3, 4}));
                a.close();   // must NOT free the cache b is still using

                assertTrue(b.read(hash).isPresent(),
                        "closing one store freed the shared cache out from under another");
                assertEquals(ASKED_MB << 20, b.blockCacheCapacityBytes());
                b.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void aStoreWithItsOwnTuningStillOwnsAndClosesIt(@TempDir Path dir) throws Exception {
        // The existing single-argument constructor must be unchanged: it builds its own handles and
        // closes them, so a caller that never heard of RocksTuning behaves exactly as before.
        assertNull(System.getProperty("prolly.rocksdb.block-cache.mb"),
                "test pollution: another test left the property set");
        try (RocksNodeStore solo = new RocksNodeStore(dir.resolve("solo").toString())) {
            byte[] h = solo.write(MemorySegment.ofArray(new byte[] {9}));
            assertTrue(solo.read(h).isPresent());
        }
        // Closing twice must stay safe — it already is, and sharing must not change that.
        RocksNodeStore again = new RocksNodeStore(dir.resolve("solo").toString());
        again.close();
        again.close();
    }
}
