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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/**
 * The retention invariant for {@link InMemoryNodeStore} (plans/off-heap-use-after-free-tests.md
 * Step 5, D-4) — the sibling of {@code RocksNodeStoreReadInvariantTest}, and the one {@link
 * CursorUseAfterFreeTest} (Step 6) leans on. {@code InMemoryNodeStore} must <b>copy on write</b>
 * (so the stored chunk is independent of the pool/arena that produced the source segment) and
 * <b>return an on-heap copy on read</b>. The poison pool makes this stronger than a plain
 * assertion: a chunk written from a pool segment must survive that segment being freed + poisoned —
 * if the store kept a view instead of a copy, the read would come back poison.
 */
class InMemoryNodeStoreReadInvariantTest {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    @Test
    void writeCopiesTheSource_soReadSurvivesTheSourceBeingFreedAndPoisoned() {
        try (InMemoryNodeStore store = new InMemoryNodeStore()) {
            byte[] hash;
            try (PoisoningBufferPool pool = new PoisoningBufferPool()) {
                MemorySegment block = pool.borrow(8);
                MemorySegment view = block.asSlice(0, 8);
                for (int i = 0; i < 8; i++) {
                    view.set(BYTE, i, (byte) (i + 1)); // bytes 1..8
                }
                hash = store.write(view); // the store must copy these bytes now
                pool.release(block); // free + poison the SOURCE block after the write
            }

            // The source is gone (pool closed, arena freed) and was poisoned before that — yet the
            // store reads back the ORIGINAL bytes, proving write took an independent copy.
            MemorySegment read = store.read(hash).orElseThrow();
            assertFalse(
                    read.isNative(), "InMemoryNodeStore.read must return an on-heap copy (D-4)");
            assertFalse(
                    PoisoningBufferPool.isPoisoned(read),
                    "the store kept a copy, not a view of the freed+poisoned source segment");
            assertEquals(8, read.byteSize());
            for (int i = 0; i < 8; i++) {
                assertEquals(
                        (byte) (i + 1),
                        read.get(BYTE, i),
                        "the store returned the original bytes, not the poisoned source");
            }
        }
    }

    @Test
    void readAliasesTheStoredChunk_immutableByConvention_notAUseAfterFree() {
        // CHARACTERIZATION (a real finding from the InMemoryNodeStore probe, not a use-after-free):
        // read does MemorySegment.ofArray(storedArray), wrapping the stored byte[] DIRECTLY, so two
        // reads alias the same heap array — mutating a read result is visible to a later read. This
        // DIFFERS from RocksNodeStore, whose read returns a fresh copy per call (db.get semantics).
        // It is safe here only because nodes are immutable by content-addressing (no correct caller
        // mutates a read result), and it is NOT a use-after-free — the stored array is heap
        // (garbage-collected), never freed. Pinned so the aliasing is a known, deliberate contract;
        // a defensive copy-on-read would make it independent like RocksNodeStore, at a per-read
        // cost
        // the in-memory store deliberately avoids.
        try (InMemoryNodeStore store = new InMemoryNodeStore()) {
            byte[] hash = store.write(new byte[] {9, 9, 9, 9});
            MemorySegment s1 = store.read(hash).orElseThrow();
            s1.set(BYTE, 0, (byte) 0x7F); // mutating a read result...
            MemorySegment s2 = store.read(hash).orElseThrow();
            assertEquals(
                    (byte) 0x7F,
                    s2.get(BYTE, 0),
                    "read aliases the stored chunk — a later read sees the mutation (immutable-by-"
                            + "convention contract; differs from RocksNodeStore's per-call copy)");
        }
    }
}
