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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for {@link Node} (plans/off-heap-use-after-free-tests.md Phase 1 Step 5).
 * {@link Node#fromBytes} wraps its backing segment zero-copy; {@link Node#getKeySegment} hands out
 * slices of it. Pins: a node read after its backing arena closes throws (H1); a retained {@code
 * getKeySegment} view reads poison once its backing block is released (H4 — the retention hazard a
 * cache/cursor holding a pool-backed node would hit).
 */
class NodeUseAfterFreeTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    /** A real one-entry node's serialized bytes (built via the normal mutate path). */
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

    @Test
    void nodeReadAfterBackingArenaCloses_throws() {
        byte[] chunk = validChunk();
        Arena arena = Arena.ofShared();
        MemorySegment seg = arena.allocate(chunk.length);
        MemorySegment.copy(MemorySegment.ofArray(chunk), 0, seg, 0, chunk.length);
        Node node = Node.fromBytes(seg);
        node.getKey(0); // alive while the arena is open
        arena.close();
        assertThrows(
                IllegalStateException.class,
                () -> node.getKey(0),
                "a node read after its backing arena closed must throw, not read freed memory");
    }

    @Test
    void retainedKeySegmentView_readsPoison_afterBackingReleased() {
        byte[] chunk = validChunk();
        try (PoisoningBufferPool pool = new PoisoningBufferPool()) {
            MemorySegment block = pool.borrow(chunk.length);
            MemorySegment.copy(MemorySegment.ofArray(chunk), 0, block, 0, chunk.length);
            Node node = Node.fromBytes(block.asSlice(0, chunk.length));

            MemorySegment keyView = node.getKeySegment(0); // a zero-copy slice of the backing block
            assertFalse(
                    PoisoningBufferPool.isPoisoned(keyView), "the key view is live before release");

            pool.release(block); // free + poison the backing block

            assertTrue(
                    PoisoningBufferPool.isPoisoned(keyView),
                    "a retained getKeySegment view must be detectable as poison after its backing is "
                            + "released (the retention hazard a pool-backed cached node would hit)");
        }
    }

    @Test
    void freshNodeKeyIsNotPoison() {
        // Sanity / no-false-positive: a node over a heap chunk reads its real key, not poison.
        Node node = Node.fromBytes(MemorySegment.ofArray(validChunk()));
        byte[] key = node.getKey(0);
        assertFalse(
                ArenaScopeProbe.containsPoisonRun(key, 4),
                "a live node's key must not look like poison");
        Tuple keyTuple = new Tuple(MemorySegment.ofArray(key));
        assertTrue(keyTuple.getField(0).length > 0, "the key field is present");
    }
}
