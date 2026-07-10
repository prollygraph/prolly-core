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
 * A transient input/output failure in the storage layer — a RocksDB error, a failed durable flush,
 * a disk write that could not complete. Unlike {@link ProllyCorruptionException}, the data is not
 * wrong; the operation may succeed on a later attempt once the underlying condition clears.
 *
 * @apiNote <b>Retryable</b> with backoff. A disk-full variant carries operator guidance in its
 *     message (the {@code /tmp} cleanup recipe + a pointer to the operations guide); freeing space
 *     and retrying is the recovery. The underlying {@code RocksDBException} is preserved as {@link
 *     #getCause()} for diagnostics.
 * @implNote Produced by {@code RocksNodeStore.rethrow}, which wraps every {@code RocksDBException}
 *     from {@code read}/{@code write}/{@code endWriteBatch} and special-cases the disk-full
 *     message. Introduced by {@code core-error-taxonomy-and-failpaths.md} (D-1).
 */
public final class ProllyIoException extends ProllyException {

    public ProllyIoException(String message) {
        super(message);
    }

    public ProllyIoException(String message, Throwable cause) {
        super(message, cause);
    }
}
