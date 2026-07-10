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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Measurement-first gate for a possible {@code DiffEngine} internal-subtree-skip optimization
 * (named in the streaming-commit-diff build-log). The question: when two trees differ by a single
 * key, does {@code DiffEngine.diffIterator}'s node-read count grow with the tree size (O(n) — it
 * walks every leaf of both trees), or is it already O(log n + changes) (it skips unchanged subtrees
 * by reference)?
 *
 * <p><b>Why this instrument.</b> Same discipline as {@code TreeMutatorFastForwardComplexityTest}: a
 * deterministic {@link NodeStore} read-CALL count over an in-memory store, with the n-scaling
 * itself as the control — flat/log ⇒ already efficient (the optimization is moot), linear ⇒ the
 * leaf-walk reads everything (the optimization is justified). No timing noise, no input/output
 * confound. This isolates the pure tree-diff read cost (no RDF dictionary / SPOC noise). The result
 * decides whether the (substantial, core-engine) hierarchical-hash-descent rewrite is worth doing —
 * measure before optimizing.
 */
class DiffEngineReadScalingTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static String k(int i) {
        return String.format("key-%07d", i);
    }

    private static List<TreeMutator.Mutation> muts(HeapBufferPool pool, TreeMap<String, String> m) {
        List<TreeMutator.Mutation> res = new ArrayList<>();
        for (var e : m.entrySet()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, e.getKey().getBytes());
            MemorySegment v =
                    e.getValue() == null ? null : MemorySegment.ofArray(e.getValue().getBytes());
            res.add(new TreeMutator.Mutation(tb.build().segment(), v));
        }
        return res;
    }

    /** Counts read CALLS — the per-diff work proxy. */
    static final class CountingNodeStore implements NodeStore, AutoCloseable {
        private final InMemoryNodeStore delegate = new InMemoryNodeStore();
        long reads;

        @Override
        public Optional<MemorySegment> read(byte[] hash) {
            reads++;
            return delegate.read(hash);
        }

        @Override
        public byte[] write(MemorySegment data) {
            return delegate.write(data);
        }

        @Override
        public byte[] write(byte[] data) {
            return delegate.write(data);
        }

        @Override
        public void beginWriteBatch() {
            delegate.beginWriteBatch();
        }

        @Override
        public void endWriteBatch() {
            delegate.endWriteBatch();
        }

        void reset() {
            reads = 0;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Test
    void singleKeyDiffReadCountVsTreeSize() {
        int[] ns = {2_000, 8_000, 32_000}; // 16x range
        long[] readsAt = new long[ns.length];

        try (HeapBufferPool pool = new HeapBufferPool()) {
            for (int idx = 0; idx < ns.length; idx++) {
                int n = ns[idx];
                try (CountingNodeStore store = new CountingNodeStore()) {
                    TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
                    TreeMap<String, String> model = new TreeMap<>();
                    for (int i = 0; i < n; i++) model.put(k(i), "v" + i);
                    Node base = m.applyMutations(null, muts(pool, model).iterator());

                    TreeMap<String, String> editModel = new TreeMap<>();
                    editModel.put(k(n / 2), "UPDATED"); // change one middle key
                    Node edited = m.applyMutations(base, muts(pool, editModel).iterator());
                    int levels = base.level() + 1;

                    store.reset(); // count only the diff walk
                    int[] diffs = {0};
                    new DiffEngine(store, STRING_DESC)
                            .diff(
                                    base,
                                    edited,
                                    e -> {
                                        diffs[0]++;
                                        return true;
                                    });
                    readsAt[idx] = store.reads;
                    System.out.printf(
                            "[diff-read-scaling] n=%6d levels=%d diff-reads=%6d diffs=%d%n",
                            n, levels, store.reads, diffs[0]);
                    assertEquals(1, diffs[0], "a single-key edit must diff to exactly one change");
                }
            }
        }

        double ratio = readsAt[ns.length - 1] / (double) Math.max(1, readsAt[0]);
        System.out.printf(
                "[diff-read-scaling] n grew %dx (%,d -> %,d); diff-reads grew %.1fx%n",
                ns[ns.length - 1] / ns[0], ns[0], ns[ns.length - 1], ratio);
        // No complexity assertion yet — this is the measurement that DECIDES whether the
        // internal-subtree-skip optimization is justified. ~16x growth ⇒ O(n) leaf-walk
        // (justified);
        // ~flat ⇒ already O(log n) (moot). The printed ratio is the verdict.
    }
}
