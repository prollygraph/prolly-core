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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/**
 * Differential oracle for {@link Cursor#atKeyFrom}: seeded from ANY prior cursor over the same base
 * tree, it must produce a chain positionally identical — same depth, same per-level node bytes,
 * same per-level index — to a from-root {@link Cursor#atKey} for the same target
 * (plans/flush-node-read-alloc.md Step 2; the read-elimination fast path must be a pure fetch
 * optimization, never a positioning change). Probes are arbitrary-order (not just ascending), so
 * the property covers backward seeks and cross-subtree jumps, both divergence-heavy; the seed
 * carries forward probe-to-probe the way {@code TreeMutator.Chunker.advanceTo} carries its cursor.
 * Auto-shrinks to a minimal failing probe sequence.
 */
class CursorAtKeyFromProperty {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static String k(int i) {
        return String.format("key-%05d", i);
    }

    private static MemorySegment keySeg(HeapBufferPool pool, String key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes());
        return tb.build().segment();
    }

    private static Node buildBase(HeapBufferPool pool, InMemoryNodeStore store, int n) {
        TreeMap<String, String> m = new TreeMap<>();
        for (int i = 0; i < n; i++) m.put(k(i), "val-" + i);
        List<TreeMutator.Mutation> muts = new ArrayList<>(m.size());
        for (var e : m.entrySet()) {
            muts.add(
                    new TreeMutator.Mutation(
                            keySeg(pool, e.getKey()),
                            MemorySegment.ofArray(e.getValue().getBytes())));
        }
        TreeMutator mutator = new TreeMutator(store, STRING_DESC, pool);
        return java.util.Objects.requireNonNull(
                mutator.applyMutations(null, muts.iterator()), "base tree must be non-empty");
    }

    /** Chains must match level-for-level: depth, node bytes, index. */
    private static void assertChainEquals(Cursor expected, Cursor actual) {
        Cursor e = expected;
        Cursor a = actual;
        int level = 0;
        while (e != null || a != null) {
            assertEquals(e == null, a == null, "chain depth diverged at level " + level);
            assertEquals(e.index(), a.index(), "index diverged at level " + level);
            assertArrayEquals(
                    e.node().segment().toArray(ValueLayout.JAVA_BYTE),
                    a.node().segment().toArray(ValueLayout.JAVA_BYTE),
                    "node bytes diverged at level " + level);
            e = e.parent();
            a = a.parent();
            level++;
        }
    }

    // 3000 dense entries -> a multi-level tree, so seeks exercise spine reuse AND divergence.
    private static final int BASE_N = 3000;

    @Property(tries = 200)
    void atKeyFromMatchesAtKeyForArbitraryProbeSequences(
            @ForAll @Size(min = 1, max = 40) List<@IntRange(min = -50, max = 3300) Integer> probes,
            @ForAll @IntRange(min = 0, max = 3299) int seedProbe) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = buildBase(pool, store, BASE_N);

            // Seed: an arbitrary starting position, as advanceTo would hold one mid-flush.
            Cursor seed = Cursor.atKey(store, root, keySeg(pool, k(seedProbe)), STRING_DESC);

            for (int p : probes) {
                // Probes include absent keys (negative and > BASE_N) and backward jumps.
                MemorySegment target = keySeg(pool, k(p));
                Cursor viaRoot = Cursor.atKey(store, root, target, STRING_DESC);
                Cursor viaSeed = Cursor.atKeyFrom(seed, target, STRING_DESC);
                assertChainEquals(viaRoot, viaSeed);
                seed = viaSeed; // carry forward, as the chunker carries its cursor
            }
        }
    }

    /** The ascending same-leaf run — the exact shape the flush stream produces. */
    @Test
    void ascendingRunReusesWithoutDivergence() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node root = buildBase(pool, store, BASE_N);
            Cursor seed = Cursor.atKey(store, root, keySeg(pool, k(0)), STRING_DESC);
            for (int i = 0; i < BASE_N; i += 7) {
                MemorySegment target = keySeg(pool, k(i));
                Cursor viaSeed = Cursor.atKeyFrom(seed, target, STRING_DESC);
                assertChainEquals(Cursor.atKey(store, root, target, STRING_DESC), viaSeed);
                seed = viaSeed;
            }
        }
    }
}
