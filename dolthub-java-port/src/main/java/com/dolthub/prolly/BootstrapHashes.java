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
 *
 *
 * <h3>Bootstrap Hashes — Cross-Module Bit-Compat Pins</h3>
 *
 * <p>Pinned values produced by the deterministic corpora used in port-core's golden-vector tests.
 * Lives in main code so other modules (e.g. upstream cross-language fixture tests) can reference
 * the same constants without a test-jar dance.
 *
 * <p>If any of these change, EVERY DOWNSTREAM CONSUMER must update too — a change here implies the
 * on-disk format or hashing semantics drifted. Update protocol (only for a <i>deliberate</i> format
 * change): run {@code ChunkerDeterminismGateTest} — it recomputes these values through the
 * production splitter/mutator, and its assertion failure prints the recomputed actual values; paste
 * them in, then run all tests in both modules.
 */
public final class BootstrapHashes {
    private BootstrapHashes() {}

    /**
     * SHA-512/20 root hash of the deterministic 1000-tuple Prolly Tree built from corpus {@code
     * golden-NNNNN → payload-N}. Recomputed + asserted by {@code ChunkerDeterminismGateTest}'s
     * root-hash arm and consumed by {@code CrossLanguageFixtureTest}'s bit-compat oracle 4.
     *
     * <p>Re-pinned 2026-06-27 for ADR-0072 (the node {@code [PNOD][version]} header changed every
     * node's bytes → every hash). Was {@code 1d9d81f40033ea3955bb85048704cb1fa53f710a}
     * (pre-header); the value below is the post-header root, verified after round-trip correctness
     * was confirmed (the re-pin characterizes the new format, it does not define it).
     */
    public static final byte[] BOUNDARY_GOLDEN_ROOT =
            new byte[] {
                (byte) 0xf5, (byte) 0x3f, (byte) 0x09, (byte) 0xdb,
                (byte) 0xe1, (byte) 0xef, (byte) 0x87, (byte) 0x59,
                (byte) 0x08, (byte) 0xff, (byte) 0xec, (byte) 0xbc,
                (byte) 0xb8, (byte) 0xfd, (byte) 0x54, (byte) 0x6f,
                (byte) 0xed, (byte) 0x08, (byte) 0x00, (byte) 0xc2
            };

    /**
     * BuzHash boundary offsets for the deterministic 64KB byte stream (seed {@code 0xCAFEBABEL}).
     * Recomputed + asserted by {@code ChunkerDeterminismGateTest}'s boundary-differential arm.
     */
    public static final int[] BOUNDARY_BUZHASH_OFFSETS =
            new int[] {
                4554, 8331, 15448, 20078, 24601, 30719, 36658, 41997, 45585, 50810, 52643, 59311,
                62078
            };
}
