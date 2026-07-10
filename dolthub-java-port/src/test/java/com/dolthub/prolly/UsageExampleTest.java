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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Compile-pins the "hello world" in {@code dolthub-java-port/USAGE.md}. If this test stops
 * compiling or passing, the usage guide has drifted from the real API — fix both together. (The
 * guide is the project's code-grounded-docs standard applied to the library surface; this is its
 * executable proof.)
 */
class UsageExampleTest {

    @Test
    void usage_guide_hello_world_round_trips() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {

            // 1. schema: a single non-nullable raw-bytes (String) key field
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));

            // 2. empty immutable map (null root) -> edit buffer
            StaticMap empty = new StaticMap(store, /* root */ null, desc);
            MutableMap m = new MutableMap(empty, store, desc, pool);

            // 3. put a value under a key tuple
            MemorySegment key = key(pool, "alice");
            m.put(key, MemorySegment.ofArray("hello".getBytes()));

            // 4. materialise the new immutable tree, 5. read it back
            StaticMap result = m.flush();
            byte[] v = result.get(key).orElseThrow().toArray(ValueLayout.JAVA_BYTE);

            assertEquals("hello", new String(v));
        }
    }

    /**
     * Build a one-field key tuple (cf. the guide's {@code key(...)} helper /
     * JsonLeafStore.keyTuple).
     */
    private static MemorySegment key(BufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }
}
