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
package com.earasoft.prolly.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Coverage for {@link SyncPackCodec} — the SyncPack binary wire codec. */
class SyncPackCodecTest {

    private static byte[] hash(int seed) {
        byte[] h = new byte[20];
        h[0] = (byte) seed;
        return h;
    }

    @Test
    void a_pack_round_trips_through_the_codec() {
        SyncPack pack =
                new SyncPack(
                        List.of(new byte[] {1, 2, 3}, new byte[] {}, new byte[] {9, 8, 7, 6, 5}),
                        List.of(
                                new SyncCommitEntry(
                                        Instant.ofEpochSecond(1),
                                        hash(1),
                                        hash(1),
                                        List.of(),
                                        "genesis",
                                        ""),
                                new SyncCommitEntry(
                                        Instant.ofEpochSecond(2),
                                        hash(2),
                                        hash(2),
                                        List.of(hash(1)),
                                        "second",
                                        "")));

        SyncPack back = SyncPackCodec.parse(SyncPackCodec.serialize(pack));

        assertEquals(3, back.chunks().size());
        assertArrayEquals(new byte[] {1, 2, 3}, back.chunks().get(0));
        assertArrayEquals(new byte[] {}, back.chunks().get(1));
        assertArrayEquals(new byte[] {9, 8, 7, 6, 5}, back.chunks().get(2));
        assertEquals(2, back.commits().size());
        assertEquals("genesis", back.commits().get(0).message());
        assertEquals("second", back.commits().get(1).message());
        assertEquals(
                List.of("0100000000000000000000000000000000000000"),
                back.commits().get(1).parentsHex());
    }

    @Test
    void an_empty_pack_round_trips() {
        SyncPack back =
                SyncPackCodec.parse(SyncPackCodec.serialize(new SyncPack(List.of(), List.of())));
        assertTrue(back.isEmpty());
    }

    @Test
    void a_tampered_chunk_is_rejected() {
        byte[] wire =
                SyncPackCodec.serialize(new SyncPack(List.of(new byte[] {1, 2, 3}), List.of()));
        // Layout: [u32 magic][u8 version][u32 count][20-byte hash][u32 len][data...].
        // 4+1+4+20+4 = 33, so byte 33 is data[0].
        wire[33] ^= 0x7f; // corrupt the chunk payload
        assertThrows(IllegalArgumentException.class, () -> SyncPackCodec.parse(wire));
    }

    @Test
    void a_future_protocol_version_is_rejected() {
        byte[] wire = SyncPackCodec.serialize(new SyncPack(List.of(new byte[] {1}), List.of()));
        wire[4] = 99; // the version byte, just past the 4-byte magic
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> SyncPackCodec.parse(wire));
        assertTrue(e.getMessage().contains("version"));
    }

    @Test
    void a_bad_magic_is_rejected() {
        byte[] wire = SyncPackCodec.serialize(new SyncPack(List.of(new byte[] {1}), List.of()));
        wire[0] ^= 0x7f; // corrupt the first magic byte
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> SyncPackCodec.parse(wire));
        assertTrue(e.getMessage().contains("magic"));
    }

    @Test
    void truncated_bytes_are_rejected() {
        byte[] wire =
                SyncPackCodec.serialize(new SyncPack(List.of(new byte[] {1, 2, 3}), List.of()));
        byte[] cut = new byte[wire.length - 5];
        System.arraycopy(wire, 0, cut, 0, cut.length);
        assertThrows(IllegalArgumentException.class, () -> SyncPackCodec.parse(cut));
    }

    // ---- Resource-exhaustion DoS guards (2026-05-28 bug-hunt) ----
    // A tiny body whose untrusted size fields decode to huge values must
    // be rejected with IllegalArgumentException BEFORE allocating — not
    // OutOfMemoryError. The original bug: a 49-byte body whose first 4
    // bytes decoded to ~1.2e9 OOM'd `new ArrayList<>(chunkCount)`, which
    // surfaced as a raw 500 (OutOfMemoryError escapes the codec's catch).

    private static byte[] u32(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /**
     * Prefix a raw probe body with a valid magic + version header so it reaches the inner guards.
     */
    private static byte[] withHeader(byte[] body) {
        byte[] wire = new byte[5 + body.length];
        System.arraycopy(u32(SyncPackCodec.MAGIC), 0, wire, 0, 4);
        wire[4] = SyncPackCodec.PROTOCOL_VERSION;
        System.arraycopy(body, 0, wire, 5, body.length);
        return wire;
    }

    @Test
    void arbitrary_garbage_is_rejected_not_OOM() {
        // The exact repro shape from the bug-hunt probe.
        byte[] garbage =
                "GARBAGE-NOT-A-VALID-PACK-aaaaaaaaaaaaaaaaaaaaaaaa"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThrows(IllegalArgumentException.class, () -> SyncPackCodec.parse(garbage));
    }

    @Test
    void absurd_chunk_count_is_rejected_before_allocating() {
        // After a valid header, chunkCount = Integer.MAX_VALUE, nothing else.
        assertThrows(
                IllegalArgumentException.class,
                () -> SyncPackCodec.parse(withHeader(u32(Integer.MAX_VALUE))));
    }

    @Test
    void absurd_chunk_length_is_rejected_before_allocating() {
        // chunkCount=1, then a 20-byte hash, then length=MAX_VALUE (after the header).
        byte[] body = new byte[28];
        System.arraycopy(u32(1), 0, body, 0, 4); // chunkCount = 1
        System.arraycopy(u32(Integer.MAX_VALUE), 0, body, 24, 4); // length = MAX
        assertThrows(IllegalArgumentException.class, () -> SyncPackCodec.parse(withHeader(body)));
    }

    @Test
    void absurd_commit_section_length_is_rejected_before_allocating() {
        // chunkCount=0, then commitSectionLength=MAX_VALUE (after the header).
        byte[] body = new byte[8];
        System.arraycopy(u32(0), 0, body, 0, 4); // chunkCount = 0
        System.arraycopy(u32(Integer.MAX_VALUE), 0, body, 4, 4); // commitSectionLength = MAX
        assertThrows(IllegalArgumentException.class, () -> SyncPackCodec.parse(withHeader(body)));
    }
}
