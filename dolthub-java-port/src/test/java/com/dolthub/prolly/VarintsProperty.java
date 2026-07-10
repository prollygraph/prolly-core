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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * I-5 codec fidelity for {@link Varints} as properties (plans/core-engine-test-strategy.md Step
 * 12). Complements the example-based {@link VarintsTest} with full-range property-based testing.
 *
 * <p>Varint encoding is on the critical path for every chunk serialization and is a Dolt bit-compat
 * surface, so the invariants must hold for <em>all</em> 64-bit values, not just hand-picked
 * boundaries:
 *
 * <ul>
 *   <li><b>Round-trip</b> over the full unsigned 64-bit range.
 *   <li><b>Encoded length</b> equals {@code ceil(unsignedBitWidth / 7)}, capped at 10 — pins the
 *       2⁷/2¹⁴/2²¹… multi-byte boundaries as a law.
 *   <li><b>{@code getUvarintAt} is the running prefix sum</b>, and reading it does not advance the
 *       caller's position. This is the contract the internal-node subtree-count code relies on —
 *       and it pins the <em>cumulative vs individual</em> distinction that is the {@code
 *       getSubtreeCount} trap noted in project memory (the flatbuffer node returns the prefix sum;
 *       a per-entry reader returns the individual count — confusing them double-counts).
 * </ul>
 */
class VarintsProperty {

    // ---- full-range round-trip ----

    @Property(tries = 5000)
    void roundTripOverFullUnsignedRange(@ForAll long v) {
        ByteBuffer bb = ByteBuffer.allocate(10);
        Varints.putUvarint(bb, v);
        bb.flip();
        assertEquals(
                v,
                Varints.getUvarint(bb),
                "putUvarint→getUvarint must be identity for every 64-bit pattern (unsigned)");
    }

    // ---- encoded length law: ceil(unsigned bit width / 7), capped at 10 ----

    @Property(tries = 5000)
    void encodedLengthMatchesUnsignedBitWidth(@ForAll long v) {
        ByteBuffer bb = ByteBuffer.allocate(10);
        Varints.putUvarint(bb, v);
        int actualLen = bb.position();

        int bits = (v == 0) ? 1 : (64 - Long.numberOfLeadingZeros(v)); // unsigned bit width
        int expectedLen = (bits + 6) / 7; // ceil; max is (64+6)/7 = 10
        assertEquals(
                expectedLen,
                actualLen,
                "varint length must be ceil(unsignedBitWidth/7) for v=" + Long.toUnsignedString(v));
    }

    // ---- fail-closed: an overlong varint is rejected, never looped unbounded (D-2) ----

    @Property(tries = 2000)
    void overlongVarintFailsClosed(
            @ForAll @IntRange(min = 10, max = 25) int len, @ForAll byte payload) {
        // `len` (>= 10) bytes that ALL carry the continuation bit — so no varint terminates within
        // the 10-byte limit. getUvarint must fail closed (a clear exception), never loop unbounded
        // or shift `s` past 63. The payload bits vary; only the continuation bit drives the bound.
        byte[] buf = new byte[len];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (byte) (payload | 0x80); // high bit set → "more bytes follow"
        }
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Varints.getUvarint(ByteBuffer.wrap(buf)));
        assertTrue(ex.getMessage().contains("varint exceeds 10 bytes"), ex.getMessage());
    }

    // ---- list round-trip ----

    @Property(tries = 2000)
    void listRoundTrips(@ForAll("longLists") List<Long> xs) {
        byte[] encoded = Varints.encodeVarints(xs);
        assertEquals(
                xs,
                Varints.decodeVarints(encoded, xs.size()),
                "encodeVarints→decodeVarints must recover the list exactly");
    }

    // ---- getUvarintAt is the running prefix sum (the subtree-count contract) ----

    @Property(tries = 2000)
    void getUvarintAtIsRunningPrefixSum(@ForAll("longLists") List<Long> xs) {
        byte[] encoded = Varints.encodeVarints(xs);
        ByteBuffer bb = ByteBuffer.wrap(encoded);
        int posBefore = bb.position();

        // Oracle: running sum. Both sides use long addition, so any overflow
        // wraps identically — the equality holds across the full range.
        long running = 0;
        for (int i = 0; i < xs.size(); i++) {
            running += xs.get(i);
            assertEquals(
                    running,
                    Varints.getUvarintAt(bb, i),
                    "getUvarintAt(i) must equal the sum of the first i+1 entries");
        }
        assertEquals(
                posBefore, bb.position(), "getUvarintAt must not advance the caller's position");
    }

    /**
     * The {@code getSubtreeCount} trap, pinned as a property: the <em>individual</em> count at
     * index i is the prefix-sum delta — {@code at(i) - at(i-1)} — NOT {@code at(i)} itself. Code
     * that reads the cumulative value as if it were the per-entry count silently double-counts
     * every entry but the first.
     */
    @Property(tries = 2000)
    void prefixSumDeltaRecoversIndividualCounts(@ForAll("longLists") List<Long> xs) {
        byte[] encoded = Varints.encodeVarints(xs);
        ByteBuffer bb = ByteBuffer.wrap(encoded);
        long prev = 0;
        for (int i = 0; i < xs.size(); i++) {
            long cumulative = Varints.getUvarintAt(bb, i);
            assertEquals(
                    xs.get(i).longValue(),
                    cumulative - prev,
                    "individual entry i = cumulative(i) - cumulative(i-1)");
            prev = cumulative;
        }
    }

    @Example
    void getUvarintAtLastEqualsTotal() {
        List<Long> xs = List.of(3L, 0L, 7L, 1L, 100L);
        ByteBuffer bb = ByteBuffer.wrap(Varints.encodeVarints(xs));
        long total = xs.stream().mapToLong(Long::longValue).sum();
        assertEquals(
                total,
                Varints.getUvarintAt(bb, xs.size() - 1),
                "the last prefix sum is the grand total (a leaf-count rollup at an internal node)");
    }

    // ---- generators ----

    /** Non-empty lists of arbitrary 64-bit values (edge cases mixed in by jqwik). */
    @Provide
    Arbitrary<List<Long>> longLists() {
        return Arbitraries.longs().list().ofMinSize(1).ofMaxSize(40);
    }
}
