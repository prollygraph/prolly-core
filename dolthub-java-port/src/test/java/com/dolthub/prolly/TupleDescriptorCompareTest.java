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
 * Branch coverage for {@link TupleDescriptor#compare} — specifically {@code compareFields}: the
 * null-field arms and the {@code index >= types.size()} fallthrough that {@code TupleTest} does not
 * exercise.
 *
 * <p>A field is NULL-encoded when its start and end offsets coincide ({@link Tuple#getFieldSegment}
 * returns {@code null} for a zero-length field), so an empty {@code putField} is the way to reach
 * the null-comparison branches through the public API.
 */
class TupleDescriptorCompareTest {

    private static final TupleDescriptor ONE_STRING =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static Tuple t(HeapBufferPool pool, byte[]... fields) {
        TupleBuilder tb = new TupleBuilder(pool);
        for (int i = 0; i < fields.length; i++) tb.putField(i, fields[i]);
        return tb.build();
    }

    private static byte[] b(String s) {
        return s.getBytes();
    }

    private static final byte[] EMPTY = new byte[0];

    // ---- public schema accessors (mutation audit: NO_COVERAGE) ----

    @Test
    void schema_accessors_report_arity_and_column_types() {
        // size()/typeAt() are the public schema accessors. compare() reaches the
        // column types through the private `types` field, never these methods, so
        // the Phase-2 mutation audit flagged both uncovered — pin them directly.
        assertEquals(1, ONE_STRING.size());
        assertEquals(Encoding.String, ONE_STRING.typeAt(0).encoding());

        TupleDescriptor two =
                new TupleDescriptor(
                        List.of(
                                new Type(Encoding.String, false),
                                new Type(Encoding.String, false)));
        assertEquals(2, two.size());
        assertEquals(Encoding.String, two.typeAt(0).encoding());
        assertEquals(Encoding.String, two.typeAt(1).encoding());
    }

    // ---- null-field arms of compareFields ----

    @Test
    void empty_field_is_null_encoded_and_sorts_before_a_value() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple nullField = t(pool, EMPTY);
            Tuple realField = t(pool, b("x"));
            assertNull(nullField.getFieldSegment(0), "zero-length field must be null-encoded");
            assertTrue(
                    ONE_STRING.compare(nullField, realField) < 0,
                    "a null field must sort before any present value");
            assertTrue(
                    ONE_STRING.compare(realField, nullField) > 0,
                    "and the reverse comparison must be the mirror");
        }
    }

    @Test
    void two_null_fields_compare_equal() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple a = t(pool, EMPTY);
            Tuple b = t(pool, EMPTY);
            assertEquals(
                    0,
                    ONE_STRING.compare(a, b),
                    "two null-encoded fields at the same index compare equal");
        }
    }

    @Test
    void null_field_then_value_decides_before_later_fields() {
        // field0 null vs field0 present — must decide at index 0, never
        // looking at field1.
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple a = t(pool, EMPTY, b("zzz"));
            Tuple b = t(pool, b("a"), b("aaa"));
            assertTrue(
                    ONE_STRING.compare(a, b) < 0,
                    "null field0 sorts first regardless of field1 contents");
        }
    }

    // ---- index >= types.size() fallthrough ----

    @Test
    void field_beyond_descriptor_arity_uses_unsigned_byte_compare() {
        // Descriptor declares ONE type; the tuples carry TWO fields.
        // field0 is equal, so the result is decided at field1 (index 1),
        // which has no declared Type → ByteUtils.compareUnsigned.
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple a = t(pool, b("same"), b("apple"));
            Tuple b = t(pool, b("same"), b("banana"));
            assertTrue(
                    ONE_STRING.compare(a, b) < 0,
                    "undeclared trailing field must fall through to byte compare");
            assertTrue(ONE_STRING.compare(b, a) > 0);
        }
    }

    @Test
    void field_beyond_descriptor_arity_unsigned_high_byte() {
        // 0x80 must compare ABOVE 0x01 — the fallthrough path must treat
        // the trailing field's bytes as unsigned, not signed.
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple lo = t(pool, b("k"), new byte[] {0x01});
            Tuple hi = t(pool, b("k"), new byte[] {(byte) 0x80});
            assertTrue(
                    ONE_STRING.compare(lo, hi) < 0,
                    "0x80 > 0x01 — trailing field compared as unsigned");
        }
    }

    @Test
    void all_declared_and_undeclared_fields_equal_resolves_to_zero() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple a = t(pool, b("x"), b("y"));
            Tuple b = t(pool, b("x"), b("y"));
            assertEquals(0, ONE_STRING.compare(a, b), "identical multi-field tuples compare equal");
        }
    }

    // ---- arity / count tie-breaker ----

    @Test
    void shorter_tuple_sorts_first_when_shared_prefix_equal() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple shortT = t(pool, b("k"));
            Tuple longT = t(pool, b("k"), b("extra"));
            assertTrue(
                    ONE_STRING.compare(shortT, longT) < 0,
                    "fewer fields sorts before more when the shared prefix is equal");
            assertTrue(ONE_STRING.compare(longT, shortT) > 0);
        }
    }

    @Test
    void empty_tuple_sorts_before_any_nonempty_tuple() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple empty = t(pool);
            Tuple nonEmpty = t(pool, b("k"));
            assertEquals(0, empty.count(), "no putField calls → count 0");
            assertTrue(
                    ONE_STRING.compare(empty, nonEmpty) < 0,
                    "a zero-field tuple sorts before any populated tuple");
        }
    }

    // ---- ordering properties ----

    @Test
    void compare_is_antisymmetric() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple a = t(pool, b("alpha"));
            Tuple b = t(pool, b("beta"));
            assertEquals(
                    -Integer.signum(ONE_STRING.compare(a, b)),
                    Integer.signum(ONE_STRING.compare(b, a)),
                    "compare(a,b) and compare(b,a) must have opposite signs");
        }
    }

    @Test
    void compare_is_reflexive() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple a = t(pool, b("self"), b("ref"));
            assertEquals(0, ONE_STRING.compare(a, a), "a tuple compared with itself is 0");
        }
    }
}
