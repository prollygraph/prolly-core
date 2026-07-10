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

/**
 * The store returned bytes that fail their content-address check — corruption or bit-rot detected
 * on a read by re-hashing the stored bytes and finding they do not hash to the key requested
 * (ADR-0064 verify-below-the-cache). The data is <i>wrong</i>, not merely momentarily unavailable.
 *
 * @apiNote <b>Do not retry</b> — a re-read returns the same bad bytes. The operational response is
 *     to alert and restore the affected store/chunk from a known-good backup. This is the type that
 *     distinguishes corruption from a transient {@link ProllyIoException} (which a caller SHOULD
 *     retry); both are {@link ProllyException}, so a caller that does not care about the difference
 *     can still catch the root.
 * @implNote Thrown by {@code RocksNodeStore.read} when {@code verifyOnRead} is on and a disk node's
 *     hash mismatches its key. Born from {@code core-read-integrity-default.md} (the check, which
 *     first threw a bare {@link IllegalStateException}) + {@code
 *     core-error-taxonomy-and-failpaths.md} (this branchable type).
 */
public final class ProllyCorruptionException extends ProllyException {

    public ProllyCorruptionException(String message) {
        super(message);
    }

    public ProllyCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
