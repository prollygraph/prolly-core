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
package com.dolthub.prolly.chunkbench;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The hash-function study's NATIVE arm: the official C {@code libblake3} (AVX2 SIMD dispatch)
 * through Panama Foreign Function &amp; Memory downcalls — what "fast BLAKE3" actually means, as
 * opposed to BouncyCastle's scalar Java (measured 20-40× slower in {@link HashThroughputBench}).
 *
 * <p>One op = {@code blake3_hasher_init} + {@code update} + {@code finalize(20 bytes)} — the
 * content-address shape. Input bytes live in a NATIVE segment (placed once at setup): this measures
 * the HASH, not a per-op heap→native copy. A production binding would either pay that copy (a
 * memcpy, small next to hashing) or use critical-access heap segments — noted honestly in the
 * writeup, not measured here.
 *
 * <p>Requires {@code -Dblake3.lib=<path to libblake3.so>} (the Arch-packaged official build; its
 * TBB dependency must be on {@code LD_LIBRARY_PATH}). Correctness is pinned by a
 * BouncyCastle-agreement probe before any benching (blake3("ab") byte-identical).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
public class Blake3NativeThroughputBench {

    @Param({"64", "512", "4096", "16384"})
    int size;

    private MethodHandle init;
    private MethodHandle update;
    private MethodHandle finalize20;
    private Arena arena;
    private MemorySegment hasher;
    private MemorySegment data;
    private MemorySegment out;

    @Setup(Level.Trial)
    public void setUp() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lib =
                SymbolLookup.libraryLookup(System.getProperty("blake3.lib"), Arena.global());
        init =
                linker.downcallHandle(
                        lib.find("blake3_hasher_init").orElseThrow(),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        update =
                linker.downcallHandle(
                        lib.find("blake3_hasher_update").orElseThrow(),
                        FunctionDescriptor.ofVoid(
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        finalize20 =
                linker.downcallHandle(
                        lib.find("blake3_hasher_finalize").orElseThrow(),
                        FunctionDescriptor.ofVoid(
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        arena = Arena.ofConfined();
        hasher = arena.allocate(2048); // sizeof(blake3_hasher) = 1912; headroom is cheap
        out = arena.allocate(20);
        data = arena.allocate(size);
        byte[] bytes = new byte[size];
        new SplittableRandom(0x5EED_CAFE_F00DL).nextBytes(bytes);
        MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, size);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void hashTo20Bytes(Blackhole bh) throws Throwable {
        init.invoke(hasher);
        update.invoke(hasher, data, (long) size);
        finalize20.invoke(hasher, out, 20L);
        bh.consume(out.get(ValueLayout.JAVA_BYTE, 0));
    }
}
