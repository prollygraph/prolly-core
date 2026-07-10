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

/**
 * The content-addressing hash algorithm, behind one enum so the {@code "SHA-512"} algorithm name
 * and the 20-byte truncation length live in <b>one place</b> instead of scattered string/int
 * literals ({@code core-format-versioning.md} D-4). A future hash change is then a new enum value +
 * a detectable id tag — not a silent, store-wide reinterpretation of every content address.
 *
 * @apiNote The algorithm is unchanged today (SHA-512 truncated to 20 bytes, Dolt's content
 *     address); this is agility insurance, not a change. The {@link #id()} is recorded in the
 *     store's format marker so a store written with a different algorithm fails closed on open
 *     ({@link UnsupportedFormatException}) — defense in depth against a hash change that forgot to
 *     bump {@link FormatVersion#CORE_FORMAT_VERSION}.
 * @implNote {@link #CURRENT} is the single algorithm this engine writes + verifies with. Adding a
 *     second value is the entire surface a future hash migration touches.
 */
public enum HashAlgorithm {
    /** SHA-512 truncated to its first 20 bytes — the production default (Dolt lineage). */
    SHA512_20((byte) 1, "SHA-512", 20),

    /**
     * SHA-256 truncated to 20 bytes — the hash-function study's JDK-native candidate (cores with
     * SHA-NI compute it in hardware). Selectable via {@code -Dprolly.hash.algorithm=SHA256_20}; not
     * the default.
     */
    SHA256_20((byte) 2, "SHA-256", 20),

    /**
     * BLAKE2b at its native 160-bit output — no truncation. Requires the BouncyCastle provider on
     * the classpath ({@link HashUtils} registers it reflectively on first use); test-scope today.
     */
    BLAKE2B_160((byte) 3, "BLAKE2B-160", 20),

    /**
     * BLAKE3 truncated to 20 bytes. Requires the BouncyCastle provider on the classpath; test-scope
     * today.
     */
    BLAKE3_20((byte) 4, "BLAKE3-256", 20);

    private final byte id;
    private final String messageDigestAlgorithm;
    private final int length;

    HashAlgorithm(byte id, String messageDigestAlgorithm, int length) {
        this.id = id;
        this.messageDigestAlgorithm = messageDigestAlgorithm;
        this.length = length;
    }

    /** The 1-byte on-disk identifier recorded in the store's format marker. */
    public byte id() {
        return id;
    }

    /** The {@link java.security.MessageDigest} algorithm name (e.g. {@code "SHA-512"}). */
    public String messageDigestAlgorithm() {
        return messageDigestAlgorithm;
    }

    /** The content-address length in bytes (the digest is truncated to this). */
    public int length() {
        return length;
    }

    /**
     * The algorithm this engine writes + verifies with in THIS process — selected ONCE at
     * class-init from {@code -Dprolly.hash.algorithm} (an enum name), default {@link #SHA512_20}
     * (the hash-function study's D-1 selection hook). Safe by construction: {@code RocksNodeStore}
     * stamps the algorithm into every new store's format marker and fail-closes on mismatch, so a
     * mis-flagged process cannot silently corrupt an existing store — it refuses to open it.
     */
    public static final HashAlgorithm CURRENT = fromSystemProperty();

    private static HashAlgorithm fromSystemProperty() {
        String name = System.getProperty("prolly.hash.algorithm");
        if (name == null || name.isEmpty()) {
            return SHA512_20;
        }
        return valueOf(name);
    }
}
