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

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link Tuple}, {@link TupleBuilder}, and {@link TupleDescriptor}. These
 * three drive every key encoding in the Prolly tree — tuple format drift breaks every downstream
 * index.
 *
 * <p>Pre-existing tests ({@code TupleOrderingEdgeTest}, {@code TupleChaosTest}, {@code
 * BinaryParityTest}) cover ordering properties heavily; this file fills the unit-level gaps: build
 * → access round-trip, null encoding, boundary field counts, the descriptor's compare dispatch.
 */
class TupleTest {

    // ---- TupleBuilder + Tuple round-trip ----

    @Test
    void single_int64_field_roundtrip() {
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);
        BufferPool pool = new HeapBufferPool();
        TupleBuilder tb = new TupleBuilder(pool, desc);
        tb.putInt64(0, 42L);
        Tuple t = tb.build();
        assertEquals(1, t.count());
        assertEquals(42L, TypeCodec.decodeInt64(t.getFieldSegment(0)));
    }

    @Test
    void multi_field_int64_roundtrip() {
        TupleDescriptor desc =
                new TupleDescriptor(
                        List.of(
                                new Type(Encoding.Int64, false),
                                new Type(Encoding.Int64, false),
                                new Type(Encoding.Int64, false),
                                new Type(Encoding.Int64, false)),
                        true);
        BufferPool pool = new HeapBufferPool();
        TupleBuilder tb = new TupleBuilder(pool, desc);
        tb.putInt64(0, 1L);
        tb.putInt64(1, 2L);
        tb.putInt64(2, 3L);
        tb.putInt64(3, 4L);
        Tuple t = tb.build();
        assertEquals(4, t.count());
        assertEquals(1L, TypeCodec.decodeInt64(t.getFieldSegment(0)));
        assertEquals(2L, TypeCodec.decodeInt64(t.getFieldSegment(1)));
        assertEquals(3L, TypeCodec.decodeInt64(t.getFieldSegment(2)));
        assertEquals(4L, TypeCodec.decodeInt64(t.getFieldSegment(3)));
    }

    @Test
    void bytes_field_roundtrip() {
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Bytes, false)), true);
        BufferPool pool = new HeapBufferPool();
        TupleBuilder tb = new TupleBuilder(pool, desc);
        byte[] payload = "hello".getBytes();
        tb.putField(0, payload);
        Tuple t = tb.build();
        assertArrayEquals(payload, t.getField(0));
    }

    @Test
    void variable_length_fields_keep_their_lengths() {
        TupleDescriptor desc =
                new TupleDescriptor(
                        List.of(
                                new Type(Encoding.Bytes, false),
                                new Type(Encoding.Bytes, false),
                                new Type(Encoding.Bytes, false)),
                        true);
        BufferPool pool = new HeapBufferPool();
        TupleBuilder tb = new TupleBuilder(pool, desc);
        tb.putField(0, new byte[] {0x01});
        tb.putField(1, new byte[] {0x02, 0x03, 0x04});
        tb.putField(2, new byte[] {0x05, 0x06});
        Tuple t = tb.build();
        assertArrayEquals(new byte[] {0x01}, t.getField(0));
        assertArrayEquals(new byte[] {0x02, 0x03, 0x04}, t.getField(1));
        assertArrayEquals(new byte[] {0x05, 0x06}, t.getField(2));
    }

    @Test
    void getFieldSegment_out_of_range_returns_null() {
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);
        BufferPool pool = new HeapBufferPool();
        TupleBuilder tb = new TupleBuilder(pool, desc);
        tb.putInt64(0, 42L);
        Tuple t = tb.build();
        assertNull(t.getFieldSegment(1));
        assertNull(t.getFieldSegment(100));
    }

    @Test
    void large_field_roundtrips() {
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Bytes, false)), true);
        BufferPool pool = new HeapBufferPool();
        TupleBuilder tb = new TupleBuilder(pool, desc);
        byte[] big = new byte[8192];
        new Random(7).nextBytes(big);
        tb.putField(0, big);
        Tuple t = tb.build();
        assertArrayEquals(big, t.getField(0));
    }

    @Test
    void empty_segment_count_zero() {
        Tuple t = new Tuple(MemorySegment.ofArray(new byte[0]));
        assertEquals(0, t.count());
    }

    @Test
    void memory_segment_overload_of_putField_matches_byte_array() {
        TupleDescriptor desc =
                new TupleDescriptor(
                        List.of(new Type(Encoding.Bytes, false), new Type(Encoding.Bytes, false)),
                        true);
        byte[] payload = "via segment".getBytes();
        BufferPool pool = new HeapBufferPool();
        TupleBuilder a = new TupleBuilder(pool, desc);
        a.putField(0, payload);
        a.putField(1, payload);
        TupleBuilder b = new TupleBuilder(pool, desc);
        b.putField(0, MemorySegment.ofArray(payload));
        b.putField(1, MemorySegment.ofArray(payload));
        Tuple ta = a.build();
        Tuple tb2 = b.build();
        assertArrayEquals(ta.getField(0), tb2.getField(0));
        assertArrayEquals(ta.getField(1), tb2.getField(1));
    }

    // ---- TupleDescriptor.compare ----

    @Test
    void descriptor_compare_int64_natural_order_off_parity() {
        // Without binary-parity: TypeCodec.compare uses readInt64 (little-endian).
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), false);
        Tuple t1 = intTuple(-100L, desc);
        Tuple t2 = intTuple(100L, desc);
        assertTrue(desc.compare(t1, t2) < 0);
        assertTrue(desc.compare(t2, t1) > 0);
        assertEquals(0, desc.compare(t1, t1));
    }

    @Test
    void descriptor_compare_int64_binary_parity_byte_order() {
        // With binary-parity: byte-lex on the encoded payload. -100 should
        // still encode lower than 100 thanks to TypeCodec.encodeInt64's flip.
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);
        Tuple t1 = intTuple(-100L, desc);
        Tuple t2 = intTuple(100L, desc);
        assertTrue(desc.compare(t1, t2) < 0);
    }

    @Test
    void descriptor_compare_shorter_lt_longer_when_prefix_matches() {
        TupleDescriptor desc =
                new TupleDescriptor(
                        List.of(new Type(Encoding.Int64, false), new Type(Encoding.Int64, false)),
                        true);
        Tuple shorter =
                intTuple(42L, new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true));
        Tuple longer = intTupleN(desc, 42L, 0L);
        assertTrue(desc.compare(shorter, longer) < 0);
    }

    @Test
    void descriptor_compare_total_order_property() {
        // 500 random Int64 tuples — encoded comparison must match natural Long.compare.
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);
        Random rng = new Random(11);
        long[] values = new long[500];
        Tuple[] tuples = new Tuple[500];
        for (int i = 0; i < values.length; i++) {
            values[i] = rng.nextLong();
            tuples[i] = intTuple(values[i], desc);
        }
        for (int i = 0; i < 50; i++) {
            for (int j = 0; j < 50; j++) {
                int byCodec = desc.compare(tuples[i], tuples[j]);
                int byNatural = Long.compare(values[i], values[j]);
                assertEquals(
                        Integer.signum(byNatural),
                        Integer.signum(byCodec),
                        String.format("disagree for %d vs %d", values[i], values[j]));
            }
        }
    }

    @Test
    void descriptor_isBinaryParity_passes_through_ctor_flag() {
        assertTrue(new TupleDescriptor(List.of(), true).isBinaryParity());
        assertFalse(new TupleDescriptor(List.of(), false).isBinaryParity());
        // Single-arg ctor defaults to false.
        assertFalse(new TupleDescriptor(List.of()).isBinaryParity());
    }

    // ---- Tuple metadata invariants ----

    @Test
    void count_reads_last_two_bytes_little_endian() {
        // Hand-rolled 1-byte payload tuple: [0x42] [offset=1 LE u16] [count=1 LE u16]
        byte[] raw = new byte[] {0x42, 0x01, 0x00, 0x01, 0x00};
        Tuple t = new Tuple(MemorySegment.ofArray(raw));
        assertEquals(1, t.count());
        assertArrayEquals(new byte[] {0x42}, t.getField(0));
    }

    @Test
    void offsets_are_uint16_unsigned() {
        // Encode a tuple with first field of length > 127 to verify unsigned widening.
        TupleDescriptor desc =
                new TupleDescriptor(
                        List.of(new Type(Encoding.Bytes, false), new Type(Encoding.Bytes, false)),
                        true);
        BufferPool pool = new HeapBufferPool();
        TupleBuilder tb = new TupleBuilder(pool, desc);
        byte[] first = new byte[200]; // length > 127 stresses signed-vs-unsigned u16
        for (int i = 0; i < 200; i++) first[i] = (byte) i;
        tb.putField(0, first);
        tb.putField(1, new byte[] {0x77});
        Tuple t = tb.build();
        assertArrayEquals(first, t.getField(0));
        assertArrayEquals(new byte[] {0x77}, t.getField(1));
    }

    // ---- helpers ----

    private static Tuple intTuple(long v, TupleDescriptor desc) {
        BufferPool pool = new HeapBufferPool();
        TupleBuilder tb = new TupleBuilder(pool, desc);
        tb.putInt64(0, v);
        return tb.build();
    }

    private static Tuple intTupleN(TupleDescriptor desc, long... vs) {
        BufferPool pool = new HeapBufferPool();
        TupleBuilder tb = new TupleBuilder(pool, desc);
        for (int i = 0; i < vs.length; i++) tb.putInt64(i, vs[i]);
        return tb.build();
    }
}
