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
 * Fuzz / invariant coverage for the rewritten leaf-cursor {@link DiffEngine}.
 *
 * <p>The defining correctness property of a diff engine: for any two trees {@code A} and {@code B},
 * replaying {@code diff(A → B)} as mutations onto {@code A} must reconstruct {@code B} exactly —
 * same content, same root hash. A diff that drops a change, emits a phantom one, or mis-types an
 * entry breaks this round-trip. Running it over many randomized tree shapes (single-leaf through
 * multi-level) exercises far more boundary configurations than hand-written cases can.
 *
 * <p>All trees built via {@link TreeMutator} — no mocks.
 */
class DiffEngineApplyRoundTripTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, int i) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, String.format("k-%06d", i).getBytes());
        return tb.build().segment();
    }

    /** Build a tree from a sorted int→value map; null/absent value = no entry. */
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

    @Test
    void diff_then_apply_reconstructs_the_target_over_many_random_shapes() {
        SplittableRandom rng = new SplittableRandom(0xD1FFE7A11L);

        for (int trial = 0; trial < 40; trial++) {
            try (HeapBufferPool pool = new HeapBufferPool();
                    InMemoryNodeStore store = new InMemoryNodeStore()) {

                // --- random source tree A ---
                int span = 1 + rng.nextInt(2500); // mix of single-leaf & multi-level
                TreeMap<Integer, String> a = new TreeMap<>();
                for (int i = 0; i < span; i++) {
                    if (rng.nextInt(100) < 75) a.put(i, "a" + i);
                }
                if (a.isEmpty()) a.put(0, "a0"); // keep A non-empty

                // --- derive target tree B by random edits ---
                TreeMap<Integer, String> b = new TreeMap<>(a);
                for (int i = 0; i < span; i++) {
                    int roll = rng.nextInt(100);
                    if (b.containsKey(i)) {
                        if (roll < 12) b.remove(i); // delete
                        else if (roll < 28) b.put(i, "B" + i); // modify
                    } else if (roll < 15) {
                        b.put(i, "n" + i); // insert a new key
                    }
                }
                if (b.isEmpty()) b.put(0, "b0");

                Node treeA = build(pool, store, a);
                Node treeB = build(pool, store, b);

                // --- diff A → B, replay the entries onto A ---
                List<DiffEngine.DiffEntry> entries = new ArrayList<>();
                new DiffEngine(store, STRING_DESC)
                        .diff(
                                treeA,
                                treeB,
                                e -> {
                                    entries.add(e);
                                    return true;
                                });

                List<TreeMutator.Mutation> replay = new ArrayList<>();
                for (DiffEngine.DiffEntry e : entries) {
                    // ADD/MOD → write valueB; DEL → write null (delete).
                    MemorySegment v = (e.type() == DiffEngine.DiffType.DEL) ? null : e.valueB();
                    replay.add(new TreeMutator.Mutation(e.key(), v));
                }
                Node reconstructed =
                        new TreeMutator(store, STRING_DESC, pool)
                                .applyMutations(treeA, replay.iterator());

                // --- the invariant ---
                assertArrayEquals(
                        rootHash(treeB),
                        rootHash(reconstructed),
                        "trial "
                                + trial
                                + " (span="
                                + span
                                + ", diff="
                                + entries.size()
                                + "): apply(diff(A→B)) must reconstruct B exactly");

                // The diff size must equal the true number of differences.
                int expectedDiffs = 0;
                for (int i = 0; i < span; i++) {
                    String va = a.get(i), vb = b.get(i);
                    if (va == null ? vb != null : !va.equals(vb)) expectedDiffs++;
                }
                assertEquals(
                        expectedDiffs,
                        entries.size(),
                        "trial " + trial + ": diff must emit exactly one entry per real change");
            }
        }
    }

    @Test
    void diff_of_a_tree_against_itself_is_empty_and_apply_is_a_no_op() {
        SplittableRandom rng = new SplittableRandom(99);
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMap<Integer, String> contents = new TreeMap<>();
            for (int i = 0; i < 1500; i++) contents.put(i, "v" + rng.nextInt());
            Node tree = build(pool, store, contents);

            List<DiffEngine.DiffEntry> entries = new ArrayList<>();
            new DiffEngine(store, STRING_DESC)
                    .diff(
                            tree,
                            tree,
                            e -> {
                                entries.add(e);
                                return true;
                            });
            assertEquals(0, entries.size(), "a tree diffed against itself has no differences");
        }
    }
}
