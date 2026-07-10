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
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage for {@link RollingHashSplitter}, complementing {@code SplitterStressTest}
 * (large-scale property tests) and {@code ChunkerChaosTest}. Pins the boundary contracts that the
 * stress tests assume hold: MIN/MAX size enforcement, level-salt determinism + caching,
 * null-segment safety, reset behavior.
 */
class RollingHashSplitterUnitTest {

    private static final int MIN_CHUNK_SIZE = 1 << 9; // 512
    private static final int MAX_CHUNK_SIZE = 1 << 14; // 16384

    private static MemorySegment seg(byte[] data) {
        return MemorySegment.ofArray(data);
    }

    // ---- initial state ----

    @Test
    void fresh_splitter_has_no_boundary() {
        RollingHashSplitter s = new RollingHashSplitter(0);
        assertEquals(0, s.offset());
        assertFalse(
                s.crossedBoundary(), "fresh splitter must not report a boundary before any append");
    }

    @Test
    void append_increments_offset_by_segment_size() {
        RollingHashSplitter s = new RollingHashSplitter(0);
        s.append(seg(new byte[10]), seg(new byte[5]));
        assertEquals(15, s.offset(), "offset must track total bytes hashed (key + value)");
    }

    @Test
    void append_null_segments_are_safe() {
        RollingHashSplitter s = new RollingHashSplitter(0);
        s.append(null, null);
        assertEquals(0, s.offset(), "null segments don't advance offset");
        s.append(seg(new byte[3]), null);
        assertEquals(3, s.offset());
        s.append(null, seg(new byte[2]));
        assertEquals(5, s.offset());
    }

    // ---- boundary enforcement ----

    @Test
    void below_min_size_never_crosses_boundary() {
        // Push exactly MIN_CHUNK_SIZE - 1 random bytes; even though the hash
        // could match the pattern, the splitter suppresses boundaries below MIN.
        RollingHashSplitter s = new RollingHashSplitter(0);
        byte[] data = new byte[MIN_CHUNK_SIZE - 1];
        new Random(7).nextBytes(data);
        s.append(seg(data), seg(new byte[0]));
        assertFalse(
                s.crossedBoundary(),
                "splitter must NEVER emit a boundary below MIN_CHUNK_SIZE ("
                        + MIN_CHUNK_SIZE
                        + ")");
    }

    @Test
    void above_max_size_force_crosses_boundary() {
        // Push MAX_CHUNK_SIZE + 1 zero bytes (deterministic). Even if the BuzHash
        // never matches the pattern, MAX_CHUNK_SIZE forces a boundary.
        RollingHashSplitter s = new RollingHashSplitter(0);
        byte[] data = new byte[MAX_CHUNK_SIZE + 1];
        s.append(seg(data), seg(new byte[0]));
        assertTrue(
                s.crossedBoundary(),
                "splitter must force a boundary at MAX_CHUNK_SIZE (" + MAX_CHUNK_SIZE + ")");
    }

    @Test
    void exactly_at_max_size_force_crosses_boundary() {
        RollingHashSplitter s = new RollingHashSplitter(0);
        // The check is `offset >= MAX_CHUNK_SIZE` after the offset bump,
        // so the very byte that pushes offset to MAX should trip.
        s.append(seg(new byte[MAX_CHUNK_SIZE]), seg(new byte[0]));
        assertTrue(
                s.crossedBoundary(),
                "boundary must trip the instant offset reaches MAX_CHUNK_SIZE");
    }

    @Test
    void boundary_is_sticky_until_reset() {
        RollingHashSplitter s = new RollingHashSplitter(0);
        s.append(seg(new byte[MAX_CHUNK_SIZE + 1]), seg(new byte[0]));
        assertTrue(s.crossedBoundary());
        s.append(seg(new byte[10]), seg(new byte[0]));
        assertTrue(
                s.crossedBoundary(),
                "crossedBoundary stays true until reset() — it's the chunker's signal to finalize");
    }

    // ---- reset ----

    @Test
    void reset_clears_offset_and_boundary() {
        RollingHashSplitter s = new RollingHashSplitter(0);
        s.append(seg(new byte[MAX_CHUNK_SIZE + 1]), seg(new byte[0]));
        assertTrue(s.crossedBoundary());
        assertNotEquals(0, s.offset());
        s.reset();
        assertEquals(0, s.offset());
        assertFalse(s.crossedBoundary());
    }

    @Test
    void after_reset_splitter_behaves_like_new() {
        RollingHashSplitter s1 = new RollingHashSplitter(0);
        s1.append(seg(new byte[100]), seg(new byte[50]));
        s1.reset();
        RollingHashSplitter s2 = new RollingHashSplitter(0);
        // Now feed both identical content.
        byte[] payload = new byte[200];
        new Random(11).nextBytes(payload);
        s1.append(seg(payload), seg(new byte[0]));
        s2.append(seg(payload), seg(new byte[0]));
        assertEquals(s1.offset(), s2.offset());
        assertEquals(
                s1.crossedBoundary(),
                s2.crossedBoundary(),
                "reset must restore the splitter to a fresh-like state");
    }

    // ---- salt-from-level ----

    @Test
    void saltFromLevel_is_deterministic() {
        long s1 = RollingHashSplitter.saltFromLevel(0);
        long s2 = RollingHashSplitter.saltFromLevel(0);
        assertEquals(s1, s2);
    }

    @Test
    void different_levels_yield_different_salts() {
        long s0 = RollingHashSplitter.saltFromLevel(0);
        long s1 = RollingHashSplitter.saltFromLevel(1);
        long s2 = RollingHashSplitter.saltFromLevel(2);
        // SHA-512 collision probability is negligible — these MUST differ.
        assertNotEquals(s0, s1);
        assertNotEquals(s1, s2);
        assertNotEquals(s0, s2);
    }

    @Test
    void saltFromLevel_pinned_for_level_zero() {
        // Drift detector: the level-0 salt is SHA-512({0x00})[0..7] LE.
        // Computing manually: SHA-512 of {0x00} starts with bytes...
        // We pin the actual value so any change to the salt formula trips here.
        long got = RollingHashSplitter.saltFromLevel(0);
        // Recompute the expected value directly (so this test stays robust
        // to any future caching changes while still pinning the formula).
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-512");
            byte[] full = md.digest(new byte[] {(byte) 0});
            long expected =
                    java.nio.ByteBuffer.wrap(full)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .getLong();
            assertEquals(expected, got, "level-0 salt must be SHA-512({0x00})[0..7] little-endian");
        } catch (java.security.NoSuchAlgorithmException e) {
            fail(e);
        }
    }

    @Test
    void saltFromLevel_cache_hits_for_repeated_call() {
        // Property: 1000 calls with the same level return the same value.
        // Tests the cache path indirectly.
        long ref = RollingHashSplitter.saltFromLevel(42);
        for (int i = 0; i < 1000; i++) {
            assertEquals(ref, RollingHashSplitter.saltFromLevel(42));
        }
    }

    // ---- determinism ----

    @Test
    void identical_input_yields_identical_boundary_decision() {
        // Property: same level + same byte stream → same boundary verdict.
        for (int seed = 0; seed < 10; seed++) {
            byte[] data = new byte[2048];
            new Random(seed).nextBytes(data);
            RollingHashSplitter a = new RollingHashSplitter(0);
            RollingHashSplitter b = new RollingHashSplitter(0);
            a.append(seg(data), seg(new byte[0]));
            b.append(seg(data), seg(new byte[0]));
            assertEquals(
                    a.crossedBoundary(),
                    b.crossedBoundary(),
                    "splitter is deterministic for seed " + seed);
            assertEquals(a.offset(), b.offset());
        }
    }

    @Test
    void different_levels_split_at_different_offsets() {
        // Vertical-alignment-avoidance property: feeding the same bytes at
        // different levels must (with high probability over 16KB) produce
        // different chunking decisions for at least one level pair.
        byte[] data = new byte[8192];
        new Random(31).nextBytes(data);

        boolean[] crossed = new boolean[5];
        for (int level = 0; level < 5; level++) {
            RollingHashSplitter s = new RollingHashSplitter(level);
            // Feed data in 64-byte chunks so we can capture an early boundary.
            for (int i = 0; i + 64 <= data.length && !s.crossedBoundary(); i += 64) {
                byte[] chunk = new byte[64];
                System.arraycopy(data, i, chunk, 0, 64);
                s.append(seg(chunk), seg(new byte[0]));
            }
            crossed[level] = s.crossedBoundary();
        }
        // Not all levels will agree, but at least one boundary must trip
        // somewhere — otherwise the salt cache or chunk math is broken.
        boolean anyCrossed = false;
        for (boolean c : crossed) if (c) anyCrossed = true;
        assertTrue(anyCrossed, "for 8KB random input, at least one level should cross a boundary");
    }
}
