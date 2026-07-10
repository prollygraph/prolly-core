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

import com.dolthub.prolly.SplitterGeometry.Chunk;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase-0 <b>characterization</b> of the content-defined splitter's node geometry under degenerate
 * input — Step 1 of {@code plans/prepublic/splitter-productionization.md}. <b>No production code
 * changes:</b> it drives the <i>real</i> {@link RollingHashSplitter} via {@link SplitterGeometry}
 * (the shared model of {@link TreeMutator.Chunker}'s emit loop) and records the emitted node-byte
 * distribution per named degenerate case.
 *
 * @apiNote The measured frontier printed here is the <i>input</i> to Phase 1's generated property
 *     ({@link SplitterGeometryProperty}, Step 3). It asserts the invariants the byte caps make
 *     <b>unconditional</b> — determinism (same stream → identical boundaries), byte conservation, a
 *     boundary-emitted chunk never below {@code MIN}, and no chunk above {@code MAX + maxItemBytes}
 *     (a chunk can exceed {@code MAX} because the splitter cannot split mid-item, so the crossing
 *     item overshoots; see {@link #multi_item_chunk_can_exceed_MAX}).
 */
class SplitterGeometryCharacterizationTest {

    private static final int MIN = SplitterGeometry.MIN; // 512
    private static final int MAX = SplitterGeometry.MAX; // 16384

    // ---- stream builders (deterministic — no RNG, so the characterization is repeatable) ----

    /**
     * {@code n} items, each a {@code keyLen}-byte key + {@code valLen}-byte value filled by {@code
     * f}.
     */
    private static List<byte[][]> uniform(int n, int keyLen, int valLen, FillFn f) {
        List<byte[][]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new byte[][] {fill(keyLen, i, f), fill(valLen, i, f)});
        }
        return out;
    }

    @FunctionalInterface
    private interface FillFn {
        byte at(int itemIndex, int byteIndex);
    }

    private static byte[] fill(int len, int itemIndex, FillFn f) {
        byte[] b = new byte[len];
        for (int j = 0; j < len; j++) {
            b[j] = f.at(itemIndex, j);
        }
        return b;
    }

    private static final FillFn CONSTANT = (i, j) -> (byte) 0x41; // all-identical bytes
    private static final FillFn VARIED = (i, j) -> (byte) (i * 31 + j); // content-varied

    // ---- the report ----

    private static String describe(String name, List<Chunk> chunks) {
        int minB = Integer.MAX_VALUE;
        int maxB = 0;
        long totalB = 0;
        for (Chunk c : chunks) {
            minB = Math.min(minB, c.bytes());
            maxB = Math.max(maxB, c.bytes());
            totalB += c.bytes();
        }
        int n = chunks.size();
        if (n == 0) {
            minB = 0;
        }
        return String.format(
                "%-26s chunks=%-5d bytes[min=%-6d max=%-6d total=%-8d] firstItems=%s",
                name, n, minB, maxB, totalB, n == 0 ? "-" : String.valueOf(chunks.get(0).items()));
    }

    @Test
    void characterize_degenerate_geometry() {
        record Case(String name, List<byte[][]> items) {}
        List<Case> cases =
                List.of(
                        // a single value larger than MAX — the one >MAX chunk that holds ONE item
                        // (Goal 2)
                        new Case("single-oversized-value", uniform(1, 8, MAX + 4096, VARIED)),
                        // an item exactly at MAX bytes (boundary of the cap)
                        new Case("single-item-exactly-MAX", uniform(1, 0, MAX, VARIED)),
                        // all-identical bytes — does the ramp force a boundary, or only the MAX
                        // cap?
                        new Case("all-identical-bytes", uniform(4000, 4, 4, CONSTANT)),
                        // many tiny items (1-byte key, empty value) — fine-grained accumulation
                        new Case("all-min-tiny-items", uniform(8000, 1, 0, VARIED)),
                        // many ~MAX-sized items — each likely its own chunk
                        new Case("all-large-items", uniform(8, 8, MAX - 64, VARIED)),
                        // mid-sized constant items — the crossing item overshoots past MAX
                        // (multi-item >MAX)
                        new Case("large-constant-items", uniform(10, 0, 3000, CONSTANT)),
                        // fully degenerate: empty key + empty value, repeated (offset never
                        // advances)
                        new Case("all-empty-items", uniform(1000, 0, 0, CONSTANT)),
                        // a realistic varied stream (the determinism/repeatability control arm)
                        new Case("varied-medium", uniform(6000, 8, 16, VARIED)));

        System.out.println("=== splitter geometry frontier (MIN=" + MIN + " MAX=" + MAX + ") ===");
        for (Case c : cases) {
            List<Chunk> first = SplitterGeometry.emit(c.items());
            List<Chunk> second = SplitterGeometry.emit(c.items()); // determinism control
            assertEquals(
                    first, second, c.name() + ": splitter must be deterministic for given content");
            System.out.println(describe(c.name(), first));

            // Byte conservation: every appended byte lands in exactly one chunk.
            long sumChunkBytes = first.stream().mapToLong(Chunk::bytes).sum();
            assertEquals(
                    SplitterGeometry.totalBytes(c.items()),
                    sumChunkBytes,
                    c.name() + ": chunk bytes must conserve total item bytes");

            int maxItem = SplitterGeometry.maxItemBytes(c.items());
            for (int i = 0; i < first.size(); i++) {
                Chunk chunk = first.get(i);
                // A boundary-emitted chunk is never declared below MIN (the MIN gate); a trailing
                // done()-flush may be smaller and is exempt.
                if (chunk.byBoundary()) {
                    assertTrue(
                            chunk.bytes() >= MIN,
                            c.name() + ": boundary chunk " + i + " below MIN: " + chunk);
                }
                // The universal upper bound: a chunk overshoots a cap by at most one (the crossing)
                // item, since the splitter cannot split mid-item.
                assertTrue(
                        chunk.bytes() <= MAX + maxItem,
                        c.name()
                                + ": chunk "
                                + i
                                + " exceeds MAX+maxItem ("
                                + maxItem
                                + "): "
                                + chunk);
            }
        }
    }

    /**
     * Corrects the plan's Step-3 wording (and my own Step-1 assertion): the geometry bound is NOT
     * "chunk ∈ [MIN, MAX] except a single oversized item." A boundary fires by {@code
     * RAMP_FORCE_OFFSET} (15360), but the splitter cannot split mid-item, so the <i>crossing</i>
     * item runs to completion — a <b>multi-item</b> chunk whose crossing item is large lands above
     * {@code MAX}. Measured here so the false "(>MAX ⇒ single item)" invariant cannot be
     * re-introduced.
     */
    @Test
    void multi_item_chunk_can_exceed_MAX() {
        // 10 constant 3000-byte values: ~5 items reach offset 15000, the 6th crosses RAMP_FORCE
        // (15360) mid-item and runs on to ~18000 — one chunk, six items, > MAX.
        List<byte[][]> items = uniform(10, 0, 3000, CONSTANT);
        List<Chunk> chunks = SplitterGeometry.emit(items);

        Chunk overMax =
                chunks.stream()
                        .filter(ch -> ch.bytes() > MAX && ch.items() > 1)
                        .findFirst()
                        .orElse(null);
        assertTrue(
                overMax != null,
                "expected a multi-item chunk above MAX (crossing item overshoots): " + chunks);
        // ...but still bounded by the one-item overshoot.
        assertTrue(
                overMax.bytes() <= MAX + SplitterGeometry.maxItemBytes(items),
                "over-MAX chunk must still be within MAX + one item: " + overMax);
    }
}
