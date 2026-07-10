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
 * Direct unit-level coverage for {@link FlatbufferNodeSerializer}. Bit-compat with Dolt's Go side
 * hinges on this serializer: drift in the {@code TUPM} identifier, the field-id layout, or the
 * level-zero vs internal-node fork breaks every persisted chunk.
 *
 * <p>Existing tests exercise this serializer transitively through {@link TreeMutator} + {@link
 * Node#fromBytes}; this file pins the standalone contract: identifier emission, level
 * discriminator, and the two-arm dispatch (leaf vs internal).
 */
class FlatbufferNodeSerializerTest {

    private static TreeMutator.PendingItem item(String key, String value, long subtreeCount) {
        return new TreeMutator.PendingItem(
                MemorySegment.ofArray(key.getBytes()),
                MemorySegment.ofArray(value.getBytes()),
                subtreeCount);
    }

    // ---- PNOD header + TUPM identifier ----

    @Test
    void output_carries_pnod_header_then_tupm_identifier() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        byte[] bytes = s.serialize(0, List.of(item("k", "v", 1L)));
        // ADR-0072: the serializer now emits [PNOD magic][1-byte CORE_FORMAT_VERSION][TUPM
        // flatbuffer],
        // so the flatbuffer "file identifier" (4 bytes at flatbuffer-offset 4) lands at byte
        // NODE_HEADER_SZ + 4 = 9, not 4.
        assertTrue(
                bytes.length >= Node.NODE_HEADER_SZ + 8,
                "serialized chunk must carry the 5-byte header + root offset + identifier");
        assertEquals(
                "PNOD",
                new String(new byte[] {bytes[0], bytes[1], bytes[2], bytes[3]}),
                "node magic drift breaks Node.fromBytes dispatch (ADR-0072)");
        assertEquals(
                FormatVersion.CORE_FORMAT_VERSION,
                bytes[Node.NODE_MAGIC.length] & 0xFF,
                "the version byte must be CORE_FORMAT_VERSION");
        int idOff = Node.NODE_HEADER_SZ + 4;
        String id =
                new String(
                        new byte[] {
                            bytes[idOff], bytes[idOff + 1], bytes[idOff + 2], bytes[idOff + 3]
                        });
        assertEquals(
                "TUPM",
                id,
                "identifier drift breaks Node.fromBytes dispatch — Dolt bit-compat boundary");
    }

    // ---- level 0 (leaf) path ----

    @Test
    void leaf_node_roundtrips_via_fromBytes() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        byte[] bytes =
                s.serialize(
                        0,
                        List.of(
                                item("alpha", "a-val", 1L),
                                item("bravo", "b-val", 1L),
                                item("charlie", "c-val", 1L)));
        Node n = Node.fromBytes(MemorySegment.ofArray(bytes));
        assertEquals(0, n.level());
        assertTrue(n.isLeaf());
        assertEquals(3, n.count());
        assertEquals(3L, n.treeCount(), "level-0 treeCount = item count exactly");
        assertArrayEquals("alpha".getBytes(), n.getKey(0));
        assertArrayEquals("a-val".getBytes(), n.getValue(0));
        assertArrayEquals("charlie".getBytes(), n.getKey(2));
        assertArrayEquals("c-val".getBytes(), n.getValue(2));
    }

    @Test
    void leaf_node_single_item_round_trips() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        byte[] bytes = s.serialize(0, List.of(item("only", "value", 1L)));
        Node n = Node.fromBytes(MemorySegment.ofArray(bytes));
        assertEquals(1, n.count());
        assertArrayEquals("only".getBytes(), n.getKey(0));
    }

    @Test
    void leaf_node_with_empty_value_byte_array() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        byte[] bytes = s.serialize(0, List.of(item("k", "", 1L)));
        Node n = Node.fromBytes(MemorySegment.ofArray(bytes));
        assertArrayEquals("".getBytes(), n.getValue(0));
    }

    // ---- level > 0 (internal) path ----

    @Test
    void internal_node_treeCount_is_subtree_sum() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        // 20-byte fake child hashes — internal nodes store hashes, not values.
        TreeMutator.PendingItem a =
                new TreeMutator.PendingItem(
                        MemorySegment.ofArray("a-key".getBytes()),
                        MemorySegment.ofArray(new byte[20]),
                        42L);
        TreeMutator.PendingItem b =
                new TreeMutator.PendingItem(
                        MemorySegment.ofArray("b-key".getBytes()),
                        MemorySegment.ofArray(new byte[20]),
                        58L);
        byte[] bytes = s.serialize(3, List.of(a, b));
        Node n = Node.fromBytes(MemorySegment.ofArray(bytes));
        assertEquals(3, n.level());
        assertFalse(n.isLeaf());
        assertEquals(2, n.count());
        assertEquals(
                100L,
                n.treeCount(),
                "internal-node treeCount = sum of subtreeCount over all children");
    }

    @Test
    void internal_node_child_value_is_20_byte_hash() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        byte[] hash = new byte[20];
        for (int i = 0; i < 20; i++) hash[i] = (byte) (i + 1);
        TreeMutator.PendingItem child =
                new TreeMutator.PendingItem(
                        MemorySegment.ofArray("k".getBytes()), MemorySegment.ofArray(hash), 7L);
        byte[] bytes = s.serialize(2, List.of(child));
        Node n = Node.fromBytes(MemorySegment.ofArray(bytes));
        assertArrayEquals(hash, n.getValue(0), "internal-node value(i) is the 20-byte child hash");
    }

    @Test
    void internal_node_subtree_count_via_varint_prefix_sum() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        // Subtree counts: 5, 7, 3 → prefix sums: 5, 12, 15
        List<TreeMutator.PendingItem> items = new ArrayList<>();
        for (long sc : new long[] {5L, 7L, 3L}) {
            items.add(
                    new TreeMutator.PendingItem(
                            MemorySegment.ofArray(("k" + sc).getBytes()),
                            MemorySegment.ofArray(new byte[20]),
                            sc));
        }
        byte[] bytes = s.serialize(1, items);
        Node n = Node.fromBytes(MemorySegment.ofArray(bytes));
        // The Node.parseFlatbuffer path returns cumulative subtreeCount per the
        // varint prefix-sum convention.
        assertEquals(5, n.getSubtreeCount(0));
        assertEquals(12, n.getSubtreeCount(1));
        assertEquals(15, n.getSubtreeCount(2));
    }

    // ---- level discriminator ----

    @Test
    void level_byte_is_stored_in_node() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        for (int level : new int[] {0, 1, 5, 10, 127}) {
            // Level fits in a signed byte (0..127). Build with appropriate items.
            List<TreeMutator.PendingItem> items;
            if (level == 0) {
                items = List.of(item("k", "v", 1L));
            } else {
                items =
                        List.of(
                                new TreeMutator.PendingItem(
                                        MemorySegment.ofArray("k".getBytes()),
                                        MemorySegment.ofArray(new byte[20]),
                                        1L));
            }
            byte[] bytes = s.serialize(level, items);
            Node n = Node.fromBytes(MemorySegment.ofArray(bytes));
            assertEquals(
                    level, n.level(), "level discriminator must round-trip for level=" + level);
        }
    }

    // ---- determinism ----

    @Test
    void serialize_is_deterministic_for_same_input() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        List<TreeMutator.PendingItem> items = List.of(item("k1", "v1", 1L), item("k2", "v2", 1L));
        byte[] a = s.serialize(0, items);
        byte[] b = s.serialize(0, items);
        assertArrayEquals(
                a, b, "content-addressed determinism: same input → byte-identical output");
    }

    @Test
    void serialize_distinct_inputs_distinct_output() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        byte[] a = s.serialize(0, List.of(item("k", "v1", 1L)));
        byte[] b = s.serialize(0, List.of(item("k", "v2", 1L)));
        assertFalse(
                java.util.Arrays.equals(a, b),
                "distinct value bytes must produce distinct serialized output");
    }

    @Test
    void serialize_level_affects_output_bytes() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        byte[] leaf = s.serialize(0, List.of(item("k", "v", 1L)));
        byte[] internal =
                s.serialize(
                        1,
                        List.of(
                                new TreeMutator.PendingItem(
                                        MemorySegment.ofArray("k".getBytes()),
                                        MemorySegment.ofArray(new byte[20]),
                                        1L)));
        assertFalse(
                java.util.Arrays.equals(leaf, internal),
                "leaf vs internal serialization must differ — different schema arms");
    }

    // ---- multi-byte keys / values ----

    @Test
    void multi_byte_key_and_value_round_trip() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        byte[] longKey = new byte[100];
        for (int i = 0; i < 100; i++) longKey[i] = (byte) (i & 0xFF);
        byte[] longVal = new byte[200];
        for (int i = 0; i < 200; i++) longVal[i] = (byte) ((i * 7) & 0xFF);
        TreeMutator.PendingItem big =
                new TreeMutator.PendingItem(
                        MemorySegment.ofArray(longKey), MemorySegment.ofArray(longVal), 1L);
        byte[] bytes = s.serialize(0, List.of(big));
        Node n = Node.fromBytes(MemorySegment.ofArray(bytes));
        assertArrayEquals(longKey, n.getKey(0));
        assertArrayEquals(longVal, n.getValue(0));
    }

    @Test
    void many_items_round_trip() {
        FlatbufferNodeSerializer s = new FlatbufferNodeSerializer();
        List<TreeMutator.PendingItem> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            items.add(item("k-" + i, "v-" + i, 1L));
        }
        byte[] bytes = s.serialize(0, items);
        Node n = Node.fromBytes(MemorySegment.ofArray(bytes));
        assertEquals(100, n.count());
        // Spot-check a few entries.
        assertArrayEquals("k-0".getBytes(), n.getKey(0));
        assertArrayEquals("k-99".getBytes(), n.getKey(99));
        assertArrayEquals("v-50".getBytes(), n.getValue(50));
    }

    // ---- FlatBufferBuilder reuse isolation ----
    //
    // serialize() reuses one FlatBufferBuilder across calls via clear().
    // These tests pin the contract that nothing leaks between calls: every
    // output from a reused serializer must be byte-identical to one from a
    // fresh serializer given the same input.

    private static List<TreeMutator.PendingItem> manyLeaf(int n) {
        List<TreeMutator.PendingItem> items = new ArrayList<>();
        for (int i = 0; i < n; i++) items.add(item("k-" + i, "v-" + i, 1L));
        return items;
    }

    private static TreeMutator.PendingItem child(String key, long subtreeCount) {
        return new TreeMutator.PendingItem(
                MemorySegment.ofArray(key.getBytes()),
                MemorySegment.ofArray(new byte[20]),
                subtreeCount);
    }

    @Test
    void builder_reuse_isolated_across_differently_shaped_leaf_calls() {
        List<List<TreeMutator.PendingItem>> inputs =
                List.of(
                        List.of(item("k", "v", 1L)),
                        List.of(
                                item("alpha", "a", 1L),
                                item("bravo", "bb", 1L),
                                item("charlie", "ccc", 1L)),
                        List.of(item("x", "", 1L)),
                        manyLeaf(50));
        FlatbufferNodeSerializer reused = new FlatbufferNodeSerializer();
        for (List<TreeMutator.PendingItem> in : inputs) {
            byte[] fromReused = reused.serialize(0, in);
            byte[] fromFresh = new FlatbufferNodeSerializer().serialize(0, in);
            assertArrayEquals(
                    fromFresh,
                    fromReused,
                    "reused builder must produce identical bytes to a fresh one");
        }
    }

    @Test
    void builder_reuse_alternating_leaf_and_internal() {
        // Alternate the two schema arms — leaf (level 0) and internal
        // (level > 0) — on one serializer; each must still match a fresh one.
        FlatbufferNodeSerializer reused = new FlatbufferNodeSerializer();
        for (int i = 0; i < 8; i++) {
            if (i % 2 == 0) {
                List<TreeMutator.PendingItem> leaf = List.of(item("k" + i, "v" + i, 1L));
                assertArrayEquals(
                        new FlatbufferNodeSerializer().serialize(0, leaf),
                        reused.serialize(0, leaf),
                        "leaf call " + i + " leaked builder state");
            } else {
                List<TreeMutator.PendingItem> internal = List.of(child("k" + i, i));
                assertArrayEquals(
                        new FlatbufferNodeSerializer().serialize(i, internal),
                        reused.serialize(i, internal),
                        "internal call " + i + " leaked builder state");
            }
        }
    }

    @Test
    void builder_reuse_large_then_small_does_not_leak() {
        // A large node followed by a tiny one: if clear() failed to reset the
        // builder the small output would carry stale bytes / wrong length.
        FlatbufferNodeSerializer reused = new FlatbufferNodeSerializer();
        reused.serialize(0, manyLeaf(200)); // grow the builder
        byte[] small = reused.serialize(0, List.of(item("k", "v", 1L)));
        byte[] fresh = new FlatbufferNodeSerializer().serialize(0, List.of(item("k", "v", 1L)));
        assertArrayEquals(fresh, small);
        Node n = Node.fromBytes(MemorySegment.ofArray(small)); // still decodes
        assertEquals(1, n.count());
        assertArrayEquals("k".getBytes(), n.getKey(0));
    }

    // ---- fail-closed size bound (core-fail-closed-bounds Step 2 / D-1) ----

    @Test
    void toIntSizeOrThrow_rejects_a_sum_past_2_GiB() {
        // The accumulated node byte-size that overflows int — guarded as a pure unit (no 2 GiB
        // alloc). Before the guard, the narrowing cast silently wrapped this to a wrong/negative
        // size and serialized a corrupt buffer with no exception.
        long overflowing = (long) Integer.MAX_VALUE + 1;
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                FlatbufferNodeSerializer.toIntSizeOrThrow(
                                        overflowing, "node value bytes"));
        assertTrue(ex.getMessage().contains("2 GiB"), ex.getMessage());
        assertTrue(ex.getMessage().contains("node value bytes"), ex.getMessage());
    }

    @Test
    void toIntSizeOrThrow_passes_an_in_range_sum_unchanged() {
        // The boundary (exactly Integer.MAX_VALUE) is in range and returns unchanged — the guard
        // must
        // not narrow valid nodes (D-3, in-range behavior unchanged).
        assertEquals(
                Integer.MAX_VALUE,
                FlatbufferNodeSerializer.toIntSizeOrThrow(Integer.MAX_VALUE, "node"));
        assertEquals(0, FlatbufferNodeSerializer.toIntSizeOrThrow(0L, "node"));
        assertEquals(1024, FlatbufferNodeSerializer.toIntSizeOrThrow(1024L, "node"));
    }
}
