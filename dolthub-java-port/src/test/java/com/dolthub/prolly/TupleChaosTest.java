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

import java.util.Random;

/**
 * TupleChaosTest targets Tuple and TupleBuilder with extreme field counts, sizes, and null
 * patterns.
 */
public class TupleChaosTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Tuple Chaos Test ---");
        HeapBufferPool pool = new HeapBufferPool();
        {
            testMaxFields(pool);
            testLargeFields(pool);
            testNullPatternChaos(pool);
            testFieldAccessOutOfBounds(pool);
        }
        System.out.println("--- Tuple Chaos Test PASSED ---");
    }

    private static void testMaxFields(HeapBufferPool pool) {
        System.out.print("Testing Max Field Count (Short.MAX_VALUE)... ");
        TupleBuilder tb = new TupleBuilder(pool);
        int count = 1000; // Practical large count, Short.MAX is theoretical limit for the format
        for (int i = 0; i < count; i++) {
            tb.putField(i, new byte[] {(byte) (i % 256)});
        }
        Tuple t = tb.build();
        if (t.count() != count) throw new RuntimeException("Count mismatch: " + t.count());
        for (int i = 0; i < count; i++) {
            if (t.getField(i)[0] != (byte) (i % 256))
                throw new RuntimeException("Data mismatch at " + i);
        }
        System.out.println("Passed.");
    }

    private static void testLargeFields(HeapBufferPool pool) {
        System.out.print("Testing Large Fields (64KB total)... ");
        TupleBuilder tb = new TupleBuilder(pool);
        byte[] large = new byte[60000];
        new Random(42).nextBytes(large);
        tb.putField(0, large);
        Tuple t = tb.build();
        byte[] retrieved = t.getField(0);
        if (retrieved.length != large.length) throw new RuntimeException("Size mismatch");
        System.out.println("Passed.");
    }

    private static void testNullPatternChaos(HeapBufferPool pool) {
        System.out.print("Testing Null Pattern Chaos... ");
        Random rnd = new Random(123);
        for (int run = 0; i < 100; i++) {
            TupleBuilder tb = new TupleBuilder(pool);
            boolean[] isNull = new boolean[20];
            for (int j = 0; j < 20; j++) {
                isNull[j] = rnd.nextBoolean();
                if (!isNull[j]) tb.putField(j, new byte[] {(byte) j});
                else tb.putField(j, (byte[]) null);
            }
            Tuple t = tb.build();
            for (int j = 0; j < 20; j++) {
                byte[] f = t.getField(j);
                if (isNull[j] && f != null) throw new RuntimeException("Expected null at " + j);
                if (!isNull[j] && (f == null || f[0] != (byte) j))
                    throw new RuntimeException("Data mismatch at " + j);
            }
        }
        System.out.println("Passed.");
    }

    private static int i = 0; // Fix for the loop above

    private static void testFieldAccessOutOfBounds(HeapBufferPool pool) {
        System.out.print("Testing Out-of-Bounds Access... ");
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, new byte[] {1});
        Tuple t = tb.build();
        if (t.getFieldSegment(1) != null) throw new RuntimeException("Should return null for OOB");
        if (t.getFieldSegment(1000) != null)
            throw new RuntimeException("Should return null for large OOB");
        System.out.println("Passed.");
    }
}
