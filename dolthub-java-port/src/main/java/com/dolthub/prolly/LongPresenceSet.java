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
import org.jspecify.annotations.Nullable;

/**
 * A two-tier presence structure over {@code long} hashes — the in-heap absent-key index behind
 * {@link SpillableSortedBuffer}'s opt-in short-circuit.
 *
 * <p><b>Why:</b> once a buffer has spilled, every point lookup of an ABSENT key walks every run
 * file (a file open plus up to an index-stride of entry decodes per run — measured at 3.6 ms per
 * lookup against just 35 runs, versus 196 ns through this index; consumer probe,
 * quarkus-ontology-editor benchmarks). A bulk load whose distinct-key count grows with the
 * transaction pays that walk per first encounter — the measured quadratic dictionary-encode wall.
 *
 * <p><b>Two tiers.</b> Tier one is an EXACT open-addressing table of the full 64-bit hashes:
 * perfect answers and exact {@link #size} telemetry, ~16–32 bytes per distinct key. When growth
 * would exceed the byte budget (heap-aware: {@code max(64 MiB, maxHeap/8)}, overridable via {@code
 * prolly.presence.max-bytes}, hard-ceilinged at 8 GiB), the stored hashes CONVERT into a <b>blocked
 * Bloom filter</b> spanning the full budget — 64-byte blocks probed with one cache miss, k=8
 * double-hashed bits per key — and adds continue into it. A Bloom "absent" is as authoritative as
 * the exact table's (bits for an added key are always set: no false absents, ever); a false
 * POSITIVE merely falls through to the real probes, so as the filter fills the cost degrades
 * smoothly along the false-positive curve instead of the old always-maybe saturation cliff. At a 1
 * GiB budget the blocked-Bloom false-positive rate is roughly 0.5–1% at 500M distinct keys and a
 * few percent at 1B — versus 100% fall-through past the old cap. Sixteen bytes of budget per
 * expected distinct key keeps a load in the exact tier; loads far beyond any budget belong on a
 * batched commit cadence, where per-batch dedup hits the committed base tree instead of spill runs.
 *
 * <p><b>Dependencies:</b> only {@link SpillableSortedBuffer} constructs one, feeding it {@link
 * #hashBytes} of each staged key's codec bytes on {@code put} and consulting {@link #mightContain}
 * before any run probe. The caller's contract (comparator equality implies byte equality) is what
 * keeps "absent" sound end to end.
 *
 * @implNote Exact tier: linear-probe open addressing over a power-of-two {@code long[]}, resized at
 *     half load; {@code 0} marks an empty slot with a genuine zero tracked by a side flag. Bloom
 *     tier: {@code long[]} bit array in 8-long (512-bit) blocks; the hash's low bits pick the
 *     block, and eight probe positions derive by double hashing within it. Conversion is a single
 *     pass over the stored hashes (they are already the material the Bloom needs); transient memory
 *     during conversion is the old table plus the new bits, both inside 1.5× the budget. Not
 *     thread-safe, exactly like the buffer that owns it.
 */
final class LongPresenceSet {

    private static final int INITIAL_CAPACITY = 1 << 10;
    private static final long MAX_BYTES_CEILING = 8L << 30;
    private static final int BLOOM_PROBES = 8;

    private static long defaultMaxBytes() {
        Long override = Long.getLong("prolly.presence.max-bytes");
        if (override != null && override > 0) {
            return Math.min(override, MAX_BYTES_CEILING);
        }
        return Math.min(
                Math.max(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8),
                MAX_BYTES_CEILING);
    }

    /** Largest exact-table slot count the byte budget allows (power of two). */
    private final int maxSlots;

    private long[] slots = new long[INITIAL_CAPACITY];
    private int size;
    private boolean containsZero;

    /** Non-null once converted; sized to the full byte budget, in 8-long blocks. */
    private long @Nullable [] bloom;

    private int bloomBlockCount;

    LongPresenceSet() {
        this(defaultMaxBytes());
    }

    /** Explicit-budget constructor (also the test seam): {@code maxBytes} bounds both tiers. */
    LongPresenceSet(long maxBytes) {
        long budgetSlots = Math.max(INITIAL_CAPACITY, maxBytes / Long.BYTES);
        this.maxSlots = Integer.highestOneBit((int) Math.min(budgetSlots, 1L << 30));
    }

    /** Registers {@code h}. Idempotent; {@code 0} is an ordinary value. */
    void add(long h) {
        long[] b = bloom;
        if (b != null) {
            bloomAdd(b, h);
            return;
        }
        if (h == 0) {
            if (!containsZero) {
                containsZero = true;
                size++;
            }
            return;
        }
        if ((size + 1) * 2 > slots.length) {
            if (slots.length >= maxSlots) {
                convertToBloom(); // the budget holds; the answers get probabilistic, never wrong
                bloomAdd(bloom, h);
                return;
            }
            grow();
        }
        if (insert(slots, h)) {
            size++;
        }
    }

    /** True if {@code h} MAY have been {@link #add}ed since the last {@link #clear}. */
    boolean mightContain(long h) {
        long[] b = bloom;
        if (b != null) {
            return bloomMightContain(b, h);
        }
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

    /** Empties the set back to the exact tier; the instance stays usable (buffer clear-reuse). */
    void clear() {
        // A fresh small array rather than Arrays.fill: a transaction that
        // staged millions of keys should not pin the grown table across reuse.
        slots = new long[INITIAL_CAPACITY];
        size = 0;
        containsZero = false;
        bloom = null;
        bloomBlockCount = 0;
    }

    /**
     * Distinct values registered while in the exact tier; after Bloom conversion the count FREEZES
     * at the conversion point (a Bloom cannot distinguish new from duplicate) — a test observable
     * and telemetry hint, not a capacity.
     */
    int size() {
        return size;
    }

    /** True once the budget forced conversion to the Bloom tier — a test observable. */
    boolean isBloomForTest() {
        return bloom != null;
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

    // ----- bloom tier -----

    /**
     * Allocate the budget-sized blocked Bloom and pour the exact tier into it: the stored values
     * ARE the 64-bit hashes the Bloom consumes, so conversion is one linear pass and loses nothing
     * in the hash domain.
     */
    private void convertToBloom() {
        // The full budget in bits, in 8-long (64-byte, one cache line) blocks. maxSlots is the
        // budget in longs already (budget/8 bytes-per-long), so reuse it as the Bloom's long
        // count: same bytes, 8x the bits, one block per 8 longs.
        int longs = Math.max(8, maxSlots);
        long[] b = new long[longs];
        this.bloomBlockCount = longs / 8;
        for (long v : slots) {
            if (v != 0) {
                bloomAdd(b, v);
            }
        }
        if (containsZero) {
            bloomAdd(b, 0);
        }
        this.bloom = b; // installed only after the pour: a throw above leaves the exact tier live
        this.slots = new long[INITIAL_CAPACITY]; // the table's memory returns to the budget
    }

    private void bloomAdd(long @Nullable [] b, long h) {
        if (b == null) {
            return; // defensive: caller checked; NullAway-visible guard
        }
        long m1 = spread(h);
        // A SECOND independent mix for the probe positions: bloomBlockCount is
        // a power of two, so block selection consumes m1's LOW bits — deriving
        // the probe stride from those same bits made it constant per block and
        // collapsed the eight probes into one shared arithmetic progression
        // (measured: ~20% false positives where theory said ~0.5%). Probes must
        // draw entropy the block index did not.
        long m2 = spread(m1 + 0x9e3779b97f4a7c15L);
        int block = (int) Long.remainderUnsigned(m1, bloomBlockCount) * 8;
        int h1 = (int) m2;
        int h2 = (int) (m2 >>> 32) | 1; // odd step so probes cover the block
        for (int i = 0; i < BLOOM_PROBES; i++) {
            int bit = (h1 + i * h2) & 511; // position within the 512-bit block
            b[block + (bit >>> 6)] |= 1L << (bit & 63);
        }
    }

    private boolean bloomMightContain(long[] b, long h) {
        long m1 = spread(h);
        long m2 = spread(m1 + 0x9e3779b97f4a7c15L);
        int block = (int) Long.remainderUnsigned(m1, bloomBlockCount) * 8;
        int h1 = (int) m2;
        int h2 = (int) (m2 >>> 32) | 1;
        for (int i = 0; i < BLOOM_PROBES; i++) {
            int bit = (h1 + i * h2) & 511;
            if ((b[block + (bit >>> 6)] & (1L << (bit & 63))) == 0) {
                return false; // one clear probe bit proves the key was never added
            }
        }
        return true;
    }

    // ----- exact tier -----

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
