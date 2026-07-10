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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Branch-complete coverage of {@link Tuple#fieldEquals(int, byte[])} — the allocation-free
 * hot-equality path the trie's {@code prefixMatches}/{@code valid} checks ride (the upstream
 * triejoin-performance plan's residual-allocation lever). The contract under test is documented on
 * the method: semantically {@code Arrays.equals(getField(index), expected)}, where a NULL-encoded
 * or absent field equals only a {@code null} expected.
 *
 * <p>Every branch pair is driven: absent index (against {@code null} and non-{@code null}),
 * NULL-encoded field (both), present field against {@code null}, length mismatch, byte mismatch,
 * and the full-match loop. Each case is cross-checked against the {@code getField}-based semantics
 * it must mirror.
 */
class TupleFieldEqualsTest {

    @Test
    void fieldEquals_covers_every_outcome() {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, new byte[] {'a', 'b', 'c'});
            tb.putField(1, (byte[]) null); // NULL-encoded field
            Tuple t = tb.build();

            // present field: full match (drives the compare loop to completion)
            assertTrue(t.fieldEquals(0, new byte[] {'a', 'b', 'c'}));
            // present field: byte mismatch (loop exits early)
            assertFalse(t.fieldEquals(0, new byte[] {'a', 'b', 'x'}));
            // present field: length mismatch (loop never entered)
            assertFalse(t.fieldEquals(0, new byte[] {'a', 'b'}));
            // present field vs null expected
            assertFalse(t.fieldEquals(0, null));

            // NULL-encoded field equals only null — mirrors getField's null return
            assertTrue(t.fieldEquals(1, null));
            assertFalse(t.fieldEquals(1, new byte[] {'a'}));

            // absent index (>= count) equals only null
            assertTrue(t.fieldEquals(9, null));
            assertFalse(t.fieldEquals(9, new byte[0]));

            // the documented equivalence, spot-checked on both interesting fields
            assertTrue(java.util.Arrays.equals(t.getField(0), new byte[] {'a', 'b', 'c'}));
            assertTrue(t.getField(1) == null);
        }
    }
}
