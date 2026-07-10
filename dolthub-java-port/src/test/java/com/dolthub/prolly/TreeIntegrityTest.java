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
 * <h3>Advanced Tree Integrity & Audit Test</h3>
 */
public class TreeIntegrityTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Advanced Integrity & Audit Test ---");
        Path tempDir = Files.createTempDirectory("prolly-integrity");

        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);
            Map<String, String> groundTruth = new TreeMap<>();

            // 1. Generate random workload
            Random rng = new Random(1337);
            System.out.print("Applying 10,000 random mutations... ");
            Node root = null;
            for (int i = 0; i < 100; i++) {
                List<TreeMutator.Mutation> batch = new ArrayList<>();
                for (int j = 0; j < 100; j++) {
                    String k = String.format("key-%08d", rng.nextInt(100000));
                    String v = "val-" + rng.nextInt(1000);
                    batch.add(
                            new TreeMutator.Mutation(
                                    buildKey(pool, k), MemorySegment.ofArray(v.getBytes())));
                    groundTruth.put(k, v);
                }
                batch.sort((a, b) -> desc.compare(new Tuple(a.key()), new Tuple(b.key())));
                root = mutator.applyMutations(root, batch.iterator());
            }
            System.out.println("Done.");

            // Debug first 5 ground truth keys
            System.out.print("Ground Truth Sample: ");
            int d = 0;
            for (var k : groundTruth.keySet()) {
                if (d++ < 5) System.out.print(k + " ");
            }
            System.out.println();

            // 2. Perform Deep Audit
            System.out.println("Starting Deep Structural Audit...");
            AuditResult result = auditNode(store, root, desc, null);
            System.out.println("  Nodes Audited: " + result.nodeCount);
            System.out.println("  Leaves Audited: " + result.leafCount);
            System.out.println("  Max Depth: " + root.level());

            if (result.totalItems != root.treeCount()) {
                throw new RuntimeException(
                        "Audit Error: Leaf sum ("
                                + result.totalItems
                                + ") does not match root treeCount ("
                                + root.treeCount()
                                + ")");
            }

            // 3. Verify Content Accuracy
            System.out.print("Verifying content against ground truth... ");
            StaticMap map = new StaticMap(store, root, desc);
            MapIterator it = map.iter();
            int count = 0;
            for (var entry : groundTruth.entrySet()) {
                if (!it.next())
                    throw new RuntimeException("Iterator ended prematurely at " + entry.getKey());
                String k = new String(new Tuple(it.key()).getField(0));
                String v = new String(it.value().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
                if (!k.equals(entry.getKey())) {
                    System.out.println("\nERROR: Mismatch at item " + count);
                    System.out.println("  Got:      " + k);
                    System.out.println("  Expected: " + entry.getKey());
                    throw new RuntimeException("Key mismatch");
                }
                if (!v.equals(entry.getValue()))
                    throw new RuntimeException("Value mismatch for " + k);
                count++;
            }
            if (it.next()) throw new RuntimeException("Iterator has extra items!");
            System.out.println("Passed (" + count + " items matched).");

            System.out.println("--- Advanced Integrity Test PASSED ---");
        }
    }

    private static class AuditResult {
        long nodeCount = 0;
        long leafCount = 0;
        long totalItems = 0;
    }

    private static AuditResult auditNode(
            NodeStore store, Node node, TupleDescriptor desc, MemorySegment expectedLastKey) {
        AuditResult res = new AuditResult();
        res.nodeCount++;

        if (expectedLastKey != null) {
            MemorySegment actualLastKey = MemorySegment.ofArray(node.getKey(node.count() - 1));
            if (desc.compare(new Tuple(actualLastKey), new Tuple(expectedLastKey)) != 0) {
                throw new RuntimeException(
                        "Integrity Violation: Last key mismatch in node at level " + node.level());
            }
        }

        if (node.level() == 0) {
            res.leafCount++;
            res.totalItems = node.count();
            return res;
        }

        long subtreeSum = 0;
        for (int i = 0; i < node.count(); i++) {
            byte[] childHash = node.getValue(i);
            Node child = store.read(childHash).map(Node::fromBytes).orElseThrow();

            MemorySegment childExpectedLastKey = MemorySegment.ofArray(node.getKey(i));
            AuditResult childRes = auditNode(store, child, desc, childExpectedLastKey);

            res.nodeCount += childRes.nodeCount;
            res.leafCount += childRes.leafCount;
            subtreeSum += childRes.totalItems;

            if (node.getSubtreeCount(i) != subtreeSum) {
                throw new RuntimeException(
                        "Integrity Violation: Subtree count mismatch at index "
                                + i
                                + " of level "
                                + node.level());
            }
        }

        res.totalItems = subtreeSum;
        return res;
    }

    private static MemorySegment buildKey(HeapBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        return tb.build().segment();
    }
}
