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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A minimal open-addressing set of {@code long} hashes — the in-heap presence index behind {@link
 * SpillableSortedBuffer}'s opt-in absent-key short-circuit.
 *
 * <p><b>Why:</b> once a buffer has spilled, every point lookup of an ABSENT key walks every run
 * file (a file open plus up to an index-stride of entry decodes per run — {@code
 * SpillableSortedBuffer.Run#lookup}). A bulk load whose distinct-key count grows with the
 * transaction pays that walk per first encounter, which is the measured quadratic dictionary-encode
 * wall. Sixteen-ish bytes per distinct key here converts those walks into one array probe.
 *
 * <p><b>Dependencies:</b> only {@link SpillableSortedBuffer} constructs one, feeding it {@link
 * #hashBytes} of each staged key's codec bytes on {@code put} and consulting {@link #mightContain}
 * before any run probe. A positive answer may be a hash collision (the caller falls through to the
 * real probes — never wrong, merely slower); a negative answer is authoritative BECAUSE every
 * {@code put} registers, so the caller's contract (comparator equality implies byte equality) is
 * what keeps "absent" sound.
 *
 * @implNote Linear-probe open addressing over a power-of-two {@code long[]}, resized at half load.
 *     {@code 0} marks an empty slot; a genuine zero value is tracked by a side flag so no key can
 *     be silently unstorable. Not thread-safe, exactly like the buffer that owns it.
 */
final class LongPresenceSet {

    private static final int INITIAL_CAPACITY = 1 << 10;

    private long[] slots = new long[INITIAL_CAPACITY];
    private int size;
    private boolean containsZero;

    /** Registers {@code h}. Idempotent; {@code 0} is an ordinary value. */
    void add(long h) {
        if (h == 0) {
            if (!containsZero) {
                containsZero = true;
                size++;
            }
            return;
        }
        if ((size + 1) * 2 > slots.length) {
            grow();
        }
        if (insert(slots, h)) {
            size++;
        }
    }

    /** True if {@code h} was ever {@link #add}ed since the last {@link #clear}. */
    boolean mightContain(long h) {
        if (h == 0) {
            return containsZero;
        }
        long[] s = slots;
        int mask = s.length - 1;
        int i = (int) spread(h) & mask;
        while (true) {
            long v = s[i];
            if (v == h) {
                return true;
            }
            if (v == 0) {
                return false;
            }
            i = (i + 1) & mask;
        }
    }

    /** Empties the set; the instance stays usable (mirrors the owning buffer's clear-reuse). */
    void clear() {
        // A fresh small array rather than Arrays.fill: a transaction that
        // staged millions of keys should not pin the grown table across reuse.
        slots = new long[INITIAL_CAPACITY];
        size = 0;
        containsZero = false;
    }

    /** Distinct values registered — a test observable, not a capacity. */
    int size() {
        return size;
    }

    /**
     * FNV-1a-64 over the segment's bytes — the same well-defined, dependency-free hash family the
     * dictionary already standardizes on for term bytes. Collisions cost a fall-through probe,
     * never a wrong answer, so hash quality is a performance dial here, not a correctness one.
     */
    static long hashBytes(MemorySegment bytes) {
        long h = 0xcbf29ce484222325L;
        long n = bytes.byteSize();
        for (long i = 0; i < n; i++) {
            h ^= bytes.get(ValueLayout.JAVA_BYTE, i) & 0xffL;
            h *= 0x100000001b3L;
        }
        return h;
    }

    private void grow() {
        long[] next = new long[slots.length << 1];
        for (long v : slots) {
            if (v != 0) {
                insert(next, v);
            }
        }
        slots = next;
    }

    /** Inserts into {@code s}; returns true if newly added. {@code h != 0} by the caller. */
    private static boolean insert(long[] s, long h) {
        int mask = s.length - 1;
        int i = (int) spread(h) & mask;
        while (true) {
            long v = s[i];
            if (v == h) {
                return false;
            }
            if (v == 0) {
                s[i] = h;
                return true;
            }
            i = (i + 1) & mask;
        }
    }

    /** Finalizer-style bit spread so low-entropy hashes still probe well. */
    private static long spread(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h;
    }
}
