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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The load-bearing retention invariant (plans/off-heap-use-after-free-tests.md Step 5 / Goal 3,
 * D-4): {@link RocksNodeStore#read} returns an <b>on-heap, independent copy</b>, which is exactly
 * why a cached or cursor-retained {@code Node} wrapping a read segment is retention-safe — the
 * segment is not a view into an arena or a RocksDB buffer that could be freed. This test fails the
 * day a zero-copy read optimization (ADR-0039 Step 4) returns a native/borrowed segment, forcing
 * the retention safety to be re-proven before the change can land — rather than silently turning
 * every cached node into a use-after-free.
 */
class RocksNodeStoreReadInvariantTest {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    @Test
    void readReturnsAnOnHeapIndependentCopy(@TempDir Path dir) throws Exception {
        try (RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString())) {
            byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
            byte[] hash = store.write(data);

            MemorySegment s1 = store.read(hash).orElseThrow();
            assertFalse(
                    s1.isNative(),
                    "read must return an on-heap copy (D-4); a zero-copy change returning a native "
                            + "segment must fail here, not silently make cached nodes a use-after-free");
            assertEquals(data.length, s1.byteSize());
            assertEquals(1, s1.get(BYTE, 0));

            // Independent copy: mutating a returned segment must not corrupt the store's bytes.
            s1.set(BYTE, 0, (byte) 0x7F);
            MemorySegment s2 = store.read(hash).orElseThrow();
            assertEquals(
                    1,
                    s2.get(BYTE, 0),
                    "read returns a fresh copy each call — mutating one must not affect the store");
        }
    }
}
