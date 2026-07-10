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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The D-1 contract of {@code core-error-taxonomy-and-failpaths.md}: a caller can branch on the
 * engine's failure type. These tests pin the <i>catch grouping</i> a caller relies on — every
 * operational failure is a {@link ProllyException}, while a caller-fault {@link
 * IllegalArgumentException} (bad input) is deliberately NOT, so {@code catch (ProllyException)}
 * does not swallow a programming bug.
 */
class ProllyExceptionHierarchyTest {

    @Test
    void operationalFailuresAreAllProllyExceptions() {
        // Each concrete leaf is catchable as the root — the "handle any engine failure" idiom.
        assertInstanceOf(ProllyException.class, new ProllyCorruptionException("corrupt"));
        assertInstanceOf(ProllyException.class, new ProllyIoException("io"));
        assertInstanceOf(ProllyException.class, new SpillQuotaExceededException(10, 5, 1));
        // ...and unchecked, so no signature churn.
        assertInstanceOf(RuntimeException.class, new ProllyIoException("io"));
    }

    @Test
    void corruptionAndIoAreDistinguishableLeaves() {
        // The whole point of the taxonomy: corruption (do-not-retry) is a different type from io
        // (retryable), so a caller can react precisely rather than parse a message. (That the two
        // final leaves are unrelated is in fact compiler-enforced — a direct instanceof between
        // them
        // won't compile — so the runtime check goes through the ProllyException base a caller
        // holds.)
        ProllyException corruption = new ProllyCorruptionException("x");
        ProllyException io = new ProllyIoException("x");
        assertTrue(corruption instanceof ProllyCorruptionException);
        assertTrue(io instanceof ProllyIoException);
        org.junit.jupiter.api.Assertions.assertFalse(
                corruption instanceof ProllyIoException,
                "corruption must not be catchable as a transient io error (a retry would be futile)");
    }

    @Test
    void catchingProllyExceptionDoesNotSwallowBadInput() {
        // Bad input is a caller bug (reject + fix the call site), NOT an operational failure — it
        // must
        // propagate past a `catch (ProllyException)` that handles engine failures.
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    try {
                        throw new IllegalArgumentException("bad arg");
                    } catch (ProllyException swallowed) {
                        org.junit.jupiter.api.Assertions.fail(
                                "ProllyException must not catch a bad-input IllegalArgumentException");
                    }
                });
    }
}
