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

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Phase 0 Step 1 of plans/core-engine-test-strategy.md — VIABILITY PROBE.
 *
 * <p>Proves jqwik's property engine is discovered + runs under this project's JUnit Platform 6.0.x
 * + the {@code --enable-preview} surefire argLine. If this runs (hundreds of generated cases),
 * property-based testing (D-1) is viable and the rest of the plan can build on it. If the engine
 * isn't discovered, jqwik does not yet support JUnit Platform 6 and D-1 must be revisited.
 */
class JqwikSmokeProperty {

    @Property
    void unsignedByteCompareIsReflexive(@ForAll @IntRange(min = 0, max = 255) int b) {
        byte[] x = {(byte) b};
        assertEquals(0, ByteUtils.compareUnsigned(x, x), "a byte array compares equal to itself");
    }

    @Property
    void hashIsDeterministic(@ForAll byte[] data) {
        // I-4 in miniature: hashing is a pure function of the bytes.
        assertEquals(HashUtils.toHex(HashUtils.hash(data)), HashUtils.toHex(HashUtils.hash(data)));
    }
}
