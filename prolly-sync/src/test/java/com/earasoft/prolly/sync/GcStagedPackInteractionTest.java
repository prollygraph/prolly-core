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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.Database;
import com.earasoft.prolly.GarbageCollector;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the ADR-0074 staged-pack interaction: a pack's chunks are staged BEFORE its ref moves
 * (torn-pack healing depends on staged chunks being harmless), so a garbage collection running in
 * that window sweeps them — the in-flight apply must then fail its head-closure verification (never
 * move the ref onto missing bytes), and a RETRY must heal, because packs are content-addressed and
 * idempotent. No corruption, one wasted transfer — the semantics the ADR accepts instead of a
 * time-based grace window.
 */
class GcStagedPackInteractionTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final String BRANCH = "main";

    private DirectBufferPool pool;
    private RocksNodeStore rocksSrc;
    private RocksNodeStore rocksDst;
    private Database src;
    private Database dst;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        pool = new DirectBufferPool();
        rocksSrc = new RocksNodeStore(dir.resolve("src").toString());
        rocksDst = new RocksNodeStore(dir.resolve("dst").toString());
        src = new Database(rocksSrc, "gc-pack-src", DESC, pool);
        dst = new Database(rocksDst, "gc-pack-dst", DESC, pool);
        src.createBranch(BRANCH, "EMPTY");
        dst.createBranch(BRANCH, "EMPTY");
    }

    @AfterEach
    void close() {
        if (rocksSrc != null) rocksSrc.close();
        if (rocksDst != null) rocksDst.close();
        if (pool != null) pool.close();
    }

    @Test
    void gcBetweenStageAndRefMove_failsVerification_andTheRetryHeals() {
        byte[] parent = null;
        for (int i = 0; i < 50; i++) {
            StaticMap base =
                    parent == null ? new StaticMap(src.store(), null, DESC) : src.getBranch(BRANCH);
            MutableMap mm = new MutableMap(base, src.store(), DESC, pool);
            mm.put(key("k" + i), MemorySegment.ofArray(("v" + i).getBytes(StandardCharsets.UTF_8)));
            assertTrue(src.commit(BRANCH, mm, parent, "t", "c" + i));
            parent = src.getHeadHash(BRANCH).orElseThrow();
        }
        byte[] head = src.getHeadHash(BRANCH).orElseThrow();
        DatabasePackSync.PackAndHead built = DatabasePackSync.buildPack(src, BRANCH, Set.of());

        // The window: chunks staged on dst, ref not yet moved…
        dst.receiveChunks(built.pack().chunks());
        // …and a collection runs (no contributor claims the staged, unanchored chunks).
        new GarbageCollector(dst, rocksDst).collect();

        // The in-flight apply's verification must fail — the ref never moves onto missing bytes.
        assertThrows(
                IllegalStateException.class,
                () -> DatabasePackSync.verifyHeadState(dst, head, null),
                "head-closure verification must fail after the staged chunks were swept");
        assertTrue(dst.getHeadHash(BRANCH).isEmpty(), "the ref must not have moved");

        // The retry heals: a full apply re-stages the (idempotent, content-addressed) pack.
        assertTrue(DatabasePackSync.apply(dst, BRANCH, built.pack(), head, null));
        assertArrayEquals(head, dst.getHeadHash(BRANCH).orElseThrow());
    }

    private MemorySegment key(String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }
}
