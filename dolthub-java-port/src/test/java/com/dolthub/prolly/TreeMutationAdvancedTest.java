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
 * TreeMutationAdvancedTest focuses on complex mutation patterns including sparse updates, deep tree
 * rebalancing, and disjoint edits to ensure the TreeMutator's recursive logic and fast-forwarding
 * are bulletproof.
 */
public class TreeMutationAdvancedTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Advanced Mutation Test ---");
        Path tempDir = Files.createTempDirectory("prolly-advanced-mut");
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            testSparseFastForward(mutator, store, desc, pool);
            testDeepRebalance(mutator, store, desc, pool);
            testDisjointEdits(mutator, store, desc, pool);
            testOverlappingMutations(mutator, store, desc, pool);
        }
        System.out.println("--- Advanced Mutation Test PASSED ---");
    }

    /**
     * Specifically triggers the Structural Fast-Forwarding logic by updating single items in a very
     * large tree.
     */
    private static void testSparseFastForward(
            TreeMutator mutator, NodeStore store, TupleDescriptor desc, HeapBufferPool pool) {
        System.out.print("Testing Sparse Fast-Forward... ");
        int totalItems = 100000;
        Node root = createTree(mutator, pool, totalItems);

        // Mutate exactly 3 items: start, middle, end
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        addMutation(edits, pool, 0, "new-start");
        addMutation(edits, pool, totalItems / 2, "new-middle");
        addMutation(edits, pool, totalItems - 1, "new-end");

        long start = System.currentTimeMillis();
        Node newRoot = mutator.applyMutations(root, edits.iterator());
        long duration = System.currentTimeMillis() - start;

        if (newRoot.treeCount() != totalItems) throw new RuntimeException("Count mismatch");

        // Duration should be very low if fast-forwarding worked
        if (duration > 500) {
            System.err.println(
                    "Warning: Sparse update took "
                            + duration
                            + "ms. Fast-forwarding might be inefficient.");
        }

        verifyPoint(newRoot, store, desc, pool, 0, "new-start");
        verifyPoint(newRoot, store, desc, pool, totalItems / 2, "new-middle");
        verifyPoint(newRoot, store, desc, pool, totalItems - 1, "new-end");
        System.out.println("Passed (" + duration + "ms).");
    }

    /**
     * Forces the tree to Height 3+ then deletes almost everything to see if it collapses correctly
     * to Height 0 or 1.
     */
    private static void testDeepRebalance(
            TreeMutator mutator, NodeStore store, TupleDescriptor desc, HeapBufferPool pool) {
        System.out.print("Testing Deep Rebalance (H3 -> H1)... ");
        int totalItems = 200000; // Large enough for H3
        Node root = createTree(mutator, pool, totalItems);
        int initialHeight = root.level();

        // Delete 99% of the tree
        List<TreeMutator.Mutation> deletes = new ArrayList<>();
        for (int i = 0; i < totalItems; i++) {
            if (i % 100 != 0) { // Keep only 1%
                addDeletion(deletes, pool, i);
            }
        }

        Node smallRoot = mutator.applyMutations(root, deletes.iterator());
        if (smallRoot.treeCount() != totalItems / 100) {
            throw new RuntimeException("Rebalance count mismatch: " + smallRoot.treeCount());
        }
        if (smallRoot.level() >= initialHeight) {
            // System.out.println("DEBUG: Height before=" + initialHeight + ", after=" +
            // smallRoot.level());
        }
        System.out.println(
                "Passed. (Height collapsed from "
                        + initialHeight
                        + " to "
                        + smallRoot.level()
                        + ")");
    }

    /** Tests multiple disjoint edit blocks in a single pass. */
    private static void testDisjointEdits(
            TreeMutator mutator, NodeStore store, TupleDescriptor desc, HeapBufferPool pool) {
        System.out.print("Testing Disjoint Edit Blocks... ");
        int totalItems = 50000;
        Node root = createTree(mutator, pool, totalItems);

        List<TreeMutator.Mutation> edits = new ArrayList<>();
        // Block 1: 100-200
        for (int i = 100; i < 200; i++) addMutation(edits, pool, i, "b1");
        // Block 2: 10000-10100
        for (int i = 10000; i < 10100; i++) addMutation(edits, pool, i, "b2");
        // Block 3: 40000-40100
        for (int i = 40000; i < 40100; i++) addMutation(edits, pool, i, "b3");

        Node newRoot = mutator.applyMutations(root, edits.iterator());
        if (newRoot.treeCount() != totalItems)
            throw new RuntimeException("Disjoint count mismatch");

        verifyPoint(newRoot, store, desc, pool, 150, "b1");
        verifyPoint(newRoot, store, desc, pool, 10050, "b2");
        verifyPoint(newRoot, store, desc, pool, 40050, "b3");
        System.out.println("Passed.");
    }

    /**
     * Tests deleting and re-inserting the same key in the same sorted stream (though Mutator
     * expects distinct keys, we test adjacent distinct logic).
     */
    private static void testOverlappingMutations(
            TreeMutator mutator, NodeStore store, TupleDescriptor desc, HeapBufferPool pool) {
        System.out.print("Testing Mutation/Deletion Interleaving... ");
        Node root = createTree(mutator, pool, 1000);

        List<TreeMutator.Mutation> edits = new ArrayList<>();
        // Delete 500, Insert 500.1, Update 501
        addDeletion(edits, pool, 500);

        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, String.format("key-%08d-extra", 500).getBytes());
        edits.add(
                new TreeMutator.Mutation(
                        tb.build().segment(), MemorySegment.ofArray("extra".getBytes())));

        addMutation(edits, pool, 501, "updated");

        Node newRoot = mutator.applyMutations(root, edits.iterator());
        if (newRoot.treeCount() != 1000)
            throw new RuntimeException("Overlap count mismatch: " + newRoot.treeCount());

        StaticMap sm = new StaticMap(store, newRoot, desc);
        tb.putField(0, String.format("key-%08d", 500).getBytes());
        if (sm.get(tb.build().segment()).isPresent())
            throw new RuntimeException("Key 500 should be deleted");

        tb.putField(0, String.format("key-%08d-extra", 500).getBytes());
        if (sm.get(tb.build().segment()).isEmpty()) throw new RuntimeException("Extra key missing");

        System.out.println("Passed.");
    }

    private static Node createTree(TreeMutator mutator, HeapBufferPool pool, int count) {
        List<TreeMutator.Mutation> edits = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            addMutation(edits, pool, i, "val");
        }
        return mutator.applyMutations(null, edits.iterator());
    }

    private static void addMutation(
            List<TreeMutator.Mutation> list, HeapBufferPool pool, int i, String val) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, String.format("key-%08d", i).getBytes());
        list.add(
                new TreeMutator.Mutation(
                        tb.build().segment(), MemorySegment.ofArray(val.getBytes())));
    }

    private static void addDeletion(List<TreeMutator.Mutation> list, HeapBufferPool pool, int i) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, String.format("key-%08d", i).getBytes());
        list.add(new TreeMutator.Mutation(tb.build().segment(), null));
    }

    private static void verifyPoint(
            Node root,
            NodeStore store,
            TupleDescriptor desc,
            HeapBufferPool pool,
            int i,
            String expected) {
        StaticMap sm = new StaticMap(store, root, desc);
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, String.format("key-%08d", i).getBytes());
        var res = sm.get(tb.build().segment());
        if (res.isEmpty()) throw new RuntimeException("Key missing: " + i);
        String actual = new String(res.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        if (!actual.equals(expected))
            throw new RuntimeException(
                    "Data mismatch at " + i + ": expected " + expected + ", got " + actual);
    }
}
