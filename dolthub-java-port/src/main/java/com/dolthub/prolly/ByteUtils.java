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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.jspecify.annotations.Nullable;

/**
 * Unsigned lexicographic byte comparison + prefix helpers over {@code MemorySegment}s and {@code
 * byte[]}s — the raw ordering primitive under every key comparison that is not type-aware ({@link
 * TypeCodec} delegates its string/bytes/unsigned cases here).
 *
 * <p>Unsigned matters: Java bytes are signed, so a naive {@code a[i] < b[i]} orders {@code 0x80+}
 * bytes before {@code 0x00} and corrupts every seek past ASCII. {@code increment} produces the
 * smallest non-prefix successor of a key — how a prefix scan's exclusive upper bound is built (null
 * when the key is all {@code 0xFF}: no successor exists, scan to the end). Used across the engine
 * (cursor seeks, diff/merge walkers) and the RDF cardinality estimators.
 */
public class ByteUtils {
    // {@code MemorySegment.mismatch(other)} already compares only the common
    // prefix length and reports the smaller size when one is a prefix of the other,
    // so the previous {@code b.asSlice(0, minLen)} was a gratuitous per-compare slice
    // allocation — a top descent allocator (the upstream triejoin-performance plan,
    // Phase 3, lever 1). Compare against the full segment instead.
    public static int compareUnsigned(MemorySegment a, MemorySegment b) {
        long aLen = a.byteSize();
        long bLen = b.byteSize();
        long mismatch = a.mismatch(b);
        if (mismatch == -1) return 0; // byte-identical, same length
        long minLen = Math.min(aLen, bLen);
        if (mismatch >= minLen) return Long.compare(aLen, bLen); // one is a prefix of the other
        int aVal = Byte.toUnsignedInt(a.get(ValueLayout.JAVA_BYTE, mismatch));
        int bVal = Byte.toUnsignedInt(b.get(ValueLayout.JAVA_BYTE, mismatch));
        return Integer.compare(aVal, bVal);
    }

    public static boolean isPrefix(MemorySegment prefix, MemorySegment target) {
        if (prefix.byteSize() > target.byteSize()) return false;
        long mismatch = prefix.mismatch(target); // no slice; prefix ≤ target by the guard above
        return mismatch == -1 || mismatch == prefix.byteSize();
    }

    public static int compareUnsigned(byte[] a, byte[] b) {
        return compareUnsigned(MemorySegment.ofArray(a), MemorySegment.ofArray(b));
    }

    /**
     * Increments the given byte sequence to the smallest sequence that is lexicographically greater
     * and NOT a prefix of the original.
     *
     * @return the incremented sequence, or {@code null} when {@code data} is all {@code 0xFF} bytes
     *     (no greater non-prefix sequence exists)
     */
    public static byte @Nullable [] increment(byte[] data) {
        for (int i = data.length - 1; i >= 0; i--) {
            int val = Byte.toUnsignedInt(data[i]);
            if (val < 255) {
                byte[] res = new byte[i + 1];
                System.arraycopy(data, 0, res, 0, i);
                res[i] = (byte) (val + 1);
                return res;
            }
        }
        return null;
    }
}
