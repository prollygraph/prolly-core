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
 * <h3>Merkle Determinism Test</h3>
 *
 * <p>Verifies that the Prolly Tree structure is purely a function of its content, regardless of the
 * order or method of construction. This is a foundational requirement for efficient Merkle
 * synchronization and deduplication.
 */
public class MerkleDeterminismTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Merkle Determinism Test ---");
        Path tempDir = Files.createTempDirectory("prolly-determinism");

        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            // 1. Generate 5000 random entries
            List<TreeMutator.Mutation> entries = new ArrayList<>();
            for (int i = 0; i < 5000; i++) {
                byte[] key = String.format("key-%08d", i).getBytes();
                byte[] val = ("value-" + i).getBytes();
                entries.add(
                        new TreeMutator.Mutation(
                                buildTuple(pool, key), MemorySegment.ofArray(val)));
            }

            // 2. Scenario A: Build from empty tree in one batch
            System.out.print("Scenario A: Full Batch Build... ");
            Node rootA = mutator.applyMutations(null, entries.iterator());
            byte[] hashA = store.write(rootA.segment());
            System.out.println("Root: " + toHex(hashA));

            // 3. Scenario B: Build by inserting shuffled entries one by one
            System.out.print("Scenario B: Shuffled Incremental Build... ");
            List<TreeMutator.Mutation> shuffled = new ArrayList<>(entries);
            Collections.shuffle(shuffled, new Random(42));
            Node rootB = null;
            for (var m : shuffled) {
                rootB = mutator.applyMutations(rootB, List.of(m).iterator());
            }
            byte[] hashB = store.write(rootB.segment());
            System.out.println("Root: " + toHex(hashB));

            if (!Arrays.equals(hashA, hashB)) {
                throw new RuntimeException(
                        "Determinism Failure: Scenario A and B resulted in different root hashes!");
            }

            // 4. Scenario C: Build in chunks of 500
            System.out.print("Scenario C: Chunked Sequential Build... ");
            Node rootC = null;
            for (int i = 0; i < entries.size(); i += 500) {
                rootC = mutator.applyMutations(rootC, entries.subList(i, i + 500).iterator());
            }
            byte[] hashC = store.write(rootC.segment());
            System.out.println("Root: " + toHex(hashC));

            if (!Arrays.equals(hashA, hashC)) {
                throw new RuntimeException(
                        "Determinism Failure: Scenario A and C resulted in different root hashes!");
            }

            System.out.println(
                    "Structural Consistency: All construction paths converged to the same Merkle Root.");
            System.out.println("--- Merkle Determinism Test PASSED ---");
        }
    }

    private static MemorySegment buildTuple(HeapBufferPool pool, byte[] key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key);
        return tb.build().segment();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
