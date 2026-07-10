/*
 * Copyright 2026 Earasoft
 *
 * Java reimplementation of the buzhash rolling-hash algorithm from
 * github.com/kch42/buzhash (MIT). The lookup-table values are taken from
 * that Go reference implementation.
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
 * A cyclic-polynomial rolling hash over a sliding byte window — the boundary signal for
 * content-defined chunking.
 *
 * <p>A rolling hash can add the next byte and drop the oldest in constant time, so the splitter can
 * scan a byte stream and test for a chunk boundary at every position cheaply. Identical byte
 * windows always produce the same hash, which is the basis of the prolly tree's deterministic,
 * content-defined chunk boundaries (and therefore its cross-version structural sharing).
 *
 * @apiNote A faithful port of the kch42/buzhash reference (its 256-entry table is taken from it).
 *     Keep the algorithm + table <b>stable</b>: changing them moves every chunk boundary, which
 *     breaks the port's <b>own</b> internal determinism and its pinned goldens ({@code
 *     ChunkerDeterminismGateTest}). This is <b>not</b> a live cross-language-parity constraint —
 *     the port's chunker diverges from Dolt v2.0.3 and byte-for-byte Dolt parity is
 *     optional/deferred ({@code cross-lang/BITCOMPAT_FINDINGS.md} + the pre-1.0 no-backwards-compat
 *     decision).
 * @implNote Cyclic shifts plus exclusive-or against a fixed 256-entry random table. Used by {@link
 *     RollingHashSplitter}.
 */
public class BuzHash {
    private int state;
    private final byte[] buf;
    private final int n;
    private final int bshiftn;
    private int bufpos;
    private boolean overflow;

    public BuzHash(int n) {
        this.n = n;
        this.bshiftn = n % 32;
        this.buf = new byte[n];
        reset();
    }

    public int hashByte(byte b) {
        if (bufpos == n) {
            overflow = true;
            bufpos = 0;
        }

        int state = this.state;
        // Cyclic shift (chunker-throughput Step 6): Integer.rotateLeft intrinsifies to a single ROL
        // and is bit-identical to the (x<<1)|(x>>>31) idiom by the JLS definition of rotateLeft.
        state = Integer.rotateLeft(state, 1);

        if (overflow) {
            int toshift = BuzHashTable.DEFAULT_TABLE[Byte.toUnsignedInt(buf[bufpos])];
            // rotate-out the byte leaving the window; ==
            // (toshift<<bshiftn)|(toshift>>>(32-bshiftn)).
            state ^= Integer.rotateLeft(toshift, bshiftn);
        }

        buf[bufpos] = b;
        bufpos++;

        state ^= BuzHashTable.DEFAULT_TABLE[Byte.toUnsignedInt(b)];
        this.state = state;
        return state;
    }

    public int sum32() {
        return state;
    }

    public void reset() {
        this.state = 0;
        this.bufpos = 0;
        this.overflow = false;
    }
}
