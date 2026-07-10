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

import java.security.MessageDigest;
import java.security.Security;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Step 2 of the hash-function study: content-address digest throughput per candidate at
 * chunk-relevant sizes — 64 B (a small commit object), 512 B (MIN chunk), 4096 B (target chunk),
 * 16384 B (MAX chunk). One op = reset + digest + 20-byte truncation, the exact {@code
 * HashUtils.hash} shape. MB/s = size / (ns/op) × 953.7.
 *
 * <p>The BLAKE candidates come from the BouncyCastle provider (test classpath; registered in
 * setup). SHA-256 is the JDK+hardware candidate — this reference box's Gracemont cores carry
 * SHA-NI, which the HotSpot intrinsic uses.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
public class HashThroughputBench {

    @Param({"SHA-512", "SHA-256", "BLAKE2B-160", "BLAKE3-256"})
    String algorithm;

    @Param({"64", "512", "4096", "16384"})
    int size;

    private MessageDigest digest;
    private byte[] data;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        digest = MessageDigest.getInstance(algorithm);
        data = new byte[size];
        new SplittableRandom(0x5EED_CAFE_F00DL).nextBytes(data);
    }

    @Benchmark
    public void hashTo20Bytes(Blackhole bh) {
        digest.reset();
        byte[] full = digest.digest(data);
        bh.consume(Arrays.copyOfRange(full, 0, 20));
    }
}
