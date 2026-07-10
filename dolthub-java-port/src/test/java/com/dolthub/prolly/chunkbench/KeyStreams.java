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

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Deterministic synthetic quad-key streams for the boundary study, in the two shapes the study
 * contrasts:
 *
 * <ul>
 *   <li>{@link #hashedIdKeys} — the TASK'S premise: fixed-width concatenations of hash-derived
 *       128-bit term ids (emulated with SHA-256; entropy-equivalent for distribution purposes),
 *       64-byte quad keys, sorted. Realistic reuse: subjects drawn from a pool (~n/10), predicates
 *       from a small vocabulary (~64), objects mostly unique — a quad stream's actual shape.
 *   <li>{@link #ordinalKeys} — THIS repo's reality: term ids are sequential dictionary ordinals
 *       (8-byte big-endian longs here; the codec's parity encoding preserves order and does not add
 *       entropy), 32-byte quad keys, sorted. The adversarial shape for a low-bits mask.
 * </ul>
 *
 * <p>Everything derives from one {@code seed} (the per-store secret stand-in) — byte-identical
 * streams across runs.
 */
final class KeyStreams {

    private KeyStreams() {}

    /** One flat, sorted key stream: {@code keyWidth}-byte keys back to back. */
    record Stream(byte[] flat, int keyWidth, int count) {
        long totalBytes() {
            return (long) keyWidth * count;
        }
    }

    /** The task-premise stream: 64-byte keys of four hash-derived 16-byte term ids, sorted. */
    static Stream hashedIdKeys(int count, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        int subjectPool = Math.max(4, count / 10);
        int predicatePool = 64;
        byte[][] subjects = idPool(subjectPool, rnd.nextLong());
        byte[][] predicates = idPool(predicatePool, rnd.nextLong());
        byte[][] contexts = idPool(4, rnd.nextLong());

        byte[][] keys = new byte[count][];
        for (int i = 0; i < count; i++) {
            byte[] k = new byte[64];
            System.arraycopy(subjects[rnd.nextInt(subjectPool)], 0, k, 0, 16);
            System.arraycopy(predicates[rnd.nextInt(predicatePool)], 0, k, 16, 16);
            System.arraycopy(hashedId(rnd.nextLong()), 0, k, 32, 16); // object: unique
            System.arraycopy(contexts[rnd.nextInt(4)], 0, k, 48, 16);
            keys[i] = k;
        }
        Arrays.sort(keys, Arrays::compareUnsigned);
        return flatten(keys, 64);
    }

    /** The repo-reality stream: 32-byte keys of four sequential-ordinal 8-byte ids, sorted. */
    static Stream ordinalKeys(int count, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        int subjectPool = Math.max(4, count / 10);
        byte[][] keys = new byte[count][];
        for (int i = 0; i < count; i++) {
            ByteBuffer b = ByteBuffer.allocate(32);
            b.putLong(1_000L + rnd.nextInt(subjectPool)); // subject ordinal
            b.putLong(10L + rnd.nextInt(64)); // predicate ordinal (small vocabulary)
            b.putLong(2_000_000L + i); // object ordinal: assigned in arrival order
            b.putLong(rnd.nextInt(4)); // context ordinal
            keys[i] = b.array();
        }
        Arrays.sort(keys, Arrays::compareUnsigned);
        return flatten(keys, 32);
    }

    private static byte[][] idPool(int size, long seed) {
        byte[][] pool = new byte[size][];
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = 0; i < size; i++) {
            pool[i] = hashedId(rnd.nextLong());
        }
        return pool;
    }

    /** A 128-bit "term id": SHA-256 of the counter, truncated — the hash-derived-id premise. */
    private static byte[] hashedId(long counter) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] full = d.digest(ByteBuffer.allocate(8).putLong(counter).array());
            return Arrays.copyOf(full, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Stream flatten(byte[][] keys, int keyWidth) {
        byte[] flat = new byte[keys.length * keyWidth];
        for (int i = 0; i < keys.length; i++) {
            System.arraycopy(keys[i], 0, flat, i * keyWidth, keyWidth);
        }
        return new Stream(flat, keyWidth, keys.length);
    }
}
