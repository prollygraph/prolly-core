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
package com.earasoft.prolly.pool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.TupleBuilder;
import org.junit.jupiter.api.Test;

/**
 * Footprint gate for buffer-pool segment recycling (plans/buffer-pool-segment-recycling.md Step 3,
 * ADR-0062 Goal 3): building many tuples through a {@link DirectBufferPool} transaction scope must
 * hold the <b>int64-scratch</b> footprint bounded — O(working-set), not O(tuples ×
 * fields-per-tuple) — once {@link TupleBuilder#close()} recycles the scratch.
 *
 * <p><b>Measurement design (why these dimensions).</b> {@code getTotalAllocatedBytes} counts only
 * <em>fresh</em> arena allocations (a reused/recycled block does not bump it). A tuple has two pool
 * costs: its K int64 <em>scratch</em> blocks ({@code putInt64} → {@code borrow(8)} → the 1024-byte
 * min bucket), which {@code close()} recycles, and its one <em>retained</em> tuple segment ({@code
 * build()} → {@code borrow(totalSize)}), which is never released (it backs the returned {@code
 * Tuple}). To stop the retained segment from masking the scratch win, K = 128 makes each tuple's
 * segment land in the <b>2048</b> bucket — separate from the 1024-byte scratch bucket — and makes
 * the scratch (128 blocks) dominate the per-tuple cost. The narrowed, honest claim this pins:
 * recycling collapses the scratch from O(M·K) to O(K); the total still carries the retained tuple
 * segments O(M), which is the coarse net's concern, not this one.
 */
class RecycleFootprintTest {

    private static final int M = 50; // tuples
    private static final int K =
            128; // int64 fields per tuple (scratch dominates; tuple → 2048 bucket)

    @Test
    void closeRecyclingBoundsTheInt64ScratchFootprint() {
        long withoutM = buildManyTuples(M, /* recycle= */ false);
        long withM = buildManyTuples(M, /* recycle= */ true);
        long with2M = buildManyTuples(2 * M, /* recycle= */ true);

        // Headline: recycling cuts the scratch-dominated footprint far below the non-recycling
        // O(M·K)
        // growth (the actual ratio is ~K-fold; assert a conservative >4x so it is robust to bucket
        // effects).
        assertTrue(
                withM * 4 < withoutM,
                "recycling must bound the scratch footprint well below the non-recycling growth: withM="
                        + withM
                        + " withoutM="
                        + withoutM);

        // Plateau: WITH recycling, DOUBLING the tuple count grows the footprint only by the
        // retained tuple
        // segments — the scratch is reused, so the M→2M increment stays far below even the single-M
        // non-recycling footprint. A scratch that still grew O(M·K) would make this increment
        // ~withoutM.
        assertTrue(
                (with2M - withM) * 4 < withoutM,
                "WITH recycling, doubling tuples must grow the footprint only by retained tuple segments"
                        + " (the int64 scratch plateaus): withM="
                        + withM
                        + " with2M="
                        + with2M
                        + " withoutM="
                        + withoutM);
    }

    /**
     * Build {@code m} single-use tuples (each K int64 fields) through a fresh {@link
     * DirectBufferPool#newTransactionScope()}; return the scope's total fresh-allocated bytes. With
     * {@code recycle}, each builder is {@code close()}d after its {@code build()} so its int64
     * scratch returns to the pool for the next builder to reuse.
     */
    private static long buildManyTuples(int m, boolean recycle) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            BufferPool scope = pool.newTransactionScope();
            try {
                for (int t = 0; t < m; t++) {
                    TupleBuilder b = new TupleBuilder(scope);
                    for (int f = 0; f < K; f++) {
                        b.putInt64(f, (long) t * K + f);
                    }
                    b.build();
                    if (recycle) {
                        b.close();
                    }
                }
                return ((DirectBufferPool) scope).getTotalAllocatedBytes();
            } finally {
                scope.close();
            }
        }
    }
}
