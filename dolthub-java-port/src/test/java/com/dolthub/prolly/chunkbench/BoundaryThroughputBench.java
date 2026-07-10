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

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Deliverable 1 of the boundary study — per-key cost of each candidate over a realistic sorted key
 * stream, with a memory-bandwidth reference point (a bare XOR-reduction pass over the same bytes:
 * the do-nothing-but-read-memory floor any boundary function is bounded below by).
 *
 * <p>One invocation processes the WHOLE stream ({@code OperationsPerInvocation = N}), so the score
 * IS ns/key; MB/s = keyWidth / (ns/key) × 953.7. Both stream shapes run: the task's hashed-id
 * premise (64-byte keys) and this repo's ordinal reality (32-byte keys).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@OperationsPerInvocation(BoundaryThroughputBench.N)
@Fork(3)
public class BoundaryThroughputBench {

    static final int N = 1_000_000;
    private static final long SEED = 0x5EED_CAFE_F00DL;

    @Param({"hashed64", "ordinal32"})
    String shape;

    private byte[] flat;
    private int keyWidth;

    private BoundaryStrategies.DirectMask a;
    private BoundaryStrategies.DirectMaskXor aPrime;
    private BoundaryStrategies.Gear gear;
    private BoundaryStrategies.BuzhashKeys buz;

    @Setup(Level.Trial)
    public void setUp() {
        KeyStreams.Stream s =
                "hashed64".equals(shape)
                        ? KeyStreams.hashedIdKeys(N, SEED)
                        : KeyStreams.ordinalKeys(N, SEED);
        this.flat = s.flat();
        this.keyWidth = s.keyWidth();
        this.a = new BoundaryStrategies.DirectMask(keyWidth, SEED);
        this.aPrime = new BoundaryStrategies.DirectMaskXor(keyWidth, SEED);
        this.gear = new BoundaryStrategies.Gear(SEED);
        this.buz = new BoundaryStrategies.BuzhashKeys(SEED);
    }

    @Benchmark
    public void a_directMask(Blackhole bh) {
        int boundaries = 0;
        for (int i = 0; i < N; i++) {
            if (a.acceptKey(flat, i * keyWidth, keyWidth)) {
                boundaries++;
            }
        }
        bh.consume(boundaries);
    }

    @Benchmark
    public void aPrime_directMaskXor(Blackhole bh) {
        int boundaries = 0;
        for (int i = 0; i < N; i++) {
            if (aPrime.acceptKey(flat, i * keyWidth, keyWidth)) {
                boundaries++;
            }
        }
        bh.consume(boundaries);
    }

    @Benchmark
    public void b_gear(Blackhole bh) {
        int boundaries = 0;
        for (int i = 0; i < N; i++) {
            if (gear.acceptKey(flat, i * keyWidth, keyWidth)) {
                boundaries++;
            }
        }
        bh.consume(boundaries);
    }

    @Benchmark
    public void c_buzhashKeys(Blackhole bh) {
        int boundaries = 0;
        for (int i = 0; i < N; i++) {
            if (buz.acceptKey(flat, i * keyWidth, keyWidth)) {
                boundaries++;
            }
        }
        bh.consume(boundaries);
    }

    /** The memory floor: read every byte once, do (almost) nothing. */
    @Benchmark
    public void memoryReference_xorReduce(Blackhole bh) {
        long acc = 0;
        byte[] f = flat;
        for (int i = 0; i < f.length; i++) {
            acc ^= f[i];
        }
        bh.consume(acc);
    }
}
