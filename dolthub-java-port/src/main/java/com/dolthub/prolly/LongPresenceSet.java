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
import java.util.Objects;
import org.apache.commons.collections4.bloomfilter.EnhancedDoubleHasher;
import org.apache.commons.collections4.bloomfilter.Hasher;
import org.apache.commons.collections4.bloomfilter.Shape;
import org.apache.commons.collections4.bloomfilter.SimpleBloomFilter;
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
 * prolly.presence.max-bytes}, hard-ceilinged at 8 GiB), the stored hashes CONVERT into Bloom
 * filters spanning the same budget, and adds continue into them. A Bloom "absent" is as
 * authoritative as the exact table's (bits for an added key are always set: no false absents,
 * ever); a false POSITIVE merely falls through to the real probes, so as the filters fill the cost
 * degrades smoothly along the false-positive curve instead of an always-maybe saturation cliff.
 * Sixteen bytes of budget per expected distinct key keeps a load in the exact tier; loads far
 * beyond any budget belong on a batched commit cadence, where per-batch dedup hits the committed
 * base tree instead of spill runs.
 *
 * <p><b>Hardened bit-mixing, not hand-rolled.</b> The Bloom tier is Apache Commons Collections'
 * {@link SimpleBloomFilter} probed through {@link EnhancedDoubleHasher} — a vetted implementation
 * of <i>enhanced</i> double hashing. This replaces a hand-rolled blocked filter whose block index
 * and probe stride shared mix bits, collapsing eight probes into a per-block arithmetic progression
 * (measured ~20% false positives where theory said ~0.5%; consumer trace {@code
 * docs/benchmarks/ncit-runs/presence-scale-probe.txt}) — precisely the defect class the library's
 * hasher is engineered against. Because a {@link Shape} caps one filter at {@code 2^31} bits,
 * budgets beyond 16 MiB shard across multiple filters, each key routed by an independent hash mix:
 * a standard partitioned Bloom filter, false-positive-neutral versus one large filter.
 *
 * <p><b>Dependencies:</b> only {@link SpillableSortedBuffer} constructs one, feeding it {@link
 * #hashBytes} of each staged key's codec bytes on {@code put} and consulting {@link #mightContain}
 * before any run probe. The caller's contract (comparator equality implies byte equality) is what
 * keeps "absent" sound end to end.
 *
 * @implNote Exact tier: linear-probe open addressing over a power-of-two {@code long[]}, resized at
 *     half load; {@code 0} marks an empty slot with a genuine zero tracked by a side flag.
 *     Conversion is a single pass over the stored hashes (they are already the material the Bloom
 *     needs); transient memory during conversion is the old table plus the new filters — {@code
 *     2.0×} the budget at that instant (with the default budget of {@code maxHeap/8}, a {@code
 *     maxHeap/4} transient peak), settling back to {@code 1.0×}. Not thread-safe, exactly like the
 *     buffer that owns it.
 */
final class LongPresenceSet {

    private static final System.Logger LOG = System.getLogger(LongPresenceSet.class.getName());

    private static final int INITIAL_CAPACITY = 1 << 10;
    private static final long MAX_BYTES_CEILING = 8L << 30;

    /** Probes per key in the Bloom tier — the measured-good k for the budget-sized filters. */
    private static final int BLOOM_PROBES = 8;

    /** One filter's bit-count cap: {@link Shape} bits are {@code int}-indexed. 16 MiB of bits. */
    private static final int MAX_BITS_PER_SHARD = 1 << 27;

    /** Independent mix constants: probe entropy and shard routing must not share bits. */
    private static final long GOLDEN = 0x9e3779b97f4a7c15L;

    private static final long SHARD_SALT = 0x5851f42d4c957f2dL;

    /**
     * Resolved once: {@code Long.getLong} is a synchronized system-property read, and a
     * per-transaction constructor (one per dictionary buffer) should not repay it every commit.
     */
    private static final long DEFAULT_MAX_BYTES = computeDefaultMaxBytes();

    private static long computeDefaultMaxBytes() {
        Long override = Long.getLong("prolly.presence.max-bytes");
        if (override != null && override > 0) {
            return Math.min(override, MAX_BYTES_CEILING);
        }
        return Math.min(
                Math.max(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8),
                MAX_BYTES_CEILING);
    }

    /** The heap-aware presence byte budget (shared by the per-run filter budget in the buffer). */
    static long defaultBudgetBytes() {
        return DEFAULT_MAX_BYTES;
    }

    /** Largest exact-table slot count the byte budget allows (power of two). */
    private final int maxSlots;

    private long[] slots = new long[INITIAL_CAPACITY];
    private int size;
    private boolean containsZero;

    /** Non-null once converted; together the shards span the full byte budget. */
    private SimpleBloomFilter @Nullable [] shards;

    private int shardCount;

    LongPresenceSet() {
        this(DEFAULT_MAX_BYTES);
    }

    /** Explicit-budget constructor (also the test seam): {@code maxBytes} bounds both tiers. */
    LongPresenceSet(long maxBytes) {
        long budgetSlots = Math.max(INITIAL_CAPACITY, maxBytes / Long.BYTES);
        this.maxSlots = Integer.highestOneBit((int) Math.min(budgetSlots, 1L << 30));
    }

    /** Registers {@code h}. Idempotent; {@code 0} is an ordinary value. */
    void add(long h) {
        SimpleBloomFilter[] b = shards;
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
            // A duplicate re-add at the capacity boundary must not force a
            // spurious doubling — or, at the budget, an irreversible Bloom
            // conversion — when no new distinct key needs a slot. Probe first;
            // the extra probe is paid only at boundary crossings.
            if (exactContains(h)) {
                return;
            }
            if (slots.length >= maxSlots) {
                convertToBloom(); // the budget holds; the answers get probabilistic, never wrong
                bloomAdd(Objects.requireNonNull(shards), h);
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
        SimpleBloomFilter[] b = shards;
        if (b != null) {
            return b[shardFor(h)].contains(hasherFor(h));
        }
        if (h == 0) {
            return containsZero;
        }
        return exactContains(h);
    }

    /** Empties the set back to the exact tier; the instance stays usable (buffer clear-reuse). */
    void clear() {
        // A fresh small array rather than Arrays.fill: a transaction that
        // staged millions of keys should not pin the grown table across reuse.
        slots = new long[INITIAL_CAPACITY];
        size = 0;
        containsZero = false;
        shards = null;
        shardCount = 0;
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
        return shards != null;
    }

    /** Number of Bloom shards after conversion, {@code 0} while exact — a test observable. */
    int shardCountForTest() {
        return shardCount;
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

    /**
     * The one probe derivation for every Bloom filter this class and {@link SpillableSortedBuffer}
     * build from a key hash: two independent finalizer mixes feeding the library's enhanced double
     * hashing. Shared so a per-run filter and the presence shards can never disagree on a key's
     * probe positions.
     */
    static Hasher hasherFor(long h) {
        long m1 = spread(h);
        return new EnhancedDoubleHasher(m1, spread(m1 + GOLDEN));
    }

    // ----- bloom tier -----

    /**
     * Allocate the budget's worth of Bloom shards and pour the exact tier into them: the stored
     * values ARE the 64-bit hashes the filters consume, so conversion is one linear pass and loses
     * nothing in the hash domain. Transiently holds old table + new shards = 2.0× the budget.
     */
    private void convertToBloom() {
        long budgetBits = (long) maxSlots * Long.SIZE; // same bytes as the table, 8× the bits
        int bitsPerShard = (int) Math.min(budgetBits, MAX_BITS_PER_SHARD);
        int count = (int) (budgetBits / bitsPerShard); // both powers of two: exact division
        Shape shape = Shape.fromKM(BLOOM_PROBES, bitsPerShard);
        SimpleBloomFilter[] b = new SimpleBloomFilter[count];
        for (int i = 0; i < count; i++) {
            b[i] = new SimpleBloomFilter(shape);
        }
        this.shardCount = count;
        for (long v : slots) {
            if (v != 0) {
                bloomAdd(b, v);
            }
        }
        if (containsZero) {
            bloomAdd(b, 0);
        }
        this.shards = b; // installed only after the pour: a throw above leaves the exact tier live
        this.slots = new long[INITIAL_CAPACITY]; // the table's memory returns to the budget
        LOG.log(
                System.Logger.Level.WARNING,
                "presence index exceeded its {0}-byte budget at {1} distinct keys; converted to"
                        + " the Bloom tier ({2} shard(s), probabilistic maybes, never a false"
                        + " absent) — for exact-tier speed budget ~16 bytes per expected distinct"
                        + " key via -Dprolly.presence.max-bytes, or switch very large loads to a"
                        + " batched commit cadence",
                (long) maxSlots * Long.BYTES,
                size,
                count);
    }

    private void bloomAdd(SimpleBloomFilter[] b, long h) {
        b[shardFor(h)].merge(hasherFor(h));
    }

    /**
     * Routes a key to its shard by a mix independent of {@link #hasherFor}'s, so shard choice
     * consumes no probe entropy. Symmetric between add and query, which is all soundness needs.
     */
    private int shardFor(long h) {
        return shardCount == 1
                ? 0
                : (int) Long.remainderUnsigned(spread(h ^ SHARD_SALT), shardCount);
    }

    // ----- exact tier -----

    private boolean exactContains(long h) {
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
