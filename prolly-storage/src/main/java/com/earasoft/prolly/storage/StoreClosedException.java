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
package com.earasoft.prolly.storage;

/**
 * Thrown when a {@link RocksNodeStore} operation (read / write / batch / flush) is attempted after
 * the store has been {@link RocksNodeStore#close() closed}. Converts what would otherwise be a
 * native use-after-free — a process-killing {@code SIGSEGV} when RocksDB dereferences a freed
 * handle — into a catchable Java exception, so a teardown that races an in-flight call fails loudly
 * and recoverably instead of taking the JVM down.
 *
 * @apiNote Unchecked, to match the store's other failures ({@link RocksNodeStore} wraps {@code
 *     RocksDBException} in a {@code RuntimeException} via its {@code rethrow} helper). A caller
 *     that races teardown sees a clear, catchable signal rather than a corrupted process.
 * @implNote This is the defense-in-depth half of {@code plans/repo-teardown-quiesce.md} (D-3): the
 *     {@link RocksNodeStore} lifecycle lock drains in-flight native calls before {@code close()}
 *     frees the handles, and rejects any call that arrives after. The root fix — draining in-flight
 *     work before the repo directory is deleted (D-2) — is a separate, higher layer.
 */
public final class StoreClosedException extends RuntimeException {
    public StoreClosedException() {
        super("RocksNodeStore is closed — operation rejected (teardown raced an in-flight call)");
    }
}
