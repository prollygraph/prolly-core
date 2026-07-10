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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code handler.onDiff(...) == false} early-stop behaviour of the rewritten leaf-cursor
 * {@link DiffEngine}. The walk has three distinct "return false → stop" sites — one each for the
 * DEL, ADD, and MOD arms — and the early-stop must hold on <em>multi-level</em> trees, where the
 * per-leaf Merkle skip is also in play.
 *
 * <p>Trees built end-to-end via {@link TreeMutator} — no mocks.
 */
class DiffEngineEarlyStopTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static Node range(
            HeapBufferPool pool, InMemoryNodeStore store, int from, int to, String valPrefix) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        for (int i = from; i < to; i++) {
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, String.format("k-%05d", i)),
                            MemorySegment.ofArray((valPrefix + i).getBytes())));
        }
        return m.applyMutations(null, edits.iterator());
    }

    /** Handler that records entries and stops once it has seen {@code limit}. */
    private static final class StopAfter implements DiffEngine.DiffHandler {
        private final int limit;
        final List<DiffEngine.DiffEntry> seen = new ArrayList<>();

        StopAfter(int limit) {
            this.limit = limit;
        }

        @Override
        public boolean onDiff(DiffEngine.DiffEntry e) {
            seen.add(e);
            return seen.size() < limit; // false once limit reached → stop
        }
    }

    @Test
    void stop_after_one_entry_on_del_stream() {
        // Large tree vs small subset → an all-DEL diff. Stop after the first.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node big = range(pool, store, 0, 2000, "v-");
            Node small = range(pool, store, 0, 8, "v-");

            StopAfter h = new StopAfter(1);
            new DiffEngine(store, STRING_DESC).diff(big, small, h);

            assertEquals(1, h.seen.size(), "handler returning false must stop after one DEL");
            assertEquals(DiffEngine.DiffType.DEL, h.seen.get(0).type());
        }
    }

    @Test
    void stop_after_k_entries_on_add_stream() {
        // Small subset vs large tree → an all-ADD diff. Stop after K.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node small = range(pool, store, 0, 8, "v-");
            Node big = range(pool, store, 0, 2000, "v-");

            StopAfter h = new StopAfter(37);
            new DiffEngine(store, STRING_DESC).diff(small, big, h);

            assertEquals(
                    37,
                    h.seen.size(),
                    "handler must stop the ADD stream at exactly the requested count");
            assertEquals(DiffEngine.DiffType.ADD, h.seen.get(36).type());
        }
    }

    @Test
    void stop_on_a_mod_entry() {
        // Two trees of equal shape, every value changed → an all-MOD diff.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node a = range(pool, store, 0, 2000, "old-");
            Node b = range(pool, store, 0, 2000, "new-");

            StopAfter h = new StopAfter(5);
            new DiffEngine(store, STRING_DESC).diff(a, b, h);

            assertEquals(5, h.seen.size(), "MOD stream must honour the early stop");
            assertTrue(
                    h.seen.stream().allMatch(e -> e.type() == DiffEngine.DiffType.MOD),
                    "every entry before the stop is a MOD");
        }
    }

    @Test
    void handler_always_true_consumes_the_whole_diff() {
        // Control: when the handler never returns false, every difference
        // is delivered — the early-stop path is simply not taken.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node small = range(pool, store, 0, 8, "v-");
            Node big = range(pool, store, 0, 2000, "v-");

            List<DiffEngine.DiffEntry> all = new ArrayList<>();
            new DiffEngine(store, STRING_DESC)
                    .diff(
                            small,
                            big,
                            e -> {
                                all.add(e);
                                return true;
                            });

            assertEquals(1992, all.size(), "a never-stopping handler receives every ADD");
        }
    }

    @Test
    void stop_at_the_very_first_entry_emits_exactly_one() {
        // Boundary: limit == 1 on a mixed diff — the walk must not emit a
        // second entry after the first false return.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Node a = range(pool, store, 0, 1000, "v-");
            Node b = range(pool, store, 500, 1500, "v-");

            StopAfter h = new StopAfter(1);
            new DiffEngine(store, STRING_DESC).diff(a, b, h);
            assertEquals(
                    1,
                    h.seen.size(),
                    "exactly one entry may be delivered when the handler stops immediately");
        }
    }
}
