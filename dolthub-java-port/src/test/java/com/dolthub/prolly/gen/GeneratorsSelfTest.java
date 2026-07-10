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
package com.dolthub.prolly.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.ByteUtils;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleDescriptor;
import java.util.NavigableMap;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Phase 0 Step 2 — proves the shared {@link Generators} produce valid, well-ordered engine data. If
 * these hold, Phases 1-2 can build invariant properties on top with confidence the inputs are
 * sound.
 */
class GeneratorsSelfTest {

    @Provide
    Arbitrary<NavigableMap<byte[], byte[]>> maps() {
        return Generators.maps(0, 50);
    }

    @Provide
    Arbitrary<Tuple> spocKeys() {
        return Generators.int64Tuples(4);
    }

    @Property
    void generatedMapsAreStrictlyOrderedAndDistinct(
            @ForAll @From("maps") NavigableMap<byte[], byte[]> map) {
        // The oracle shape: keys strictly increasing in UNSIGNED order, no
        // content-duplicates (the TreeMap collapse guarantees it).
        byte[] prev = null;
        for (byte[] k : map.keySet()) {
            if (prev != null) {
                assertTrue(
                        ByteUtils.compareUnsigned(prev, k) < 0,
                        "keys strictly increasing in unsigned order");
            }
            prev = k;
        }
    }

    @Property
    void generatedInt64TuplesAreWellFormed(@ForAll @From("spocKeys") Tuple t) {
        // A 4-column SPOC key: correct arity + reflexive comparison.
        assertEquals(4, t.count(), "4 Int64 columns");
        TupleDescriptor d = Generators.int64Descriptor(4);
        assertEquals(0, d.compare(t, t), "a tuple compares equal to itself");
    }
}
