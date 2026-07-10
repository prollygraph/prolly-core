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
 * <h3>Merkle Convergence Stress Test</h3>
 *
 * <p>Verifies that Prolly Trees are truly deterministic under complex history sequences. This test
 * ensures that deleting and re-inserting data, or updating data in different orders, always results
 * in the same cryptographic state (Root Hash).
 */
public class MerkleConvergenceStressTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Merkle Convergence Stress Test ---");
        Path tempDir = Files.createTempDirectory("prolly-convergence-stress");

        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            // Ground Truth Data
            Map<String, String> finalState = new TreeMap<>();
            for (int i = 0; i < 5000; i++) {
                finalState.put(String.format("key-%05d", i), "val-" + i);
            }

            // Path 1: Pure Batch Build
            System.out.print("Path 1: Batch Build... ");
            List<TreeMutator.Mutation> batch = convertToMutations(pool, finalState);
            Node root1 = mutator.applyMutations(null, batch.iterator());
            byte[] hash1 = store.write(root1.segment());
            System.out.println("Root: " + toHex(hash1));

            // Path 2: Chaotic Mutation Sequence (Insert -> Delete -> Update)
            System.out.print("Path 2: Chaotic Sequence... ");
            Node root2 = null;
            Random rng = new Random(42);

            // Phase A: Insert first 2500 items
            Map<String, String> phaseA = new TreeMap<>();
            for (int i = 0; i < 2500; i++) phaseA.put(String.format("key-%05d", i), "init");
            root2 = mutator.applyMutations(root2, convertToMutations(pool, phaseA).iterator());

            // Phase B: Update those items and add next 2500
            Map<String, String> phaseB = new TreeMap<>();
            for (int i = 0; i < 5000; i++) phaseB.put(String.format("key-%05d", i), "val-" + i);
            root2 = mutator.applyMutations(root2, convertToMutations(pool, phaseB).iterator());

            // Phase C: Delete 1000 items and re-insert them
            Map<String, String> deletes = new TreeMap<>();
            for (int i = 0; i < 1000; i++) deletes.put(String.format("key-%05d", i), null);
            root2 = mutator.applyMutations(root2, convertToMutations(pool, deletes).iterator());

            Map<String, String> reinserts = new TreeMap<>();
            for (int i = 0; i < 1000; i++) reinserts.put(String.format("key-%05d", i), "val-" + i);
            root2 = mutator.applyMutations(root2, convertToMutations(pool, reinserts).iterator());

            byte[] hash2 = store.write(root2.segment());
            System.out.println("Root: " + toHex(hash2));

            if (!Arrays.equals(hash1, hash2)) {
                throw new RuntimeException(
                        "Convergence Failure! Path 1 and Path 2 resulted in different hashes.");
            }

            // Path 3: Out-of-order partial commits
            System.out.print("Path 3: Shuffled Chunked Build... ");
            Node root3 = null;
            List<TreeMutator.Mutation> allMutations = convertToMutations(pool, finalState);
            Collections.shuffle(allMutations, new Random(1337));
            for (int i = 0; i < allMutations.size(); i += 100) {
                int end = Math.min(i + 100, allMutations.size());
                List<TreeMutator.Mutation> chunk = new ArrayList<>(allMutations.subList(i, end));
                chunk.sort((a, b) -> desc.compare(new Tuple(a.key()), new Tuple(b.key())));
                root3 = mutator.applyMutations(root3, chunk.iterator());
            }
            byte[] hash3 = store.write(root3.segment());
            System.out.println("Root: " + toHex(hash3));

            if (!Arrays.equals(hash1, hash3)) {
                throw new RuntimeException(
                        "Convergence Failure! Path 3 (Shuffled) did not converge.");
            }

            System.out.println(
                    "Merkle Invariant Verified: Tree state is 100% deterministic relative to content.");
            System.out.println("--- Merkle Convergence Stress Test PASSED ---");
        }
    }

    private static List<TreeMutator.Mutation> convertToMutations(
            HeapBufferPool pool, Map<String, String> data) {
        List<TreeMutator.Mutation> res = new ArrayList<>();
        for (var entry : data.entrySet()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, entry.getKey().getBytes());
            MemorySegment val =
                    entry.getValue() == null
                            ? null
                            : MemorySegment.ofArray(entry.getValue().getBytes());
            res.add(new TreeMutator.Mutation(tb.build().segment(), val));
        }
        return res;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
