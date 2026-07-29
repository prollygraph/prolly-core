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
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 *
 *
 * <h3>Recursive Tree Cursor</h3>
 */
public class Cursor {
    private final NodeStore store;
    private final @Nullable Cursor parent;
    private Node node;
    private int index;

    // Self-invalidating cache of currentKey()'s slice. The node-key slice was the #1 allocation
    // site on
    // the triejoin descent (Node$1.getKeySegment) because hot callers re-slice the SAME position
    // many times
    // per visit (atEnd / key / valid / field-compare). Recompute only when (node, index) changes —
    // detected
    // by comparison, so NO mutator needs to invalidate it (correctness-safe by construction; a
    // missed
    // mutator would just be a stale cache otherwise). the upstream triejoin-performance plan,
    // Phase 3, lever (a).
    //
    // @Nullable because lazily populated: null until the first currentKey() call — which is exactly
    // what NullAway reports as "initializer does not guarantee these fields are initialized".
    // Marking
    // them @Nullable is the correct answer (the lazy cache exists to AVOID the eager allocation an
    // initializer would force); currentKey() null-checks cachedKey before use. (See package-info.)
    private @Nullable Node cachedKeyNode;
    private int cachedKeyIndex = -2;
    private @Nullable MemorySegment cachedKey;

    public Cursor(NodeStore store, @Nullable Cursor parent, Node node, int index) {
        this.store = store;
        this.parent = parent;
        this.node = node;
        this.index = index;
    }

    public static Cursor atStart(NodeStore store, Node root) {
        Cursor cur = new Cursor(store, null, root, 0);
        while (!cur.isLeaf()) {
            byte[] childHash = cur.currentValue().toArray(ValueLayout.JAVA_BYTE);
            Node child =
                    store.read(childHash)
                            .map(Node::fromBytes)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Cursor: child node "
                                                            + toHex(childHash)
                                                            + " missing from store"));
            cur = new Cursor(store, cur, child, 0);
        }
        return cur;
    }

    public static Cursor atEnd(NodeStore store, Node root) {
        Cursor cur = new Cursor(store, null, root, root.count() - 1);
        while (!cur.isLeaf()) {
            byte[] childHash = cur.currentValue().toArray(ValueLayout.JAVA_BYTE);
            Node child =
                    store.read(childHash)
                            .map(Node::fromBytes)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Cursor: child node "
                                                            + toHex(childHash)
                                                            + " missing from store"));
            cur = new Cursor(store, cur, child, child.count() - 1);
        }
        return cur;
    }

    public static Cursor atKey(
            NodeStore store, Node root, MemorySegment key, TupleDescriptor desc) {
        Cursor cur = new Cursor(store, null, root, 0);
        while (true) {
            cur.index = searchInNode(cur.node, key, desc);
            if (cur.isLeaf()) break;

            int childIdx = Math.min(cur.index, cur.node.count() - 1);
            cur.index = childIdx;
            byte[] childHash = Objects.requireNonNull(cur.node.getValue(childIdx));
            Node child =
                    store.read(childHash)
                            .map(Node::fromBytes)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Cursor: child node "
                                                            + toHex(childHash)
                                                            + " missing from store"));
            cur = new Cursor(store, cur, child, 0);
        }
        return cur;
    }

    /**
     * Position a fresh cursor chain at {@code key} — same result as {@link #atKey} — seeded from an
     * existing chain over the <b>same immutable base tree</b>, reusing the seed's
     * already-materialized {@link Node}s and reading from the store only below the first
     * divergence.
     *
     * @apiNote The returned chain is fresh (new {@code Cursor} objects; never aliases {@code
     *     prev}), so callers that compare or {@link #copy} against {@code prev} behave exactly as
     *     with {@link #atKey}. Correct only while the base tree {@code prev} was built over is
     *     unchanged — exactly the {@code TreeMutator.applyMutations} situation, where the base tree
     *     is immutable for the whole flush and new nodes are written beside it.
     * @implNote Descent math is byte-for-byte {@link #atKey}'s ({@link #searchInNode} + {@code
     *     min(index, count-1)}); only the child FETCH is elided. Reuse condition per level: the
     *     fresh descent sits in the same {@code Node} <em>object</em> as the seed chain ({@code
     *     ==}, sound because reuse can only propagate down a matching root prefix — a freshly-read
     *     node is a new object and breaks the match) and lands on the same child index; then the
     *     seed's child node IS the node a store read would materialize (content-addressed,
     *     immutable), for zero reads. A seed level whose index was invalidated ({@code
     *     invalidateAtEnd}) simply fails the index match and falls back to a read — conservative,
     *     never wrong. Why this exists: {@code TreeMutator.Chunker.advanceTo} seeks once per
     *     mutation, and a sorted flush stream lands runs of consecutive edits in the same leaf — a
     *     root-restart {@link #atKey} re-fetched the whole spine per edit, which measured as 52% of
     *     ingest allocation (plans/flush-node-read-alloc.md, 2026-07-26). Collaborators: {@code
     *     TreeMutator.Chunker.advanceTo} (sole caller), {@link NodeStore#read} (divergent levels
     *     only).
     */
    public static Cursor atKeyFrom(Cursor prev, MemorySegment key, TupleDescriptor desc) {
        // The seed's chain, root-first. Chains always span root -> leaf, so get(0) is the root.
        java.util.ArrayList<Cursor> seed = new java.util.ArrayList<>(8);
        for (Cursor c = prev; c != null; c = c.parent) seed.add(c);
        java.util.Collections.reverse(seed);

        NodeStore store = prev.store;
        Cursor cur = new Cursor(store, null, seed.get(0).node, 0);
        int depth = 0;
        while (true) {
            cur.index = searchInNode(cur.node, key, desc);
            if (cur.isLeaf()) break;

            int childIdx = Math.min(cur.index, cur.node.count() - 1);
            cur.index = childIdx;
            Node child = null;
            if (depth + 1 < seed.size()) {
                Cursor seedHere = seed.get(depth);
                if (seedHere.node == cur.node && seedHere.index == childIdx) {
                    child = seed.get(depth + 1).node;
                }
            }
            if (child == null) {
                byte[] childHash = Objects.requireNonNull(cur.node.getValue(childIdx));
                child =
                        store.read(childHash)
                                .map(Node::fromBytes)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Cursor: child node "
                                                                + toHex(childHash)
                                                                + " missing from store"));
            }
            cur = new Cursor(store, cur, child, 0);
            depth++;
        }
        return cur;
    }

    /** Performs a raw byte comparison search. Useful for prefix lookups. */
    public static Cursor atRawKey(NodeStore store, Node root, MemorySegment key) {
        Cursor cur = new Cursor(store, null, root, 0);
        while (true) {
            cur.index = searchInNodeRaw(cur.node, key);
            if (cur.isLeaf()) break;

            int childIdx = Math.min(cur.index, cur.node.count() - 1);
            cur.index = childIdx;
            byte[] childHash = Objects.requireNonNull(cur.node.getValue(childIdx));
            Node child =
                    store.read(childHash)
                            .map(Node::fromBytes)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Cursor: child node "
                                                            + toHex(childHash)
                                                            + " missing from store"));
            cur = new Cursor(store, cur, child, 0);
        }
        return cur;
    }

    private static int searchInNode(Node node, MemorySegment key, TupleDescriptor desc) {
        int low = 0;
        int high = node.count() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            MemorySegment midKey = node.getKeySegment(mid);
            int cmp = desc.compare(new Tuple(midKey), new Tuple(key));
            if (cmp < 0) low = mid + 1;
            else if (cmp > 0) high = mid - 1;
            else return mid;
        }
        return low;
    }

    private static int searchInNodeRaw(Node node, MemorySegment key) {
        int low = 0;
        int high = node.count() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            MemorySegment midKey = node.getKeySegment(mid);
            int cmp = ByteUtils.compareUnsigned(midKey, key);
            if (cmp < 0) low = mid + 1;
            else if (cmp > 0) high = mid - 1;
            else return mid;
        }
        return low;
    }

    public boolean isValid() {
        return node != null && index >= 0 && index < node.count();
    }

    public boolean isLeaf() {
        return node.level() == 0;
    }

    /**
     * Whether this cursor sits at the <b>last item of its current node</b> ({@code index ==
     * node.count() - 1}) — a single-level check, not a whole-tree position.
     *
     * @return {@code true} iff {@code index == node.count() - 1}; {@code false} both when the
     *     cursor sits before the last item and when it has advanced past the end ({@code index ==
     *     node.count()})
     * @apiNote Single-level, and distinct from {@link #isValid()}: a cursor at the last item of a
     *     non-final node is at-node-end yet {@link #advance()} still succeeds (it crosses into the
     *     next node). A one-item node's cursor at index 0 is both valid and at-node-end.
     * @implNote Restored as a fast-forward primitive — {@code TreeMutator.Chunker.advanceTo} pairs
     *     it with a fresh {@link RollingHashSplitter} boundary to detect that a newly-built chunk
     *     boundary <em>aligns</em> with an old node's right edge, the point at which an unchanged
     *     run becomes safe to skip by reference (Dolt {@code node_cursor.go atNodeEnd}; the
     *     upstream tree-write-fast-forwarding-impl plan Phase A).
     */
    public boolean atNodeEnd() {
        return index == node.count() - 1;
    }

    /**
     * The <b>individual</b> subtree size of the entry under this cursor: {@code 1} for a leaf
     * entry, else the number of leaf entries beneath this internal child.
     *
     * @return {@code 1} when {@link #isLeaf()}; otherwise the per-child count, derived from the
     *     node's <em>cumulative</em> subtree counts as {@code getSubtreeCount(index) -
     *     getSubtreeCount(index - 1)} (and {@code getSubtreeCount(0)} at {@code index == 0})
     * @apiNote Precondition: the cursor is valid ({@link #isValid()}). A fast-forward skip emits
     *     the skipped child by reference carrying THIS count, so summing it over a node's children
     *     reconstructs the node's total — emitting the raw cumulative value instead would
     *     over-count.
     * @implNote <b>{@code Node.getSubtreeCount(i)} returns a PREFIX SUM</b> (cumulative {@code
     *     varints[0..i]}, see {@code Node.parseFlatbuffer}), diverging from Dolt's per-item {@code
     *     SubtreeCount(i)} — this method recovers the per-child count as the delta. The prefix-sum
     *     semantic is now the documented contract on {@code Node.getSubtreeCount} itself, pinned by
     *     {@code SubtreeCountContractProperty}. (Historical: a test-only per-item {@code
     *     SimpleNodeSerializer} implementation violated it — "a documented wart" — deleted
     *     2026-07-01, plan subtree-count-contract D-3; every remaining implementation conforms.)
     *     The raw inline {@code (level==0)?1:getSubtreeCount(index)} the chunker used is latently
     *     wrong and must be replaced by this method once fast-forwarding activates the internal
     *     branch (Dolt {@code node_cursor.go currentSubtreeSize}; the upstream
     *     tree-write-fast-forwarding-impl plan Phase A + D-6).
     */
    public long currentSubtreeSize() {
        if (isLeaf()) return 1;
        long cumulative = node.getSubtreeCount(index);
        return (index == 0) ? cumulative : cumulative - node.getSubtreeCount(index - 1);
    }

    /**
     * Position comparison against another cursor over the same tree: the index difference at the
     * <b>highest level where the two differ</b> (a parent's position outranks a child's), or {@code
     * 0} when every level is aligned.
     *
     * @param other a cursor of equal height over the same tree
     * @return negative if this is positioned before {@code other}, positive if after, {@code 0} if
     *     at the same position; the magnitude is the index delta at the deciding (highest
     *     differing) level
     * @apiNote Equal-height precondition (both cursors descend the same tree). Callers use the sign
     *     ("has the building cursor reached/passed the edit point?"); the magnitude is incidental.
     * @implNote Ports Dolt {@code node_cursor.go compareCursors} — walk both cursors leaf→root in
     *     lockstep, keeping the LAST (highest-level) non-zero index difference, so a difference in
     *     an ancestor outranks one in a descendant. {@code TreeMutator.Chunker.advanceTo}'s
     *     synchronize-then-skip uses it (the upstream tree-write-fast-forwarding-impl plan Phase
     *     A).
     */
    public int compare(Cursor other) {
        int diff = 0;
        Cursor left = this;
        Cursor right = other;
        while (true) {
            int d = left.index - right.index;
            if (d != 0) diff = d;
            if (left.parent == null || right.parent == null) break;
            left = left.parent;
            right = right.parent;
        }
        return diff;
    }

    /**
     * Mark this cursor exhausted by moving its index <em>past</em> the last item ({@code index =
     * node.count()}), so {@link #isValid()} and {@link #atNodeEnd()} both become false.
     *
     * @apiNote The same end-state {@link #advance()} reaches at the end of a node with no further
     *     parent. {@code advanceTo} uses it to retire the current level before recursing into the
     *     parent.
     * @implNote Ports Dolt {@code node_cursor.go invalidateAtEnd} (the upstream
     *     tree-write-fast-forwarding-impl plan Phase A).
     */
    public void invalidateAtEnd() {
        index = node.count();
    }

    /**
     * Move this cursor to the first item of its current node ({@code index = 0}) — Dolt {@code
     * node_cursor.go skipToNodeStart}. Used by {@code TreeMutator.Chunker.processPrefix} to re-emit
     * an edited node's prefix from its start.
     */
    public void skipToNodeStart() {
        index = 0;
    }

    /**
     * Copy {@code other}'s position <b>into this cursor in place</b> — node + index at every level
     * of the parent chain — preserving this cursor's object identity (unlike {@link #clone()},
     * which returns a <em>new</em> cursor).
     *
     * @param other a cursor of equal height over the same store to copy from
     * @throws IllegalStateException if the two cursors are not the same height
     * @apiNote In-place is the point: {@code advanceTo} holds a cursor the chunker references and
     *     must re-point it to the edit location without swapping the object. The self-invalidating
     *     key cache ({@link #currentKey()}) detects the changed {@code (node, index)} and
     *     recomputes — no manual invalidation needed.
     * @implNote Ports Dolt {@code node_cursor.go copy}; the {@code store} is shared across cursors
     *     over one tree, so (unlike Dolt's {@code nrw}) it needn't be copied (the upstream
     *     tree-write-fast-forwarding-impl plan Phase A).
     */
    public void copy(Cursor other) {
        this.node = other.node;
        this.index = other.index;
        if (this.parent != null) {
            if (other.parent == null) {
                throw new IllegalStateException("cursors must be equal height to copy()");
            }
            this.parent.copy(other.parent);
        } else if (other.parent != null) {
            throw new IllegalStateException("cursors must be equal height to copy()");
        }
    }

    public MemorySegment currentKey() {
        if (cachedKey == null || cachedKeyIndex != index || cachedKeyNode != node) {
            cachedKey = node.getKeySegment(index);
            cachedKeyIndex = index;
            cachedKeyNode = node;
        }
        return cachedKey;
    }

    public MemorySegment currentValue() {
        return MemorySegment.ofArray(Objects.requireNonNull(node.getValue(index)));
    }

    public @Nullable Cursor parent() {
        return parent;
    }

    public Node node() {
        return node;
    }

    public int index() {
        return index;
    }

    public boolean advance() {
        if (index < node.count() - 1) {
            index++;
            return true;
        }
        if (parent == null) {
            index = node.count();
            return false;
        }
        if (!parent.advance()) {
            index = node.count();
            return false;
        }
        fetchNodeFromParent();
        index = 0;
        return true;
    }

    public boolean retreat() {
        if (index > 0) {
            index--;
            return true;
        }
        if (parent == null) {
            index = -1;
            return false;
        }
        if (!parent.retreat()) {
            index = -1;
            return false;
        }
        fetchNodeFromParent();
        index = node.count() - 1;
        return true;
    }

    private void fetchNodeFromParent() {
        // Only called from advance()/retreat() after parent.advance()/retreat() succeeded — so
        // parent
        // is non-null here (a top-level cursor has no parent and never reaches this).
        byte[] hash = Objects.requireNonNull(parent).currentValue().toArray(ValueLayout.JAVA_BYTE);
        this.node =
                store.read(hash)
                        .map(Node::fromBytes)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Cursor: child node "
                                                        + toHex(hash)
                                                        + " missing from store"));
    }

    public Cursor clone() {
        Cursor parentClone = (parent != null) ? parent.clone() : null;
        return new Cursor(store, parentClone, node, index);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
