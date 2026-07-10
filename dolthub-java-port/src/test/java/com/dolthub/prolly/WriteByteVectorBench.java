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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.flatbuffers.FlatBufferBuilder;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Paired A/B for the {@code FlatbufferNodeSerializer.writeByteVector} fix (an upstream
 * performance-bottleneck plan, D-1 / D-4) — an <b>isolating microbench with a control arm</b>:
 * strip everything but the variable (how the items' bytes reach the flatbuffer vector), keep two
 * arms — {@code perByte} (the OLD reverse {@code seg.get(JAVA_BYTE, j)} loop) and {@code bulk} (the
 * NEW {@link MemorySegment#copy} + {@link FlatBufferBuilder#createByteVector}). Asserts the two are
 * <b>byte-identical</b> (the load-bearing on-wire contract) and times both.
 *
 * <p>Opt-in ({@code -Dprolly.bench=true}) — it is a measurement, not a unit test. Uses
 * <b>native</b> segments via {@link Arena}, matching the production pool, so the JIT cannot fold
 * the per-element alignment check the way it might for a heap segment (regime fidelity — the cost
 * only appears on the native access path the flame captured). Numbers are single-run wall-clock on
 * the host that runs it; the authoritative byte-identity pin is {@code CrossLanguageFixtureTest} +
 * the codec round-trip, which this complements, not replaces.
 */
@EnabledIfSystemProperty(named = "prolly.bench", matches = "true")
class WriteByteVectorBench {

    private static final int ITEMS = 200; // a node's worth of leaves
    private static final int KEY = 24; // representative key/value sizes
    private static final int VAL = 40;

    @Test
    void perByteVsBulk_identicalBytes_andFaster() {
        try (Arena arena = Arena.ofConfined()) {
            List<MemorySegment> segs = new ArrayList<>(ITEMS);
            int total = 0;
            for (int i = 0; i < ITEMS; i++) {
                int n = (i % 2 == 0) ? KEY : VAL;
                MemorySegment s = arena.allocate(n);
                for (int j = 0; j < n; j++) {
                    s.set(ValueLayout.JAVA_BYTE, j, (byte) ((i * 31 + j) & 0xff));
                }
                segs.add(s);
                total += n;
            }

            // --- correctness: the two strategies must produce byte-identical flatbuffers ---
            byte[] a = build(this::perByte, segs, total);
            byte[] b = build(this::bulk, segs, total);
            assertArrayEquals(a, b, "bulk copy must be byte-identical to the per-byte loop");

            // --- timing: warm both, then measure ---
            final int iters = 20_000;
            for (int w = 0; w < 5; w++) { // warm (JIT)
                run(this::perByte, segs, total, 2000);
                run(this::bulk, segs, total, 2000);
            }
            long tOld = run(this::perByte, segs, total, iters);
            long tNew = run(this::bulk, segs, total, iters);

            double oldNs = tOld / (double) iters;
            double newNs = tNew / (double) iters;
            System.out.printf(
                    "[bench] writeByteVector  per-byte=%.0f ns/op  bulk=%.0f ns/op  speedup=%.2fx%n",
                    oldNs, newNs, oldNs / newNs);
            assertTrue(
                    newNs < oldNs,
                    "bulk copy must not be slower (was " + newNs + " vs " + oldNs + ")");
        }
    }

    private interface Strategy {
        int write(FlatBufferBuilder fbb, List<MemorySegment> segs, int total);
    }

    private byte[] build(Strategy s, List<MemorySegment> segs, int total) {
        FlatBufferBuilder fbb = new FlatBufferBuilder(1024);
        int v = s.write(fbb, segs, total);
        fbb.finish(v);
        return fbb.sizedByteArray();
    }

    private long run(Strategy s, List<MemorySegment> segs, int total, int iters) {
        FlatBufferBuilder fbb = new FlatBufferBuilder(1024);
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            fbb.clear();
            int v = s.write(fbb, segs, total);
            fbb.finish(v);
        }
        return System.nanoTime() - start;
    }

    /** The OLD implementation: per-byte reverse loop (each get runs isAlignedForElement). */
    private int perByte(FlatBufferBuilder fbb, List<MemorySegment> segs, int total) {
        fbb.startVector(1, total, 1);
        for (int i = segs.size() - 1; i >= 0; i--) {
            MemorySegment seg = segs.get(i);
            for (int j = (int) seg.byteSize() - 1; j >= 0; j--) {
                fbb.addByte(seg.get(ValueLayout.JAVA_BYTE, j));
            }
        }
        return fbb.endVector();
    }

    /** The NEW implementation: one bulk copy per segment + createByteVector. */
    private int bulk(FlatBufferBuilder fbb, List<MemorySegment> segs, int total) {
        byte[] data = new byte[total];
        int off = 0;
        for (MemorySegment seg : segs) {
            int n = (int) seg.byteSize();
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0L, data, off, n);
            off += n;
        }
        return fbb.createByteVector(data);
    }
}
