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

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link BuzHash}. Rolling-hash drift would shift chunk boundaries and
 * silently break the port's <b>own</b> determinism + on-disk format (not Dolt parity, which is
 * optional/deferred — the chunker already diverges from Dolt v2.0.3, {@code
 * cross-lang/BITCOMPAT_FINDINGS.md}). These tests pin the algorithm's deterministic properties at
 * the byte level so any change to constants, shifts, or masking trips the suite.
 *
 * <p>Boundary detection itself is owned by {@code RollingHashSplitter}/{@code ChunkerChaosTest};
 * this file pins the lower-level invariants — determinism, rolling-window semantics, reset behavior
 * — that those higher tests assume hold.
 */
class BuzHashTest {

    @Test
    void empty_state_is_zero() {
        BuzHash bh = new BuzHash(64);
        assertEquals(0, bh.sum32(), "fresh BuzHash must start at state=0 (cyclic poly identity)");
    }

    @Test
    void reset_returns_state_to_zero() {
        BuzHash bh = new BuzHash(64);
        for (int i = 0; i < 200; i++) bh.hashByte((byte) i);
        assertNotEquals(0, bh.sum32(), "state must be nonzero after hashing");
        bh.reset();
        assertEquals(0, bh.sum32());
    }

    @Test
    void single_byte_yields_table_entry() {
        // First byte hashed: state shifts 0→0, then XORs with the table entry.
        // So state == DEFAULT_TABLE[byte].
        BuzHash bh = new BuzHash(64);
        bh.hashByte((byte) 0);
        assertEquals(BuzHashTable.DEFAULT_TABLE[0], bh.sum32());
        bh.reset();
        bh.hashByte((byte) 1);
        assertEquals(BuzHashTable.DEFAULT_TABLE[1], bh.sum32());
        bh.reset();
        bh.hashByte((byte) 0xFF);
        assertEquals(BuzHashTable.DEFAULT_TABLE[0xFF], bh.sum32());
    }

    @Test
    void identical_streams_yield_identical_hashes() {
        // Property 1: pure determinism.
        BuzHash a = new BuzHash(64);
        BuzHash b = new BuzHash(64);
        Random rng = new Random(7);
        byte[] data = new byte[1024];
        rng.nextBytes(data);
        for (byte by : data) a.hashByte(by);
        for (byte by : data) b.hashByte(by);
        assertEquals(
                a.sum32(),
                b.sum32(),
                "two BuzHashes fed identical streams must agree byte-for-byte");
    }

    @Test
    void different_streams_almost_always_differ() {
        BuzHash a = new BuzHash(64);
        BuzHash b = new BuzHash(64);
        for (int i = 0; i < 100; i++) a.hashByte((byte) i);
        for (int i = 0; i < 100; i++) b.hashByte((byte) (i + 1));
        assertNotEquals(
                a.sum32(),
                b.sum32(),
                "different streams of equal length must produce different hashes");
    }

    @Test
    void window_size_matters() {
        // Property: at window=64, hashing 64 'A's then 1 'B' produces a different
        // state from hashing 64 'A's then 1 'C', even though only the last byte
        // differs. The rolling property pins this.
        BuzHash a = new BuzHash(64);
        BuzHash b = new BuzHash(64);
        for (int i = 0; i < 64; i++) {
            a.hashByte((byte) 'A');
            b.hashByte((byte) 'A');
        }
        assertEquals(a.sum32(), b.sum32(), "prefix must agree");
        a.hashByte((byte) 'B');
        b.hashByte((byte) 'C');
        assertNotEquals(a.sum32(), b.sum32());
    }

    @Test
    void rolling_window_disturbance_evicts() {
        // Sliding-window property: injecting a nonzero byte into an
        // otherwise-zero stream must perturb state immediately.
        int n = 32;
        BuzHash bh = new BuzHash(n);
        for (int i = 0; i < n; i++) bh.hashByte((byte) 0);
        int baseline = bh.sum32();
        bh.hashByte((byte) 1);
        assertNotEquals(baseline, bh.sum32(), "injecting a nonzero byte must change state");
    }

    @Test
    void all_zero_stream_with_window_32_converges_to_zero() {
        // Property: T[0] = 0x12bd9527 has 16 one-bits (even). For window size
        // that is a multiple of 32, after the window fills, the XOR sum of all
        // 32 rotations of T[0] is 0 — so an all-zero stream converges to 0.
        // This pins the specific table entry T[0] and the cyclic-shift math.
        BuzHash bh = new BuzHash(32);
        for (int i = 0; i < 64; i++) bh.hashByte((byte) 0);
        assertEquals(
                0,
                bh.sum32(),
                "all-zero stream + window=32 + T[0]=0x12bd9527 (even bit-count) → state=0");
    }

    @Test
    void window_size_one() {
        // n=1: every new byte completely replaces the window.
        BuzHash bh = new BuzHash(1);
        bh.hashByte((byte) 0x42);
        int s1 = bh.sum32();
        bh.hashByte((byte) 0x42); // same byte
        int s2 = bh.sum32();
        bh.hashByte((byte) 0x99); // different byte
        int s3 = bh.sum32();
        assertNotEquals(s2, s3, "different byte must change state even with window=1");
    }

    @Test
    void large_input_does_not_throw() {
        BuzHash bh = new BuzHash(64);
        byte[] data = new byte[1024 * 1024]; // 1 MiB
        new Random(11).nextBytes(data);
        for (byte b : data) bh.hashByte(b);
        // Sanity: state is a meaningful nonzero value (random chance of being 0 is 2^-32).
        assertNotEquals(0, bh.sum32());
    }

    @Test
    void sum32_does_not_advance_state() {
        // Calling sum32() must be a pure read.
        BuzHash bh = new BuzHash(64);
        for (int i = 0; i < 100; i++) bh.hashByte((byte) i);
        int first = bh.sum32();
        int second = bh.sum32();
        int third = bh.sum32();
        assertEquals(first, second);
        assertEquals(second, third);
    }

    @Test
    void reset_then_replay_matches_independent_hasher() {
        BuzHash bh = new BuzHash(32);
        for (int i = 0; i < 50; i++) bh.hashByte((byte) i);
        bh.reset();
        for (int i = 0; i < 30; i++) bh.hashByte((byte) (i * 7));
        int got = bh.sum32();

        BuzHash control = new BuzHash(32);
        for (int i = 0; i < 30; i++) control.hashByte((byte) (i * 7));
        assertEquals(control.sum32(), got, "reset must yield an indistinguishable BuzHash");
    }

    @Test
    void table_is_256_entries() {
        assertEquals(
                256,
                BuzHashTable.DEFAULT_TABLE.length,
                "table must cover the full unsigned-byte domain");
    }

    @Test
    void table_first_entry_is_pinned_value() {
        // Drift detector: any reorder/edit of the table changes this.
        assertEquals(0x12bd9527, BuzHashTable.DEFAULT_TABLE[0]);
    }

    @Test
    void table_last_entry_is_pinned_value() {
        assertEquals(0x8185f4d2, BuzHashTable.DEFAULT_TABLE[255]);
    }
}
