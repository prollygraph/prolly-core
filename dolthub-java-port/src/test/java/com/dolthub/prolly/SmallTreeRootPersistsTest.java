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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regression for the {@code TreeMutator.Chunker.done()} root-write bug.
 *
 * <p>Before the fix, a small tree (single chunk, no parent split) returned a root {@link Node} via
 * {@code Node.fromBytes(...)} without calling {@code store.write(nodeBytes)}. The bytes lived in
 * JVM memory only, so a {@code NodeStore} reopen lost the root chunk.
 *
 * <p>After the fix, the root chunk's bytes are always written. This test exercises the smallest
 * possible tree (1 key) and asserts the root chunk is readable via {@code store.read(hash)} where
 * {@code hash} is computed the same way the store would.
 */
class SmallTreeRootPersistsTest {

    @Test
    void single_key_tree_root_chunk_is_in_store() {
        // Smallest possible map: 1 key, 1 value.
        InMemoryNodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        TupleDescriptor schema = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)));

        StaticMap base = new StaticMap(store, null, schema);
        MutableMap m = new MutableMap(base, store, schema, pool);

        TupleBuilder kb = new TupleBuilder(pool, schema);
        kb.putInt64(0, 42L);
        m.put(kb.build().segment(), MemorySegment.ofArray(new byte[0]));

        StaticMap committed = m.flush();
        assertNotNull(committed.root(), "tree has a root after flush");

        // The root chunk's content hash should be reachable through store.read.
        byte[] expectedHash = HashUtils.hash(committed.root().bytes());
        Optional<MemorySegment> chunk = store.read(expectedHash);
        assertTrue(
                chunk.isPresent(),
                "TreeMutator regression: small-tree root not persisted to store");

        // And the bytes round-trip.
        byte[] back = chunk.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        assertArrayEquals(committed.root().bytes(), back);
    }

    @Test
    void empty_tree_remains_empty_no_chunks_written() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        TupleDescriptor schema = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)));

        StaticMap base = new StaticMap(store, null, schema);
        MutableMap m = new MutableMap(base, store, schema, pool);

        StaticMap committed = m.flush();
        // No edits → returns the base unchanged → root stays null.
        assertNull(committed.root(), "empty tree has no root chunk to write");
        assertEquals(0, store.size(), "no chunks written for an empty tree");
    }

    @Test
    void many_keys_root_persists() {
        // Larger tree (likely with internal parents); also verify the root chunk persists.
        InMemoryNodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        TupleDescriptor schema = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)));

        StaticMap base = new StaticMap(store, null, schema);
        MutableMap m = new MutableMap(base, store, schema, pool);

        for (int i = 0; i < 1000; i++) {
            TupleBuilder kb = new TupleBuilder(pool, schema);
            kb.putInt64(0, (long) i);
            m.put(kb.build().segment(), MemorySegment.ofArray(new byte[0]));
        }
        StaticMap committed = m.flush();

        byte[] rootHash = HashUtils.hash(committed.root().bytes());
        assertTrue(store.read(rootHash).isPresent(), "large-tree root chunk must be persisted");
    }
}
