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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Runnable companion to {@code
 * blog/integer-rotateleft-identical-faster-and-the-rotate-instruction.md} — the probe behind that
 * post, saved so anyone can reproduce both halves of its claim.
 *
 * <p><b>Run it:</b>
 *
 * <pre>  mvn -pl dolthub-java-port test -Dtest=RotateProbeTest</pre>
 *
 * The throughput method prints an ops/sec table to stdout. To see that {@code Integer.rotateLeft}
 * is <i>inlined</i> (not applied as a named intrinsic) on your JVM, add {@code
 * -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining} via {@code MAVEN_OPTS}.
 *
 * <p><b>Two halves, deliberately different in rigor:</b>
 *
 * <ol>
 *   <li>{@link #rotateLeft_is_bit_identical_to_the_shift_or_idiom()} — the rigorous, deterministic
 *       half. Pins the language guarantee that {@code Integer.rotateLeft(x,d)} equals the
 *       hand-written {@code (x<<d)|(x>>>(32-d))} idiom for <i>every</i> input and <i>every</i>
 *       distance. Both sides mask the shift count modulo the width (JLS §15.19), so the equality
 *       holds even for {@code d=0}, {@code d>=width}, and negative {@code d}. This is why swapping
 *       the idiom for {@code rotateLeft} is a pure, non-behavioral refactor.
 *   <li>{@link #prints_indicative_rotate_throughput()} — an <b>INDICATIVE</b> ops/sec print, not a
 *       rigorous benchmark. It is a single-JVM, un-forked timing loop; it does the bare minimum to
 *       be non-garbage (warm-up, a dead-code-elimination-defeating escaping sink, best-of-N rounds,
 *       a latency-bound dependency chain like a real rolling hash). It deliberately <b>does not
 *       assert a speed ordering</b> — that is the blog's whole point: bit-identity is guaranteed,
 *       but a speedup is contextual and must be <i>measured</i>, not assumed. C2 often folds BOTH
 *       the manual idiom and {@code rotateLeft} to a single hardware rotate, so you may well see
 *       them tie here. For rigorous, forked, control-armed numbers use JMH — see {@code an upstream
 *       BuzHash benchmark}.
 * </ol>
 */
class RotateProbeTest {

    // The four variants the blog compares. Kept tiny and static so C2 inlines them into the timing
    // loops below, exactly as it inlines them into real callers.
    private static int manualInt(int x) {
        return (x << 1) | (x >>> 31);
    }

    private static int rotlInt(int x) {
        return Integer.rotateLeft(x, 1);
    }

    private static int manualVarInt(int x, int d) {
        return (x << d) | (x >>> (32 - d));
    }

    private static int rotlVarInt(int x, int d) {
        return Integer.rotateLeft(x, d);
    }

    @Test
    void rotateLeft_is_bit_identical_to_the_shift_or_idiom() {
        // distances include 0, the common 1..31, and wrap/negative values to exercise the
        // shift-count
        // masking that makes the identity total (not just "for 1..31").
        int[] xs = {
            0,
            1,
            -1,
            Integer.MIN_VALUE,
            Integer.MAX_VALUE,
            0x80000000,
            0x12bd9527,
            0xCAFEBABE,
            7,
            -7
        };
        int[] ds = {-65, -33, -32, -1, 0, 1, 2, 7, 15, 16, 17, 31, 32, 33, 64, 65};
        Random rng = new Random(0xC0FFEEL);
        for (int t = 0; t < 5000; t++) {
            final int x = (t < xs.length) ? xs[t] : rng.nextInt();
            for (final int d : ds) {
                assertEquals(
                        Integer.rotateLeft(x, d),
                        (x << d) | (x >>> (32 - d)),
                        () -> "int rotateLeft != idiom: x=" + x + " d=" + d);
            }
        }

        // The Long variant carries the same guarantee at width 64.
        long[] lxs = {
            0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x0123456789ABCDEFL, 0xDEADBEEFCAFEBABEL
        };
        int[] lds = {-129, -65, -64, -1, 0, 1, 31, 32, 33, 63, 64, 65, 128};
        Random lrng = new Random(0xBEEFL);
        for (int t = 0; t < 5000; t++) {
            final long x = (t < lxs.length) ? lxs[t] : lrng.nextLong();
            for (final int d : lds) {
                assertEquals(
                        Long.rotateLeft(x, d),
                        (x << d) | (x >>> (64 - d)),
                        () -> "long rotateLeft != idiom: x=" + x + " d=" + d);
            }
        }
    }

    @Test
    void prints_indicative_rotate_throughput() {
        final long ops = 30_000_000L;
        final int rounds = 4;
        // A runtime (non-constant) distance, like BuzHash's per-level salt shift — so C2 cannot
        // treat the
        // variable-distance rotate as a compile-time constant.
        final int d = Integer.getInteger("rotate.d", 7);

        // Warm every variant so C2 compiles each before any is timed (reduces ordering bias).
        long w = SINK;
        for (int i = 0; i < 15_000_000; i++) {
            w += manualInt((int) w ^ i);
            w += rotlInt((int) w ^ i);
            w += manualVarInt((int) w ^ i, d);
            w += rotlVarInt((int) w ^ i, d);
        }
        SINK = w;

        double manual = bestOpsPerSec(0, ops, rounds, d);
        double rotl = bestOpsPerSec(1, ops, rounds, d);
        double manualVar = bestOpsPerSec(2, ops, rounds, d);
        double rotlVar = bestOpsPerSec(3, ops, rounds, d);

        System.out.printf(
                "%n=== Integer.rotateLeft throughput — INDICATIVE (single JVM, no fork; NOT JMH) ===%n");
        System.out.printf(
                "  best of %d rounds x %,d ops; sink=%d (escapes → proves the loop is not eliminated)%n",
                rounds, ops, SINK);
        System.out.printf("  manual  (x<<1)|(x>>>31)          : %8.1f M ops/s%n", manual / 1e6);
        System.out.printf("  Integer.rotateLeft(x, 1)         : %8.1f M ops/s%n", rotl / 1e6);
        System.out.printf(
                "  manual  (x<<d)|(x>>>(32-d)) d=%-2d  : %8.1f M ops/s%n", d, manualVar / 1e6);
        System.out.printf(
                "  Integer.rotateLeft(x, d)    d=%-2d  : %8.1f M ops/s%n", d, rotlVar / 1e6);
        System.out.printf(
                "  NOTE: order is NOT asserted — C2 often folds BOTH forms to one hardware rotate (blog).%n");
        System.out.printf(
                "        For rigorous forked numbers use JMH: BuzHashBenchmark + scripts/run-bench.sh.%n");

        // Sanity only (NOT a perf-ordering claim): the loops ran and produced plausible
        // single-thread
        // rates. A dead-code-eliminated loop would read as absurdly fast; a broken harness as
        // ~zero.
        for (double v : new double[] {manual, rotl, manualVar, rotlVar}) {
            assertTrue(
                    v > 1e6 && v < 1e12,
                    "implausible ops/s (harness broken / loop eliminated?): " + v);
        }
    }

    // --- timing scaffolding: one monomorphic loop per variant (no switch in the hot path) ---

    private static long SINK = 1;

    private static double bestOpsPerSec(int variant, long ops, int rounds, int d) {
        double best = 0;
        for (int r = 0; r < rounds; r++) {
            long t0 = System.nanoTime();
            long acc =
                    switch (variant) {
                        case 0 -> runManual(ops);
                        case 1 -> runRotl(ops);
                        case 2 -> runManualVar(ops, d);
                        default -> runRotlVar(ops, d);
                    };
            long ns = System.nanoTime() - t0;
            SINK ^= acc; // escape → defeats dead-code elimination
            if (ns > 0) best = Math.max(best, ops / (ns / 1e9));
        }
        return best;
    }

    private static long runManual(long ops) {
        long a = SINK;
        for (long i = 0; i < ops; i++) a += manualInt((int) a ^ (int) i);
        return a;
    }

    private static long runRotl(long ops) {
        long a = SINK;
        for (long i = 0; i < ops; i++) a += rotlInt((int) a ^ (int) i);
        return a;
    }

    private static long runManualVar(long ops, int d) {
        long a = SINK;
        for (long i = 0; i < ops; i++) a += manualVarInt((int) a ^ (int) i, d);
        return a;
    }

    private static long runRotlVar(long ops, int d) {
        long a = SINK;
        for (long i = 0; i < ops; i++) a += rotlVarInt((int) a ^ (int) i, d);
        return a;
    }
}
