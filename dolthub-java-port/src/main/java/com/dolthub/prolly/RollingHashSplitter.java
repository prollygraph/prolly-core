/*
 * Copyright 2026 Earasoft
 * Copyright 2021 Dolthub, Inc.
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Decides prolly-tree node boundaries by content — content-defined chunking — so that structurally
 * similar trees share most of their chunks.
 *
 * <p>This is what makes the tree a <i>prolly</i> tree rather than a plain B-tree: boundaries are
 * chosen by the data itself (a rolling hash over a sliding window), not by a fixed fan-out. The
 * payoff is <b>boundary stability</b> — inserting or changing one item re-chunks only a local span,
 * so an edited tree reuses the unchanged chunks of its predecessor (cheap versioning, cross-version
 * deduplication).
 *
 * @apiNote Feed bytes through the splitter; it signals a boundary when the rolling hash matches the
 *     target pattern, yielding chunks in a target range of roughly 512 bytes to 16 KiB. Boundaries
 *     are <b>deterministic</b> for given content — the same bytes always split the same way, which
 *     is what lets two independently-built trees share chunks. Per-level salts keep boundaries from
 *     aligning vertically across tree heights (which would defeat sharing at higher levels).
 * @implNote Wraps {@link BuzHash} (the incremental rolling hash); driven by {@link TreeMutator}'s
 *     chunker as it emits a new tree. The boundary function is the port's <b>own</b> deterministic
 *     rule, <b>not</b> Dolt's — byte-for-byte parity with the Go reference is optional and deferred
 *     (the chunker layer diverges from Dolt v2.0.3; see {@code cross-lang/BITCOMPAT_FINDINGS.md}
 *     and the pre-1.0 no-backwards-compat decision). The live contract is therefore <b>internal
 *     determinism</b> — identical content yields identical boundaries, so a tree shares chunks with
 *     its own prior versions (the cheap-versioning + dedup payoff) — plus <b>bounded,
 *     non-degenerate geometry</b>, not Go parity. Determinism is pinned by {@code
 *     ChunkerDeterminismGateTest} and {@code SplitterGeometryProperty}; the geometry bounds +
 *     internal-node degenerate guard by {@code SplitterGeometryProperty} and {@code
 *     DegenerateInternalNodeGuardTest} (ADR-0069).
 */
public class RollingHashSplitter implements BoundarySplitter {
    private static final int MIN_CHUNK_SIZE = 1 << 9;
    private static final int MAX_CHUNK_SIZE = 1 << 14;
    private static final int WINDOW_SIZE = 67;

    /**
     * First in-chunk byte position whose value can influence a boundary decision: {@code
     * MIN_CHUNK_SIZE - WINDOW_SIZE = 445}. The first boundary check is at offset {@code
     * MIN_CHUNK_SIZE}, where the windowed {@link BuzHash} reflects only bytes {@code
     * [MIN_CHUNK_SIZE - WINDOW_SIZE, MIN_CHUNK_SIZE)}; bytes before that roll out of the window
     * before any check, so hashing them is provably wasted work (chunker-throughput D-4).
     */
    private static final int HASH_FROM = MIN_CHUNK_SIZE - WINDOW_SIZE;

    private final BuzHash bz;
    private final long salt;
    private int offset;
    private boolean crossedBoundary;

    /**
     * Reusable scratch for {@link #hashSegment} — a per-Chunker splitter is single-threaded, so one
     * buffer can be reused across every key/value (grown on demand). Lets the rolling hash read
     * bytes from a heap array (plain loads) after ONE bulk copy, instead of per-byte {@code
     * MemorySegment.get} — a CPU flame attributed ~42% of a document write's Java CPU to the
     * per-element {@code isAlignedForElement} check on that get (an upstream performance-bottleneck
     * plan, D-1, 2026-06-10). Keys/values are small, so it starts at 256 and rarely grows.
     */
    private byte[] scratch = new byte[256];

    /**
     * Cached per-level salts. The salt is a SHA-512 of the level byte; computing it lazily once per
     * level avoids re-hashing for every Chunker.
     */
    private static final long[] SALT_CACHE = new long[256];

    private static final boolean[] SALT_CACHED = new boolean[256];

    public RollingHashSplitter(int level) {
        this.bz = new BuzHash(WINDOW_SIZE);
        this.salt = saltFromLevel(level);
        reset();
    }

    public static long saltFromLevel(int level) {
        int key = level & 0xFF;
        if (SALT_CACHED[key]) return SALT_CACHE[key];
        long s = computeSalt(level);
        SALT_CACHE[key] = s;
        SALT_CACHED[key] = true;
        return s;
    }

    private static long computeSalt(int level) {
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            byte[] full = sha512.digest(new byte[] {(byte) level});
            return ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void append(MemorySegment key, MemorySegment value) {
        hashSegment(key);
        hashSegment(value);
    }

    /**
     * Feeds one segment's bytes through the rolling hash, <b>skipping the provably-wasted min-chunk
     * prefix</b> (chunker-throughput Step 4, the headline work-elimination lever). Bytes at
     * in-chunk position {@code < HASH_FROM} can never affect a boundary check, so a
     * fully-pre-threshold segment is a pure {@code offset} bump (no bulk copy, no per-byte loop),
     * and a straddling segment copies/hashes only its {@code [HASH_FROM, end)} tail. From {@code
     * HASH_FROM} on, the hash state — and therefore every boundary — is byte-identical to hashing
     * from zero (proof: D-4; pinned by {@code ChunkerDeterminismGateTest}).
     */
    private void hashSegment(MemorySegment segment) {
        if (segment == null) return;
        int size = (int) segment.byteSize();
        if (size == 0) return;

        int start = HASH_FROM - offset; // leading bytes still below the boundary-relevant threshold
        if (start >= size) {
            offset += size; // whole segment is pre-threshold: advance the counter, hash nothing
            return;
        }
        if (start < 0) start = 0; // already at/past the threshold: hash the whole segment
        offset += start; // skip the wasted prefix without hashing it (it has rolled out by MIN)

        int tail = size - start;
        if (scratch.length < tail) {
            scratch = new byte[Integer.highestOneBit(tail - 1) << 1];
        }
        MemorySegment.copy(
                segment, ValueLayout.JAVA_BYTE, start, scratch, 0, tail); // copy only tail
        for (int i = 0; i < tail; i++) {
            hashByte(scratch[i]); // plain heap-array loads — no per-element alignment check
        }
    }

    private void hashByte(byte b) {
        offset++;
        if (crossedBoundary) return;
        bz.hashByte((byte) ((long) Byte.toUnsignedInt(b) ^ salt));

        if (offset < MIN_CHUNK_SIZE) return;
        if (offset >= MAX_CHUNK_SIZE) {
            crossedBoundary = true;
            return;
        }
        int hash = bz.sum32();
        int patt = rollingHashPattern(offset);
        if ((hash & patt) == patt) crossedBoundary = true;
    }

    @Override
    public int offset() {
        return offset;
    }

    @Override
    public boolean crossedBoundary() {
        return crossedBoundary;
    }

    @Override
    public void reset() {
        this.offset = 0;
        this.crossedBoundary = false;
        this.bz.reset();
    }

    private int rollingHashPattern(int offset) {
        int shift = 15 - (offset >> 10);
        return (1 << shift) - 1;
    }
}
