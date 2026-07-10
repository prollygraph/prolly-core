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
package com.dolthub.prolly.chunkbench;

import com.dolthub.prolly.BuzHash;
import java.util.SplittableRandom;

/**
 * The candidate boundary functions, all KEYS-ONLY, deterministic, secret-seeded, and MIN/MAX
 * clamped, deciding at ENTRY granularity (the production splitter's {@code crossedBoundary} is only
 * consulted between entries, so per-entry decisions are the shared structural contract):
 *
 * <ul>
 *   <li><b>A — direct per-key mask</b>: boundary when the key's first term id (its low bits,
 *       salt-mixed only by upstream seeding per the task premise) masks to zero. No state across
 *       keys at all — a pure function of the key.
 *   <li><b>B — gear (FastCDC lineage)</b>: {@code h = (h << 1) + GEAR[b]} over the key's bytes;
 *       boundary when the HIGH bits mask to zero.
 *   <li><b>C — buzhash reference</b>: the production {@link BuzHash} (window 67, level-salt XOR)
 *       fed keys-only, with the production STAIRCASE pattern — the incumbent's geometry.
 * </ul>
 *
 * <p>A and B use FastCDC-style <b>normalized two-mask chunking</b>: a STRICT mask (+2 bits, rarer)
 * before the target size, a LOOSE mask (−2 bits, commoner) after, MIN/MAX clamped. A single-mask
 * geometric baseline of B exists for the variance comparison. Mask bit-widths derive from the
 * per-decision byte granularity (A decides once per key; B/C decide per byte), so all candidates
 * aim at the same ~4 KiB mean.
 */
final class BoundaryStrategies {

    static final int MIN = 512;
    static final int MAX = 16 * 1024;
    static final int TARGET = 4 * 1024;

    private BoundaryStrategies() {}

    /**
     * One chunk-boundary decider over a key stream. Stateful; reset() between chunks is implicit.
     */
    interface Boundary {
        /**
         * Feed one key; returns true when a boundary lands AFTER this key. Implementations track
         * the running chunk size in bytes and self-reset when they signal a boundary.
         */
        boolean acceptKey(byte[] flat, int off, int len);

        String name();
    }

    // ---- A: direct per-key mask ------------------------------------------------

    /**
     * Boundary = (first-term-id low bits & mask) == 0. Two-mask normalization: strict (+2 bits)
     * below TARGET, loose (−2 bits) above. The per-key boundary probability is calibrated from the
     * key width: normalBits = log2(TARGET / keyWidth).
     */
    static final class DirectMask implements Boundary {
        private final long strictMask;
        private final long looseMask;
        private final long salt;
        private int size;

        DirectMask(int keyWidth, long seed) {
            int normalBits = Integer.numberOfTrailingZeros(TARGET / keyWidth);
            this.strictMask = (1L << (normalBits + 2)) - 1;
            this.looseMask = (1L << Math.max(1, normalBits - 2)) - 1;
            // The task premise seeds ids upstream; the salt keeps the study's streams honest
            // when they are NOT upstream-seeded (the ordinal shape). XOR-only — one op.
            this.salt = new SplittableRandom(seed).nextLong();
        }

        @Override
        public boolean acceptKey(byte[] flat, int off, int len) {
            size += len;
            if (size < MIN) {
                return false;
            }
            if (size >= MAX) {
                size = 0;
                return true;
            }
            long id = readLong(flat, off); // the key's FIRST term id (its low 8 bytes suffice)
            long mask = size < TARGET ? strictMask : looseMask;
            if (((id ^ salt) & mask) == 0) {
                size = 0;
                return true;
            }
            return false;
        }

        @Override
        public String name() {
            return "A-directMask";
        }
    }

    /**
     * A-prime: the minimal repair of A for SORTED streams. As specified, A keys on the FIRST term
     * id — but a sorted quad stream holds the subject constant across runs of keys, so the per-key
     * verdict is constant within a run (degenerate geometry, measured in the study). A' mixes ALL
     * fixed-width lanes (XOR of each 8-byte word) — still a pure per-key function in the same op
     * class, but every key contributes fresh entropy through its varying object lane.
     */
    static final class DirectMaskXor implements Boundary {
        private final long strictMask;
        private final long looseMask;
        private final long salt;
        private int size;

        DirectMaskXor(int keyWidth, long seed) {
            int normalBits = Integer.numberOfTrailingZeros(TARGET / keyWidth);
            this.strictMask = (1L << (normalBits + 2)) - 1;
            this.looseMask = (1L << Math.max(1, normalBits - 2)) - 1;
            this.salt = new SplittableRandom(seed).nextLong();
        }

        @Override
        public boolean acceptKey(byte[] flat, int off, int len) {
            size += len;
            if (size < MIN) {
                return false;
            }
            if (size >= MAX) {
                size = 0;
                return true;
            }
            long mix = salt;
            for (int w = 0; w + 8 <= len; w += 8) {
                mix ^= readLong(flat, off + w);
            }
            mix *= 0x9E3779B97F4A7C15L; // one multiply spreads lane-XOR bits into the low mask
            long mask = size < TARGET ? strictMask : looseMask;
            if ((mix & mask) == 0) {
                size = 0;
                return true;
            }
            return false;
        }

        @Override
        public String name() {
            return "A'-directMaskXor";
        }
    }

    // ---- B: gear ---------------------------------------------------------------

    /** FastCDC-lineage gear over key bytes; high-bit masks; two-mask normalized. */
    static final class Gear implements Boundary {
        private final long[] gear = new long[256];
        private final long strictMask;
        private final long looseMask;
        private long h;
        private int size;

        Gear(long seed) {
            SplittableRandom rnd = new SplittableRandom(seed);
            for (int i = 0; i < 256; i++) {
                gear[i] = rnd.nextLong();
            }
            // Per-BYTE decision rate: normalBits = log2(TARGET) = 12; masks live in the HIGH bits
            // (the gear's freshest entropy). Strict 14 bits, loose 10.
            this.strictMask = highBits(14);
            this.looseMask = highBits(10);
        }

        private static long highBits(int n) {
            return ~0L << (64 - n);
        }

        @Override
        public boolean acceptKey(byte[] flat, int off, int len) {
            long hh = h;
            boolean hit = false;
            int s = size;
            for (int i = 0; i < len; i++) {
                hh = (hh << 1) + gear[flat[off + i] & 0xFF];
                s++;
                if (hit || s < MIN) {
                    continue;
                }
                long mask = s < TARGET ? strictMask : looseMask;
                if ((hh & mask) == 0 || s >= MAX) {
                    hit = true;
                }
            }
            h = hh;
            size = s;
            if (hit) {
                size = 0;
                h = 0;
                return true;
            }
            return false;
        }

        @Override
        public String name() {
            return "B-gear";
        }
    }

    /** B with ONE mask (the plain geometric baseline the two-mask variant is judged against). */
    static final class GearSingleMask implements Boundary {
        private final long[] gear = new long[256];
        private final long mask;
        private long h;
        private int size;

        GearSingleMask(long seed) {
            SplittableRandom rnd = new SplittableRandom(seed);
            for (int i = 0; i < 256; i++) {
                gear[i] = rnd.nextLong();
            }
            this.mask = ~0L << (64 - 12); // 12 bits ≈ 1/4096 per byte
        }

        @Override
        public boolean acceptKey(byte[] flat, int off, int len) {
            long hh = h;
            boolean hit = false;
            int s = size;
            for (int i = 0; i < len; i++) {
                hh = (hh << 1) + gear[flat[off + i] & 0xFF];
                s++;
                if (hit || s < MIN) {
                    continue;
                }
                if ((hh & mask) == 0 || s >= MAX) {
                    hit = true;
                }
            }
            h = hh;
            size = s;
            if (hit) {
                size = 0;
                h = 0;
                return true;
            }
            return false;
        }

        @Override
        public String name() {
            return "B-gearSingleMask";
        }
    }

    // ---- C: buzhash reference ----------------------------------------------------

    /**
     * The incumbent, keys-only: production {@link BuzHash} (window 67), level-salt XOR per byte,
     * and the production staircase pattern (progressively looser mask as the chunk grows).
     */
    static final class BuzhashKeys implements Boundary {
        private final BuzHash bz = new BuzHash(67);
        private final long salt;
        private int size;

        BuzhashKeys(long salt) {
            this.salt = salt;
        }

        @Override
        public boolean acceptKey(byte[] flat, int off, int len) {
            boolean hit = false;
            for (int i = 0; i < len; i++) {
                size++;
                if (hit) {
                    continue;
                }
                bz.hashByte((byte) ((long) Byte.toUnsignedInt(flat[off + i]) ^ salt));
                if (size < MIN) {
                    continue;
                }
                if (size >= MAX) {
                    hit = true;
                    continue;
                }
                int hash = bz.sum32();
                int patt = (1 << (15 - (size >> 10))) - 1; // the production staircase
                if ((hash & patt) == patt) {
                    hit = true;
                }
            }
            if (hit) {
                size = 0;
                bz.reset();
                return true;
            }
            return false;
        }

        @Override
        public String name() {
            return "C-buzhashKeys";
        }
    }

    static long readLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }
}
