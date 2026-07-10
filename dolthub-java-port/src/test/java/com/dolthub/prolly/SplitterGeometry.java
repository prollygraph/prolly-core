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
import java.util.ArrayList;
import java.util.List;

/**
 * Test-side model of {@link TreeMutator.Chunker}'s chunk emission, driving the <b>real</b> {@link
 * RollingHashSplitter}. The single source of truth shared by {@link
 * SplitterGeometryCharacterizationTest} (Step 1, named degenerate cases) and {@code
 * SplitterGeometryProperty} (Step 3, generated streams) of {@code
 * plans/prepublic/splitter-productionization.md}, so both pin the <i>same</i> real behavior.
 *
 * @apiNote This replays only the Chunker's <i>orchestration</i> (append each item; on {@link
 *     RollingHashSplitter#crossedBoundary()}, the emitted chunk's byte size is {@link
 *     RollingHashSplitter#offset()}, then {@link RollingHashSplitter#reset()}) — it does <b>not</b>
 *     reimplement the boundary decision rule, which stays solely in {@link RollingHashSplitter}
 *     (the D-4 "nothing reimplemented" discipline). The store/serializer are intentionally absent:
 *     the byte caps gate on {@code offset} (cumulative key+value bytes), so {@code offset} at a
 *     boundary <i>is</i> the geometry the caps bound.
 */
final class SplitterGeometry {

    /** The splitter's byte caps ({@code RollingHashSplitter.MIN/MAX_CHUNK_SIZE}). */
    static final int MIN = 1 << 9; // 512

    static final int MAX = 1 << 14; // 16384

    /**
     * Offset at which the homegrown ramp ({@code RollingHashSplitter.rollingHashPattern}) forces
     * {@code patt == 0} — a boundary then fires unconditionally, independent of content. This is
     * the <b>measured operative ceiling</b> on where a boundary is declared (Step 1: an
     * all-identical stream boundaried at exactly this offset), and it sits <i>below</i> the {@link
     * #MAX} hard cap, so under the current ramp the {@code MAX} cap is never the operative limit
     * for multi-item streams.
     */
    static final int RAMP_FORCE_OFFSET = 15 << 10; // 15360

    private SplitterGeometry() {}

    /**
     * One emitted chunk: its byte size (the splitter {@code offset} at emit), its item count, and
     * whether a rolling-hash boundary closed it ({@code true}) or it is the trailing {@code
     * done()}-flush ({@code false} — the only chunk that may fall below {@link #MIN}).
     */
    record Chunk(int bytes, int items, boolean byBoundary) {}

    /**
     * Replays {@link TreeMutator.Chunker}'s emit loop on a fresh real splitter at {@code level},
     * returning every emitted chunk including the trailing flush.
     */
    static List<Chunk> emit(int level, List<byte[][]> items) {
        RollingHashSplitter s = new RollingHashSplitter(level);
        List<Chunk> chunks = new ArrayList<>();
        int itemsInChunk = 0;
        for (byte[][] kv : items) {
            s.append(MemorySegment.ofArray(kv[0]), MemorySegment.ofArray(kv[1]));
            itemsInChunk++;
            if (s.crossedBoundary()) {
                chunks.add(new Chunk(s.offset(), itemsInChunk, true));
                itemsInChunk = 0;
                s.reset();
            }
        }
        if (itemsInChunk > 0) {
            chunks.add(new Chunk(s.offset(), itemsInChunk, false)); // trailing done()-flush
        }
        return chunks;
    }

    /** {@link #emit(int, List)} at level 0 (leaf). */
    static List<Chunk> emit(List<byte[][]> items) {
        return emit(0, items);
    }

    /**
     * Total key+value bytes across the stream — equals the sum of emitted chunk bytes
     * (conservation).
     */
    static long totalBytes(List<byte[][]> items) {
        long t = 0;
        for (byte[][] kv : items) {
            t += kv[0].length + kv[1].length;
        }
        return t;
    }

    /**
     * The largest single item's key+value byte size — the maximum a chunk can overshoot a cap by.
     */
    static int maxItemBytes(List<byte[][]> items) {
        int m = 0;
        for (byte[][] kv : items) {
            m = Math.max(m, kv[0].length + kv[1].length);
        }
        return m;
    }
}
