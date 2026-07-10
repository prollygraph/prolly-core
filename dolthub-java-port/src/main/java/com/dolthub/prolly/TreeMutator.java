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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Builds a new prolly-tree root by merging a sorted edit stream into an existing tree — the {@code
 * applyMutations} core.
 *
 * <p><b>Not the RDF 3-way merge</b> (that is {@code MergeEngine}): this is the structural
 * tree-mutation engine that every write goes through to turn {base tree + sorted edits} into a new
 * immutable root.
 *
 * @apiNote The edit iterator <b>must be sorted by key</b> (the merge walks both streams in order) —
 *     an unsorted stream silently corrupts the result. {@link #applyMutations} returns the new root
 *     {@link Node} and <i>writes every new/changed chunk to the store as it builds</i>; under a
 *     concurrent garbage collector the caller must hold the garbage-collection read lock across the
 *     whole call (see {@code Database}'s garbage-collection-safe commit path / {@code
 *     bugs/gc-concurrent-write-flush-window.md}).
 * @implNote A {@link RollingHashSplitter} sets node boundaries by content, so an unchanged subtree
 *     keeps its hash and is <b>not</b> rewritten — that's the structural sharing that makes history
 *     cheap. The inner {@code Chunker} emits nodes bottom-up via {@link NodeStore#write},
 *     serializing each with the node serializer.
 *     <p><b>Collaborators:</b> {@link NodeStore} (writes chunks), {@link RollingHashSplitter}
 *     (boundaries), {@link Node}, {@link TupleDescriptor}/{@link BufferPool}, the node serializer.
 *     <b>Dependents:</b> {@link MutableMap#flush}, plus {@code MergeEngine} / cherryPick / revert
 *     downstream.
 */
public class TreeMutator {
    private final NodeStore store;
    private final BoundarySplitter.Factory splitterFactory;
    private final TupleDescriptor descriptor;
    private final BufferPool pool;

    public TreeMutator(NodeStore store, TupleDescriptor descriptor, BufferPool pool) {
        this(store, descriptor, pool, BoundarySplitter.ROLLING_HASH);
    }

    /**
     * Seam constructor (the upstream SPOC boundary-function-adoption plan, D-1): inject the
     * boundary function per tree. The 3-arg constructor keeps the production default.
     */
    public TreeMutator(
            NodeStore store,
            TupleDescriptor descriptor,
            BufferPool pool,
            BoundarySplitter.Factory splitterFactory) {
        this.store = store;
        this.descriptor = descriptor;
        this.pool = pool;
        this.splitterFactory = splitterFactory;
    }

    public static record Mutation(MemorySegment key, @Nullable MemorySegment value) {}

    /**
     * Applies a SORTED stream of edits to {@code root}, producing a new tree. The input iterator
     * must be sorted ascending by key under {@code descriptor}; unsorted input silently corrupts
     * the resulting tree. This method enforces the contract by throwing on any out-of-order edit.
     */
    /**
     * Merges a sorted edit stream into {@code root}, returning the new tree's root node.
     *
     * @param root the base tree's root, or {@code null} to build from empty
     * @param edits the mutations to apply, <b>sorted ascending by key</b> (precondition — an
     *     unsorted stream silently produces a wrong tree); a {@code null}/tombstone value deletes
     *     the key
     * @return the new root {@link Node}; unchanged subtrees keep their hashes (structural sharing)
     * @apiNote Every new/changed chunk is written to the {@link NodeStore} during the call — hold
     *     the garbage-collection read lock across it under a concurrent collector (see {@code
     *     Database}'s garbage-collection-safe commit path).
     * @implNote Walks {@code root} and {@code edits} in lockstep through a {@link
     *     RollingHashSplitter}. {@link Chunker#advanceTo} <b>fast-forwards</b>: it skips unchanged
     *     subtrees by reference (synchronize-then-skip, ported from Dolt's chunker) instead of
     *     re-emitting every entry, so a single-key edit does <b>{@code O(log n)}</b> work — only
     *     the affected root→leaf spine — and a commit-per-change loop is {@code O(n log n)}, not
     *     the old re-emit's {@code O(n)}/{@code O(n²)}. Convergence is preserved exactly: a
     *     fast-forwarded build yields the byte-identical root to a from-scratch build (pinned by
     *     {@code TreeMutatorFastForwardDifferentialProperty} + the Merkle convergence/determinism
     *     stress tests); the {@code O(log n)} per-commit cost is measured by {@code
     *     TreeMutatorFastForwardComplexityTest} (flat across 16× history). Disk sharing also holds
     *     (re-emitted boundary chunks are content-addressed → {@code store.write} dedups them). The
     *     fast-forwarding restoration: the upstream tree-write fast-forwarding plan + the upstream
     *     ADR-0068 (tree-write re-emit and fast-forwarding).
     */
    public @Nullable Node applyMutations(@Nullable Node root, Iterator<Mutation> edits) {
        // One write batch spans the whole build: every chunk this method
        // persists is new, and the build only reads the prior tree (via the
        // cursor), never a chunk it just wrote — so buffering the new chunks
        // until the build finishes is safe. endWriteBatch runs in a finally
        // so a mid-build failure still flushes (orphan chunks are
        // content-addressed and harmless).
        store.beginWriteBatch();
        try {
            Chunker leafChkr = new Chunker(0, (root == null) ? null : Cursor.atStart(store, root));
            Mutation lastEdit = null;
            while (edits.hasNext()) {
                Mutation edit = edits.next();
                if (lastEdit != null) {
                    int cmp = descriptor.compare(new Tuple(lastEdit.key()), new Tuple(edit.key()));
                    if (cmp > 0) {
                        throw new IllegalArgumentException(
                                "TreeMutator.applyMutations requires a sorted edit stream; "
                                        + "got out-of-order key");
                    }
                    if (cmp == 0) {
                        // Duplicate key: take the last value.
                        lastEdit = edit;
                        continue;
                    }
                }
                if (lastEdit != null) applyOne(leafChkr, lastEdit);
                lastEdit = edit;
            }
            if (lastEdit != null) applyOne(leafChkr, lastEdit);
            return leafChkr.done();
        } finally {
            store.endWriteBatch();
        }
    }

    private void applyOne(Chunker leafChkr, Mutation edit) {
        leafChkr.advanceTo(edit.key());
        if (edit.value() != null) {
            leafChkr.put(edit.key(), edit.value(), 1);
        }
    }

    protected class Chunker {
        private final int level;
        private final BoundarySplitter splitter;
        private final FlatbufferNodeSerializer serializer;
        private @Nullable Cursor cursor;
        private final List<PendingItem> pending = new ArrayList<>();
        private @Nullable Chunker parent;

        private Chunker(int level, @Nullable Cursor cursor) {
            this.level = level;
            this.cursor = cursor;
            this.splitter = splitterFactory.create(level);
            this.serializer = new FlatbufferNodeSerializer();
        }

        /**
         * Fast-forward this chunker's cursor to the next edit at {@code targetKey}, skipping
         * unchanged subtrees by reference rather than re-emitting every entry ({@code O(log n)},
         * not {@code O(existing-tree)}). Builds a fresh cursor {@code next} at the edit point over
         * the same base tree, runs the synchronize-then-skip ({@link #advanceToCursor}), then
         * consumes the existing entry the edit updates/deletes (an insert finds a non-equal
         * successor and is not consumed). Ported from Dolt {@code chunker.advanceTo}.
         */
        public void advanceTo(MemorySegment targetKey) {
            if (cursor == null) return;
            // cursor is non-null here and the field is never reassigned (only advanced/copied in
            // place), so a captured local stays valid across the method calls below — which
            // NullAway
            // would otherwise treat as possibly clearing the field's nullness.
            Cursor cur = cursor;
            // Seeded seek: reuse the spine nodes already materialized in this chunker's cursor and
            // read only below the divergence — the flush's sorted stream lands runs of edits in the
            // same leaf, and the root-restart atKey here re-fetched the whole spine per edit (52%
            // of ingest allocation; plans/flush-node-read-alloc.md Step 2).
            Cursor next = Cursor.atKeyFrom(cur, targetKey, descriptor);
            advanceToCursor(next);
            if (cur.isValid()
                    && descriptor.compare(new Tuple(cur.currentKey()), new Tuple(targetKey)) == 0) {
                cur.advance();
            }
        }

        /**
         * The synchronize-then-skip core (Dolt {@code chunker.advanceTo}): re-emit entries until a
         * freshly-built chunk boundary <em>aligns</em> with an old node end ({@code split &&
         * cursor.atNodeEnd()}); at that alignment the run up to {@code next} is unchanged, so
         * advance the (shared) parent cursor, recurse into the parent chunker to skip the run by
         * reference, jump the cursor to {@code next}, and re-emit the edited node's prefix ({@link
         * #processPrefix}). If the cursor catches up to {@code next} before any boundary aligns,
         * the prefix was simply re-emitted (the slow-but-correct fall-back, D-5).
         */
        private void advanceToCursor(Cursor next) {
            // Reached only from advanceTo() (after its cursor!=null guard) or recursively as a
            // parent chunker that was seeded with a non-null parent cursor — so cursor is non-null
            // here. requireNonNull asserts that (and the captured local survives the method calls
            // below, which NullAway would otherwise treat as clearing the field's nullness).
            Cursor cur = Objects.requireNonNull(cursor);
            int cmp = cur.compare(next);
            if (cmp == 0) {
                return;
            } else if (cmp > 0) {
                // Defensive (Dolt's seek-bug note): if somehow past next, walk next up to us.
                while (cur.compare(next) > 0) {
                    next.advance();
                }
                return;
            }

            boolean split = append(cur.currentKey(), cur.currentValue(), cur.currentSubtreeSize());
            while (!(split && cur.atNodeEnd())) {
                cur.advance();
                if (cur.compare(next) >= 0) {
                    return; // caught up before a boundary aligned — the prefix is fully re-emitted
                }
                split = append(cur.currentKey(), cur.currentValue(), cur.currentSubtreeSize());
            }

            // advance() moves position but never re-points the parent cursor, so cur.parent() is
            // the
            // same object throughout; capture both so the null-checks below narrow them for
            // NullAway.
            Cursor curParent = cur.parent();
            Cursor nextParent = next.parent();
            if (curParent == null || nextParent == null) {
                cur.copy(next); // reached the end of the tree spine
                return;
            }
            if (curParent.compare(nextParent) == 0) {
                cur.copy(next); // parents already aligned (caught up at the same moment)
                return;
            }

            // Synchronized one level up: skip the unchanged run by advancing the shared parent
            // cursor and recursing, then jump to the edit point and re-emit its node's prefix.
            curParent.advance();
            cur.invalidateAtEnd();
            // parent is non-null here: the loop exits only with split==true, and a split means
            // append() crossed a boundary → handleChunkBoundary → ensureParent created the parent.
            Objects.requireNonNull(parent).advanceToCursor(nextParent);
            cur.copy(next);
            processPrefix();
        }

        /**
         * Re-emit the prefix of the cursor's current node (from its start up to the cursor index)
         * after a parent-level synchronization, so the edited node rebuilds from the correct offset
         * (Dolt {@code chunker.processPrefix}). Re-emits within a single node, so it never crosses
         * a leaf boundary. The parent-creation guard mirrors Dolt; in the build-from-start driver
         * the parent already exists by the time this runs.
         */
        private void processPrefix() {
            // Reached from advanceToCursor()'s sync branch, where cursor is non-null.
            Cursor cur = Objects.requireNonNull(cursor);
            if (cur.parent() != null && parent == null) {
                parent = createParentChunker();
            }
            int idx = cur.index();
            cur.skipToNodeStart();
            while (cur.index() < idx) {
                append(cur.currentKey(), cur.currentValue(), cur.currentSubtreeSize());
                cur.advance();
            }
        }

        /** The base tree's root node, derived from the cursor's top ancestor (always the root). */
        private Node rootNode() {
            // Only called from advanceTo() after its cursor!=null guard.
            Cursor c = Objects.requireNonNull(cursor);
            Cursor p = c.parent();
            while (p != null) {
                c = p;
                p = c.parent();
            }
            return c.node();
        }

        public void put(MemorySegment key, MemorySegment value, long subtreeCount) {
            append(key, value, subtreeCount);
        }

        /**
         * Append one item to the in-progress chunk; returns whether it crossed a chunk boundary, so
         * the fast-forward path can detect a new↔old boundary alignment. Ported from Dolt {@code
         * chunker.append}, including its <b>constraint (3)</b> — an internal node must hold ≥2
         * children — via the {@code degenerateInternalNode} guard below.
         *
         * @implNote Constraint (3) is load-bearing, not cosmetic: a key whose bytes alone exceed
         *     the splitter's ramp force-offset crosses a boundary even as a <i>lone</i> {@code
         *     (key, childHash)} internal item, so without the guard each level would emit a
         *     single-child node and create another, cascading upward forever — an unbounded
         *     recursion (StackOverflowError) reachable by <b>one</b> adversarial ~16&nbsp;KiB+ key,
         *     i.e. a denial-of-service on the core write path (pinned by {@link
         *     DegenerateInternalNodeGuardTest}; see ADR-0069). Dolt's other constraint — (2), the
         *     {@code hasCapacity} byte-overflow pre-flush — is intentionally <b>not</b> modeled:
         *     the port's lower rolling-hash cap already bounds multi-item nodes, and a lone
         *     oversized item is the defined large-chunk behavior ({@code
         *     SplitterGeometryProperty}). Normal trees are unaffected — an internal node only ever
         *     holds a single item when a key is large enough to cross on its own, which never
         *     happens for non-pathological keys.
         */
        private boolean append(MemorySegment key, MemorySegment value, long subtreeCount) {
            splitter.append(key, value);
            pending.add(new PendingItem(key, value, subtreeCount));
            // Constraint (3): suppress a boundary on a single-item internal node so it cannot start
            // an
            // unbounded single-child cascade; it accumulates a 2nd child (or is flushed by done()).
            boolean degenerateInternalNode = level > 0 && pending.size() == 1;
            if (splitter.crossedBoundary() && !degenerateInternalNode) {
                handleChunkBoundary();
                return true;
            }
            return false;
        }

        private void handleChunkBoundary() {
            if (pending.isEmpty()) return;
            byte[] nodeBytes = serializer.serialize(level, pending);
            byte[] hash = store.write(nodeBytes);
            appendToParent(
                    pending.get(pending.size() - 1).key(),
                    MemorySegment.ofArray(hash),
                    pending.stream().mapToLong(PendingItem::subtreeCount).sum());
            pending.clear();
            splitter.reset();
        }

        /**
         * Emit a completed child chunk {@code (key, childHash, subtreeCount)} up to the parent
         * chunker, lazily creating it. Named once here (Dolt {@code chunker.appendToParent}) so
         * both {@code handleChunkBoundary} and {@code done} share it — and so Phase C's
         * fast-forward skip can emit a reused subtree by reference through the same path.
         */
        private void appendToParent(MemorySegment key, MemorySegment childHash, long subtreeCount) {
            ensureParent().put(key, childHash, subtreeCount);
        }

        private Chunker ensureParent() {
            if (parent == null) {
                parent = createParentChunker();
            }
            return parent; // non-null after the guard above
        }

        /**
         * Create the parent (next-level-up) chunker, <b>seeded with the old tree's parent-level
         * cursor</b> ({@code cursor.parent()} — the SHARED object, so advancing the leaf cursor
         * across a boundary advances it too) so the parent can skip unchanged subtrees by
         * reference. A from-scratch build (null cursor) keeps a null-cursor parent that can only
         * re-emit.
         *
         * <p><b>No {@code processPrefix} here</b> (unlike Dolt's {@code createParentChunker} +
         * {@code newChunker}). Dolt builds the chunker AT the first edit, so each parent must
         * re-emit its prefix on construction; this port builds from {@code atStart} and processes
         * left-to-right, so a parent's prior siblings arrive naturally via {@code appendToParent}
         * as leaves finish. Calling {@code processPrefix} on lazy creation double-emits: when a
         * parent is created late — e.g. an insert merges two leaves, advancing the shared parent
         * cursor past index 0 — its prefix is already folded into the re-chunked node, so
         * re-emitting it by reference produced a stale duplicate child (the single-insert
         * convergence bug, 2026-06-24).
         */
        private Chunker createParentChunker() {
            return new Chunker(level + 1, (cursor != null) ? cursor.parent() : null);
        }

        public @Nullable Node done() {
            finalizeCursor();

            if (pending.isEmpty()) {
                return (parent != null) ? parent.done() : null;
            }

            byte[] nodeBytes = serializer.serialize(level, pending);
            // Always persist the root chunk. Earlier this was skipped on the
            // parent==null path (single-chunk tree), which meant small trees
            // never reached disk. store.write is content-addressed/idempotent,
            // so writing the chunk that a future `parent.put` references is a
            // no-op duplicate.
            byte[] hash = store.write(nodeBytes);
            if (parent == null) return Node.fromBytes(MemorySegment.ofArray(nodeBytes));

            appendToParent(
                    pending.get(pending.size() - 1).key(),
                    MemorySegment.ofArray(hash),
                    pending.stream().mapToLong(PendingItem::subtreeCount).sum());
            // parent is non-null here: the parent==null case returned above; appendToParent doesn't
            // clear it (NullAway can't see that across the call), so assert it rather than weaken.
            return Objects.requireNonNull(parent).done();
        }

        /**
         * Drain the cursor's remaining entries (the suffix after the last edit), <b>fast-forwarding
         * the right side too</b> (Dolt {@code chunker.finalizeCursor}): re-emit until a new
         * boundary aligns with an old node end, then advance the (shared) parent cursor so the
         * parent chunker's own finalize skips the rest of the suffix by reference. The rightmost
         * spine (no natural boundary) is fully re-emitted at each level — required for convergence.
         * Uses {@code cursor.currentSubtreeSize()} (the D-6 individual count), never the raw
         * cumulative {@code getSubtreeCount}. (This also delivers the parent plan's Phase 2
         * right-side skip.)
         */
        private void finalizeCursor() {
            if (cursor == null) return;
            Cursor cur = cursor; // non-null past the guard; field never reassigned
            while (cur.isValid()) {
                boolean split =
                        append(cur.currentKey(), cur.currentValue(), cur.currentSubtreeSize());
                if (split && cur.atNodeEnd()) {
                    break; // boundary aligned with the old node end — the parent handles the rest
                }
                cur.advance();
            }
            Cursor curParent = cur.parent();
            if (curParent != null) {
                curParent.advance();
                cur.invalidateAtEnd(); // mark this level finalized (Dolt nulls cur.nd)
            }
        }
    }

    public static record PendingItem(MemorySegment key, MemorySegment value, long subtreeCount) {}
}
