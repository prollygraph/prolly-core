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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Deliverables 2–4 of the boundary study: chunk-size distribution tables per candidate (printed +
 * loosely sanity-asserted), the history-independence check (shuffle → re-sort → identical
 * boundaries), and the adversarial probe (candidate A on hash-derived ids vs this repo's
 * sequential-ordinal ids). Stats are computed over ~2M keys; every stream is seed-deterministic.
 */
class BoundaryDistributionStudyTest {

    private static final long SEED = 0x5EED_CAFE_F00DL;
    private static final int N = 2_000_000;

    private record Stats(
            int chunks, double mean, long p50, long p95, long p99, long min, long max, double sd) {

        static Stats of(List<Integer> sizes) {
            long[] s = sizes.stream().mapToLong(Integer::longValue).sorted().toArray();
            double mean = Arrays.stream(s).average().orElse(0);
            double var =
                    Arrays.stream(s).mapToDouble(v -> (v - mean) * (v - mean)).sum() / s.length;
            return new Stats(
                    s.length,
                    mean,
                    s[(int) (s.length * 0.50)],
                    s[(int) (s.length * 0.95)],
                    s[(int) (s.length * 0.99)],
                    s[0],
                    s[s.length - 1],
                    Math.sqrt(var));
        }

        String row(String name) {
            return String.format(
                    "%-22s %8d %8.0f %8d %8d %8d %6d %6d %8.0f",
                    name, chunks, mean, p50, p95, p99, min, max, sd);
        }
    }

    private static List<Integer> chunkSizes(BoundaryStrategies.Boundary b, KeyStreams.Stream s) {
        List<Integer> sizes = new ArrayList<>();
        int chunkStart = 0;
        for (int i = 0; i < s.count(); i++) {
            if (b.acceptKey(s.flat(), i * s.keyWidth(), s.keyWidth())) {
                int end = (i + 1) * s.keyWidth();
                sizes.add(end - chunkStart);
                chunkStart = end;
            }
        }
        return sizes;
    }

    private static List<Supplier<BoundaryStrategies.Boundary>> candidates(int keyWidth) {
        return List.of(
                () -> new BoundaryStrategies.DirectMask(keyWidth, SEED),
                () -> new BoundaryStrategies.DirectMaskXor(keyWidth, SEED),
                () -> new BoundaryStrategies.Gear(SEED),
                () -> new BoundaryStrategies.GearSingleMask(SEED),
                () -> new BoundaryStrategies.BuzhashKeys(SEED));
    }

    @Test
    void distributionTables_hashedPremise_and_ordinalReality() {
        for (var shape :
                List.of(
                        new Object[] {"hashed64 (task premise)", KeyStreams.hashedIdKeys(N, SEED)},
                        new Object[] {
                            "ordinal32 (repo reality)", KeyStreams.ordinalKeys(N, SEED)
                        })) {
            String label = (String) shape[0];
            KeyStreams.Stream stream = (KeyStreams.Stream) shape[1];
            System.out.printf(
                    "%n== %s — %d keys × %dB (target %dB, min %d, max %d) ==%n",
                    label,
                    stream.count(),
                    stream.keyWidth(),
                    BoundaryStrategies.TARGET,
                    BoundaryStrategies.MIN,
                    BoundaryStrategies.MAX);
            System.out.printf(
                    "%-22s %8s %8s %8s %8s %8s %6s %6s %8s%n",
                    "candidate", "chunks", "mean", "p50", "p95", "p99", "min", "max", "sd");
            for (var mk : candidates(stream.keyWidth())) {
                BoundaryStrategies.Boundary b = mk.get();
                Stats st = Stats.of(chunkSizes(b, stream));
                System.out.println(st.row(b.name()));
                // Loose sanity floor for every candidate: geometry is bounded.
                assertTrue(st.min() >= BoundaryStrategies.MIN, b.name() + " min >= MIN");
                assertTrue(
                        st.max() <= BoundaryStrategies.MAX + stream.keyWidth(),
                        b.name() + " max <= MAX + one key");
            }
        }
    }

    @Test
    void twoMask_tightensVariance_overSingleMask_onThePremiseStream() {
        KeyStreams.Stream s = KeyStreams.hashedIdKeys(N, SEED);
        Stats twoMask = Stats.of(chunkSizes(new BoundaryStrategies.Gear(SEED), s));
        Stats single = Stats.of(chunkSizes(new BoundaryStrategies.GearSingleMask(SEED), s));
        System.out.printf(
                "%ntwo-mask sd/mean = %.3f, single-mask sd/mean = %.3f%n",
                twoMask.sd() / twoMask.mean(), single.sd() / single.mean());
        assertTrue(
                twoMask.sd() / twoMask.mean() < single.sd() / single.mean(),
                "normalized two-mask must tighten relative variance vs the single-mask baseline");
    }

    @Test
    void historyIndependence_shuffleThenResort_identicalBoundaries() {
        KeyStreams.Stream sorted = KeyStreams.hashedIdKeys(200_000, SEED);
        // Shuffle the keys, then re-sort: the rebuilt stream must be byte-identical, and every
        // candidate's boundary INDEX SET must match exactly (the stream-level equivalent of the
        // byte-identical-root assertion; the incumbent's tree-level determinism is pinned by
        // ChunkerDeterminismGateTest).
        byte[][] keys = new byte[sorted.count()][];
        for (int i = 0; i < sorted.count(); i++) {
            keys[i] =
                    Arrays.copyOfRange(
                            sorted.flat(), i * sorted.keyWidth(), (i + 1) * sorted.keyWidth());
        }
        java.util.Collections.shuffle(Arrays.asList(keys), new java.util.Random(7));
        Arrays.sort(keys, Arrays::compareUnsigned);
        byte[] rebuilt = new byte[sorted.flat().length];
        for (int i = 0; i < keys.length; i++) {
            System.arraycopy(keys[i], 0, rebuilt, i * sorted.keyWidth(), sorted.keyWidth());
        }
        assertTrue(Arrays.equals(sorted.flat(), rebuilt), "re-sorted stream is byte-identical");
        KeyStreams.Stream resorted =
                new KeyStreams.Stream(rebuilt, sorted.keyWidth(), sorted.count());

        for (var mk : candidates(sorted.keyWidth())) {
            List<Integer> a = chunkSizes(mk.get(), sorted);
            List<Integer> b = chunkSizes(mk.get(), resorted);
            assertEquals(a, b, mk.get().name() + " boundaries must be history-independent");
        }
    }
}
