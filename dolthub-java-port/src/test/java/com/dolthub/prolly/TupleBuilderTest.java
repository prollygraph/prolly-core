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

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage for {@link TupleBuilder}. {@code TupleTest} exercises the builder only through
 * happy-path round-trips; this file pins the branches it skips: the uint16 size ceiling, null /
 * sparse field encoding, index overwrite, and the binary-parity fork in {@link
 * TupleBuilder#putInt64}.
 */
class TupleBuilderTest {

    // ---- uint16 size ceiling ----

    @Test
    void oversized_tuple_throws_with_max_size_in_message() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, new byte[70_000]); // 70_000 + 2 offsets + 2 footer > 65535
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, tb::build);
            assertTrue(
                    ex.getMessage().contains("65535"),
                    "the overflow message must state the uint16 ceiling: " + ex.getMessage());
        }
    }

    @Test
    void tuple_at_the_size_ceiling_still_builds() {
        // dataSize + (1 offset * 2) + (footer 2) == 65535 exactly.
        try (HeapBufferPool pool = new HeapBufferPool()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, new byte[65_531]);
            Tuple t = assertDoesNotThrow(tb::build);
            assertEquals(1, t.count());
            assertEquals(65_531, t.getFieldSegment(0).byteSize());
        }
    }

    // ---- null / sparse field encoding ----

    @Test
    void explicit_null_byte_array_is_null_encoded() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, (byte[]) null);
            Tuple t = tb.build();
            assertEquals(1, t.count(), "a null field still occupies a slot");
            assertNull(
                    t.getFieldSegment(0),
                    "a null field must round-trip as a zero-length / null-encoded slot");
        }
    }

    @Test
    void sparse_putField_fills_intervening_indices_with_null() {
        // putField only at index 2 — indices 0 and 1 must auto-fill as null.
        try (HeapBufferPool pool = new HeapBufferPool()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(2, "third".getBytes());
            Tuple t = tb.build();
            assertEquals(3, t.count(), "putField(2) must grow the tuple to 3 fields");
            assertNull(t.getFieldSegment(0), "gap index 0 auto-fills as null");
            assertNull(t.getFieldSegment(1), "gap index 1 auto-fills as null");
            assertArrayEquals("third".getBytes(), t.getField(2));
        }
    }

    @Test
    void putField_overwrites_an_already_set_index() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, "first".getBytes());
            tb.putField(0, "second".getBytes());
            Tuple t = tb.build();
            assertEquals(1, t.count(), "overwriting an index must not add a field");
            assertArrayEquals(
                    "second".getBytes(), t.getField(0), "the last putField at an index wins");
        }
    }

    @Test
    void zero_fields_builds_empty_tuple_with_count_zero() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple t = new TupleBuilder(pool).build();
            assertEquals(0, t.count());
        }
    }

    // ---- putInt64 binary-parity fork ----

    @Test
    void putInt64_without_parity_descriptor_uses_little_endian() {
        // No descriptor → plain little-endian. LE does NOT sort numerically:
        // -1 (0xFF..FF) byte-compares ABOVE +1 (0x01 00..00).
        try (HeapBufferPool pool = new HeapBufferPool()) {
            TupleBuilder neg = new TupleBuilder(pool);
            neg.putInt64(0, -1L);
            TupleBuilder pos = new TupleBuilder(pool);
            pos.putInt64(0, 1L);
            assertTrue(
                    ByteUtils.compareUnsigned(neg.build().getField(0), pos.build().getField(0)) > 0,
                    "plain little-endian int64 does not preserve numeric order — "
                            + "this is exactly why binary-parity mode exists");
        }
    }

    @Test
    void putInt64_with_parity_descriptor_preserves_numeric_byte_order() {
        // Binary-parity descriptor → TypeCodec.encodeInt64 lex-flips the bytes
        // so unsigned byte comparison matches numeric order.
        TupleDescriptor parity =
                new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);
        try (HeapBufferPool pool = new HeapBufferPool()) {
            byte[] negF = field(pool, parity, -1L);
            byte[] zeroF = field(pool, parity, 0L);
            byte[] posF = field(pool, parity, 1L);
            byte[] bigF = field(pool, parity, Long.MAX_VALUE);

            assertTrue(ByteUtils.compareUnsigned(negF, zeroF) < 0, "-1 < 0");
            assertTrue(ByteUtils.compareUnsigned(zeroF, posF) < 0, "0 < 1");
            assertTrue(ByteUtils.compareUnsigned(posF, bigF) < 0, "1 < MAX");
        }
    }

    @Test
    void putInt64_parity_and_non_parity_produce_different_bytes() {
        TupleDescriptor parity =
                new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);
        try (HeapBufferPool pool = new HeapBufferPool()) {
            TupleBuilder plain = new TupleBuilder(pool); // descriptor == null
            plain.putInt64(0, 42L);
            assertFalse(
                    java.util.Arrays.equals(plain.build().getField(0), field(pool, parity, 42L)),
                    "parity and non-parity encodings of the same value must differ");
        }
    }

    private static byte[] field(HeapBufferPool pool, TupleDescriptor desc, long v) {
        TupleBuilder tb = new TupleBuilder(pool, desc);
        tb.putInt64(0, v);
        return tb.build().getField(0);
    }
}
