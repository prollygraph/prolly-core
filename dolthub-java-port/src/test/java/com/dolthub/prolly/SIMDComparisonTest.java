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
 * SIMDComparisonTest provides an empirical comparison between traditional Java loops and Panama's
 * SIMD-accelerated mismatch() method.
 */
public class SIMDComparisonTest {
    private static final int DATA_SIZE = 1024 * 1024 * 50; // 50MB
    private static final int ITERATIONS = 100;

    public static void main(String[] args) {
        System.out.println("--- Prolly Tree SIMD Comparison Test ---");

        byte[] a = new byte[DATA_SIZE];
        byte[] b = new byte[DATA_SIZE];
        Random rnd = new Random(42);
        rnd.nextBytes(a);
        System.arraycopy(a, 0, b, 0, DATA_SIZE);

        // Introduce a mismatch at the very end to force full scan
        b[DATA_SIZE - 1] ^= 1;

        MemorySegment msA = MemorySegment.ofArray(a);
        MemorySegment msB = MemorySegment.ofArray(b);

        // 1. Benchmark Standard Loop
        System.out.print("Running Standard Loop comparison... ");
        long startLoop = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            compareStandardLoop(a, b);
        }
        long endLoop = System.nanoTime();
        double loopTime = (endLoop - startLoop) / 1_000_000.0;
        System.out.printf("%.2f ms\n", loopTime);

        // 2. Benchmark SIMD mismatch()
        System.out.print("Running SIMD mismatch() comparison... ");
        long startSIMD = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            msA.mismatch(msB);
        }
        long endSIMD = System.nanoTime();
        double simdTime = (endSIMD - startSIMD) / 1_000_000.0;
        System.out.printf("%.2f ms\n", simdTime);

        // 3. Verification & Assertion
        double speedup = loopTime / simdTime;
        System.out.printf("SIMD Speedup: %.2fx\n", speedup);

        if (speedup < 2.0) {
            System.err.println(
                    "WARNING: SIMD speedup was less than 2x. Ensure you are running on a modern CPU with AVX/SSE.");
        } else {
            System.out.println("SUCCESS: SIMD acceleration verified.");
        }
    }

    private static int compareStandardLoop(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) return i;
        }
        return -1;
    }
}
