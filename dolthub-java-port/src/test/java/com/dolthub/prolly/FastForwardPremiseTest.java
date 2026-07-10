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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 Step 2 of the upstream monorepo's the upstream tree-write-fast-forwarding plan —
 * empirically pins the premise the "derive natural boundaries from structure" design (ADR-0068 D-3,
 * option B) rests on, so the fast-forward fix can skip subtrees by reference <b>without an encoding
 * tag</b> (no format change).
 *
 * <p><b>The premise:</b> in a freshly-built tree, every chunk <em>except the rightmost at its
 * level</em> ends at a <b>reproducible</b> boundary — the {@link RollingHashSplitter}, re-fed that
 * chunk's entries from a reset state, crosses a boundary by the last entry. Only {@code
 * TreeMutator.Chunker.done()}'s force-flush of the rightmost-per-level is non-reproducible (it ends
 * because the input ran out, which moves when an edit adds content). If this holds, a subtree is
 * safe to reuse-by-reference <em>iff it is not on the rightmost spine</em> — derivable from
 * structure, no hash-affecting tag.
 *
 * <p>A non-rightmost leaf that does <b>not</b> re-cross would refute option B and force the
 * encoding tag (option A) — so this test is the decision instrument for D-3, and a permanent
 * regression guard (if a future chunker change force-flushes mid-tree, the tag-free fast-forward
 * would silently break; this catches it). Re-feeding uses the stored key/value bytes the build
 * serialized, so it reproduces the build's splitter progression exactly (the splitter resets at
 * every boundary, so each chunk's progression depends only on its own content).
 */
class FastForwardPremiseTest {

    @Test
    void nonRightmostLeavesEndAtReproducibleBoundaries() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            // A multi-leaf tree: 20k small entries far exceed the 16 KiB max chunk.
            List<TreeMutator.Mutation> batch = new ArrayList<>();
            for (int i = 0; i < 20_000; i++) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("key-%06d", i).getBytes());
                batch.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(),
                                MemorySegment.ofArray(("val-" + i).getBytes())));
            }
            Node root = mutator.applyMutations(null, batch.iterator());

            List<Node> leaves = new ArrayList<>();
            collectLeaves(store, root, leaves);
            assertTrue(leaves.size() >= 3, "need a multi-leaf tree; got " + leaves.size());

            int natural = 0;
            int violations = 0;
            for (int li = 0; li < leaves.size() - 1; li++) { // all but the rightmost leaf
                if (reFeedCrossesBoundary(leaves.get(li))) {
                    natural++;
                } else {
                    violations++;
                }
            }
            boolean rightmostNatural = reFeedCrossesBoundary(leaves.get(leaves.size() - 1));

            System.out.printf(
                    "[ff-premise] leaves=%d  non-rightmost-natural=%d  violations=%d "
                            + " rightmost-natural=%b%n",
                    leaves.size(), natural, violations, rightmostNatural);

            assertEquals(
                    0,
                    violations,
                    "every non-rightmost leaf must re-cross a boundary — the premise for a tag-free,"
                            + " derive-from-structure fast-forward (ADR-0068 D-3 option B)");
        }
    }

    /** Depth-first left-to-right leaf collection. */
    private static void collectLeaves(NodeStore store, Node n, List<Node> out) {
        if (n.isLeaf()) {
            out.add(n);
            return;
        }
        for (int i = 0; i < n.count(); i++) {
            Node child = store.read(n.getValue(i)).map(Node::fromBytes).orElseThrow();
            collectLeaves(store, child, out);
        }
    }

    /** Re-feed a leaf's entries through a fresh level-0 splitter; did it cross a boundary? */
    private static boolean reFeedCrossesBoundary(Node leaf) {
        RollingHashSplitter sp = new RollingHashSplitter(0);
        for (int i = 0; i < leaf.count(); i++) {
            sp.append(leaf.getKeySegment(i), MemorySegment.ofArray(leaf.getValue(i)));
        }
        return sp.crossedBoundary();
    }
}
