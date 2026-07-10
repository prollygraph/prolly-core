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
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-pins the content-defined splitter's node geometry on the <b>real</b> {@link
 * RollingHashSplitter} — Steps 3–4 (Phase 1) of {@code
 * plans/prepublic/splitter-productionization.md} (Step 3 = the general geometry bounds; Step 4 =
 * the oversized-item invariant, Goal 2, at the bottom). Generated key/value streams (small / empty
 * / large, mixed) drive {@link SplitterGeometry}'s replay of {@link TreeMutator.Chunker}'s emit
 * loop; the invariants below are the production bar: a pathological caller controls key/value
 * bytes, so node geometry must stay bounded + deterministic.
 *
 * <p>The asserted bound is <b>not</b> the plan's original "chunk ∈ [MIN, MAX] except a lone
 * oversized item" — Step 1/2 measurement showed that to be false: a boundary fires by {@code
 * RAMP_FORCE_OFFSET}, but the splitter cannot split mid-item, so the crossing item overshoots and a
 * <i>multi-item</i> chunk can exceed {@code MAX} (pinned by {@link
 * SplitterGeometryCharacterizationTest#multi_item_chunk_can_exceed_MAX}). The true, measured bound
 * — {@code bytes ≤ MAX + maxItemBytes}, and {@code ≥ MIN} only for boundary-emitted chunks — is
 * what is pinned here.
 *
 * @apiNote The internal-node infinite-recursion guard (Dolt {@code chunker.append} constraint 3,
 *     the load-bearing gap from Step 2) is <b>not</b> here — it needs the multi-level {@link
 *     TreeMutator}, not the bare splitter, and is Phase 2's {@code DegenerateInternalNodeGuardTest}
 *     (Step 5).
 */
class SplitterGeometryProperty {

    private static final int MIN = SplitterGeometry.MIN; // 512
    private static final int MAX = SplitterGeometry.MAX; // 16384

    /**
     * Mixed streams: items whose key and value are each {@code small} (0–80 B, incl. empty), or
     * occasionally {@code large} (1.5–3.5 KiB). {@code small} appears 3× in the {@code oneOf} to
     * weight streams toward the common case while still regularly producing the large + empty +
     * many adversarial shapes from Step 1.
     */
    @Provide
    Arbitrary<List<byte[][]>> itemStreams() {
        Arbitrary<byte[]> small =
                Arbitraries.bytes().array(byte[].class).ofMinSize(0).ofMaxSize(80);
        Arbitrary<byte[]> large =
                Arbitraries.bytes().array(byte[].class).ofMinSize(1500).ofMaxSize(3500);
        Arbitrary<byte[]> field = Arbitraries.oneOf(small, small, small, large);
        Arbitrary<byte[][]> item =
                Combinators.combine(field, field).as((k, v) -> new byte[][] {k, v});
        return item.list().ofMinSize(0).ofMaxSize(250);
    }

    /**
     * Streams guaranteed into the large regime (≥6 mid-sized values), so the {@code MAX +
     * maxItemBytes} upper bound is exercised against multi-item chunks that overshoot {@code MAX}
     * rather than passing vacuously on small streams.
     */
    @Provide
    Arbitrary<List<byte[][]>> largeItemStreams() {
        Arbitrary<byte[]> mid =
                Arbitraries.bytes().array(byte[].class).ofMinSize(2000).ofMaxSize(4000);
        Arbitrary<byte[][]> item =
                Combinators.combine(Arbitraries.just(new byte[0]), mid)
                        .as((k, v) -> new byte[][] {k, v});
        return item.list().ofMinSize(6).ofMaxSize(40);
    }

    @Property(tries = 300)
    void geometry_is_deterministic_bounded_and_conserving(
            @ForAll @From("itemStreams") List<byte[][]> items,
            @ForAll @IntRange(min = 0, max = 3) int level) {
        assertGeometryInvariants(items, level);
    }

    @Property(tries = 200)
    void large_regime_geometry_stays_bounded(
            @ForAll @From("largeItemStreams") List<byte[][]> items) {
        assertGeometryInvariants(items, 0);
    }

    // ---- Step 4: the oversized-item invariant (Goal 2) ----

    /**
     * Items whose key+value exceed {@code MAX} (value alone ≥ MAX+1) — the one legitimate >MAX
     * chunk.
     */
    @Provide
    Arbitrary<byte[][]> oversizedItems() {
        Arbitrary<byte[]> key = Arbitraries.bytes().array(byte[].class).ofMinSize(0).ofMaxSize(64);
        Arbitrary<byte[]> bigValue =
                Arbitraries.bytes().array(byte[].class).ofMinSize(MAX + 1).ofMaxSize(MAX + 8192);
        return Combinators.combine(key, bigValue).as((k, v) -> new byte[][] {k, v});
    }

    /**
     * An item larger than {@code MAX} always signals a boundary (so the Chunker gives it its own
     * chunk rather than accumulating unboundedly), and the splitter's {@code offset} equals the
     * item's exact byte size — no integer overflow or miscount on a large item.
     */
    @Property(tries = 100)
    void oversized_item_signals_a_boundary_with_exact_offset(
            @ForAll @From("oversizedItems") byte[][] oversized) {
        RollingHashSplitter s = new RollingHashSplitter(0);
        s.append(MemorySegment.ofArray(oversized[0]), MemorySegment.ofArray(oversized[1]));
        assertTrue(s.crossedBoundary(), "an item > MAX must signal a boundary");
        assertEquals(
                oversized[0].length + oversized[1].length,
                s.offset(),
                "offset must equal the oversized item's exact byte size");
    }

    /**
     * Goal 2: an oversized item that <i>starts</i> a chunk forms a chunk of <b>exactly</b> that one
     * item (the splitter cannot split mid-item), and the reset afterwards isolates it — the tail
     * chunks <b>identically</b> whether or not the oversized item preceded it ("the next chunk
     * starts clean"). The metamorphic equality pins both the lone-item chunk and the clean reset at
     * once.
     */
    @Property(tries = 200)
    void oversized_item_is_isolated_and_resets_clean(
            @ForAll @From("oversizedItems") byte[][] oversized,
            @ForAll @From("itemStreams") List<byte[][]> tail) {
        List<byte[][]> combined = new ArrayList<>();
        combined.add(oversized);
        combined.addAll(tail);

        List<Chunk> all = SplitterGeometry.emit(combined);
        List<Chunk> tailOnly = SplitterGeometry.emit(tail);

        int oversizedBytes = oversized[0].length + oversized[1].length;
        assertEquals(
                new Chunk(oversizedBytes, 1, true),
                all.get(0),
                "an oversized item starting a chunk must be a lone, boundary-closed chunk");
        assertEquals(
                tailOnly,
                all.subList(1, all.size()),
                "reset after an oversized chunk must isolate subsequent chunking");
    }

    /**
     * The defined behavior for a pathologically large single item: it is one whole chunk, not torn
     * and not unbounded-by-recursion — a 1&nbsp;MiB value yields exactly one chunk of 1&nbsp;MiB,
     * and the {@code int} offset does not overflow.
     */
    @Example
    void single_megabyte_item_is_one_whole_chunk() {
        int oneMiB = 1 << 20;
        // A one-element List<byte[][]> — built explicitly because List.of(new byte[][]{..}) would
        // collapse via varargs into a two-element List<byte[]>.
        List<byte[][]> items = new ArrayList<>();
        items.add(new byte[][] {new byte[0], new byte[oneMiB]});
        List<Chunk> chunks = SplitterGeometry.emit(items);
        assertEquals(1, chunks.size(), "a single huge item is exactly one chunk");
        assertEquals(new Chunk(oneMiB, 1, true), chunks.get(0));
    }

    /**
     * Characterizes the port's Goal-2 caveat and the Step-5 input: unlike Dolt's {@code
     * hasCapacity} pre-append flush (constraint 2, from Step 2), the port has <b>no</b> pre-flush,
     * so a small item immediately preceding an oversized one <b>rides along</b> in the same chunk
     * rather than being flushed first — so "a chunk of exactly that item" holds only when the
     * oversized item <i>starts</i> its chunk.
     */
    @Example
    void port_does_not_pre_flush_a_small_item_before_an_oversized_one() {
        byte[][] tiny = {new byte[] {1}, new byte[] {2}}; // 2 bytes
        byte[][] huge = {new byte[0], new byte[20_000]}; // 20000 bytes
        List<Chunk> chunks = SplitterGeometry.emit(List.of(tiny, huge));
        assertEquals(
                1, chunks.size(), "tiny + oversized share one chunk — the port has no pre-flush");
        assertEquals(new Chunk(20_002, 2, true), chunks.get(0));
    }

    /** The three production-bar geometry invariants, shared by both properties. */
    private static void assertGeometryInvariants(List<byte[][]> items, int level) {
        List<Chunk> first = SplitterGeometry.emit(level, items);
        List<Chunk> second = SplitterGeometry.emit(level, items);

        // (a) Deterministic: same stream + level → identical boundaries (metamorphic).
        assertEquals(first, second, "splitter must be deterministic for given content + level");

        // (b) Byte conservation: every appended byte lands in exactly one chunk.
        long sumChunkBytes = first.stream().mapToLong(Chunk::bytes).sum();
        assertEquals(
                SplitterGeometry.totalBytes(items),
                sumChunkBytes,
                "chunk bytes must conserve total item bytes");

        int maxItem = SplitterGeometry.maxItemBytes(items);
        for (int i = 0; i < first.size(); i++) {
            Chunk chunk = first.get(i);
            // (c) Lower bound: a boundary-emitted chunk is never declared below MIN (the trailing
            // done()-flush is exempt — it is closed by stream end, not by a boundary).
            if (chunk.byBoundary()) {
                assertTrue(
                        chunk.bytes() >= MIN,
                        "boundary chunk "
                                + i
                                + " below MIN ("
                                + MIN
                                + "): "
                                + chunk
                                + " level="
                                + level);
            }
            // (d) Upper bound: a chunk overshoots a cap by at most one (the crossing) item, since
            // the
            // splitter cannot split mid-item. This is the real bound — NOT [MIN, MAX].
            assertTrue(
                    chunk.bytes() <= MAX + maxItem,
                    "chunk "
                            + i
                            + " exceeds MAX+maxItemBytes ("
                            + (MAX + maxItem)
                            + ") — unbounded geometry: "
                            + chunk
                            + " level="
                            + level);
        }
    }
}
