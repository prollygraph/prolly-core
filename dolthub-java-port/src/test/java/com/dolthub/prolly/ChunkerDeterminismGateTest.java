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

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The <b>determinism gate</b> for the chunker-throughput levers (Step 3 of the upstream
 * chunker-throughput plan). Every speed lever in that plan is required to be
 * <b>byte-boundary-preserving</b>: a faster chunker that splits the <i>identical</i> way. This test
 * is the gate that proves it — it recomputes the two load-bearing outputs <b>through the production
 * code path</b> and asserts them against the pinned {@link BootstrapHashes} golden vectors.
 *
 * @apiNote Two arms, matching the plan's Step 3:
 *     <ol>
 *       <li><b>Boundary differential</b> — drives the real {@link RollingHashSplitter#append} over
 *           the deterministic 64&nbsp;KiB stream (seed {@code 0xCAFEBABEL}) and asserts the
 *           captured boundary offsets equal {@link BootstrapHashes#BOUNDARY_BUZHASH_OFFSETS}. A
 *           lever that moves <i>any</i> boundary (e.g. an off-by-one in Step 4's skip-prefix) fails
 *           here loudly.
 *       <li><b>Root-hash determinism</b> — builds the deterministic 1000-tuple corpus through the
 *           real {@link TreeMutator} and asserts the root SHA-512/20 equals {@link
 *           BootstrapHashes#BOUNDARY_GOLDEN_ROOT}. A moved boundary silently re-chunks the tree and
 *           changes the root; this catches it.
 *     </ol>
 *
 * @implNote <b>Why this is not redundant with what already existed</b> (the gap Step 3 closes): no
 *     test in CI recomputed these outputs through the production splitter and compared them to the
 *     pin. {@code BootstrapHashesTest} <i>used to</i> assert the {@code BootstrapHashes} constants
 *     equal literal copies of <i>themselves</i> — a tautological self-pin that a chunker change
 *     could not trip (those literal pins were removed in splitter-productionization Step 7; it now
 *     keeps only structural invariants). The retired {@code BoundaryGoldenVectorTest} drove the
 *     right corpus but was a {@code main()} driver whose boundary arm <i>reimplemented</i> the
 *     decision rule instead of calling {@link RollingHashSplitter#append}, so a change to {@code
 *     hashSegment} would not have been caught even when it ran — this test fully superseded it
 *     (same 64&nbsp;KiB stream + same 1000-tuple corpus, through the real code) and it was retired
 *     in Step 7. {@link InvDeterminismProperty} does drive the real mutator, but only asserts that
 *     four <i>build paths</i> agree with each other — all four move together when the splitter
 *     changes, so it pins history-independence, not a fixed baseline. This test is the
 *     recompute-and-compare-vs-pin that closes that hole, runs under Surefire, and exercises
 *     exactly the {@code append}/{@code hashSegment} path the Phase&nbsp;1–2 levers touch.
 *     <p>The boundary arm feeds the stream <b>one byte at a time</b> ({@code reset()}-ing on each
 *     boundary) so the captured offsets are byte-exact (a multi-byte feed would overshoot the
 *     observable boundary to the end of the piece). This also drives Step 4's per-byte skip path on
 *     every pre-threshold byte; Step 4's multi-byte <i>straddling-segment</i> path is gated by the
 *     root-hash arm, which builds real (multi-byte) tuples. Collaborators: {@link
 *     RollingHashSplitter} + {@link BuzHash} (the unit under test), {@link TreeMutator} + {@link
 *     InMemoryNodeStore} + {@link HeapBufferPool} (the root-hash build), {@link BootstrapHashes}
 *     (the single source of truth for the pinned vectors).
 */
class ChunkerDeterminismGateTest {

    /**
     * Corpus parameters for the pinned vector (formerly shared with the retired {@code
     * BoundaryGoldenVectorTest}; this test is now their sole source of truth).
     */
    private static final int STREAM_BYTES = 64 * 1024;

    private static final long STREAM_SEED = 0xCAFEBABEL;
    private static final int CORPUS_TUPLES = 1000;

    @Test
    void boundaryOffsets_throughRealSplitter_matchPinnedGoldenVector() {
        byte[] stream = new byte[STREAM_BYTES];
        new Random(STREAM_SEED).nextBytes(stream);
        MemorySegment seg = MemorySegment.ofArray(stream);

        // Drive the REAL splitter, one byte at a time, resetting on each boundary — the way a
        // boundary differential captures byte-exact offsets through the production append() path.
        RollingHashSplitter splitter = new RollingHashSplitter(0);
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < stream.length; i++) {
            splitter.append(seg.asSlice(i, 1), MemorySegment.NULL);
            if (splitter.crossedBoundary()) {
                offsets.add(i + 1); // bytes consumed up to and including the triggering byte
                splitter.reset();
            }
        }

        int[] actual = offsets.stream().mapToInt(Integer::intValue).toArray();
        assertArrayEquals(
                BootstrapHashes.BOUNDARY_BUZHASH_OFFSETS,
                actual,
                "RollingHashSplitter.append moved a chunk boundary: a throughput lever must split"
                        + " byte-identically (chunker-throughput D-1). expected="
                        + java.util.Arrays.toString(BootstrapHashes.BOUNDARY_BUZHASH_OFFSETS)
                        + " actual="
                        + java.util.Arrays.toString(actual));
    }

    @Test
    void rootHash_ofFixedCorpus_throughRealTreeMutator_matchesPinnedGolden() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < CORPUS_TUPLES; i++) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("golden-%05d", i).getBytes());
                edits.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(),
                                MemorySegment.ofArray(("payload-" + i).getBytes())));
            }

            Node root = mutator.applyMutations(null, edits.iterator());
            byte[] actualRoot = store.write(root.segment());

            assertArrayEquals(
                    BootstrapHashes.BOUNDARY_GOLDEN_ROOT,
                    actualRoot,
                    "Prolly-tree root hash drifted: a throughput lever re-chunked the tree"
                            + " (chunker-throughput D-1). A moved boundary changes the root even when"
                            + " the logical content is identical.");
        }
    }
}
