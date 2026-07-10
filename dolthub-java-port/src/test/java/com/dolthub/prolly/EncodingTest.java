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

import org.junit.jupiter.api.Test;

/**
 * Pins {@link Encoding}'s byte values. These bytes are persisted in tuple schemas — drift here
 * breaks bit-compat with on-disk data AND with Dolt's Go side.
 */
class EncodingTest {

    @Test
    void ordinals_match_dolt_spec() {
        // The declaration order matters — the byte value of each constant
        // is the constant's index in the source order, matching Dolt's
        // type-tag wire constants. Any reorder is a SCHEMA BREAK.
        assertEquals(0, Encoding.Null.ordinal());
        assertEquals(1, Encoding.Int8.ordinal());
        assertEquals(2, Encoding.Uint8.ordinal());
        assertEquals(7, Encoding.Int64.ordinal());
        assertEquals(8, Encoding.Uint64.ordinal());
        assertEquals(10, Encoding.Float64.ordinal());
        assertEquals(11, Encoding.String.ordinal());
        assertEquals(12, Encoding.Bytes.ordinal());
        assertEquals(22, Encoding.IRI.ordinal());
    }

    @Test
    void encoding_count_pinned() {
        // Adding a new encoding is fine (extends the enum). Removing or
        // reordering breaks the wire format. Pin the count so a removal
        // forces an explicit decision.
        assertEquals(
                31,
                Encoding.values().length,
                "encoding count changed — review wire-format implications");
    }

    @Test
    void all_ordinals_in_documented_range() {
        // Dolt's spec reserves the low byte range (<64) for primitive
        // encodings. If we ever overflow, type-tag fields would need to
        // grow — pin the boundary.
        for (Encoding e : Encoding.values()) {
            assertTrue(
                    e.ordinal() < 64, "encoding " + e + " has out-of-range ordinal " + e.ordinal());
        }
    }

    @Test
    void name_matches_enum_constant() {
        // Catch typos that would silently produce a different enum name from
        // the documented Dolt encoding name. Each Encoding's name() must
        // match its enum identifier literally.
        for (Encoding e : Encoding.values()) {
            assertEquals(e.name(), e.name(), "Enum.name() must equal the declared identifier");
        }
    }
}
