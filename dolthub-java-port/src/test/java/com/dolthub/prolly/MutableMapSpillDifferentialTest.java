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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The differential (metamorphic) oracle for {@code plans/prolly-bulk-load.md} D-8 Step 4c: a {@link
 * MutableMap} whose edit buffer <b>spills to disk</b> must produce the <b>byte-identical root</b> a
 * fully in-heap flush would — spilling changes nothing the tree-build sees. Two maps over the same
 * base get the same random edit sequence, one with a tiny spill threshold (spills constantly), one
 * with a huge one (never spills); their flushed root hashes must be equal, and both must equal a
 * {@link TreeMap} oracle on the read surface. This is the highest-value invariant for the no-OOM
 * write path: "slow is better than blow up" is only safe if slow is also <i>correct</i>.
 */
class MutableMapSpillDifferentialTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    private static MemorySegment val(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String valStr(MemorySegment v) {
        return new String(v.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private static String rootHash(InMemoryNodeStore store, StaticMap map) {
        Node r = map.root();
        return r == null ? "<empty>" : HexFormat.of().formatHex(store.write(r.segment()));
    }

    @Test
    void spilledFlushBuildsTheSameRootAsInHeapFlush(@TempDir Path dir) {
        for (long seed : new long[] {1L, 42L, 20260602L}) {
            runDifferential(seed, dir);
        }
    }

    private void runDifferential(long seed, Path dir) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            StaticMap base = new StaticMap(store, null, STRING_DESC);
            // Same store + base; the only difference is the spill threshold.
            MutableMap inHeap =
                    new MutableMap(
                            base, store, STRING_DESC, pool, null, 1L << 30, dir); // never spills
            MutableMap spilled =
                    new MutableMap(
                            base, store, STRING_DESC, pool, null, 256L, dir); // spills constantly
            TreeMap<String, String> oracle = new TreeMap<>();

            Random rnd = new Random(seed);
            int ops = 6000, keySpace = 1500;
            for (int i = 0; i < ops; i++) {
                String k = String.format("k%05d", rnd.nextInt(keySpace));
                if (rnd.nextInt(100) < 70) { // 70% put
                    String v = "v" + i;
                    inHeap.put(key(pool, k), val(v));
                    spilled.put(key(pool, k), val(v));
                    oracle.put(k, v);
                } else { // 30% delete
                    inHeap.delete(key(pool, k));
                    spilled.delete(key(pool, k));
                    oracle.remove(k);
                }
            }

            // The spill path must actually have been exercised — else the test passes trivially.
            assertTrue(
                    spilled.spilledRunCount() > 0,
                    "seed=" + seed + ": tiny threshold must have spilled");
            assertEquals(
                    0,
                    inHeap.spilledRunCount(),
                    "seed=" + seed + ": huge threshold must stay in heap");

            StaticMap a = inHeap.flush();
            StaticMap b = spilled.flush();

            // (1) The defining invariant: identical content ⇒ identical root, regardless of
            // spilling.
            assertEquals(
                    rootHash(store, a),
                    rootHash(store, b),
                    "seed="
                            + seed
                            + ": a spilled flush must build the same root as an in-heap flush");

            // (2) And both agree with the TreeMap oracle on the read surface.
            for (var e : oracle.entrySet()) {
                assertEquals(
                        Optional.of(e.getValue()),
                        b.get(key(pool, e.getKey())).map(MutableMapSpillDifferentialTest::valStr),
                        "seed=" + seed + ": spilled get(" + e.getKey() + ")");
            }
        }
    }
}
