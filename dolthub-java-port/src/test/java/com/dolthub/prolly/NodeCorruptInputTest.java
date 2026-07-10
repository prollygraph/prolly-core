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
import org.junit.jupiter.api.Test;

/**
 * Characterizes {@link Node#fromBytes} on malformed / truncated chunk bytes. A content-addressed
 * store can hand back a corrupt chunk (bit rot, a partial write, a hostile blob) — SQLite-grade
 * behaviour is to fail loudly and predictably, never to silently return a Node that yields wrong
 * data.
 *
 * <p>These tests pin the <em>observed</em> failure mode of each corrupt input so a future change to
 * the parse path can't quietly turn a hard failure into silent corruption.
 */
class NodeCorruptInputTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    /** A real, valid TUPM-tagged leaf chunk built end-to-end. */
    private static byte[] validChunk() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, "k1".getBytes());
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node root =
                    m.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    tb.build().segment(),
                                                    MemorySegment.ofArray("v1".getBytes())))
                                    .iterator());
            return root.bytes();
        }
    }

    // ---- null / empty ----

    @Test
    void null_segment_returns_null() {
        assertNull(Node.fromBytes(null), "fromBytes(null) is the documented empty-tree sentinel");
    }

    @Test
    void empty_segment_fails_loudly() {
        // 0 bytes → too short to carry the PNOD header or a TUPM identifier → fails closed
        // (ADR-0072) before any field is read.
        assertThrows(
                RuntimeException.class,
                () -> Node.fromBytes(MemorySegment.ofArray(new byte[0])),
                "an empty chunk must throw, not yield a usable Node");
    }

    @Test
    void one_byte_segment_fails_loudly() {
        // 1 byte → too short for the PNOD header / TUPM identifier → fails closed (ADR-0072).
        assertThrows(
                RuntimeException.class,
                () -> Node.fromBytes(MemorySegment.ofArray(new byte[] {0})),
                "a 1-byte chunk cannot carry the format header → must throw");
    }

    // ---- non-PNOD / non-TUPM blobs ----
    // ADR-0072: Node.fromBytes verifies the [PNOD magic][version] header (or Dolt-framed TUPM)
    // BEFORE any field read; an unrecognized blob fails closed with UnsupportedFormatException.
    // (The direct edge-case characterizations of the retired test-only TLV decoder were deleted
    // with the class — plan subtree-count-contract D-3: hardening a parser production never runs
    // proves nothing about production; this production boundary is fuzz-covered by
    // NodeDeserializerFuzzTest.)

    @Test
    void fromBytes_fails_closed_on_a_non_versioned_blob() {
        // A blob with neither the PNOD header nor a TUPM identifier is rejected, never misread
        // (ADR-0072 D-4).
        assertThrows(
                UnsupportedFormatException.class,
                () -> Node.fromBytes(MemorySegment.ofArray(new byte[] {0, 0, 0, 0, 0})),
                "a non-PNOD/non-TUPM blob must fail closed");
    }

    // ---- TUPM (flatbuffer) path ----

    @Test
    void valid_chunk_is_the_baseline_and_parses_cleanly() {
        Node n = Node.fromBytes(MemorySegment.ofArray(validChunk()));
        assertNotNull(n);
        assertEquals(1, n.count(), "the baseline valid chunk holds exactly one entry");
        // getKey returns the full tuple segment; field 0 is the "k1" key.
        Tuple key = new Tuple(MemorySegment.ofArray(n.getKey(0)));
        assertArrayEquals("k1".getBytes(), key.getField(0));
    }

    @Test
    void truncated_flatbuffer_chunk_fails_loudly() {
        byte[] valid = validChunk();
        assertTrue(valid.length > 16, "precondition: chunk large enough to truncate");
        // Keep the TUPM identifier (bytes 4..7) so it still routes to the
        // flatbuffer parser, but cut the body in half.
        byte[] truncated = new byte[valid.length / 2];
        System.arraycopy(valid, 0, truncated, 0, truncated.length);

        assertThrows(
                RuntimeException.class,
                () -> {
                    Node n = Node.fromBytes(MemorySegment.ofArray(truncated));
                    // Force field access in case the failure is lazy.
                    for (int i = 0; i < Math.max(1, n.count()); i++) n.getKey(i);
                },
                "a truncated TUPM chunk must fail loudly, not yield silent garbage");
    }

    @Test
    void corrupt_root_offset_in_flatbuffer_fails_loudly() {
        byte[] valid = validChunk();
        byte[] corrupt = valid.clone();
        // Bytes 0..3 are the root-table offset. Point it far past the buffer.
        corrupt[0] = (byte) 0xff;
        corrupt[1] = (byte) 0xff;
        corrupt[2] = (byte) 0xff;
        corrupt[3] = (byte) 0x7f;

        assertThrows(
                RuntimeException.class,
                () -> {
                    Node n = Node.fromBytes(MemorySegment.ofArray(corrupt));
                    for (int i = 0; i < Math.max(1, n.count()); i++) n.getKey(i);
                },
                "a bogus flatbuffer root offset must fail loudly");
    }
}
