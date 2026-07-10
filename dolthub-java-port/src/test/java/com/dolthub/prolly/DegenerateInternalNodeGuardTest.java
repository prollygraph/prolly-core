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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins the resolution of Dolt {@code chunker.append} constraint (3) — internal nodes must hold ≥2
 * children — which the port's {@link TreeMutator} "did not yet model" (the gap from Step 2 of
 * {@code plans/prepublic/splitter-productionization.md}). A key large enough to trigger a chunk
 * boundary at <i>every</i> level (its bytes alone exceed {@code RAMP_FORCE_OFFSET}, so even a lone
 * {@code (key, childHash)} internal item crosses) would, without a degenerate-node guard, form a
 * single-child node at each level and cascade upward forever — an unbounded recursion
 * (StackOverflowError) reachable by one adversarial key, i.e. a denial-of-service on the core write
 * path.
 *
 * @apiNote Phase 2 / Step 5. The fix models Dolt's intent (suppress a boundary on an internal node
 *     with a single pending item), so the tree height stays bounded; this test pins that.
 */
class DegenerateInternalNodeGuardTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment keyTuple(HeapBufferPool pool, byte[] key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key);
        return tb.build().segment();
    }

    @Test
    @Timeout(60)
    void a_single_huge_key_builds_a_bounded_tree_not_infinite_recursion() {
        byte[] hugeKey = new byte[20_000]; // > RAMP_FORCE_OFFSET (15360): crosses at every level
        Arrays.fill(hugeKey, (byte) 'k');
        byte[] smallVal = {1, 2, 3};
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> muts = new ArrayList<>();
            muts.add(
                    new TreeMutator.Mutation(
                            keyTuple(pool, hugeKey), MemorySegment.ofArray(smallVal)));
            Node root =
                    new TreeMutator(store, STRING_DESC, pool).applyMutations(null, muts.iterator());
            assertNotNull(root, "build must produce a root");
            assertTrue(
                    root.level() <= 8,
                    "a single huge key must not cascade into unbounded height; got level "
                            + root.level());
        }
    }

    @Test
    @Timeout(60)
    void a_huge_key_among_normal_keys_builds_a_bounded_readable_tree() {
        byte[] hugeKey = new byte[20_000]; // sorts last (all 'z' > the 'k...' normal keys)
        Arrays.fill(hugeKey, (byte) 'z');
        byte[] hugeVal = {9, 9, 9};
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> muts = new ArrayList<>();
            for (int i = 0; i < 500; i++) { // normal keys "key0000".."key0499" (sorted, < hugeKey)
                byte[] k = String.format("key%04d", i).getBytes(StandardCharsets.UTF_8);
                muts.add(new TreeMutator.Mutation(keyTuple(pool, k), MemorySegment.ofArray(k)));
            }
            muts.add(
                    new TreeMutator.Mutation(
                            keyTuple(pool, hugeKey), MemorySegment.ofArray(hugeVal)));

            Node root =
                    new TreeMutator(store, STRING_DESC, pool).applyMutations(null, muts.iterator());
            assertNotNull(root, "build must produce a root");
            assertTrue(root.level() <= 8, "height must stay bounded; got level " + root.level());

            // The guard must produce a CORRECT tree, not merely a non-crashing one: the huge key
            // and a
            // normal key on the far side of it both read back, so traversal past the large leaf
            // works.
            StaticMap sm = new StaticMap(store, root, STRING_DESC);
            Optional<MemorySegment> huge = sm.get(keyTuple(pool, hugeKey));
            assertTrue(huge.isPresent(), "the huge key must be present");
            assertArrayEquals(
                    hugeVal, huge.get().toArray(ValueLayout.JAVA_BYTE), "huge value mismatch");
            byte[] mid = "key0250".getBytes(StandardCharsets.UTF_8);
            assertTrue(
                    sm.get(keyTuple(pool, mid)).isPresent(), "a normal key must remain readable");
        }
    }
}
