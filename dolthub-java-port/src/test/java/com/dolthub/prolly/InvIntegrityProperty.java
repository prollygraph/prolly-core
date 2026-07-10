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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.gen.Generators;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * I-4 (Merkle integrity + bounded churn / structural sharing) as a property
 * (plans/core-engine-test-strategy.md Step 10):
 *
 * <ul>
 *   <li><b>Integrity</b>: walking the tree, every node's stored address equals the hash of its
 *       bytes, and every internal child reference resolves to a real node.
 *   <li><b>Bounded churn</b>: a single-key value edit on a multi-node tree reuses MOST of the prior
 *       node set (only the root→leaf path changes) — proving structural sharing. A regression that
 *       rebuilds the whole tree would share ~nothing and fail.
 * </ul>
 */
class InvIntegrityProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Provide
    Arbitrary<NavigableMap<byte[], byte[]>> bigMaps() {
        // Enough entries to (usually) build a multi-level tree so sharing
        // is exercised; non-empty keys to avoid the null-field ambiguity.
        return Generators.mapsNonEmptyKeys(30, 400);
    }

    @Property(tries = 120)
    void integrityHolds_andSingleEditReusesMostNodes(
            @ForAll @From("bigMaps") NavigableMap<byte[], byte[]> content) {
        if (content.isEmpty()) return;
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator mut = new TreeMutator(store, DESC, pool);
            Node rootA = mut.applyMutations(null, mutations(pool, content).iterator());
            byte[] rootHashA = store.write(rootA.segment());

            // Integrity: walk A, verifying address == hash(bytes) at every node.
            Set<String> sA = new HashSet<>();
            walkVerify(store, rootHashA, sA);

            // Bounded churn: change ONE existing key's value (LENGTH-PRESERVING
            // so no chunk boundary shifts), rebuild, and count NEW nodes. Only
            // the root→leaf path is rewritten, so new nodes are O(tree height)
            // — NOT a fraction of the tree (a 3-node tree correctly rewrites 2
            // of 3 on an edit; the bound must scale with height, ~log(size),
            // not with total size). A regression that rebuilds the whole tree
            // creates O(size) new nodes and fails.
            Map.Entry<byte[], byte[]> editable = firstNonEmptyValue(content);
            if (editable != null && sA.size() > 2) {
                byte[] newVal = editable.getValue().clone();
                newVal[0] ^= 0x5A; // same length → no boundary movement
                Node rootB =
                        mut.applyMutations(
                                rootA,
                                List.of(
                                                new TreeMutator.Mutation(
                                                        keyTuple(pool, editable.getKey()),
                                                        MemorySegment.ofArray(newVal)))
                                        .iterator());
                byte[] rootHashB = store.write(rootB.segment());

                Set<String> sB = new HashSet<>();
                walkVerify(store, rootHashB, sB);

                Set<String> shared = new HashSet<>(sA);
                shared.retainAll(sB);
                int newNodes = sB.size() - shared.size();
                int heightBound =
                        (int) (4 * (1 + Math.ceil(Math.log(Math.max(2, sA.size())) / Math.log(2))));
                assertTrue(
                        newNodes <= heightBound,
                        "single-key edit created "
                                + newNodes
                                + " new nodes (bound "
                                + heightBound
                                + " for "
                                + sA.size()
                                + " nodes) — churn not "
                                + "O(height); structural sharing broken");
            }
        }
    }

    /** DFS from a node hash: verify address == hash(bytes), recurse children. */
    private static void walkVerify(InMemoryNodeStore store, byte[] hash, Set<String> seen) {
        String hex = HashUtils.toHex(hash);
        if (!seen.add(hex)) return; // shared subtree already verified
        MemorySegment seg =
                store.read(hash)
                        .orElseThrow(() -> new AssertionError("dangling child reference: " + hex));
        byte[] raw = seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        assertArrayEquals(
                hash,
                HashUtils.hash(raw),
                "node address must equal hash of its bytes (" + hex + ")");
        Node node = Node.fromBytes(seg);
        if (!node.isLeaf()) {
            for (int i = 0; i < node.count(); i++) {
                walkVerify(store, node.getValue(i), seen);
            }
        }
    }

    /** First entry whose value is non-empty (for a length-preserving edit). */
    private static Map.Entry<byte[], byte[]> firstNonEmptyValue(NavigableMap<byte[], byte[]> m) {
        for (Map.Entry<byte[], byte[]> e : m.entrySet()) {
            if (e.getValue().length > 0) return e;
        }
        return null;
    }

    private static List<TreeMutator.Mutation> mutations(
            HeapBufferPool pool, NavigableMap<byte[], byte[]> m) {
        List<TreeMutator.Mutation> out = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> e : m.entrySet()) {
            out.add(
                    new TreeMutator.Mutation(
                            keyTuple(pool, e.getKey()), MemorySegment.ofArray(e.getValue())));
        }
        return out;
    }

    private static MemorySegment keyTuple(HeapBufferPool pool, byte[] key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key);
        return tb.build().segment();
    }
}
