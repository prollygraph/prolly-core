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

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * The {@link Node#getSubtreeCount} contract, pinned on the <b>production path</b>: nodes are built
 * via the production {@link FlatbufferNodeSerializer} (what {@code TreeMutator} ships) and parsed
 * via {@link Node#fromBytes} — no test-only serializer (the test-the-production-primitive
 * convention; plan {@code subtree-count-contract} D-2/D-3).
 *
 * <p>The contract (now stated on {@code Node.getSubtreeCount} itself): the accessor returns the
 * <b>prefix sum</b> of the per-item subtree counts — {@code getSubtreeCount(i)} = total entries
 * under children {@code 0..i} — and the per-child count is recovered as the delta, exactly as
 * {@link Cursor#currentSubtreeSize} computes it. Every production consumer ({@code Cursor}, {@code
 * CardinalityEstimator}, {@code TreeMutator} fast-forward, the tree-integrity audits) relies on
 * this; a per-item-count implementation would silently corrupt them all — which is why the contract
 * gets a generative pin, not just prose.
 */
class SubtreeCountContractProperty {

    /**
     * Internal node (level ≥ 1): the accessor returns prefix sums of the written per-item counts.
     */
    @Property(tries = 200)
    void internal_node_subtree_counts_are_prefix_sums(
            @ForAll @Size(min = 1, max = 48) List<@IntRange(min = 1, max = 100_000) Integer> counts,
            @ForAll @IntRange(min = 1, max = 5) int level) {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            List<TreeMutator.PendingItem> items = new ArrayList<>(counts.size());
            for (int i = 0; i < counts.size(); i++) {
                items.add(
                        new TreeMutator.PendingItem(
                                key(i), childAddress(i), counts.get(i).longValue()));
            }
            byte[] bytes = new FlatbufferNodeSerializer().serialize(level, items);
            Node node = Node.fromBytes(MemorySegment.ofArray(bytes));

            long runningSum = 0;
            for (int i = 0; i < counts.size(); i++) {
                runningSum += counts.get(i);
                assertEquals(
                        runningSum,
                        node.getSubtreeCount(i),
                        "getSubtreeCount(" + i + ") must be the prefix sum through item " + i);
                // The Cursor-style delta recovers the per-item count.
                long delta =
                        (i == 0)
                                ? node.getSubtreeCount(0)
                                : node.getSubtreeCount(i) - node.getSubtreeCount(i - 1);
                assertEquals(
                        counts.get(i).longValue(),
                        delta,
                        "the delta at " + i + " must recover the written per-item count");
            }
        }
    }

    /** Leaf level: every entry is unit-count — the leaf convention the consumers assume. */
    @Property(tries = 50)
    void leaf_entries_are_unit_count(@ForAll @IntRange(min = 1, max = 48) int n) {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            List<TreeMutator.PendingItem> items = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                items.add(
                        new TreeMutator.PendingItem(
                                key(i),
                                MemorySegment.ofArray(("v" + i).getBytes(StandardCharsets.UTF_8)),
                                1L));
            }
            byte[] bytes = new FlatbufferNodeSerializer().serialize(0, items);
            Node node = Node.fromBytes(MemorySegment.ofArray(bytes));
            for (int i = 0; i < n; i++) {
                assertEquals(1L, node.getSubtreeCount(i), "leaf entry " + i + " is unit-count");
            }
        }
    }

    /** Sorted, distinct keys (the serializer's input contract). */
    private static MemorySegment key(int i) {
        return MemorySegment.ofArray(String.format("key-%05d", i).getBytes(StandardCharsets.UTF_8));
    }

    /** An internal node's value is a 20-byte child address. */
    private static MemorySegment childAddress(int i) {
        byte[] h = new byte[20];
        h[0] = (byte) i;
        h[1] = (byte) (i >>> 8);
        return MemorySegment.ofArray(h);
    }
}
