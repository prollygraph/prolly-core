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
import java.util.ArrayList;
import java.util.List;

/**
 * ChunkerChaosTest targets the recursive Chunker in TreeMutator by performing massive, fine-grained
 * updates that force extreme tree heights and churn.
 */
public class ChunkerChaosTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Chunker Chaos Test ---");
        Path tempDir = Files.createTempDirectory("prolly-chunker-chaos");
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);
            TupleBuilder tb = new TupleBuilder(pool);

            // 1. Force massive tree growth
            int numEdits = 50000;
            System.out.print("Applying " + numEdits + " fine-grained edits... ");
            List<TreeMutator.Mutation> edits = new ArrayList<>(numEdits);
            for (int i = 0; i < numEdits; i++) {
                tb.putField(0, String.format("%08d", i).getBytes());
                edits.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(), MemorySegment.ofArray("val".getBytes())));
            }

            Node root = mutator.applyMutations(null, edits.iterator());
            System.out.println(
                    "Done. (Height: " + root.level() + ", Count: " + root.treeCount() + ")");

            if (root.treeCount() != numEdits) throw new RuntimeException("Count mismatch");
            if (root.level() < 1) throw new RuntimeException("Tree didn't grow enough");

            // 2. Perform massive deletions in the middle
            System.out.print("Performing massive middle deletions... ");
            List<TreeMutator.Mutation> deletions = new ArrayList<>(numEdits / 2);
            for (int i = numEdits / 4; i < 3 * numEdits / 4; i++) {
                tb.putField(0, String.format("%08d", i).getBytes());
                deletions.add(new TreeMutator.Mutation(tb.build().segment(), null));
            }

            Node smallerRoot = mutator.applyMutations(root, deletions.iterator());
            System.out.println(
                    "Done. (Height: "
                            + smallerRoot.level()
                            + ", Count: "
                            + smallerRoot.treeCount()
                            + ")");

            if (smallerRoot.treeCount() != numEdits - (numEdits / 2))
                throw new RuntimeException("Delete count mismatch");

            // 3. Verify structure via sequential scan
            System.out.print("Verifying structural integrity via scan... ");
            StaticMap sm = new StaticMap(store, smallerRoot, desc);
            MapIterator it = sm.iter();
            int found = 0;
            while (it.next()) {
                found++;
            }
            if (found != smallerRoot.treeCount())
                throw new RuntimeException("Scan mismatch: " + found);
            System.out.println("Passed.");
        }
        System.out.println("--- Chunker Chaos Test PASSED ---");
    }
}
