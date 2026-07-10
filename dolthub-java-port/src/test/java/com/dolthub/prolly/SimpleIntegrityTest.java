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

public class SimpleIntegrityTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Simple Integrity Test ---");
        Path tempDir = Files.createTempDirectory("prolly-simple");

        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);
            Map<String, String> groundTruth = new TreeMap<>();

            System.out.print("Applying 500 mutations in one batch... ");
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                String k = String.format("k-%05d", i);
                String v = "v-" + i;
                edits.add(
                        new TreeMutator.Mutation(
                                buildKey(pool, k), MemorySegment.ofArray(v.getBytes())));
                groundTruth.put(k, v);
            }
            Node root = mutator.applyMutations(null, edits.iterator());
            System.out.println("Done. Height: " + root.level() + ", Count: " + root.treeCount());

            StaticMap map = new StaticMap(store, root, desc);
            MapIterator it = map.iter();
            int count = 0;
            while (it.next()) {
                String k = new String(new Tuple(it.key()).getField(0));
                String v = new String(it.value().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
                String expectedK = String.format("k-%05d", count);
                String expectedV = "v-" + count;
                if (!k.equals(expectedK))
                    throw new RuntimeException(
                            "Mismatch at " + count + ": got " + k + ", expected " + expectedK);
                if (!v.equals(expectedV)) throw new RuntimeException("Val mismatch at " + count);
                count++;
            }
            if (count != 500)
                throw new RuntimeException("Count mismatch: got " + count + ", expected 500");
            System.out.println("Scan Integrity: Passed.");

            System.out.print("Applying update batch (250-750)... ");
            List<TreeMutator.Mutation> updates = new ArrayList<>();
            for (int i = 250; i < 750; i++) {
                String k = String.format("k-%05d", i);
                String v = "v-upd-" + i;
                updates.add(
                        new TreeMutator.Mutation(
                                buildKey(pool, k), MemorySegment.ofArray(v.getBytes())));
                groundTruth.put(k, v);
            }
            root = mutator.applyMutations(root, updates.iterator());
            System.out.println("Done. Height: " + root.level() + ", Count: " + root.treeCount());

            if (root.treeCount() != 750)
                throw new RuntimeException("Final count should be 750, got " + root.treeCount());

            System.out.println("--- Simple Integrity Test PASSED ---");
        }
    }

    private static MemorySegment buildKey(HeapBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        return tb.build().segment();
    }
}
