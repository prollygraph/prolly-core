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
 * <h3>Large Object & Structural Limit Test</h3>
 *
 * <p>Verifies that the engine correctly handles values up to the 64KB uint16 limit and fails
 * gracefully beyond that.
 */
public class LargeObjectTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Large Object Test ---");
        Path tempDir = Files.createTempDirectory("prolly-large-obj");

        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            // 1. Test 60KB Blob (Within uint16 limit)
            System.out.print("Testing 60KB blob (Valid Range)... ");
            byte[] blob60 = new byte[60 * 1024];
            new Random(1).nextBytes(blob60);

            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, "key1".getBytes());
            tb.putField(1, blob60);
            Node root =
                    mutator.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    buildKey(pool, "key1"), tb.build().segment()))
                                    .iterator());

            StaticMap map = new StaticMap(store, root, desc);
            MemorySegment res = map.get(buildKey(pool, "key1")).orElseThrow();
            // Tuple has 2 fields: [key1][blob60][offset1][offset2][count]
            // We want to check field index 1
            MemorySegment val = new Tuple(res).getFieldSegment(1);
            if (!Arrays.equals(blob60, val.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))) {
                throw new RuntimeException("60KB Blob Data Corruption!");
            }
            System.out.println("Passed.");

            // 2. Test 1MB Blob (Outside uint16 limit)
            System.out.print("Testing 1MB blob (Expected Failure)... ");
            byte[] blobLarge = new byte[1024 * 1024];
            try {
                TupleBuilder tb2 = new TupleBuilder(pool);
                tb2.putField(0, blobLarge);
                tb2.build();
                System.err.println("FAILED: Should have thrown IllegalArgumentException!");
                System.exit(1);
            } catch (IllegalArgumentException e) {
                System.out.println("Passed (Detected: " + e.getMessage() + ")");
            }

            System.out.println("--- Large Object Test PASSED ---");
        }
    }

    private static MemorySegment buildKey(HeapBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        return tb.build().segment();
    }
}
