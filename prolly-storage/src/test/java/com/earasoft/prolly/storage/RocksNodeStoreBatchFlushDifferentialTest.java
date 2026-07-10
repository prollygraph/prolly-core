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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the no-OOM commit-build safety net (plans/prolly-bulk-load.md D-7): the periodic per-build
 * {@link java.lang.AutoCloseable WriteBatch} flush ({@link RocksNodeStore#setBatchFlushBytes})
 * bounds native memory during a large tree build, and must be <b>content-neutral</b> — the prolly
 * tree's root is content-addressed, so the flush cadence cannot change it.
 *
 * <p>Builds the same 20k edits with a tiny flush threshold (forcing many mid-build flushes) vs
 * flushing disabled (one batch, the original behaviour), and asserts the resulting root hashes are
 * byte-identical. That is the operational proof of the safety argument behind the split batch:
 * {@code TreeMutator} never re-reads a chunk it just wrote, so flushing some of them early cannot
 * affect the tree it produces.
 */
class RocksNodeStoreBatchFlushDifferentialTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Test
    void periodicFlushYieldsByteIdenticalRoot(@TempDir Path dir) throws Exception {
        byte[] oneBatch =
                buildRoot(dir.resolve("one-batch"), 0L); // 0 = flush disabled (single batch)
        byte[] manyFlush =
                buildRoot(dir.resolve("many-flush"), 2048L); // tiny → many mid-build flushes
        assertArrayEquals(
                oneBatch,
                manyFlush,
                "periodic WriteBatch flush must not change the content-addressed root");
    }

    private static byte[] buildRoot(Path path, long flushBytes) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(path.toString())) {
            store.setBatchFlushBytes(flushBytes);
            MutableMap mm = new MutableMap(new StaticMap(store, null, DESC), store, DESC, pool);
            for (int i = 0; i < 20_000; i++) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("key-%08d", i).getBytes());
                mm.put(tb.build().segment(), MemorySegment.ofArray(("val-" + i).getBytes()));
            }
            StaticMap sm = mm.flush();
            return store.write(sm.root().segment()); // content hash of the root node
        }
    }
}
