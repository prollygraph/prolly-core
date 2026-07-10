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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Fuzz / invariant coverage for three-way {@link MergeEngine}, which runs on top of the rewritten
 * {@link DiffEngine}.
 *
 * <p>Three invariants are checked over randomized multi-level trees:
 *
 * <ul>
 *   <li><b>Disjoint union:</b> when {@code ours} and {@code theirs} edit disjoint key sets, the
 *       merge is conflict-free and equals applying both edit sets to the ancestor.
 *   <li><b>Symmetry:</b> a conflict-free merge yields the same result regardless of which side is
 *       "ours".
 *   <li><b>Total conflict:</b> when both sides edit the same keys differently, every such key is a
 *       conflict and the merged root is the ancestor unchanged.
 * </ul>
 *
 * <p>All trees built via {@link TreeMutator} — no mocks.
 */
class MergeEngineFuzzTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, int i) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, String.format("k-%06d", i).getBytes());
        return tb.build().segment();
    }

    private static Node build(
            HeapBufferPool pool, InMemoryNodeStore store, TreeMap<Integer, String> contents) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        for (var e : contents.entrySet()) {
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, e.getKey()), MemorySegment.ofArray(e.getValue().getBytes())));
        }
        return m.applyMutations(null, edits.iterator());
    }

    private static byte[] rootHash(Node n) {
        return n == null ? new byte[0] : HashUtils.hash(n.bytes());
    }

    /** Random edits restricted to keys with {@code k % 2 == parity}. */
    private static TreeMap<Integer, String> edit(
            TreeMap<Integer, String> base, int span, int parity, String tag, SplittableRandom rng) {
        TreeMap<Integer, String> out = new TreeMap<>(base);
        for (int k = 0; k < span; k++) {
            if (k % 2 != parity) continue;
            int roll = rng.nextInt(100);
            if (out.containsKey(k)) {
                if (roll < 15) out.remove(k);
                else if (roll < 35) out.put(k, tag + k);
            } else if (roll < 20) {
                out.put(k, tag + "new" + k);
            }
        }
        return out;
    }

    @Test
    void disjoint_three_way_merge_equals_the_union_of_both_edit_sets() {
        SplittableRandom rng = new SplittableRandom(0x3EE_433L);

        for (int trial = 0; trial < 30; trial++) {
            try (HeapBufferPool pool = new HeapBufferPool();
                    InMemoryNodeStore store = new InMemoryNodeStore()) {

                int span = 200 + rng.nextInt(2300);
                TreeMap<Integer, String> anc = new TreeMap<>();
                for (int i = 0; i < span; i++) {
                    if (rng.nextInt(100) < 80) anc.put(i, "anc" + i);
                }

                // ours edits only even keys, theirs only odd → disjoint.
                TreeMap<Integer, String> ourMap = edit(anc, span, 0, "our", rng);
                TreeMap<Integer, String> theirMap = edit(anc, span, 1, "their", rng);

                // Expected: even keys resolved by ours, odd keys by theirs.
                TreeMap<Integer, String> expected = new TreeMap<>();
                for (int k = 0; k < span; k++) {
                    String v = (k % 2 == 0) ? ourMap.get(k) : theirMap.get(k);
                    if (v != null) expected.put(k, v);
                }

                Node ancestor = build(pool, store, anc);
                Node ours = build(pool, store, ourMap);
                Node theirs = build(pool, store, theirMap);
                Node expectedTree = build(pool, store, expected);

                MergeEngine.MergeResult r =
                        new MergeEngine(store, STRING_DESC, pool).merge(ancestor, ours, theirs);

                assertTrue(
                        r.conflicts().isEmpty(),
                        "trial " + trial + ": disjoint edits must never conflict");
                assertArrayEquals(
                        rootHash(expectedTree),
                        rootHash(r.root()),
                        "trial "
                                + trial
                                + " (span="
                                + span
                                + "): merge must equal "
                                + "ancestor + ourEdits + theirEdits");
            }
        }
    }

    @Test
    void conflict_free_merge_is_symmetric_in_ours_and_theirs() {
        SplittableRandom rng = new SplittableRandom(0x5EE_433L);

        for (int trial = 0; trial < 20; trial++) {
            try (HeapBufferPool pool = new HeapBufferPool();
                    InMemoryNodeStore store = new InMemoryNodeStore()) {

                int span = 200 + rng.nextInt(1800);
                TreeMap<Integer, String> anc = new TreeMap<>();
                for (int i = 0; i < span; i++) {
                    if (rng.nextInt(100) < 80) anc.put(i, "anc" + i);
                }
                Node ancestor = build(pool, store, anc);
                Node ours = build(pool, store, edit(anc, span, 0, "our", rng));
                Node theirs = build(pool, store, edit(anc, span, 1, "their", rng));

                MergeEngine engine = new MergeEngine(store, STRING_DESC, pool);
                MergeEngine.MergeResult ot = engine.merge(ancestor, ours, theirs);
                MergeEngine.MergeResult to = engine.merge(ancestor, theirs, ours);

                assertTrue(ot.conflicts().isEmpty() && to.conflicts().isEmpty());
                assertArrayEquals(
                        rootHash(ot.root()),
                        rootHash(to.root()),
                        "trial " + trial + ": merge(a,o,t) and merge(a,t,o) must agree");
            }
        }
    }

    @Test
    void edits_to_the_same_keys_with_different_values_all_conflict() {
        SplittableRandom rng = new SplittableRandom(0x7EE_433L);

        for (int trial = 0; trial < 15; trial++) {
            try (HeapBufferPool pool = new HeapBufferPool();
                    InMemoryNodeStore store = new InMemoryNodeStore()) {

                int span = 200 + rng.nextInt(1500);
                TreeMap<Integer, String> anc = new TreeMap<>();
                for (int i = 0; i < span; i++) anc.put(i, "anc" + i);

                // Both sides modify the SAME random subset, to different values.
                List<Integer> contested = new ArrayList<>();
                TreeMap<Integer, String> ourMap = new TreeMap<>(anc);
                TreeMap<Integer, String> theirMap = new TreeMap<>(anc);
                for (int k = 0; k < span; k++) {
                    if (rng.nextInt(100) < 20) {
                        contested.add(k);
                        ourMap.put(k, "ours-" + k);
                        theirMap.put(k, "theirs-" + k);
                    }
                }

                Node ancestor = build(pool, store, anc);
                Node ours = build(pool, store, ourMap);
                Node theirs = build(pool, store, theirMap);

                MergeEngine.MergeResult r =
                        new MergeEngine(store, STRING_DESC, pool).merge(ancestor, ours, theirs);

                assertEquals(
                        contested.size(),
                        r.conflicts().size(),
                        "trial " + trial + ": every commonly-edited key must be one conflict");
                assertArrayEquals(
                        rootHash(ancestor),
                        rootHash(r.root()),
                        "trial "
                                + trial
                                + ": with only conflicting edits, the merged root "
                                + "is the ancestor unchanged");
            }
        }
    }
}
