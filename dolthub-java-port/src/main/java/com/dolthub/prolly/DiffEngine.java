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
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Compares two prolly trees and reports the key-level differences between them — additions,
 * modifications, and deletions.
 *
 * <p>This is the read-only counterpart to {@link MergeEngine}: it answers "what changed between
 * root A and root B?" key by key, emitting one {@link DiffEntry} per difference. The three
 * difference kinds are the {@link DiffType} constants {@code ADD} (a key only in B), {@code MOD} (a
 * key in both with different values), and {@code DEL} (a key only in A). It is the basis for
 * showing a commit's changes, for cherry-pick / revert, and for the change-replay path inside a
 * three-way merge.
 *
 * @apiNote {@link #diff(Node, Node, DiffHandler) diff(rootA, rootB, handler)} streams differences
 *     to the handler in key order; the handler returns {@code false} to stop early (for "is there
 *     any difference?" or "first N" queries). Because the trees are content-addressed, the walk is
 *     cheap on near-identical trees — see the two short-circuits below — so diffing two large
 *     commits that touched a few keys costs work proportional to the change, not to the tree size.
 *     <h4>Algorithm</h4>
 *     <p>Two {@link Cursor}s descend to the leaf level and advance in key order — a lockstep merge
 *     walk. Differences are emitted as they are found. Two Merkle short-circuits keep it cheap on
 *     near-identical trees:
 *     <ul>
 *       <li><b>Whole-tree:</b> identical root bytes → no differences at all.
 *       <li><b>Per-leaf:</b> when both cursors sit at the start of a leaf and those leaves are
 *           byte-identical, the whole leaf is skipped.
 *     </ul>
 *     <p><b>History:</b> an earlier implementation recursed over internal nodes and aligned their
 *     children by <em>separator key</em> (the last key of a child's subtree). Separator keys are
 *     not stable identifiers — a boundary insert/delete shifts them — so that approach
 *     over-reported boundary-shifting edits and crashed outright on trees of differing height. The
 *     leaf-cursor walk compares actual keys, so neither case is special.
 * @implNote <b>Collaborators:</b> {@link Cursor} (the two leaf-level walkers advanced in lockstep),
 *     {@link NodeStore} (resolve child hashes to nodes as each cursor descends), {@link Node} (the
 *     roots and the byte-identity short-circuits), and {@link TupleDescriptor} (key/value column
 *     layout). <b>Dependents:</b> {@link MergeEngine} (replays per-side changes during a three-way
 *     merge) and, in {@code prolly-storage}, {@code Database}'s cherry-pick / revert /
 *     commit-changes paths.
 */
public class DiffEngine {
    private final NodeStore store;
    private final TupleDescriptor descriptor;

    public DiffEngine(NodeStore store, TupleDescriptor descriptor) {
        this.store = store;
        this.descriptor = descriptor;
    }

    public enum DiffType {
        ADD,
        MOD,
        DEL
    }

    public record DiffEntry(
            MemorySegment key,
            @Nullable MemorySegment valueA,
            @Nullable MemorySegment valueB,
            DiffType type) {}

    public interface DiffHandler {
        boolean onDiff(DiffEntry entry); // Return false to stop
    }

    public void diff(@Nullable Node rootA, @Nullable Node rootB, DiffHandler handler) {
        // The push form is a thin wrapper over the streaming cursor — ONE implementation of the
        // lockstep walk (no duplicated logic), so {@link #diffIterator} and {@code diff} can never
        // drift. The handler's {@code false} return stops early simply by ceasing to pull.
        Iterator<DiffEntry> it = diffIterator(rootA, rootB);
        while (it.hasNext()) {
            if (!handler.onDiff(it.next())) return;
        }
    }

    /**
     * Streaming, pull-based form of {@link #diff(Node, Node, DiffHandler)} — yields one {@link
     * DiffEntry} per difference, in key order, computing each on demand as the iterator is
     * advanced.
     *
     * @apiNote Memory is O(tree height) — the two leaf cursors plus their parent chains — and
     *     <b>independent of the change-set size</b>. This is the lever that lets cherry-pick /
     *     revert (and, later, three-way merge) apply an arbitrarily large patch without
     *     materialising it into a heap list first: feed this iterator straight into {@code
     *     TreeMutator.applyMutations}. Same emission order and same {@link DiffEntry} contents as
     *     the push form (pinned by the {@code DiffEngineCursorParityProperty} differential test).
     * @implNote The whole-tree and per-leaf Merkle short-circuits of the push walk are preserved;
     *     an equal-key/equal-value pair emits nothing and the iterator advances both cursors and
     *     keeps looking, so {@code next()} always returns a genuine difference.
     */
    public Iterator<DiffEntry> diffIterator(@Nullable Node rootA, @Nullable Node rootB) {
        return new DiffIterator(rootA, rootB);
    }

    private final class DiffIterator implements Iterator<DiffEntry> {
        private final @Nullable Cursor a;
        private final @Nullable Cursor b;
        private @Nullable DiffEntry lookahead; // next entry to return, or null
        private boolean computed; // whether `lookahead` is a valid computed value

        DiffIterator(@Nullable Node rootA, @Nullable Node rootB) {
            // Whole-tree Merkle short-circuit: identical (or both-absent) roots → zero differences.
            boolean identical =
                    (rootA == null && rootB == null)
                            || (rootA != null
                                    && rootB != null
                                    && Arrays.equals(rootA.bytes(), rootB.bytes()));
            if (identical) {
                this.a = null;
                this.b = null;
            } else {
                this.a = (rootA != null) ? Cursor.atStart(store, rootA) : null;
                this.b = (rootB != null) ? Cursor.atStart(store, rootB) : null;
            }
        }

        @Override
        public boolean hasNext() {
            if (!computed) {
                lookahead = computeNext();
                computed = true;
            }
            return lookahead != null;
        }

        @Override
        public DiffEntry next() {
            if (!hasNext()) throw new NoSuchElementException();
            DiffEntry e =
                    Objects.requireNonNull(lookahead); // hasNext() computed a non-null lookahead
            lookahead = null;
            computed = false;
            return e;
        }

        private @Nullable DiffEntry computeNext() {
            while (isValid(a) || isValid(b)) {
                // Per-leaf Merkle skip: both cursors at the start of a leaf and those leaves
                // byte-identical → the whole leaf matches, skip it.
                if (a != null
                        && b != null
                        && a.isValid()
                        && b.isValid()
                        && a.index() == 0
                        && b.index() == 0
                        && a.isLeaf()
                        && b.isLeaf()
                        && Arrays.equals(a.node().bytes(), b.node().bytes())) {
                    // Both cursors sit at the start of byte-identical leaves. Rather than walking
                    // through them (which fetches the next leaf on each side as it crosses the
                    // boundary), step the PARENTS over every following identical subtree by hash,
                    // reading nothing until the trees actually diverge.
                    if (!skipIdenticalSubtrees(a, b)) {
                        skipLeaf(a);
                        skipLeaf(b);
                    }
                    continue;
                }

                MemorySegment keyA = (a != null && a.isValid()) ? a.currentKey() : null;
                MemorySegment keyB = (b != null && b.isValid()) ? b.currentKey() : null;
                int cmp = compareKeys(keyA, keyB);

                if (cmp < 0) {
                    // Present only in A → deleted in B. cmp<0 ⇒ keyA non-null ⇒ a is valid
                    // (non-null).
                    // requireNonNull asserts that control-flow invariant (which NullAway can't
                    // trace
                    // from cmp) instead of marking the cursor @Nullable — the latter would defeat
                    // the
                    // null check at every other a-deref. (See package-info null-safety notes.)
                    Cursor ca = Objects.requireNonNull(a);
                    DiffEntry e =
                            new DiffEntry(ca.currentKey(), ca.currentValue(), null, DiffType.DEL);
                    ca.advance();
                    return e;
                } else if (cmp > 0) {
                    // Present only in B → added in B. (cmp>0 ⇒ keyB non-null ⇒ b is valid.)
                    Cursor cb = Objects.requireNonNull(b);
                    DiffEntry e =
                            new DiffEntry(cb.currentKey(), null, cb.currentValue(), DiffType.ADD);
                    cb.advance();
                    return e;
                } else {
                    // Same key in both → a MOD iff the values differ. (cmp==0 ⇒ both valid.)
                    Cursor ca = Objects.requireNonNull(a);
                    Cursor cb = Objects.requireNonNull(b);
                    MemorySegment valA = ca.currentValue();
                    MemorySegment valB = cb.currentValue();
                    boolean mod = ByteUtils.compareUnsigned(valA, valB) != 0;
                    DiffEntry e =
                            mod ? new DiffEntry(ca.currentKey(), valA, valB, DiffType.MOD) : null;
                    ca.advance();
                    cb.advance();
                    if (e != null) return e; // equal values emit nothing → keep looking
                }
            }
            return null;
        }
    }

    private static boolean isValid(@Nullable Cursor c) {
        return c != null && c.isValid();
    }

    /** Advances {@code c} past every entry of its current leaf node. */
    /**
     * Step both cursors past sibling subtrees whose child-reference hashes are equal, reading
     * nothing.
     *
     * <p>This is what turns a near-identical diff from {@code O(n)} into {@code O(log n +
     * changes)}. The per-leaf byte comparison can only skip a leaf it has already READ, so the walk
     * still paid one store read per leaf; here the parents' child hashes are compared first, and
     * equal hashes prove the subtrees identical under content addressing — so neither side is
     * materialised at all.
     *
     * <p>Conservative by construction: it steps only while both parents are valid and their child
     * hashes match, and stops at the first difference or exhaustion, leaving the ordinary lockstep
     * walk to handle everything else. It never skips a subtree the two sides do not share.
     *
     * @return {@code true} if at least one subtree pair was skipped (both cursors were re-fetched
     *     to their new positions), {@code false} if nothing matched and the cursors are untouched
     */
    private static boolean skipIdenticalSubtrees(Cursor a, Cursor b) {
        if (a.parent() == null || b.parent() == null) return false;
        boolean skippedAny = false;
        while (true) {
            // Step both parents to their next child WITHOUT materialising it.
            boolean movedA = a.advanceParentOnly();
            boolean movedB = b.advanceParentOnly();
            if (!movedA || !movedB) {
                // One side ran out. The other may have advanced its parent, leaving its own node
                // STALE — a stale node with a live index yields wrong keys, so re-materialise it
                // before handing control back to the ordinary walk.
                if (movedA) a.refetchFromParent();
                if (movedB) b.refetchFromParent();
                return skippedAny;
            }
            MemorySegment refA = a.parentChildRef();
            MemorySegment refB = b.parentChildRef();
            if (refA == null || refB == null || ByteUtils.compareUnsigned(refA, refB) != 0) {
                // Differing (or unavailable) children: descend on both sides and let the
                // ordinary key-wise walk take over from here.
                a.refetchFromParent();
                b.refetchFromParent();
                return true;
            }
            skippedAny = true; // identical subtree — step over it without reading either side
        }
    }

    private static void skipLeaf(Cursor c) {
        Node leaf = c.node();
        while (c.isValid() && c.node() == leaf) {
            c.advance();
        }
    }

    /** Orders two cursor keys; an exhausted (null) side sorts last. */
    private int compareKeys(@Nullable MemorySegment a, @Nullable MemorySegment b) {
        if (a == null) return 1; // A exhausted → only B remains, treat as B < A
        if (b == null) return -1; // B exhausted → only A remains
        return descriptor.compare(new Tuple(a), new Tuple(b));
    }
}
