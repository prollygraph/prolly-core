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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link BufferPool}'s two default methods — the leak fix's type-preserving transaction-scope
 * mechanism (docs/write-ups/direct-buffer-pool-write-path-leak.md).
 *
 * <p>A garbage-collected pool needs no per-transaction freeing, so the defaults are: {@link
 * BufferPool#newTransactionScope()} returns {@code this} (the pool is its own scope) and {@link
 * BufferPool#close()} is a no-op. An arena-backed pool ({@code DirectBufferPool}, in {@code
 * prolly-storage}) overrides both; those overrides are pinned by that module's tests. This pins the
 * defaults a non-overriding pool inherits.
 */
class BufferPoolDefaultsTest {

    @Test
    void defaultTransactionScope_isThePoolItself_andCloseIsNoOp() {
        // A minimal pool that overrides only borrow(), inheriting both default methods.
        BufferPool pool = size -> MemorySegment.ofArray(new byte[size]);

        assertSame(
                pool,
                pool.newTransactionScope(),
                "a garbage-collected pool is its own transaction scope (no off-heap arena to free"
                        + " per transaction)");
        assertDoesNotThrow(pool::close, "the default close() is a no-op and throws nothing");
    }

    @Test
    void heapBufferPool_inheritsTheSelfScopingDefault() {
        HeapBufferPool pool = new HeapBufferPool();
        assertSame(
                pool,
                pool.newTransactionScope(),
                "HeapBufferPool does not override newTransactionScope — it is its own scope");
        assertDoesNotThrow(pool::close, "HeapBufferPool.close() is a no-op");
    }
}
