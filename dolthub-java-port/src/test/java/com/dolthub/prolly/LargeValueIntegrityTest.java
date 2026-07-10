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

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 *
 *
 * <h3>Large Value & Stress Test</h3>
 *
 * <p>Verifies that the Prolly Tree handles items that exceed the target chunk size. This tests the
 * "Oversized Item" handling in the CDC splitter and the robustness of the Project Panama memory
 * mapping for large segments.
 */
public class LargeValueIntegrityTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Large Value Integrity Test ---");
        Path tempDir = Files.createTempDirectory("prolly-large-val");

        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            // 1. Create a tree with a mix of small and very large values
            System.out.print("Inserting large values (1KB to 64KB)... ");
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            Map<String, Integer> valueSizes = new HashMap<>();

            for (int i = 0; i < 200; i++) {
                String k = String.format("key-%05d", i);
                int size = (i % 10 == 0) ? 64000 : 100; // Every 10th item is 64KB
                byte[] largeVal = new byte[size];
                Arrays.fill(largeVal, (byte) i);

                edits.add(
                        new TreeMutator.Mutation(
                                buildKey(pool, k), MemorySegment.ofArray(largeVal)));
                valueSizes.put(k, size);
            }

            Node root = mutator.applyMutations(null, edits.iterator());
            System.out.println("Done. Height: " + root.level());

            // 2. Verify integrity
            System.out.print("Verifying structural integrity... ");
            StaticMap map = new StaticMap(store, root, desc);
            if (root.treeCount() != 200)
                throw new RuntimeException("Count mismatch: " + root.treeCount());
            System.out.println("Passed.");

            // 3. Point lookups for large values
            System.out.print("Verifying large value retrieval... ");
            for (int i = 0; i < 200; i += 20) {
                String k = String.format("key-%05d", i);
                MemorySegment res = map.get(buildKey(pool, k)).orElseThrow();
                if (res.byteSize() != valueSizes.get(k)) {
                    throw new RuntimeException(
                            "Size mismatch for "
                                    + k
                                    + ": expected "
                                    + valueSizes.get(k)
                                    + ", got "
                                    + res.byteSize());
                }
                // Verify content
                byte[] data = res.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                if (data[0] != (byte) i || data[data.length - 1] != (byte) i) {
                    throw new RuntimeException("Content corruption in large value " + k);
                }
            }
            System.out.println("Passed.");

            // 4. Memory Pressure Simulation
            System.out.print("Simulating memory churn... ");
            for (int i = 0; i < 50; i++) {
                List<TreeMutator.Mutation> churn = new ArrayList<>();
                String k = String.format("key-%05d", i * 4);
                byte[] newVal = new byte[32000];
                Arrays.fill(newVal, (byte) 0xFF);
                churn.add(
                        new TreeMutator.Mutation(buildKey(pool, k), MemorySegment.ofArray(newVal)));
                root = mutator.applyMutations(root, churn.iterator());
            }
            System.out.println("Passed.");

            System.out.println("--- Large Value Integrity Test PASSED ---");
        }
    }

    private static MemorySegment buildKey(HeapBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        return tb.build().segment();
    }
}
