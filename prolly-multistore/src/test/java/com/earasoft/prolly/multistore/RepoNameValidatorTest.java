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
package com.earasoft.prolly.multistore;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Phase 0 Step 3 / Phase 1 Step 7 of the upstream multi-tenant hosting plan — pins the
 * well-formedness regex + path-traversal rejection. Creation-time reserved-name policy belongs to
 * the hosting product and is pinned by its own tests there.
 */
class RepoNameValidatorTest {

    // ---- happy path ---------------------------------------------------

    @Test
    void validate_accepts_well_formed_names() {
        String[] valid = {
            "alpha",
            "alpha-beta",
            "a",
            "alpha123",
            "z9-a-b-c",
            // length boundary — exactly 63 chars
            "a".repeat(63)
        };
        for (String name : valid) {
            assertDoesNotThrow(() -> RepoNameValidator.validate(name), "must accept: " + name);
        }
    }

    @Test
    void validate_accepts_product_reserved_names_at_request_time() {
        // Names a hosting product reserves at CREATION time are still legitimate
        // request targets (e.g. /repos/default/...) — syntax validation knows
        // nothing about reservation policy.
        for (String reserved : new String[] {"default", "admin", "system", "sparql", "jobs"}) {
            assertDoesNotThrow(
                    () -> RepoNameValidator.validate(reserved),
                    "request-time validate must accept reserved name: " + reserved);
        }
    }

    @Test
    void validate_accepts_default() {
        // The default repo name is reserved AND valid by regex AND a
        // legitimate request target. Pin explicitly so a future regex
        // change can't accidentally reject it.
        assertDoesNotThrow(() -> RepoNameValidator.validate("default"));
    }

    // ---- malformed input ---------------------------------------------

    @Test
    void validate_rejects_null() {
        var ex =
                assertThrows(
                        RepoNameInvalidException.class, () -> RepoNameValidator.validate(null));
        assertEquals("null repoId", ex.reason());
    }

    @Test
    void validate_rejects_empty() {
        var ex = assertThrows(RepoNameInvalidException.class, () -> RepoNameValidator.validate(""));
        assertEquals("empty repoId", ex.reason());
    }

    @Test
    void validate_rejects_overlong() {
        String tooLong = "a".repeat(64);
        var ex =
                assertThrows(
                        RepoNameInvalidException.class, () -> RepoNameValidator.validate(tooLong));
        assertTrue(ex.reason().contains("too long"));
    }

    @Test
    void validate_rejects_traversal_or_disallowed_chars() {
        String[] invalid = {
            // path traversal
            "..",
            "../",
            "../alpha",
            "alpha/..",
            "alpha/beta",
            // backslash
            "alpha\\beta",
            // uppercase
            "Alpha",
            "ALPHA",
            // starts with digit / hyphen
            "1alpha",
            "-alpha",
            // disallowed punctuation
            "alpha.beta",
            "alpha_beta",
            "alpha:beta",
            "alpha beta",
            "alpha@beta",
            "alpha,beta",
            // unicode
            "alphaβ",
            "αlpha"
        };
        for (String name : invalid) {
            assertThrows(
                    RepoNameInvalidException.class,
                    () -> RepoNameValidator.validate(name),
                    "must reject: " + name);
        }
    }

    @Test
    void validate_rejects_NUL_byte() {
        var ex =
                assertThrows(
                        RepoNameInvalidException.class,
                        () -> RepoNameValidator.validate("alpha\0"));
        // either "path-traversal characters" or the regex failure — either
        // is acceptable since both are correct rejection categories
        assertNotNull(ex.reason());
    }

    @Test
    void validate_exception_carries_repoId() {
        var ex =
                assertThrows(
                        RepoNameInvalidException.class,
                        () -> RepoNameValidator.validate("Bad-Name"));
        assertEquals("Bad-Name", ex.repoId());
    }
}
