/*
 * Copyright 2026 Earasoft
 * Copyright 2021 Dolthub, Inc.
 *
 * Derived from Dolt's design, adapted for Java by Earasoft.
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

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The one place content-address hashes are computed and rendered: {@code hash(bytes)} is the
 * 20-byte truncated SHA-512 digest (Dolt's content address — see {@link HashAlgorithm} for the
 * agility story), {@code toHex} the lowercase-hex rendering used as map/store keys.
 *
 * <p>Every content address in the system — node hashes, commit ids, store keys — funnels through
 * here (115 referencing files as of 2026-07-02), which is exactly the point: hashing semantics live
 * in one place, so a deliberate algorithm change is one edit plus the format-version bump, never a
 * hunt.
 *
 * @implNote The {@link java.security.MessageDigest} is held in a {@code ThreadLocal} (instantiation
 *     is expensive; hashing is per-node-write hot). {@code toHex} is a manual hex loop, not {@code
 *     String.format} — the format path allocated a Formatter + Matcher per byte on the descent hot
 *     path (see the method doc). Algorithm name + truncation length come from {@link
 *     HashAlgorithm#CURRENT}, not literals (core-format-versioning D-4).
 */
public class HashUtils {
    // Algorithm name + truncation length come from HashAlgorithm.CURRENT (core-format-versioning
    // D-4), so a future hash change is one enum value, not scattered "SHA-512"/20 literals.
    private static final ThreadLocal<MessageDigest> DIGEST =
            ThreadLocal.withInitial(HashUtils::newDigest);

    private static MessageDigest newDigest() {
        String algorithm = HashAlgorithm.CURRENT.messageDigestAlgorithm();
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException first) {
            // The BLAKE candidates live in the BouncyCastle provider, which is test-scope
            // (hash-function study D-2): register it reflectively — no compile dependency —
            // and retry once. A JDK algorithm never reaches this path.
            try {
                var providerClass =
                        Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider");
                java.security.Security.addProvider(
                        (java.security.Provider)
                                providerClass.getDeclaredConstructor().newInstance());
                return MessageDigest.getInstance(algorithm);
            } catch (ReflectiveOperationException | NoSuchAlgorithmException second) {
                throw new IllegalStateException(
                        "hash algorithm '"
                                + algorithm
                                + "' unavailable — the BLAKE candidates need BouncyCastle on the"
                                + " classpath (test-scope; hash-function study D-2)",
                        second);
            }
        }
    }

    public static byte[] hash(byte[] data) {
        MessageDigest md = DIGEST.get();
        md.reset();
        byte[] full = md.digest(data);
        return Arrays.copyOfRange(full, 0, HashAlgorithm.CURRENT.length());
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * Renders a hash (or any byte sequence) as a lowercase hex string.
     *
     * <p>Manual hex loop, not {@code String.format("%02x", b)} per byte: the format path allocated
     * a {@code Formatter} + regex {@code Matcher} per byte and was a top hot-path allocator (called
     * per node-read during prolly-tree descent — see the upstream triejoin-performance plan, Phase
     * 3). Same lowercase-hex output; {@link #fromHex} round-trips it.
     */
    public static String toHex(byte[] bytes) {
        if (bytes == null) return "null";
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0xF];
        }
        return new String(out);
    }

    /**
     * Parses a hex string back into bytes, STRICTLY. Throws on odd length or any character outside
     * {@code [0-9a-fA-F]}.
     *
     * <p>Strictness is the point, and the previous implementation did not have it. Decoding
     * byte-by-byte with {@code Integer.parseInt(_, 16)} inherits two behaviours nobody wants in a
     * hash parser: it accepts a sign prefix, and it accepts any Unicode digit {@code
     * Character.digit} recognises. So {@code "-1"} decoded to {@code 0xFF}, {@code "+f"} to {@code
     * 0x0F}, and the Arabic-Indic digits {@code "٩٩"} to the same bytes as the ASCII {@code "99"} —
     * two distinct strings naming one chunk, in the component whose whole premise is that the hash
     * IS the identity. {@code SyncPackCodec} feeds this straight from a remote sync pack, so the
     * malformed input was arriving from off-machine.
     *
     * <p>{@code HexFormat.parseHex} is ASCII-only and sign-free, and it also drops the per-byte
     * {@code String} allocation the old loop paid — the same allocation {@link #toHex}'s comment
     * above records removing from the descent path.
     *
     * @throws IllegalArgumentException on odd length, or {@code NumberFormatException} (a subclass)
     *     on a non-hex character — the same types the old implementation threw for those cases.
     */
    public static byte[] fromHex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    /**
     * Computes SHA-512/20 of the buffer's remaining bytes. The caller's buffer position is
     * preserved — internally we hash a duplicate so that {@code md.update} cannot consume the
     * original.
     */
    public static byte[] hash(ByteBuffer data) {
        MessageDigest md = DIGEST.get();
        md.reset();
        md.update(data.duplicate());
        byte[] full = md.digest();
        return Arrays.copyOfRange(full, 0, HashAlgorithm.CURRENT.length());
    }
}
