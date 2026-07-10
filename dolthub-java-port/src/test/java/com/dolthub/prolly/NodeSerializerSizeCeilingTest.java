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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Resolves ADR-0069 Q1 — the single-item serialized ceiling. {@link FlatbufferNodeSerializer}'s
 * per-item end-offset table is {@code uint16} ({@code (short) offset}), so a node whose cumulative
 * key-bytes or value-bytes exceed 65535 would truncate the offset. The {@code MAX_CHUNK_SIZE}
 * splitter cap keeps <i>multi</i>-item nodes far under that, but a <b>lone item larger than
 * 65535</b> becomes its own chunk (the splitter cannot split mid-item), so it reaches the
 * serializer at full size — the one way the cap is exceeded.
 *
 * @apiNote Before the fix this <b>silently corrupted</b> (a 65536-byte value read back as 0 bytes,
 *     no exception). The fix (matching the existing 2 GiB {@code toIntSizeOrThrow} guard + Dolt's
 *     {@code MaxVectorOffset}) is to <b>fail closed</b>: a lone value over 65535 now throws a clear
 *     {@code IllegalArgumentException} rather than serializing a corrupt node. Values up to 65535
 *     round-trip unchanged.
 */
class NodeSerializerSizeCeilingTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment keyTuple(HeapBufferPool pool, byte[] key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key);
        return tb.build().segment();
    }

    private static byte[] value(int size) {
        byte[] v = new byte[size];
        for (int i = 0; i < size; i++) v[i] = (byte) (i * 31 + 7); // content-varied, deterministic
        return v;
    }

    private static Node buildSingleItem(HeapBufferPool pool, InMemoryNodeStore store, byte[] val) {
        List<TreeMutator.Mutation> muts = new ArrayList<>();
        muts.add(
                new TreeMutator.Mutation(
                        keyTuple(pool, "k".getBytes()), MemorySegment.ofArray(val)));
        return new TreeMutator(store, STRING_DESC, pool).applyMutations(null, muts.iterator());
    }

    @Test
    void lone_value_up_to_uint16_round_trips_intact() {
        for (int size : new int[] {1000, 65535}) {
            try (HeapBufferPool pool = new HeapBufferPool();
                    InMemoryNodeStore store = new InMemoryNodeStore()) {
                byte[] val = value(size);
                Node root = buildSingleItem(pool, store, val);
                Optional<MemorySegment> got =
                        new StaticMap(store, root, STRING_DESC).get(keyTuple(pool, "k".getBytes()));
                assertTrue(got.isPresent(), "value of size " + size + " must be present");
                byte[] back = got.get().toArray(ValueLayout.JAVA_BYTE);
                assertEquals(
                        size, back.length, "value of size " + size + " round-tripped truncated");
                assertArrayEquals(val, back, "value of size " + size + " content must round-trip");
            }
        }
    }

    @Test
    void lone_value_above_uint16_fails_closed_not_silent_corruption() {
        for (int size : new int[] {65536, 70000, 200000}) {
            byte[] val = value(size);
            // Before ADR-0069 Q1's fix this silently truncated to (size & 0xFFFF) and corrupted the
            // value; now it throws a clear error instead of writing a corrupt node.
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> {
                                try (HeapBufferPool pool = new HeapBufferPool();
                                        InMemoryNodeStore store = new InMemoryNodeStore()) {
                                    buildSingleItem(pool, store, val);
                                }
                            },
                            "a lone value of " + size + " bytes (> 65535) must fail closed");
            assertTrue(
                    ex.getMessage().contains("offset-table limit"),
                    "the error must name the uint16 offset-table limit: " + ex.getMessage());
        }
    }
}
