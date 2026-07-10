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
import java.nio.BufferUnderflowException;

/**
 * Parser-fuzzing of {@link Commit#deserialize(byte[])} (an upstream test-strategy step — the
 * untrusted-bytes gate). Commit chunks are read back from the on-disk commit log <i>and</i>
 * received over the sync path from an untrusted peer (a forged pack reaches {@code deserialize}
 * before its hash is verified), so a malformed/hostile commit blob must be <b>rejected with a
 * controlled exception</b> — never crash the JVM, hang, OOM via an attacker-controlled length
 * field, or mis-parse silently.
 *
 * <p>This harness exists because {@code deserialize} previously did {@code new byte[bb.getInt()]}
 * on attacker-controlled lengths — a NegativeArraySize / multi-GB-allocation DoS. The reader now
 * bounds every length against the bytes remaining; this test pins that hardening and any future
 * regression of it.
 *
 * <p>Runs in fast REGRESSION mode (replays the checked-in seed corpus) on every build; active
 * coverage-guided fuzzing is the {@code -Pfuzz} profile ({@code JAZZER_FUZZ=1} + a time budget).
 * Any input that trips an uncontrolled failure is written to the corpus and becomes a permanent
 * regression seed.
 *
 * <p>NB: {@code IllegalArgumentException} / {@code IndexOutOfBoundsException} / {@code
 * BufferUnderflowException} / {@code UnsupportedFormatException} are the <i>expected</i> "this
 * isn't a valid commit" rejections — caught here. The last one is the magic/version check ({@code
 * core-format-versioning.md} Step 2): a blob without the commit magic fails closed before any field
 * is read. {@code OutOfMemoryError}, {@code NegativeArraySizeException}, or anything else
 * propagates as a finding.
 */
class CommitDeserializerFuzzTest {

    @FuzzTest(maxDuration = "60s") // only active under -Pfuzz (JAZZER_FUZZ); else regression-replay
    void deserializeRejectsMalformedWithoutCrashing(byte[] data) {
        try {
            Commit c = Commit.deserialize(data);
            if (c != null) {
                // Touch the parsed surface — a silently mis-parsed commit would
                // expose inconsistent state here.
                c.getParents();
                c.getRootValueHash();
                c.getAuthor();
                c.getMessage();
            }
        } catch (IllegalArgumentException
                | IndexOutOfBoundsException
                | BufferUnderflowException
                | UnsupportedFormatException expected) {
            // Controlled rejection of a malformed commit — correct behavior.
        }
    }
}
