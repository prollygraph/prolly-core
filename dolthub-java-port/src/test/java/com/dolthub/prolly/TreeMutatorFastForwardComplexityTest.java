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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Phase D Step 13 of the upstream tree-write-fast-forwarding-impl plan — measures that a single-key
 * commit's tree-build cost is now {@code O(log n)} in history depth, not {@code O(n)}.
 *
 * <p><b>Why this instrument, not the RocksDB wall-clock bench (the discipline's "measure the real
 * thing").</b> The fix targets the <em>tree-build CPU/serialization</em> layer. The RocksDB
 * commit-latency bench conflates that with the compaction tail + input/output and is dev-box
 * shape-only (D-7 of {@code commit-latency-vs-history-benchmark}). This isolating in-memory
 * microbench counts the <b>deterministic work per commit</b> — {@link NodeStore} read/write
 * <em>calls</em> — as a function of {@code n}. The control is built in: the {@code n}-scaling
 * itself decides the complexity class (flat/log ⇒ {@code O(log n)}; linear ⇒ {@code O(n)}), with no
 * compaction/IO confound and no timing noise. Before the fix {@code advanceTo} re-emitted every
 * entry (so it read/re-serialised the whole tree, {@code O(n)}); after, it skips unchanged subtrees
 * by reference, touching only the affected root→leaf spine.
 */
class TreeMutatorFastForwardComplexityTest {

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

    /** A {@link NodeStore} decorator that counts read/write CALLS (the per-commit work proxy). */
    static final class CountingNodeStore implements NodeStore, AutoCloseable {
        private final InMemoryNodeStore delegate = new InMemoryNodeStore();
        long reads;
        long writes;

        @Override
        public Optional<MemorySegment> read(byte[] hash) {
            reads++;
            return delegate.read(hash);
        }

        @Override
        public byte[] write(MemorySegment data) {
            writes++;
            return delegate.write(data);
        }

        @Override
        public byte[] write(byte[] data) {
            writes++;
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
            writes = 0;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Test
    void perCommitWorkIsSublinearInHistory() {
        int[] ns = {1000, 2000, 4000, 8000, 16000};
        long[] writesAt = new long[ns.length];
        long[] readsAt = new long[ns.length];

        try (HeapBufferPool pool = new HeapBufferPool()) {
            for (int idx = 0; idx < ns.length; idx++) {
                int n = ns[idx];
                try (CountingNodeStore store = new CountingNodeStore()) {
                    TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
                    TreeMap<String, String> model = new TreeMap<>();
                    for (int i = 0; i < n; i++) model.put(k(i), "v" + i);
                    Node base = m.applyMutations(null, muts(pool, model).iterator());
                    int levels = base.level() + 1;

                    store.reset(); // count only the single-commit work
                    TreeMap<String, String> edit = new TreeMap<>();
                    edit.put(k(n / 2), "UPDATED"); // update one existing middle key
                    long t0 = System.nanoTime();
                    m.applyMutations(base, muts(pool, edit).iterator());
                    long us = (System.nanoTime() - t0) / 1000;

                    writesAt[idx] = store.writes;
                    readsAt[idx] = store.reads;
                    System.out.printf(
                            "[ff-complexity] n=%6d levels=%d reads/commit=%4d writes/commit=%4d"
                                    + " build=%5dus%n",
                            n, levels, store.reads, store.writes, us);
                }
            }
        }

        // n grows 16x across the range. O(n) re-emit would scale work ~16x; O(log n) skip scales
        // ~tree-height (≈1–2x). A <5x ratio decisively rules out the old linear behaviour.
        assertTrue(writesAt[0] > 0, "the single edit must do real work (writes > 0)");
        double writeRatio = (double) writesAt[ns.length - 1] / writesAt[0];
        double readRatio = (double) readsAt[ns.length - 1] / readsAt[0];
        System.out.printf(
                "[ff-complexity] 16x-history ratios: writes=%.2fx reads=%.2fx (O(n) would be ~16x)%n",
                writeRatio, readRatio);
        assertTrue(
                writeRatio < 5.0,
                "per-commit writes must scale ~log n, not linear: 16x history → "
                        + writeRatio
                        + "x");
        assertTrue(
                readRatio < 5.0,
                "per-commit reads must scale ~log n, not linear: 16x history → " + readRatio + "x");
    }
}
