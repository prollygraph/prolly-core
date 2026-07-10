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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for {@link Cursor} (plans/off-heap-use-after-free-tests.md Phase 1 Step
 * 6). A cursor holds a root→leaf {@link Node} path + a cached key slice; the hazard is a cursor
 * outliving the buffer pool / arena whose segments back its nodes (H4 — "cursor escape across a
 * transaction boundary"). This pins the retention-safety that makes that safe in practice: the
 * nodes a cursor reads come from the {@link NodeStore}, which returns independent copies ({@link
 * InMemoryNodeStore} copies on write+read, as {@code RocksNodeStore} does — Step 5), so a cursor
 * over a persisted tree navigates cleanly even after the build pool that created it is closed. A
 * regression that made a cursor hold a pool-backed segment would read poison/garbage here.
 */
class CursorUseAfterFreeTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Test
    void cursorOverPersistedTree_navigatesCorrectlyAfterBuildPoolCloses() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        int n = 60; // enough entries to force a multi-level tree
        byte[] rootHash;

        // Build the tree with a poison pool, persist it, then CLOSE the pool (frees its arena +
        // poisons its blocks). The persisted nodes were copied into the store on write, so they are
        // independent of the pool.
        try (PoisoningBufferPool buildPool = new PoisoningBufferPool()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, buildPool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                TupleBuilder tb = new TupleBuilder(buildPool);
                tb.putField(0, String.format("k%04d", i).getBytes(StandardCharsets.UTF_8));
                edits.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(),
                                MemorySegment.ofArray(("v" + i).getBytes(StandardCharsets.UTF_8))));
            }
            Node root = m.applyMutations(null, edits.iterator());
            rootHash = store.write(root.bytes());
        }

        // The build pool is gone. Re-read the root from the store (a heap copy) and navigate a
        // cursor
        // over the whole tree — every node it touches is a store-resident heap copy, not a pool
        // view.
        Node root = Node.fromBytes(store.read(rootHash).orElseThrow());
        Cursor cursor = Cursor.atStart(store, root);

        List<String> keys = new ArrayList<>();
        do {
            byte[] keyBytes = new Tuple(cursor.currentKey()).getField(0);
            assertFalse(
                    PoisoningBufferPool.isPoisoned(MemorySegment.ofArray(keyBytes)),
                    "cursor key reads poison after the build pool closed — a retention use-after-free");
            keys.add(new String(keyBytes, StandardCharsets.UTF_8));
        } while (cursor.advance());

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            expected.add(String.format("k%04d", i));
        }
        assertEquals(
                expected,
                keys,
                "cursor must read all the correct keys after the build pool closed (heap-backed reads)");
    }
}
