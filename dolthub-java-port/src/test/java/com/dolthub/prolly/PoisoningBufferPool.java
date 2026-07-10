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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link BufferPool} test double that makes the <b>silent</b> use-after-free hazard <b>loud</b>
 * (plans/off-heap-use-after-free-tests.md D-1 — the ASan-quarantine analog). On {@link #release} it
 * fills the freed block with a recognizable poison pattern and quarantines it; the next same-bucket
 * {@link #borrow} hands that <i>same</i> block back <b>without zeroing</b> (unlike the production
 * {@code DirectBufferPool}, which zero-fills). So a stale reference to a released segment reads
 * poison where the new tenant has not yet written, and the new tenant's data where it has — either
 * way it is no longer the original, and {@link #isPoisoned} / a differential check detects the read
 * of freed memory.
 *
 * <p>Off-heap ({@code Arena.ofShared}) on purpose: it reuses the real block so a stale {@code
 * asSlice} view genuinely aliases the recycled backing (hazard H3), exactly as {@code
 * DirectBufferPool} would — the heap pool cannot reproduce this (the garbage collector keeps the
 * old array alive).
 *
 * @implNote test-only; lives in {@code dolthub-java-port/src/test} beside {@link BufferPool}.
 *     Promote to a {@code test-jar} when a second module needs it (D-5). Not thread-safe —
 *     single-test use; the cross-thread hazard (H5) gets its own stress harness, not this.
 */
public final class PoisoningBufferPool implements BufferPool {

    /**
     * A distinctive non-zero byte; a whole-segment fill of it is what {@link #isPoisoned} looks
     * for.
     */
    public static final byte POISON = (byte) 0xDE;

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private final Arena arena = Arena.ofShared();
    private final Map<Long, Deque<MemorySegment>> quarantine = new HashMap<>();
    private long borrowedCount;
    private long releasedCount;

    @Override
    public MemorySegment borrow(int size) {
        long bucket = bucketSize(size);
        Deque<MemorySegment> q = quarantine.get(bucket);
        borrowedCount++;
        if (q != null && !q.isEmpty()) {
            // Reuse the most-recently-freed block, poison intact (do NOT zero): a stale reference
            // to it
            // now reads poison, and a new write through this handle aliases that stale reference.
            return q.pollFirst();
        }
        return arena.allocate(bucket); // fresh blocks are zero-initialised by the arena
    }

    @Override
    public void release(MemorySegment segment) {
        releasedCount++;
        segment.fill(POISON); // poison the freed block so any later read of it is detectable
        quarantine.computeIfAbsent(segment.byteSize(), k -> new ArrayDeque<>()).offerFirst(segment);
    }

    @Override
    public void close() {
        arena.close();
    }

    public long borrowedCount() {
        return borrowedCount;
    }

    public long releasedCount() {
        return releasedCount;
    }

    /**
     * True iff every byte of {@code seg} is {@link #POISON} — i.e. it is freed (or
     * unwritten-since-free) memory. A live value being entirely {@code 0xDE} repeated is
     * astronomically unlikely.
     */
    public static boolean isPoisoned(MemorySegment seg) {
        long n = seg.byteSize();
        if (n == 0) {
            return false;
        }
        for (long i = 0; i < n; i++) {
            if (seg.get(BYTE, i) != POISON) {
                return false;
            }
        }
        return true;
    }

    /**
     * The bucket a {@code borrow(size)} maps to — power-of-two ≥ size, minimum 1024 (mirrors {@code
     * DirectBufferPool}, so release-then-reuse aliases the same block as production would).
     */
    public static long bucketSize(int size) {
        if (size <= 1024) {
            return 1024L;
        }
        int n = size - 1;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
        return (long) n + 1L;
    }
}
