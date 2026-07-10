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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Model-based ("oracle") test for the map layer — {@link MutableMap} edit buffer, {@link
 * StaticMap#flush}-equivalent materialization, and the {@link StaticMap} read/scan surface.
 *
 * <p>A {@link TreeMap} is the reference model: the same pseudo-random put/delete sequence is
 * applied to both, and the prolly map is cross-checked against the oracle for {@code get}
 * (read-your-writes), full forward iteration, reverse iteration, and range iteration. This is the
 * SQLite-style net — thousands of random ops over a sorted-map reference exercise the cursor /
 * mutator / flush paths far past what example-based tests reach.
 *
 * <p>It also asserts the defining prolly-tree property: the materialized root hash depends only on
 * the final key/value set, never on insertion order.
 */
class OracleModelTest {

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

    private static String keyStr(MemorySegment keyTuple) {
        return new String(new Tuple(keyTuple).getField(0), StandardCharsets.UTF_8);
    }

    private static String valStr(MemorySegment v) {
        return new String(v.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    /** Content hash of a materialized map's root — a stable identity for determinism checks. */
    private static String rootHash(InMemoryNodeStore store, StaticMap map) {
        Node r = map.root();
        return r == null ? "<empty>" : HexFormat.of().formatHex(store.write(r.segment()));
    }

    // ------------------------------------------------------------------
    // 1. Random op sequence vs TreeMap oracle
    // ------------------------------------------------------------------

    @Test
    void randomOps_trackTreeMapOracle() {
        for (long seed : new long[] {1L, 7L, 42L, 99L, 20260515L}) {
            runOracle(seed, 6000, 1500);
        }
    }

    private void runOracle(long seed, int ops, int keySpace) {
        Random rnd = new Random(seed);
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            TreeMap<String, String> oracle = new TreeMap<>();
            StaticMap base = new StaticMap(store, null, STRING_DESC);
            MutableMap mm = new MutableMap(base, store, STRING_DESC, pool);

            for (int i = 0; i < ops; i++) {
                String k = String.format("k%05d", rnd.nextInt(keySpace));
                if (rnd.nextInt(100) < 70) { // 70% put
                    String v = "v" + i;
                    mm.put(key(pool, k), val(v));
                    oracle.put(k, v);
                } else { // 30% delete
                    mm.delete(key(pool, k));
                    oracle.remove(k);
                }

                // Read-your-writes: a buffered MutableMap must agree with the
                // oracle on every probe, present or absent, before any flush.
                String probe = String.format("k%05d", rnd.nextInt(keySpace));
                assertEquals(
                        Optional.ofNullable(oracle.get(probe)),
                        mm.get(key(pool, probe)).map(OracleModelTest::valStr),
                        "seed=" + seed + " op=" + i + " get(" + probe + ")");

                // Periodically materialize and fully cross-check, then continue
                // editing on top of the flushed map — mirrors commit cadence.
                if (i % 750 == 749) {
                    StaticMap flushed = mm.flush();
                    assertConsistent(pool, flushed, oracle, rnd, "seed=" + seed + " op=" + i);
                    mm = new MutableMap(flushed, store, STRING_DESC, pool);
                }
            }
            assertConsistent(pool, mm.flush(), oracle, rnd, "seed=" + seed + " final");
        }
    }

    /** Cross-check a materialized map against the oracle: get, forward/reverse/range scans. */
    private static void assertConsistent(
            HeapBufferPool pool,
            StaticMap map,
            TreeMap<String, String> oracle,
            Random rnd,
            String ctx) {

        // get() over every present key.
        for (var e : oracle.entrySet()) {
            assertEquals(
                    Optional.of(e.getValue()),
                    map.get(key(pool, e.getKey())).map(OracleModelTest::valStr),
                    ctx + " get(" + e.getKey() + ")");
        }

        // Forward iteration must equal the oracle in ascending key order.
        List<String> fwdKeys = new ArrayList<>();
        List<String> fwdVals = new ArrayList<>();
        MapIterator it = map.iter();
        while (it.next()) {
            fwdKeys.add(keyStr(it.key()));
            fwdVals.add(valStr(it.value()));
        }
        assertEquals(new ArrayList<>(oracle.keySet()), fwdKeys, ctx + " iter keys");
        assertEquals(new ArrayList<>(oracle.values()), fwdVals, ctx + " iter values");

        // Reverse iteration must yield exactly the reverse of forward iteration.
        // reverseIter() returns an end-positioned bidirectional cursor; it is
        // walked with prev() (forward iter() is walked with next()).
        List<String> revKeys = new ArrayList<>();
        MapIterator rit = map.reverseIter();
        while (rit.prev()) {
            revKeys.add(keyStr(rit.key()));
        }
        List<String> expectRev = new ArrayList<>(fwdKeys);
        Collections.reverse(expectRev);
        assertEquals(expectRev, revKeys, ctx + " reverseIter keys");

        // Range iteration from a few random start keys must equal oracle.tailMap.
        for (int t = 0; t < 5; t++) {
            String start = String.format("k%05d", rnd.nextInt(1500));
            List<String> rangeKeys = new ArrayList<>();
            MapIterator rg = map.iterRange(key(pool, start));
            while (rg.next()) {
                rangeKeys.add(keyStr(rg.key()));
            }
            assertEquals(
                    new ArrayList<>(oracle.tailMap(start, true).keySet()),
                    rangeKeys,
                    ctx + " iterRange(" + start + ")");
        }
    }

    // ------------------------------------------------------------------
    // 2. Insertion-order independence — the defining prolly-tree property
    // ------------------------------------------------------------------

    @Test
    void insertionOrderIndependence_producesIdenticalRootHash() {
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            pairs.add(new String[] {String.format("k%05d", i), "v" + i});
        }

        String canonical = null;
        for (long seed : new long[] {1L, 2L, 3L, 4L, 5L}) {
            List<String[]> shuffled = new ArrayList<>(pairs);
            Collections.shuffle(shuffled, new Random(seed));
            try (HeapBufferPool pool = new HeapBufferPool();
                    InMemoryNodeStore store = new InMemoryNodeStore()) {
                MutableMap mm =
                        new MutableMap(
                                new StaticMap(store, null, STRING_DESC), store, STRING_DESC, pool);
                for (String[] p : shuffled) {
                    mm.put(key(pool, p[0]), val(p[1]));
                }
                String h = rootHash(store, mm.flush());
                if (canonical == null) {
                    canonical = h;
                } else {
                    assertEquals(
                            canonical,
                            h,
                            "root hash must not depend on insertion order (seed=" + seed + ")");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. Delete-to-empty then rebuild restores the exact root hash
    // ------------------------------------------------------------------

    @Test
    void deleteToEmpty_thenRebuild_restoresRootHash() {
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            pairs.add(new String[] {String.format("k%05d", i), "v" + i});
        }
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            MutableMap build =
                    new MutableMap(
                            new StaticMap(store, null, STRING_DESC), store, STRING_DESC, pool);
            for (String[] p : pairs) {
                build.put(key(pool, p[0]), val(p[1]));
            }
            StaticMap populated = build.flush();
            String populatedHash = rootHash(store, populated);
            assertTrue(populated.root() != null, "populated map must have a root");

            // Delete every key — the map must collapse back to empty.
            MutableMap clearing = new MutableMap(populated, store, STRING_DESC, pool);
            for (String[] p : pairs) {
                clearing.delete(key(pool, p[0]));
            }
            StaticMap emptied = clearing.flush();
            assertEquals(
                    "<empty>",
                    rootHash(store, emptied),
                    "deleting every key must collapse the tree back to empty");

            // Re-insert the same pairs — the root hash must be identical again.
            MutableMap rebuild = new MutableMap(emptied, store, STRING_DESC, pool);
            for (String[] p : pairs) {
                rebuild.put(key(pool, p[0]), val(p[1]));
            }
            assertEquals(
                    populatedHash,
                    rootHash(store, rebuild.flush()),
                    "rebuilding the same key/value set must restore the exact root hash");
        }
    }
}
