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
import java.util.Random;

/**
 * SplitterStressTest targets the RollingHashSplitter with chaotic data patterns to ensure boundary
 * stability and deterministic behavior under duress.
 */
public class SplitterStressTest {
    public static void main(String[] args) {
        System.out.println("--- Prolly Tree Splitter Chaos Test ---");

        testStabilityUnderLocalChange();
        testPathologicalPatterns();
        testMinMaxBoundaries();

        System.out.println("--- Splitter Chaos Test PASSED ---");
    }

    /**
     * Verifies the "Local Change" invariant: changing one byte should only affect a small number of
     * chunks.
     */
    private static void testStabilityUnderLocalChange() {
        System.out.print("Verifying Local Change Invariant... ");
        Random rnd = new Random(42);
        byte[] data = new byte[1024 * 1024]; // 1MB
        rnd.nextBytes(data);

        int[] boundaries1 = getBoundaries(data);

        // Mutate one byte in the middle
        data[512 * 1024] ^= 0xFF;
        int[] boundaries2 = getBoundaries(data);

        // Most boundaries should be identical
        int matches = 0;
        int maxDiff = 0;
        for (int b1 : boundaries1) {
            for (int b2 : boundaries2) {
                if (b1 == b2) {
                    matches++;
                    break;
                }
            }
        }

        if (matches < boundaries1.length - 2) {
            throw new RuntimeException(
                    "Stability failed: too many boundaries changed. Found "
                            + matches
                            + "/"
                            + boundaries1.length);
        }
        System.out.println("Passed (" + matches + "/" + boundaries1.length + " shared).");
    }

    /** Tests patterns that usually break rolling hashes (all zeros, all ones, repeating). */
    private static void testPathologicalPatterns() {
        System.out.print("Testing Pathological Patterns (Zeros/Ones/Repeats)... ");
        testPattern(new byte[100 * 1024]); // All zeros
        byte[] ones = new byte[100 * 1024];
        for (int i = 0; i < ones.length; i++) ones[i] = (byte) 0xFF;
        testPattern(ones);

        byte[] repeats = new byte[100 * 1024];
        for (int i = 0; i < repeats.length; i++) repeats[i] = (byte) (i % 2);
        testPattern(repeats);
        System.out.println("Passed.");
    }

    private static void testPattern(byte[] data) {
        RollingHashSplitter splitter = new RollingHashSplitter(0);
        MemorySegment seg = MemorySegment.ofArray(data);
        int boundaryCount = 0;
        for (int i = 0; i < data.length; i++) {
            splitter.append(seg.asSlice(i, 1), null);
            if (splitter.crossedBoundary()) {
                boundaryCount++;
                splitter.reset();
            }
        }
        if (boundaryCount == 0)
            throw new RuntimeException("Splitter failed to find ANY boundaries in 100KB pattern");
    }

    /** Verifies that the splitter respects the Hard Min/Max limits. */
    private static void testMinMaxBoundaries() {
        System.out.print("Verifying Min/Max Constraints... ");
        RollingHashSplitter splitter = new RollingHashSplitter(0);
        byte[] data = new byte[100 * 1024];
        new Random(7).nextBytes(data);
        MemorySegment seg = MemorySegment.ofArray(data);

        int lastBoundary = 0;
        for (int i = 0; i < data.length; i++) {
            splitter.append(seg.asSlice(i, 1), null);
            if (splitter.crossedBoundary()) {
                int chunkSize = i - lastBoundary + 1;
                if (chunkSize < 512 || chunkSize > 16384) {
                    throw new RuntimeException("Constraint violation: chunk size " + chunkSize);
                }
                lastBoundary = i + 1;
                splitter.reset();
            }
        }
        System.out.println("Passed.");
    }

    private static int[] getBoundaries(byte[] data) {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        RollingHashSplitter splitter = new RollingHashSplitter(0);
        MemorySegment seg = MemorySegment.ofArray(data);
        for (int i = 0; i < data.length; i++) {
            splitter.append(seg.asSlice(i, 1), null);
            if (splitter.crossedBoundary()) {
                list.add(i);
                splitter.reset();
            }
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }
}
