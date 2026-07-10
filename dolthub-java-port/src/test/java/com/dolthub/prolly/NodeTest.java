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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link Node}. {@code fromBytes} is the boundary between durable chunks
 * and the in-memory tree — any drift in the Flatbuffer-vs-simple format dispatch, or in
 * level/count/key extraction, would silently break every tree read.
 *
 * <p>Trees built through {@link TreeMutator} so we exercise the actual Flatbuffer serializer path
 * that {@link FlatbufferNodeSerializer} produces.
 */
class NodeTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    @Test
    void fromBytes_null_segment_returns_null() {
        assertNull(Node.fromBytes(null));
    }

    @Test
    void leaf_node_basic_metadata() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, "k-" + i), MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node root = m.applyMutations(null, edits.iterator());

            assertEquals(0, root.level(), "small tree → single leaf");
            assertTrue(root.isLeaf());
            assertEquals(5, root.count());
            assertEquals(5L, root.treeCount(), "leaf treeCount equals item count");
        }
    }

    @Test
    void leaf_node_getKey_getValue_roundtrip() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, "alpha"), MemorySegment.ofArray("first".getBytes())));
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, "bravo"), MemorySegment.ofArray("second".getBytes())));
            Node root = m.applyMutations(null, edits.iterator());

            // Tuple-encoded key: extract field 0.
            byte[] k0 = new Tuple(MemorySegment.ofArray(root.getKey(0))).getField(0);
            byte[] k1 = new Tuple(MemorySegment.ofArray(root.getKey(1))).getField(0);
            assertEquals("alpha", new String(k0));
            assertEquals("bravo", new String(k1));
            assertEquals("first", new String(root.getValue(0)));
            assertEquals("second", new String(root.getValue(1)));
        }
    }

    @Test
    void internal_node_metadata() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node root = m.applyMutations(null, edits.iterator());

            assertTrue(root.level() >= 1, "2000 items must split into an internal-node tree");
            assertFalse(root.isLeaf(), "level>0 → !isLeaf");
            assertEquals(
                    2000L,
                    root.treeCount(),
                    "treeCount equals total leaf items, not just child count");
        }
    }

    @Test
    void internal_node_value_at_i_is_20_byte_hash() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node root = m.applyMutations(null, edits.iterator());

            for (int i = 0; i < root.count(); i++) {
                byte[] childHash = root.getValue(i);
                assertEquals(
                        20, childHash.length, "internal-node value is a SHA-512/20 child address");
                assertTrue(
                        store.read(childHash).isPresent(),
                        "every child hash must resolve to a stored chunk");
            }
        }
    }

    @Test
    void bytes_is_deterministic_for_same_chunk() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, "k"), MemorySegment.ofArray("v".getBytes())));
            Node root = m.applyMutations(null, edits.iterator());
            assertArrayEquals(
                    root.bytes(),
                    root.bytes(),
                    "bytes() must be deterministic — backs HashUtils.hash()");
        }
    }

    @Test
    void fromBytes_roundtrip_identical_metadata() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, "k-" + i), MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node original = m.applyMutations(null, edits.iterator());
            // Persist via hash, read back, re-parse.
            byte[] hash = HashUtils.hash(original.bytes());
            store.write(original.bytes());
            Node reread = Node.fromBytes(store.read(hash).orElseThrow());

            assertEquals(original.level(), reread.level());
            assertEquals(original.count(), reread.count());
            assertEquals(original.treeCount(), reread.treeCount());
            assertEquals(original.isLeaf(), reread.isLeaf());
            for (int i = 0; i < original.count(); i++) {
                assertArrayEquals(original.getKey(i), reread.getKey(i));
                assertArrayEquals(original.getValue(i), reread.getValue(i));
            }
        }
    }

    @Test
    void fromBytes_strips_dolt_serial_message_prefix() {
        // A Dolt chunk is a bare FlatBuffer (the "TUPM" identifier at offset 4)
        // framed as [1-byte NomsKind=SerialMessage][3-byte big-endian size][FlatBuffer],
        // pushing the identifier to offset 8. fromBytes must strip the 4-byte
        // prefix and parse the inner FlatBuffer — this is the Dolt-on-disk
        // read path, the heart of bit-level compatibility. (The port's own chunks
        // are PNOD-headered since ADR-0072; this test strips that header off `bare`
        // below to recover the inner bare FlatBuffer a Dolt node carries.)
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, "k-" + i), MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node bare = m.applyMutations(null, edits.iterator());
            // The port now writes a [PNOD][version] header (ADR-0072); a Dolt node carries no such
            // header — it is a *bare* TUPM flatbuffer behind the 4-byte serial prefix. Strip the
            // port
            // header so `flat` is the bare flatbuffer the Dolt-strip path is meant to exercise.
            byte[] full = bare.bytes();
            byte[] flat = java.util.Arrays.copyOfRange(full, Node.NODE_HEADER_SZ, full.length);

            // Frame it. The prefix bytes are opaque to the parser — it slices
            // off exactly 4 bytes regardless of content — but we fill in a
            // realistic NomsKind tag + 3-byte big-endian payload size.
            byte[] framed = new byte[4 + flat.length];
            framed[0] = 27; // NomsKind.SerialMessage
            framed[1] = (byte) ((flat.length >>> 16) & 0xFF);
            framed[2] = (byte) ((flat.length >>> 8) & 0xFF);
            framed[3] = (byte) (flat.length & 0xFF);
            System.arraycopy(flat, 0, framed, 4, flat.length);

            Node reread = Node.fromBytes(MemorySegment.ofArray(framed));
            assertEquals(bare.level(), reread.level());
            assertEquals(bare.count(), reread.count());
            assertEquals(bare.treeCount(), reread.treeCount());
            assertEquals(bare.isLeaf(), reread.isLeaf());
            for (int i = 0; i < bare.count(); i++) {
                assertArrayEquals(
                        bare.getKey(i),
                        reread.getKey(i),
                        "key " + i + " must survive the prefix strip");
                assertArrayEquals(
                        bare.getValue(i),
                        reread.getValue(i),
                        "value " + i + " must survive the prefix strip");
            }
        }
    }

    @Test
    void fromBytes_short_segment_fails_closed() {
        // A 4-byte blob is neither a PNOD-headered node nor a TUPM flatbuffer; ADR-0072 makes
        // Node.fromBytes fail closed (UnsupportedFormatException) rather than NPE on the identifier
        // check or silently TLV-parse it (the silent SimpleNodeSerializer fallback was removed).
        assertThrows(
                UnsupportedFormatException.class,
                () -> Node.fromBytes(MemorySegment.ofArray(new byte[] {1, 2, 3, 4})),
                "a short non-PNOD/non-TUPM blob must fail closed");
    }

    @Test
    void segment_accessor_returns_original_bytes() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, "k"), MemorySegment.ofArray("v".getBytes())));
            Node root = m.applyMutations(null, edits.iterator());

            MemorySegment seg = root.segment();
            assertEquals(root.bytes().length, seg.byteSize());
            assertArrayEquals(root.bytes(), seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }
    }

    @Test
    void identical_data_produces_identical_serialized_bytes() {
        // Determinism property: two independent tree builds with the same
        // input must produce byte-identical chunks (the core of content-addressing).
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore storeA = new InMemoryNodeStore();
                InMemoryNodeStore storeB = new InMemoryNodeStore()) {
            List<TreeMutator.Mutation> editsA = new ArrayList<>();
            List<TreeMutator.Mutation> editsB = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                editsA.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
                editsB.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node rootA =
                    new TreeMutator(storeA, STRING_DESC, pool)
                            .applyMutations(null, editsA.iterator());
            Node rootB =
                    new TreeMutator(storeB, STRING_DESC, pool)
                            .applyMutations(null, editsB.iterator());
            assertArrayEquals(
                    rootA.bytes(),
                    rootB.bytes(),
                    "content-addressed determinism: same data → same chunk bytes");
        }
    }

    // ---- zero-copy getKeySegment ----

    @Test
    void getKeySegment_matches_getKey_for_a_leaf_node() {
        // The zero-copy getKeySegment must yield the exact same bytes as the
        // copying getKey, for every key in the node.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, "k-" + i), MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node root = m.applyMutations(null, edits.iterator());
            assertEquals(0, root.level(), "small tree → single leaf");
            for (int i = 0; i < root.count(); i++) {
                byte[] viaCopy = root.getKey(i);
                byte[] viaSegment =
                        root.getKeySegment(i).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                assertArrayEquals(
                        viaCopy,
                        viaSegment,
                        "getKeySegment(" + i + ") must equal getKey(" + i + ")");
            }
        }
    }

    @Test
    void getKeySegment_matches_getKey_on_an_internal_node() {
        // A multi-level tree — exercises getKeySegment on an internal node's
        // separator keys, not just a leaf.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node root = m.applyMutations(null, edits.iterator());
            assertTrue(root.level() >= 1, "2000 items → internal-node tree");
            for (int i = 0; i < root.count(); i++) {
                assertArrayEquals(
                        root.getKey(i),
                        root.getKeySegment(i).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                        "internal-node getKeySegment(" + i + ") mismatch");
            }
        }
    }

    @Test
    void getKeySegment_round_trips_via_full_cursor_scan() {
        // Cursor.currentKey() now returns getKeySegment — a full scan must
        // still surface every key, in order, decoding to the expected value.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            int n = 500;
            for (int i = 0; i < n; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("k-%05d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node root = m.applyMutations(null, edits.iterator());
            StaticMap map = new StaticMap(store, root, STRING_DESC);
            int seen = 0;
            MapIterator it = map.iter();
            while (it.next()) {
                String s = new String(new Tuple(it.key()).getField(0));
                assertEquals(
                        String.format("k-%05d", seen), s, "scan must surface keys in tree order");
                seen++;
            }
            assertEquals(n, seen, "scan must surface every key");
        }
    }
}
