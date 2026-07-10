/*
 * Copyright 2026 Earasoft
 * Copyright 2021 Dolthub, Inc.
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

import java.lang.foreign.MemorySegment;
import java.util.*;
import org.jspecify.annotations.Nullable;

/**
 * Three-way structural merge of two prolly trees against their common ancestor.
 *
 * <p>This is the data-level engine behind a branch merge: given the ancestor tree and the two
 * divergent trees ("ours" and "theirs"), it produces one merged tree plus the list of conflicts it
 * could not resolve automatically. Edits that touch disjoint keys merge cleanly; edits where both
 * sides changed the <em>same</em> key to <em>different</em> values surface as a {@link Conflict}
 * for the caller to settle. The engine never picks a winner on a real conflict — that policy
 * belongs to the caller (a clean merge has an empty conflict list; the commit layer refuses to
 * commit otherwise).
 *
 * @apiNote {@link #merge(Node, Node, Node) merge(ancestor, ours, theirs)} returns a {@link
 *     MergeResult} carrying the merged {@link Node} root and a {@code List<}{@link Conflict}{@code
 *     >}. Each conflict names the key plus the ancestor / our / their values so the caller can
 *     present or resolve it. Two fast paths short-circuit the common cases: with no ancestor an
 *     empty side contributes nothing (keep the other side); a side byte-identical to the ancestor
 *     is a fast-forward (take the other side wholesale). Merging writes the merged tree's new
 *     chunks to the store, so a caller running it under a concurrent garbage collector must hold
 *     the collector's read lock for the build-and-commit window — see {@code Database}'s merge path
 *     and docs/write-ups/gc-concurrent-write-flush-window.md.
 * @implNote <b>Collaborators:</b> {@link NodeStore} (read the three input trees, write the merged
 *     chunks), {@link Node} (the tree roots), {@link TupleDescriptor} (key/value layout so a
 *     per-key three-way decision can read the columns), {@link BufferPool} (scratch buffers for
 *     rebuilt nodes), {@link HashUtils} (the fast-forward identity check), and a leaf-level diff
 *     walk like {@link DiffEngine}'s to find the changed keys on each side. <b>Dependents:</b>
 *     {@code Database.merge} (prolly-storage) drives this and only commits when the conflict list
 *     is empty.
 */
public class MergeEngine {
    private final NodeStore store;
    private final TupleDescriptor descriptor;
    private final BufferPool pool;
    private final BoundarySplitter.Factory splitterFactory;

    public MergeEngine(NodeStore store, TupleDescriptor descriptor, BufferPool pool) {
        this(store, descriptor, pool, BoundarySplitter.ROLLING_HASH);
    }

    /**
     * Seam constructor (the upstream SPOC boundary-function-adoption plan, D-1): a merged tree must
     * chunk with the SAME boundary function its inputs were built with, or the merge breaks
     * cross-version chunk sharing — callers that inject a splitter at build time must inject the
     * same one here.
     */
    public MergeEngine(
            NodeStore store,
            TupleDescriptor descriptor,
            BufferPool pool,
            BoundarySplitter.Factory splitterFactory) {
        this.store = store;
        this.descriptor = descriptor;
        this.pool = pool;
        this.splitterFactory = splitterFactory;
    }

    public record MergeResult(@Nullable Node root, List<Conflict> conflicts) {}

    public record Conflict(
            MemorySegment key,
            @Nullable MemorySegment baseVal,
            @Nullable MemorySegment ourVal,
            @Nullable MemorySegment theirVal) {}

    public MergeResult merge(@Nullable Node ancestor, @Nullable Node ours, @Nullable Node theirs) {
        MergeResult ff = fastPath(ancestor, ours, theirs);
        return ff != null ? ff : mergeStreaming(ancestor, ours, theirs);
    }

    /**
     * The two short-circuits shared by both merge implementations; {@code null} if neither applies.
     *
     * <p>A null root is an empty tree. With NO common ancestor, an empty side genuinely contributes
     * nothing — keep the other side. (With an ancestor, a now-empty side DELETED everything since
     * the ancestor; that must replay the deletes, so it falls through.) Then the fast-forward: a
     * side byte-identical to the ancestor (compared via one O(node-bytes) hash, not a full
     * materialised {@code Arrays.equals}) means take the other side wholesale.
     */
    private @Nullable MergeResult fastPath(
            @Nullable Node ancestor, @Nullable Node ours, @Nullable Node theirs) {
        if (ancestor == null) {
            if (ours == null) return new MergeResult(theirs, List.of());
            if (theirs == null) return new MergeResult(ours, List.of());
        }
        if (ancestor != null && ours != null && theirs != null) {
            byte[] ancHash = HashUtils.hash(ancestor.segment().asByteBuffer());
            if (Arrays.equals(HashUtils.hash(ours.segment().asByteBuffer()), ancHash)) {
                return new MergeResult(theirs, List.of());
            }
            if (Arrays.equals(HashUtils.hash(theirs.segment().asByteBuffer()), ancHash)) {
                return new MergeResult(ours, List.of());
            }
        }
        return null;
    }

    /**
     * Streaming three-way merge — the LIVE implementation (out-of-memory hardening, Phase 2).
     *
     * @implNote A <b>sorted-merge-join</b> of the two side-diffs: both {@code diffIterator}s emit
     *     in key order, so they are advanced in lockstep with O(1) lookahead — no per-side {@code
     *     TreeMap} is materialised (that was the {@code collectChanges} out-of-memory). The merged
     *     mutations are themselves a <b>stream</b> fed to {@code applyMutations}, so peak heap is
     *     O(tree height), not O(change-set). The per-key decision is identical to {@link
     *     #mergeMaterialized} (same-change → take it; differing change → {@link Conflict};
     *     one-sided → take that side), and the equivalence is pinned byte-for-byte by {@code
     *     MergeStreamingParityProperty}. <b>Conflicts still accumulate</b> into a list — they are
     *     rare (RDF set-union merges seldom conflict) so they are not the dominant memory term; an
     *     explicit conflict cap is a separate, behaviour- changing follow-up (plan Phase 2),
     *     deliberately not done here so this stays equivalent.
     */
    private MergeResult mergeStreaming(
            @Nullable Node ancestor, @Nullable Node ours, @Nullable Node theirs) {
        DiffEngine diff = new DiffEngine(store, descriptor);
        Iterator<DiffEngine.DiffEntry> ourIt = diff.diffIterator(ancestor, ours);
        Iterator<DiffEngine.DiffEntry> theirIt = diff.diffIterator(ancestor, theirs);
        List<Conflict> conflicts = new ArrayList<>();

        Iterator<TreeMutator.Mutation> merged =
                new Iterator<>() {
                    DiffEngine.@Nullable DiffEntry o = ourIt.hasNext() ? ourIt.next() : null;
                    DiffEngine.@Nullable DiffEntry t = theirIt.hasNext() ? theirIt.next() : null;
                    TreeMutator.@Nullable Mutation lookahead;
                    boolean computed;

                    @Override
                    public boolean hasNext() {
                        if (!computed) {
                            lookahead = computeNext();
                            computed = true;
                        }
                        return lookahead != null;
                    }

                    @Override
                    public TreeMutator.Mutation next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        TreeMutator.Mutation m =
                                Objects.requireNonNull(lookahead); // hasNext computed it
                        lookahead = null;
                        computed = false;
                        return m;
                    }

                    private TreeMutator.@Nullable Mutation computeNext() {
                        while (o != null || t != null) {
                            int cmp;
                            if (o == null) cmp = 1; // ours exhausted → theirs sorts first
                            else if (t == null) cmp = -1; // theirs exhausted → ours sorts first
                            else cmp = descriptor.compare(new Tuple(o.key()), new Tuple(t.key()));

                            if (cmp < 0) { // changed only on our side (cmp<0 ⇒ o non-null)
                                DiffEngine.DiffEntry oc = Objects.requireNonNull(o);
                                TreeMutator.Mutation m =
                                        new TreeMutator.Mutation(oc.key(), oc.valueB());
                                o = ourIt.hasNext() ? ourIt.next() : null;
                                return m;
                            } else if (cmp > 0) { // changed only on their side (cmp>0 ⇒ t non-null)
                                DiffEngine.DiffEntry tc = Objects.requireNonNull(t);
                                TreeMutator.Mutation m =
                                        new TreeMutator.Mutation(tc.key(), tc.valueB());
                                t = theirIt.hasNext() ? theirIt.next() : null;
                                return m;
                            } else { // both changed the same key (cmp==0 ⇒ both non-null)
                                DiffEngine.DiffEntry oc = Objects.requireNonNull(o);
                                DiffEngine.DiffEntry tc = Objects.requireNonNull(t);
                                TreeMutator.Mutation m;
                                if (isSameChange(oc, tc)) {
                                    m = new TreeMutator.Mutation(oc.key(), oc.valueB());
                                } else {
                                    conflicts.add(
                                            new Conflict(
                                                    oc.key(),
                                                    oc.valueA(),
                                                    oc.valueB(),
                                                    tc.valueB()));
                                    m = null; // conflict → no merged mutation
                                }
                                o = ourIt.hasNext() ? ourIt.next() : null;
                                t = theirIt.hasNext() ? theirIt.next() : null;
                                if (m != null) return m;
                            }
                        }
                        return null;
                    }
                };

        TreeMutator mutator = new TreeMutator(store, descriptor, pool, splitterFactory);
        Node newRoot = mutator.applyMutations(ancestor, merged);
        return new MergeResult(newRoot, conflicts);
    }

    /**
     * The original materialised merge — kept as the <b>differential reference</b> for {@code
     * MergeStreamingParityProperty}: it is the trusted spec the streaming form must match
     * byte-for-byte (identical merged root + identical conflicts). NOT the live path.
     * Package-private for the test. Materialises both side-diffs into {@code TreeMap}s — the
     * out-of-memory {@link #mergeStreaming} removes; kept only because a differential against the
     * trusted prior implementation is the strongest correctness guarantee for a
     * data-integrity-critical operation.
     */
    MergeResult mergeMaterialized(
            @Nullable Node ancestor, @Nullable Node ours, @Nullable Node theirs) {
        MergeResult ff = fastPath(ancestor, ours, theirs);
        if (ff != null) return ff;

        Map<MemorySegment, DiffEngine.DiffEntry> ourChanges = collectChanges(ancestor, ours);
        Map<MemorySegment, DiffEngine.DiffEntry> theirChanges = collectChanges(ancestor, theirs);

        List<TreeMutator.Mutation> mergedMutations = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();

        Set<MemorySegment> allKeys =
                new TreeSet<>((a, b) -> descriptor.compare(new Tuple(a), new Tuple(b)));
        allKeys.addAll(ourChanges.keySet());
        allKeys.addAll(theirChanges.keySet());

        for (MemorySegment key : allKeys) {
            DiffEngine.DiffEntry ourChange = ourChanges.get(key);
            DiffEngine.DiffEntry theirChange = theirChanges.get(key);

            if (ourChange != null && theirChange != null) {
                if (isSameChange(ourChange, theirChange)) {
                    mergedMutations.add(new TreeMutator.Mutation(key, ourChange.valueB()));
                } else {
                    conflicts.add(
                            new Conflict(
                                    key,
                                    ourChange.valueA(),
                                    ourChange.valueB(),
                                    theirChange.valueB()));
                }
            } else if (ourChange != null) {
                mergedMutations.add(new TreeMutator.Mutation(key, ourChange.valueB()));
            } else {
                // key ∈ ourChanges ∪ theirChanges, and ourChange == null here ⇒ the key came from
                // theirChanges, so theirChange is present. NullAway can't see this set-union
                // invariant.
                // requireNonNull asserts it — and fails loudly at THIS site if a future change ever
                // breaks it — rather than @SuppressWarnings("NullAway"), which would silence the
                // null
                // check for the whole block and could hide a genuine null bug. (See package-info:
                // the
                // bug-preserving answer keeps the @Nullable contract intact everywhere else.)
                DiffEngine.DiffEntry tc = Objects.requireNonNull(theirChange);
                mergedMutations.add(new TreeMutator.Mutation(key, tc.valueB()));
            }
        }

        TreeMutator mutator = new TreeMutator(store, descriptor, pool, splitterFactory);
        Node newRoot = mutator.applyMutations(ancestor, mergedMutations.iterator());

        return new MergeResult(newRoot, conflicts);
    }

    private Map<MemorySegment, DiffEngine.DiffEntry> collectChanges(
            @Nullable Node base, @Nullable Node head) {
        Map<MemorySegment, DiffEngine.DiffEntry> changes =
                new TreeMap<>((a, b) -> descriptor.compare(new Tuple(a), new Tuple(b)));
        DiffEngine engine = new DiffEngine(store, descriptor);
        engine.diff(
                base,
                head,
                entry -> {
                    changes.put(entry.key(), entry);
                    return true;
                });
        return changes;
    }

    private boolean isSameChange(DiffEngine.DiffEntry a, DiffEngine.DiffEntry b) {
        if (a.type() != b.type()) return false;
        if (a.type() == DiffEngine.DiffType.DEL) return true;
        if (a.valueB() == null || b.valueB() == null) return a.valueB() == b.valueB();
        return ByteUtils.compareUnsigned(a.valueB(), b.valueB()) == 0;
    }
}
