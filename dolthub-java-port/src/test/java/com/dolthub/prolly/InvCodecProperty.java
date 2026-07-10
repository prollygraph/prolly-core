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

import java.lang.foreign.MemorySegment;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * I-5 (codec fidelity + order-preservation) for {@link TypeCodec}'s lexically-encoded types — Int64
 * (XOR sign bit, big-endian) and Float64 (IEEE-754 lex-flip) — as properties
 * (plans/core-engine-test-strategy.md Step 8). Two invariants per type:
 *
 * <ul>
 *   <li><b>Round-trip</b>: {@code decode(encode(v)) == v} for ALL values, including the boundary
 *       cases that historically broke this codec (±0.0, ±INF, NaN, Long.MIN/MAX, subnormals — jqwik
 *       edge cases).
 *   <li><b>Order-preservation (binary parity)</b>: the ENCODED bytes compare (unsigned) in the same
 *       order as the values compare semantically — the property that makes the prolly tree sort
 *       correctly. {@code sgn(compareUnsigned(enc a, enc b)) == sgn(cmp(a,b))}.
 * </ul>
 *
 * <p>jqwik's automatic edge cases for {@code long}/{@code double} cover the historical boundary
 * bugs; a failure shrinks to the minimal (a, b) pair.
 */
class InvCodecProperty {

    private static MemorySegment enc8(java.util.function.Consumer<MemorySegment> writer) {
        MemorySegment s = MemorySegment.ofArray(new byte[8]);
        writer.accept(s);
        return s;
    }

    // ---- Int64 ----

    @Property(tries = 1000)
    void int64RoundTrips(@ForAll long v) {
        assertEquals(v, TypeCodec.decodeInt64(enc8(s -> TypeCodec.encodeInt64(v, s))));
    }

    @Property(tries = 1000)
    void int64EncodingPreservesOrder(@ForAll long a, @ForAll long b) {
        int enc =
                ByteUtils.compareUnsigned(
                        enc8(s -> TypeCodec.encodeInt64(a, s)),
                        enc8(s -> TypeCodec.encodeInt64(b, s)));
        assertEquals(
                Integer.signum(Long.compare(a, b)),
                Integer.signum(enc),
                "encoded byte order must match signed Long order for (" + a + ", " + b + ")");
    }

    // ---- Float64 ----

    @Property(tries = 1000)
    void float64RoundTrips(@ForAll double v) {
        double back = TypeCodec.decodeFloat64(enc8(s -> TypeCodec.encodeFloat64(v, s)));
        // Double.compare gives a total order: NaN==NaN (0), -0.0<+0.0 — so it
        // is the right equality oracle for round-trip (bit-exact incl. -0.0).
        assertEquals(
                0,
                Double.compare(v, back),
                "float64 round-trip changed the value: " + v + " -> " + back);
    }

    @Property(tries = 1000)
    void float64EncodingPreservesOrder(@ForAll double a, @ForAll double b) {
        int enc =
                ByteUtils.compareUnsigned(
                        enc8(s -> TypeCodec.encodeFloat64(a, s)),
                        enc8(s -> TypeCodec.encodeFloat64(b, s)));
        // The lex-flip is designed to match the IEEE-754 total order that
        // Double.compare implements (NaN largest, -0.0 < +0.0).
        assertEquals(
                Integer.signum(Double.compare(a, b)),
                Integer.signum(enc),
                "encoded byte order must match Double total order for (" + a + ", " + b + ")");
    }
}
