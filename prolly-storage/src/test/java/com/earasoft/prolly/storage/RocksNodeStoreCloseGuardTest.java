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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@link RocksNodeStore} close guard (plans/repo-teardown-quiesce.md D-3): after {@code
 * close()}, every native-touching operation throws {@link StoreClosedException} — a catchable Java
 * signal — instead of dereferencing the freed RocksDB handle and crashing the JVM with a SIGSEGV.
 *
 * <p><b>Why these assertions bite.</b> Before the guard, a read/write after {@code close()} called
 * straight into native RocksDB on a freed handle (the repo-drop SIGSEGV class) — so it would crash
 * or, at best, throw a wrapped {@code RocksDBException}, never a {@code StoreClosedException}.
 * Asserting the *specific* exception fails the pre-guard code and passes only with the guard. This
 * is the defense-in-depth half of the fix; draining in-flight work before the repo directory is
 * deleted (D-2) is the separate root fix.
 */
class RocksNodeStoreCloseGuardTest {

    private static MemorySegment chunk(int seed) {
        byte[] b = new byte[64];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (seed * 31 + i);
        return MemorySegment.ofArray(b);
    }

    @Test
    void writeAfterCloseThrowsStoreClosedException(@TempDir Path dir) throws Exception {
        RocksNodeStore store = new RocksNodeStore(dir.resolve("db").toString());
        store.write(chunk(1)); // works while open
        store.close();
        assertThrows(StoreClosedException.class, () -> store.write(chunk(2)));
    }

    @Test
    void readAfterCloseThrows(@TempDir Path dir) throws Exception {
        RocksNodeStore store = new RocksNodeStore(dir.resolve("db").toString());
        byte[] hash = store.write(chunk(1));
        store.close();
        assertThrows(StoreClosedException.class, () -> store.read(hash));
    }

    @Test
    void batchOpsAfterCloseThrow(@TempDir Path dir) throws Exception {
        RocksNodeStore store = new RocksNodeStore(dir.resolve("db").toString());
        store.beginWriteBatch();
        store.write(chunk(1));
        store.close();
        // The guard's closed-check runs first, so endWriteBatch throws even though close() already
        // drained + closed this thread's pending batch.
        assertThrows(StoreClosedException.class, store::endWriteBatch);
        assertThrows(StoreClosedException.class, store::beginWriteBatch);
    }

    @Test
    void flushDurableAfterCloseThrows(@TempDir Path dir) throws Exception {
        RocksNodeStore store = new RocksNodeStore(dir.resolve("db").toString());
        store.write(chunk(1));
        store.close();
        assertThrows(StoreClosedException.class, store::flushDurable);
    }

    @Test
    void closeIsIdempotent(@TempDir Path dir) throws Exception {
        RocksNodeStore store = new RocksNodeStore(dir.resolve("db").toString());
        store.write(chunk(1));
        store.close();
        assertDoesNotThrow(
                store::close); // second close is a no-op under the write lock, not a double-free
    }
}
