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

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.lang.foreign.MemorySegment;
import java.nio.BufferUnderflowException;

/**
 * I-4 deserializer robustness via coverage-guided fuzzing (Jazzer, D-5,
 * plans/core-engine-test-strategy.md Step 4). {@code Node.fromBytes} consumes persisted/adversarial
 * chunk bytes; a malformed or hostile chunk must be <b>rejected with a controlled exception</b> —
 * never crash the JVM, throw an unexpected {@code Error}/{@code NullPointerException}, hang, or
 * OOM, and never silently mis-parse into an inconsistent {@code Node}.
 *
 * <p>Runs in fast REGRESSION mode (replays the checked-in seed corpus + the empty input) on every
 * build; active coverage-guided fuzzing is the {@code -Pfuzz} profile (sets {@code JAZZER_FUZZ=1} +
 * a time budget). Any input that trips an uncontrolled failure is written to the corpus by Jazzer
 * and becomes a permanent regression seed.
 *
 * <p>NB: {@code IllegalArgumentException}/{@code IndexOutOfBoundsException}/ {@code
 * IllegalStateException}/{@code BufferUnderflowException} are the <i>expected</i> "this isn't a
 * valid node" rejections — caught here. Anything else propagates and is a Jazzer finding.
 */
class NodeDeserializerFuzzTest {

    @FuzzTest(
            maxDuration =
                    "60s") // CI-smoke budget; only applies in -Pfuzz (JAZZER_FUZZ) active mode
    void nodeFromBytesRejectsMalformedWithoutCrashing(byte[] data) {
        try {
            Node node = Node.fromBytes(MemorySegment.ofArray(data));
            if (node != null) {
                // Exercise the header/parse surface where malformed-byte bugs live.
                int count = node.count();
                node.isLeaf();
                node.treeCount();
                // untrusted-input-boundary-hardening Step 1 — drive the key/value ALLOCATION path.
                // A
                // malformed kLen/vLen must surface as a controlled IllegalArgumentException (caught
                // below), never a NegativeArraySizeException or an out-of-memory from new
                // byte[len].
                // The header-only surface above never reached these allocations. (Most random bytes
                // now fail closed earlier with UnsupportedFormatException — no PNOD header / no
                // TUPM
                // identifier, ADR-0072 — also caught below.)
                for (int i = 0; i < Math.min(count, 8); i++) {
                    node.getKey(i);
                    node.getValue(i);
                }
            }
        } catch (IllegalArgumentException
                | IndexOutOfBoundsException
                | IllegalStateException
                | UnsupportedFormatException
                | BufferUnderflowException expected) {
            // Controlled rejection of malformed bytes — this is correct behavior.
        }
    }
}
