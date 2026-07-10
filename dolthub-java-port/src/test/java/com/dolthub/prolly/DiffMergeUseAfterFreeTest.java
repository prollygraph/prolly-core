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
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for {@link DiffEngine} + {@link MergeEngine}
 * (plans/off-heap-use-after-free-tests.md Phase 1 Step 8). Both walk trees via cursors over many
 * reads (H4); {@link MergeEngine} additionally <b>builds</b> the merged tree through the {@link
 * BufferPool}. The differential nets both: running diff / merge through the {@link
 * PoisoningBufferPool} must produce the byte-identical result it does through the {@link
 * HeapBufferPool} — a read of a freed/reused segment (a retained cursor node, or a released
 * scratch/chunk during the merge build) would diverge.
 */
class DiffMergeUseAfterFreeTest {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static TreeMutator.Mutation put(BufferPool pool, String key, String val) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes(StandardCharsets.UTF_8));
        return new TreeMutator.Mutation(
                tb.build().segment(), MemorySegment.ofArray(val.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<TreeMutator.Mutation> base(BufferPool pool) {
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            edits.add(put(pool, String.format("k%03d", i), "v" + i));
        }
        return edits;
    }

    @Test
    void mergeRootIsByteIdenticalThroughPoisonAndHeapPool() {
        // A 3-way merge: ours + theirs both derived from the ancestor, touching disjoint keys (no
        // conflict). The merge READS the three trees' cursors AND BUILDS the merged tree through
        // the
        // pool — the byte-identical merged root through both pools proves neither path reads freed
        // memory.
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool -> {
                    InMemoryNodeStore store = new InMemoryNodeStore();
                    TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
                    Node ancestor = m.applyMutations(null, base(pool).iterator());
                    Node ours =
                            m.applyMutations(
                                    ancestor,
                                    List.of(put(pool, "k005", "ours"), put(pool, "k100", "x"))
                                            .iterator());
                    Node theirs =
                            m.applyMutations(
                                    ancestor,
                                    List.of(put(pool, "k015", "theirs"), put(pool, "k200", "y"))
                                            .iterator());
                    MergeEngine me = new MergeEngine(store, STRING_DESC, pool);
                    MergeEngine.MergeResult r = me.merge(ancestor, ours, theirs);
                    return r.root().bytes();
                });
    }

    @Test
    void diffStreamIsByteIdenticalThroughPoisonAndHeapPool() {
        // The streaming diff walks two leaf cursors + their parent chains; the emitted entries must
        // be
        // identical through both pools (a retained cursor segment read after free would corrupt
        // them).
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool -> {
                    InMemoryNodeStore store = new InMemoryNodeStore();
                    TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
                    Node a = m.applyMutations(null, base(pool).iterator());
                    Node b =
                            m.applyMutations(
                                    a,
                                    List.of(put(pool, "k005", "changed"), put(pool, "k999", "new"))
                                            .iterator());
                    DiffEngine de = new DiffEngine(store, STRING_DESC);
                    Iterator<DiffEngine.DiffEntry> it = de.diffIterator(a, b);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    while (it.hasNext()) {
                        DiffEngine.DiffEntry e = it.next();
                        out.writeBytes(e.key().toArray(BYTE));
                        if (e.valueA() != null) {
                            out.writeBytes(e.valueA().toArray(BYTE));
                        }
                        if (e.valueB() != null) {
                            out.writeBytes(e.valueB().toArray(BYTE));
                        }
                        out.write(e.type().ordinal());
                    }
                    return out.toByteArray();
                });
    }
}
