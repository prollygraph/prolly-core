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

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins every {@link Encoding}'s {@code getValue()} byte. EncodingTest already pins enum ordinals;
 * this file pins the EXPLICIT byte tag passed to each constant's constructor. They happen to align
 * with ordinals today, but they're separate contracts:
 *
 * <ul>
 *   <li>Ordinal — JVM-internal, used for {@code values()} ordering and serialization in tests.
 *   <li>{@code getValue()} byte — the EXPLICIT wire-format byte stored in tuple schemas and chunks.
 *       This is what would have to change if Dolt's Go side ever renumbered a type.
 * </ul>
 *
 * <p>If anyone reorders the enum without renumbering, ordinals shift but byte values stay — and
 * this file catches the divergence.
 */
class EncodingByteValueTest {

    // ---- individual byte-value pins ----

    @Test
    void null_value_is_zero() {
        assertEquals((byte) 0, Encoding.Null.getValue());
    }

    @Test
    void integer_family_byte_values() {
        assertEquals((byte) 1, Encoding.Int8.getValue());
        assertEquals((byte) 2, Encoding.Uint8.getValue());
        assertEquals((byte) 3, Encoding.Int16.getValue());
        assertEquals((byte) 4, Encoding.Uint16.getValue());
        assertEquals((byte) 5, Encoding.Int32.getValue());
        assertEquals((byte) 6, Encoding.Uint32.getValue());
        assertEquals((byte) 7, Encoding.Int64.getValue());
        assertEquals((byte) 8, Encoding.Uint64.getValue());
    }

    @Test
    void float_family_byte_values() {
        assertEquals((byte) 9, Encoding.Float32.getValue());
        assertEquals((byte) 10, Encoding.Float64.getValue());
    }

    @Test
    void string_and_bytes_byte_values() {
        assertEquals((byte) 11, Encoding.String.getValue());
        assertEquals((byte) 12, Encoding.Bytes.getValue());
    }

    @Test
    void structured_byte_values() {
        assertEquals((byte) 13, Encoding.JSON.getValue());
        assertEquals((byte) 14, Encoding.Decimal.getValue());
        assertEquals((byte) 15, Encoding.Year.getValue());
        assertEquals((byte) 16, Encoding.Date.getValue());
        assertEquals((byte) 17, Encoding.Time.getValue());
        assertEquals((byte) 18, Encoding.Datetime.getValue());
        assertEquals((byte) 19, Encoding.Enum.getValue());
        assertEquals((byte) 20, Encoding.Set.getValue());
        assertEquals((byte) 21, Encoding.Geometry.getValue());
    }

    @Test
    void iri_byte_value() {
        assertEquals(
                (byte) 22,
                Encoding.IRI.getValue(),
                "IRI=22 — load-bearing for RDF-term tuple schemas upstream");
    }

    @Test
    void hash128_and_bit64_byte_values() {
        assertEquals((byte) 23, Encoding.Hash128.getValue());
        assertEquals((byte) 24, Encoding.Bit64.getValue());
    }

    @Test
    void addr_family_byte_values() {
        assertEquals((byte) 25, Encoding.BytesAddr.getValue());
        assertEquals((byte) 26, Encoding.CommitAddr.getValue());
        assertEquals((byte) 27, Encoding.StringAddr.getValue());
        assertEquals((byte) 28, Encoding.JSONAddr.getValue());
        assertEquals((byte) 29, Encoding.GeomAddr.getValue());
        assertEquals((byte) 30, Encoding.ExtendedAddr.getValue());
    }

    // ---- contract pins ----

    @Test
    void all_byte_values_are_unique() {
        // Two encodings sharing a byte value would silently corrupt every
        // tuple schema that used the second one. Pin uniqueness.
        Set<Byte> seen = new HashSet<>();
        for (Encoding e : Encoding.values()) {
            assertTrue(
                    seen.add(e.getValue()),
                    "duplicate byte value " + e.getValue() + " for encoding " + e);
        }
    }

    @Test
    void byte_value_matches_ordinal_today() {
        // Current invariant: byte value == ordinal. If this ever stops being
        // true, it's a deliberate decision; this test makes the decision visible.
        for (Encoding e : Encoding.values()) {
            assertEquals(
                    e.ordinal(),
                    e.getValue() & 0xFF,
                    "byte value/ordinal divergence at " + e + " — schema-format break");
        }
    }

    @Test
    void byte_values_are_non_negative() {
        // Tuple schemas serialize byte values; negative bytes would surprise
        // unsigned-comparison logic. Pin that all built-in types stay non-negative.
        for (Encoding e : Encoding.values()) {
            assertTrue(
                    e.getValue() >= 0,
                    "encoding " + e + " has negative byte value " + e.getValue());
        }
    }

    @Test
    void byte_values_fit_in_unsigned_7_bits() {
        // The spec implicitly reserves byte values < 128 for built-in types
        // (top bit would let an extended type-tag domain emerge later).
        for (Encoding e : Encoding.values()) {
            assertTrue(
                    (e.getValue() & 0x80) == 0,
                    "encoding "
                            + e
                            + " uses high bit (reserved): 0x"
                            + Integer.toHexString(e.getValue() & 0xFF));
        }
    }

    @Test
    void getValue_is_pure_function() {
        // No state mutation between calls.
        for (Encoding e : Encoding.values()) {
            assertEquals(e.getValue(), e.getValue());
        }
    }
}
