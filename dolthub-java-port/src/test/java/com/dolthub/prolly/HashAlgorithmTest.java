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
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * {@code core-format-versioning.md} Step 4 — the {@link HashAlgorithm} enum centralizes the
 * content-address algorithm (name + truncation length + on-disk id) so a future hash change is one
 * enum value, not scattered literals. {@code CURRENT} is SHA-512/20 today; this pins that, and that
 * {@link HashUtils} truncates to {@code CURRENT.length()}.
 */
class HashAlgorithmTest {

    @Test
    void current_is_sha512_20() {
        assertSame(HashAlgorithm.SHA512_20, HashAlgorithm.CURRENT);
        assertEquals("SHA-512", HashAlgorithm.CURRENT.messageDigestAlgorithm());
        assertEquals(20, HashAlgorithm.CURRENT.length());
        assertEquals(1, HashAlgorithm.CURRENT.id());
    }

    @Test
    void hashUtils_truncates_to_the_current_length() {
        // The enum drives HashUtils' truncation — a content address is exactly CURRENT.length()
        // bytes.
        assertEquals(HashAlgorithm.CURRENT.length(), HashUtils.hash(new byte[] {1, 2, 3}).length);
        assertEquals(HashAlgorithm.CURRENT.length(), HashUtils.hash(new byte[0]).length);
    }
}
