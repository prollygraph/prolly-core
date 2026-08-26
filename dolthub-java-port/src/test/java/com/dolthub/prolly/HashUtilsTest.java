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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Foundational test for {@link HashUtils}. Content addressing breaks if any of these drift, so we
 * test every path: every public method, every boundary (empty, 1 byte, large), determinism, hex
 * round-trip, thread safety.
 */
class HashUtilsTest {

    // ---- hash(byte[]) ----

    @Test
    void hash_returns_20_bytes_for_any_input() {
        assertEquals(20, HashUtils.hash(new byte[0]).length);
        assertEquals(20, HashUtils.hash(new byte[] {0x42}).length);
        assertEquals(20, HashUtils.hash(new byte[1024]).length);
        assertEquals(20, HashUtils.hash(new byte[1024 * 1024]).length);
    }

    @Test
    void hash_is_deterministic() {
        byte[] data = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        byte[] h1 = HashUtils.hash(data);
        byte[] h2 = HashUtils.hash(data);
        assertArrayEquals(h1, h2);
    }

    @Test
    void hash_differs_on_one_bit_flip() {
        byte[] a = new byte[16];
        byte[] b = new byte[16];
        b[0] = 0x01;
        assertFalse(
                java.util.Arrays.equals(HashUtils.hash(a), HashUtils.hash(b)),
                "single-bit input change must produce different hash");
    }

    @Test
    void hash_of_empty_matches_known_sha512_prefix() {
        // SHA-512 of empty input, first 20 bytes:
        // cf83e1357eefb8bd f1542850d66d8007 d620e4050b5715dc
        // 83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e
        // → first 20 bytes = cf83e1357eefb8bdf1542850d66d8007d620e405
        byte[] h = HashUtils.hash(new byte[0]);
        String expected = "cf83e1357eefb8bdf1542850d66d8007d620e405";
        assertEquals(
                expected,
                HashUtils.toHex(h),
                "empty-input hash must match the well-known SHA-512/20 vector");
    }

    @Test
    void hash_is_thread_safe() throws Exception {
        // Run 20 concurrent hashes of the same data; all must agree.
        byte[] data = "shared input".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> first = new AtomicReference<>();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[20];
        for (int i = 0; i < futures.length; i++) {
            futures[i] =
                    CompletableFuture.runAsync(
                            () -> {
                                byte[] h = HashUtils.hash(data);
                                first.compareAndSet(null, h);
                                assertArrayEquals(first.get(), h);
                            });
        }
        CompletableFuture.allOf(futures).join();
    }

    // ---- hash(ByteBuffer) ----

    @Test
    void hash_bytebuffer_matches_byte_array_hash() {
        byte[] data = "consistency check".getBytes(StandardCharsets.UTF_8);
        byte[] viaArray = HashUtils.hash(data);
        byte[] viaBuffer = HashUtils.hash(ByteBuffer.wrap(data));
        assertArrayEquals(viaArray, viaBuffer);
    }

    @Test
    void hash_bytebuffer_preserves_caller_position() {
        byte[] data = "preserves position".getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.wrap(data);
        int posBefore = bb.position();
        int limitBefore = bb.limit();
        HashUtils.hash(bb);
        assertEquals(
                posBefore, bb.position(), "hash(ByteBuffer) must not advance caller's position");
        assertEquals(limitBefore, bb.limit(), "hash(ByteBuffer) must not change caller's limit");
    }

    @Test
    void hash_bytebuffer_respects_position_and_limit() {
        // Hash only [5..15) of a 20-byte buffer.
        byte[] full = new byte[20];
        for (int i = 0; i < 20; i++) full[i] = (byte) i;
        ByteBuffer bb = ByteBuffer.wrap(full);
        bb.position(5).limit(15);
        byte[] viaSlice = HashUtils.hash(bb);
        // Compare against hashing the explicit slice.
        byte[] expected = HashUtils.hash(java.util.Arrays.copyOfRange(full, 5, 15));
        assertArrayEquals(expected, viaSlice);
    }

    // ---- toHex ----

    @Test
    void toHex_lowercase() {
        assertEquals("00ff80", HashUtils.toHex(new byte[] {0x00, (byte) 0xFF, (byte) 0x80}));
    }

    @Test
    void toHex_of_empty_is_empty_string() {
        assertEquals("", HashUtils.toHex(new byte[0]));
    }

    @Test
    void toHex_of_null_returns_literal_null() {
        assertEquals("null", HashUtils.toHex(null));
    }

    @Test
    void toHex_zero_padded_two_chars_per_byte() {
        byte[] bytes = new byte[] {0x01, 0x0A, (byte) 0xA0};
        assertEquals("010aa0", HashUtils.toHex(bytes));
    }

    // ---- fromHex ----

    @Test
    void fromHex_roundtrips_with_toHex() {
        byte[] original = new byte[256];
        for (int i = 0; i < 256; i++) original[i] = (byte) i;
        String hex = HashUtils.toHex(original);
        byte[] decoded = HashUtils.fromHex(hex);
        assertArrayEquals(original, decoded);
    }

    @Test
    void fromHex_empty_string_returns_empty_array() {
        assertArrayEquals(new byte[0], HashUtils.fromHex(""));
    }

    @Test
    void fromHex_odd_length_throws() {
        assertThrows(IllegalArgumentException.class, () -> HashUtils.fromHex("abc"));
        assertThrows(IllegalArgumentException.class, () -> HashUtils.fromHex("a"));
    }

    @Test
    void fromHex_invalid_characters_throws() {
        // Non-hex chars produce NumberFormatException via Integer.parseInt.
        assertThrows(NumberFormatException.class, () -> HashUtils.fromHex("zz"));
        assertThrows(NumberFormatException.class, () -> HashUtils.fromHex("gh"));
    }

    /**
     * A hex parser for a CONTENT-ADDRESSED store must be strict, and these three inputs prove the
     * old one was not. It decoded byte-by-byte with {@code Integer.parseInt(_, 16)}, which accepts
     * a sign prefix and any Unicode digit {@code Character.digit} recognises — so {@code "-1"}
     * became {@code 0xFF}, {@code "+f"} became {@code 0x0F}, and the Arabic-Indic digits {@code
     * "٩٩"} decoded to the SAME bytes as the ASCII {@code "99"}.
     *
     * <p>That last one is the dangerous shape: two distinct strings naming one chunk is hash
     * aliasing, in the one component whose entire premise is that the hash IS the identity. And
     * this is not a theoretical input — {@code SyncPackCodec} parses commit ids, meta-tree hashes
     * and parent hashes straight out of a REMOTE sync pack, and the playground service parses
     * HTTP-supplied hex. Malformed wire data must raise, not silently decode to something else.
     */
    @Test
    void fromHex_rejects_a_sign_prefix() {
        assertThrows(IllegalArgumentException.class, () -> HashUtils.fromHex("-1"));
        assertThrows(IllegalArgumentException.class, () -> HashUtils.fromHex("+f"));
    }

    @Test
    void fromHex_rejects_non_ascii_digits() {
        assertThrows(IllegalArgumentException.class, () -> HashUtils.fromHex("\u0669\u0669"));
    }

    /**
     * The general invariant the two tests above are instances of: {@code fromHex} is INJECTIVE, so
     * whatever it accepts must render back to itself. Stated as a round-trip rather than a list of
     * rejections, this catches an aliasing input nobody thought to enumerate: if {@code fromHex(s)}
     * succeeds and {@code toHex} of the result is not {@code s} (case aside), then {@code s} and
     * that rendering are two different strings for one chunk, which is the bug regardless of which
     * exotic character produced it.
     */
    @Test
    void every_string_fromHex_accepts_renders_back_to_itself() {
        for (String candidate :
                List.of("99", "-1", "+f", "\u0669\u0669", "0x99", " 99", "99 ", "\u06f9\u06f9")) {
            byte[] decoded;
            try {
                decoded = HashUtils.fromHex(candidate);
            } catch (IllegalArgumentException refused) {
                continue; // Refusing is always a correct answer here; aliasing is not.
            }
            assertEquals(
                    candidate.toLowerCase(java.util.Locale.ROOT),
                    HashUtils.toHex(decoded),
                    "fromHex accepted '"
                            + candidate
                            + "' but it renders back as '"
                            + HashUtils.toHex(decoded)
                            + "' \u2014 two distinct strings now name one chunk");
        }
    }

    @Test
    void fromHex_accepts_uppercase() {
        // Integer.parseInt(_, 16) accepts mixed case — pin the existing behavior.
        assertArrayEquals(new byte[] {(byte) 0xAB}, HashUtils.fromHex("AB"));
        assertArrayEquals(new byte[] {(byte) 0xab}, HashUtils.fromHex("ab"));
    }

    // ---- Collision properties ----

    @Test
    void hash_no_collisions_on_small_distinct_inputs() {
        // 1024 small distinct byte strings → 1024 unique hashes (SHA-512 holds easily).
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 1024; i++) {
            byte[] data = ByteBuffer.allocate(4).putInt(i).array();
            String hex = HashUtils.toHex(HashUtils.hash(data));
            assertTrue(hashes.add(hex), "unexpected collision at i=" + i);
        }
        assertEquals(1024, hashes.size());
    }
}
