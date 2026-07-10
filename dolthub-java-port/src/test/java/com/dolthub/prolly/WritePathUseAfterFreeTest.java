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

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for the write-staging path (plans/off-heap-use-after-free-tests.md Phase
 * 1 Step 7): {@link MutableMap} (over a {@link SpillableSortedBuffer}), {@link TreeMutator}, {@link
 * StaticMap}. The hazard is the staging reading a key/value segment after its pool scope frees it,
 * or the streaming tree build retaining a released chunk (H2/H4). Both are netted by the
 * differential: building through the {@link PoisoningBufferPool} must produce the byte-identical
 * result it does through the {@link HeapBufferPool} — a read of freed/reused memory would diverge.
 */
class WritePathUseAfterFreeTest {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final int N = 80; // enough to force a multi-level tree

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void treeMutatorBuildIsByteIdenticalThroughPoisonAndHeapPool() {
        // TreeMutator streams the bottom-up build, writing chunks to the store as it goes. If it
        // ever
        // read a released scratch/chunk segment, the poison pool would corrupt the root; identical
        // root
        // bytes through both pools proves it does not.
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool -> {
                    InMemoryNodeStore store = new InMemoryNodeStore();
                    TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
                    List<TreeMutator.Mutation> edits = new ArrayList<>();
                    for (int i = 0; i < N; i++) {
                        TupleBuilder tb = new TupleBuilder(pool);
                        tb.putField(0, utf8(String.format("k%04d", i)));
                        edits.add(
                                new TreeMutator.Mutation(
                                        tb.build().segment(),
                                        MemorySegment.ofArray(utf8("v" + i))));
                    }
                    Node root = m.applyMutations(null, edits.iterator());
                    return root.bytes();
                });
    }

    @Test
    void mutableMapFlushContentIsIdenticalThroughPoisonAndHeapPool() {
        // MutableMap stages edits in a SpillableSortedBuffer keyed by Tuple segments, then flush()
        // feeds
        // the merged sorted stream to TreeMutator. The materialized content (every key+value) must
        // be
        // identical through both pools — a staged segment read after free would corrupt a value.
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool -> {
                    InMemoryNodeStore store = new InMemoryNodeStore();
                    StaticMap base = new StaticMap(store, null, STRING_DESC);
                    MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
                    for (int i = 0; i < N; i++) {
                        TupleBuilder tb = new TupleBuilder(pool);
                        tb.putField(0, utf8(String.format("k%04d", i)));
                        m.put(tb.build().segment(), MemorySegment.ofArray(utf8("v" + i)));
                    }
                    StaticMap result = m.flush();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    MapIterator it = result.iter();
                    while (it.next()) {
                        out.writeBytes(it.key().toArray(BYTE));
                        out.writeBytes(it.value().toArray(BYTE));
                    }
                    return out.toByteArray();
                });
    }
}
