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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard unsigned varint (LEB128-style, Go {@code encoding/binary} compatible) encode/decode —
 * the wire form of the <b>subtree-count vector</b> in every internal tree node.
 *
 * <p>Its two real call sites are the two sides of that vector: {@link FlatbufferNodeSerializer}
 * encodes the per-child counts at node build, and {@link Node} decodes them ({@code
 * getUvarintAt}/prefix-sum) to answer {@code getSubtreeCount} — the ordinal/count machinery
 * documented there. Counts are size-proportional to fan-out, so the variable-length form keeps
 * internal nodes small.
 */
public class Varints {

    public static byte[] encodeVarints(List<Long> ints) {
        ByteBuffer bb = ByteBuffer.allocate(ints.size() * 10);
        for (long val : ints) {
            putUvarint(bb, val);
        }
        byte[] result = new byte[bb.position()];
        bb.flip();
        bb.get(result);
        return result;
    }

    public static List<Long> decodeVarints(byte[] buf, int count) {
        ByteBuffer bb = ByteBuffer.wrap(buf);
        List<Long> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(getUvarint(bb));
        }
        return result;
    }

    public static void putUvarint(ByteBuffer bb, long v) {
        while (Long.compareUnsigned(v, 0x80L) >= 0) {
            bb.put((byte) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        bb.put((byte) v);
    }

    public static long getUvarint(ByteBuffer bb) {
        long x = 0;
        int s = 0;
        // A 64-bit unsigned varint is at most 10 bytes (ceil(64/7)). Cap the loop and reject an
        // 11th
        // continuation byte with a clear exception, instead of looping unbounded until a confusing
        // BufferUnderflowException — and the bound also closes the `s += 7` shift, which is
        // undefined
        // once s passes 63. Valid varints (<= 10 bytes) are unchanged. Fail-closed;
        // core-fail-closed-bounds D-2.
        for (int i = 0; i < 10; i++) {
            byte b = bb.get();
            if (Byte.toUnsignedInt(b) < 0x80) {
                return x | ((long) (b & 0x7F) << s);
            }
            x |= (long) (b & 0x7F) << s;
            s += 7;
        }
        throw new IllegalArgumentException("varint exceeds 10 bytes");
    }

    /** Returns the prefix sum of the first (index + 1) varints. */
    public static long getUvarintAt(ByteBuffer bb, int index) {
        bb.mark();
        long sum = 0;
        for (int i = 0; i <= index; i++) {
            sum += getUvarint(bb);
        }
        bb.reset();
        return sum;
    }
}
