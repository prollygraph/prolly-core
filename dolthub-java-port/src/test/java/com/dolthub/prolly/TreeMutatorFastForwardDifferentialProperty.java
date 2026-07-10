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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/**
 * Phase D Step 12 of the upstream monorepo's the upstream tree-write-fast-forwarding-impl plan —
 * the fast-forward-vs-re-emit differential. A fast-forward build ({@code applyMutations(baseRoot,
 * edits)}) must produce the <b>byte-identical root</b> as a from-scratch single-batch build of the
 * same final content (the convergence oracle, D-4). Auto-shrinks to a minimal failing (base, edits)
 * when the fast-forward diverges.
 */
class TreeMutatorFastForwardDifferentialProperty {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static String k(int i) {
        return String.format("key-%05d", i);
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

    // Fixed dense base of 700 entries → a multi-leaf (multi-level) tree, the regime the skip needs.
    // Only the edit pattern is shrunk, so jqwik converges on a minimal failing set of edits.
    private static final int BASE_N = 700;

    @Property(tries = 500)
    void multiEditBatchConvergesWithBatchBuild(
            @ForAll @Size(max = 40) List<@IntRange(min = 0, max = BASE_N - 1) Integer> updateIdx,
            @ForAll @Size(max = 20) List<@IntRange(min = 0, max = BASE_N - 1) Integer> deleteIdx,
            @ForAll @Size(max = 20) List<@IntRange(min = 0, max = BASE_N - 1) Integer> insertIdx) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);

            TreeMap<String, String> model = new TreeMap<>();
            for (int i = 0; i < BASE_N; i++) model.put(k(i), "v" + i);
            Node base = m.applyMutations(null, muts(pool, model).iterator());

            // One applyMutations call carrying a mix of update / delete / insert (sorted via
            // TreeMap).
            TreeMap<String, String> edits = new TreeMap<>();
            for (int i : updateIdx) {
                edits.put(k(i), "U" + i);
                model.put(k(i), "U" + i);
            }
            for (int i : deleteIdx) {
                if (model.containsKey(k(i))) {
                    edits.put(k(i), null);
                    model.remove(k(i));
                }
            }
            for (int i : insertIdx) {
                edits.put(k(i) + "-x", "I" + i);
                model.put(k(i) + "-x", "I" + i);
            }

            Node ff = m.applyMutations(base, muts(pool, edits).iterator());
            Node batch = m.applyMutations(null, muts(pool, model).iterator());

            byte[] ffBytes = (ff == null) ? new byte[0] : ff.bytes();
            byte[] batchBytes = (batch == null) ? new byte[0] : batch.bytes();
            if (!java.util.Arrays.equals(batchBytes, ffBytes)) {
                System.out.println(
                        "[ff-DIVERGE] updateIdx="
                                + updateIdx
                                + " deleteIdx="
                                + deleteIdx
                                + " insertIdx="
                                + insertIdx
                                + " ff="
                                + ffBytes.length
                                + "B batch="
                                + batchBytes.length
                                + "B");
            }
            assertArrayEquals(batchBytes, ffBytes, "fast-forward must byte-match the batch build");
        }
    }

    // ---- Deterministic isolation cases (debugging the multi-edit divergence) ----

    private Node base1000(
            HeapBufferPool pool, InMemoryNodeStore store, TreeMap<String, String> mdl) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        for (int i = 0; i < 1000; i++) mdl.put(k(i), "v" + i);
        return m.applyMutations(null, muts(pool, mdl).iterator());
    }

    private void assertConverges(
            HeapBufferPool pool,
            InMemoryNodeStore store,
            Node base,
            TreeMap<String, String> edits,
            TreeMap<String, String> finalModel,
            String label) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        Node ff = m.applyMutations(base, muts(pool, edits).iterator());
        Node batch = m.applyMutations(null, muts(pool, finalModel).iterator());
        byte[] a = (batch == null) ? new byte[0] : batch.bytes();
        byte[] b = (ff == null) ? new byte[0] : ff.bytes();
        assertArrayEquals(a, b, label + ": ff=" + b.length + "B batch=" + a.length + "B");
    }

    @Test
    void isolate_updatesOnly() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMap<String, String> mdl = new TreeMap<>();
            Node base = base1000(pool, store, mdl);
            TreeMap<String, String> edits = new TreeMap<>();
            for (int i = 50; i < 1000; i += 50) { // 19 scattered updates in one call
                edits.put(k(i), "UPDATED" + i);
                mdl.put(k(i), "UPDATED" + i);
            }
            assertConverges(pool, store, base, edits, mdl, "updatesOnly");
        }
    }

    @Test
    void isolate_deletesOnly() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMap<String, String> mdl = new TreeMap<>();
            Node base = base1000(pool, store, mdl);
            TreeMap<String, String> edits = new TreeMap<>();
            for (int i = 50; i < 1000; i += 50) { // 19 scattered deletes in one call
                edits.put(k(i), null);
                mdl.remove(k(i));
            }
            assertConverges(pool, store, base, edits, mdl, "deletesOnly");
        }
    }

    @Test
    void isolate_insertsOnly() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMap<String, String> mdl = new TreeMap<>();
            Node base = base1000(pool, store, mdl);
            TreeMap<String, String> edits = new TreeMap<>();
            for (int i = 0; i < 1000; i += 50) { // 20 inserts between existing keys
                edits.put(k(i) + "-mid", "NEW" + i);
                mdl.put(k(i) + "-mid", "NEW" + i);
            }
            assertConverges(pool, store, base, edits, mdl, "insertsOnly");
        }
    }

    @Test
    void isolate_adjacentEdits() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMap<String, String> mdl = new TreeMap<>();
            Node base = base1000(pool, store, mdl);
            TreeMap<String, String> edits = new TreeMap<>();
            for (int i = 500; i <= 530; i++) { // 31 consecutive updates
                edits.put(k(i), "ADJ" + i);
                mdl.put(k(i), "ADJ" + i);
            }
            assertConverges(pool, store, base, edits, mdl, "adjacentEdits");
        }
    }

    @Test
    void isolate_contiguousDeletes() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMap<String, String> mdl = new TreeMap<>();
            Node base = base1000(pool, store, mdl);
            TreeMap<String, String> edits = new TreeMap<>();
            for (int i = 300; i < 450; i++) { // delete a 150-key run (spans whole leaves)
                edits.put(k(i), null);
                mdl.remove(k(i));
            }
            assertConverges(pool, store, base, edits, mdl, "contiguousDeletes");
        }
    }

    @Test
    void isolate_clusteredMixed() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMap<String, String> mdl = new TreeMap<>();
            Node base = base1000(pool, store, mdl);
            TreeMap<String, String> edits = new TreeMap<>();
            for (int i = 400; i < 440; i++) { // update / delete / insert interleaved in a cluster
                if (i % 3 == 0) {
                    edits.put(k(i), "U" + i);
                    mdl.put(k(i), "U" + i);
                } else if (i % 3 == 1) {
                    edits.put(k(i), null);
                    mdl.remove(k(i));
                } else {
                    edits.put(k(i) + "-x", "I" + i);
                    mdl.put(k(i) + "-x", "I" + i);
                }
            }
            assertConverges(pool, store, base, edits, mdl, "clusteredMixed");
        }
    }

    private static void collectLeaves(NodeStore store, Node n, List<Node> out) {
        if (n == null) return;
        if (n.isLeaf()) {
            out.add(n);
            return;
        }
        for (int i = 0; i < n.count(); i++) {
            collectLeaves(store, store.read(n.getValue(i)).map(Node::fromBytes).orElseThrow(), out);
        }
    }

    private static String tk(MemorySegment seg) {
        return new String(new Tuple(seg).getField(0));
    }

    private static List<String> leafBounds(NodeStore store, Node root) {
        List<Node> leaves = new ArrayList<>();
        collectLeaves(store, root, leaves);
        List<String> res = new ArrayList<>();
        for (Node leaf : leaves) {
            res.add(
                    tk(leaf.getKeySegment(0))
                            + ".."
                            + tk(leaf.getKeySegment(leaf.count() - 1))
                            + "("
                            + leaf.count()
                            + ")");
        }
        return res;
    }

    @Test
    void regression_singleInsertMergingLeavesEmitsNoDuplicate() {
        // The single-insert convergence bug (2026-06-24): inserting key-00268-x into a 700-entry
        // tree
        // merges base leaves 0+1 (000..392) in the batch build; the fast-forward used to emit BOTH
        // the merged leaf AND a stale by-reference copy of original leaf 0 (000..272) — a duplicate
        // child from createParentChunker's eager processPrefix. Pins leaf structure +
        // byte-identity.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            TreeMap<String, String> model = new TreeMap<>();
            for (int i = 0; i < 700; i++) model.put(k(i), "v" + i);
            Node base = m.applyMutations(null, muts(pool, model).iterator());

            TreeMap<String, String> edit = new TreeMap<>();
            edit.put(k(268) + "-x", "INS");
            Node ff = m.applyMutations(base, muts(pool, edit).iterator());

            model.put(k(268) + "-x", "INS");
            Node batch = m.applyMutations(null, muts(pool, model).iterator());

            assertArrayEquals(
                    leafBounds(store, batch).toArray(),
                    leafBounds(store, ff).toArray(),
                    "fast-forward leaf structure must match the batch build (no duplicate child)");
            assertArrayEquals(
                    batch.bytes(), ff.bytes(), "fast-forward root must byte-match the batch build");
        }
    }

    @Test
    void isolate_twoUpdates() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TreeMap<String, String> mdl = new TreeMap<>();
            Node base = base1000(pool, store, mdl);
            TreeMap<String, String> edits = new TreeMap<>();
            for (int i : new int[] {200, 600}) {
                edits.put(k(i), "U" + i);
                mdl.put(k(i), "U" + i);
            }
            assertConverges(pool, store, base, edits, mdl, "twoUpdates");
        }
    }
}
