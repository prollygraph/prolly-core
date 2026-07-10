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
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An immutable, read-only view of a prolly tree at one root — point lookups and ordered iteration.
 *
 * <p>A {@code StaticMap} is the <b>committed/snapshot</b> form of the tree (vs. {@link MutableMap},
 * the write buffer). Because the {@code root} {@link Node} names a complete, content-addressed
 * tree, a {@code StaticMap} is a permanent point-in-time snapshot — the basis of the store's MVCC /
 * snapshot-isolation: a reader holding one is unaffected by any later commit.
 *
 * @apiNote <b>Immutable and safe to share across threads</b> — reads take no lock. {@link #get}
 *     returns {@code Optional.empty()} for an absent key; {@link #iter}/{@link #iterRange}/{@link
 *     #iterPrefix} give ordered scans. To pin a snapshot, just keep the reference — the tree it
 *     names never changes.
 * @implNote Navigates from {@code root} via a {@code Cursor}, reading child nodes from the {@link
 *     NodeStore} by content hash and comparing keys with the {@link TupleDescriptor}.
 *     <p><b>Collaborators:</b> {@link Node} (the root), {@link NodeStore} (child reads), {@link
 *     TupleDescriptor} (key order), {@link MapIterator}/{@code Cursor} (iteration).
 *     <b>Dependents:</b> {@link MutableMap} (as its base), and downstream the {@code MergeEngine} /
 *     {@code Database} (read trees) + the RDF4J Sail snapshots.
 *     <p><b>Wart:</b> {@link #get} and the iterators re-descend from {@code root} per call — no
 *     warm cursor is reused across calls; that per-probe descent is the cost upstream read-path
 *     work targets (see {@code prolly-web-playground/cursor-read-path.md} for the full read story).
 */
public class StaticMap {
    private final NodeStore store;
    private final @Nullable Node root;
    private final TupleDescriptor descriptor;

    public StaticMap(NodeStore store, @Nullable Node root, TupleDescriptor descriptor) {
        this.store = store;
        this.root = root;
        this.descriptor = descriptor;
    }

    public Optional<MemorySegment> get(MemorySegment key) {
        if (root == null) return Optional.empty();
        Cursor cur = Cursor.atKey(store, root, key, descriptor);
        if (cur.isValid()) {
            int cmp = descriptor.compare(new Tuple(cur.currentKey()), new Tuple(key));
            if (cmp == 0) {
                return Optional.of(cur.currentValue());
            }
        }
        return Optional.empty();
    }

    public MapIterator iter() {
        if (root == null) return new TreeIter(store, null, null, descriptor, c -> true);
        Cursor cur = Cursor.atStart(store, root);
        return new TreeIter(store, root, cur, descriptor, c -> false);
    }

    public MapIterator iterRange(MemorySegment startKey) {
        if (root == null) return new TreeIter(store, null, null, descriptor, c -> true);
        Cursor cur = Cursor.atKey(store, root, startKey, descriptor);
        return new TreeIter(store, root, cur, descriptor, c -> false);
    }

    /** Returns an iterator over items that match the given key prefix. */
    public MapIterator iterPrefix(MemorySegment prefixTup, MemorySegment rawDataPrefix) {
        if (root == null) return new TreeIter(store, null, null, descriptor, c -> true);
        Cursor cur = Cursor.atKey(store, root, prefixTup, descriptor);

        return new TreeIter(
                cur,
                c -> {
                    Tuple k = new Tuple(c.currentKey());
                    // Field 0 (the first key component) is structurally present in a prefix scan.
                    return !ByteUtils.isPrefix(
                            rawDataPrefix, Objects.requireNonNull(k.getFieldSegment(0)));
                });
    }

    public MapIterator reverseIter() {
        if (root == null) return new TreeIter(store, null, null, descriptor, c -> true);
        Cursor cur = Cursor.atEnd(store, root);
        return new TreeIter(store, root, cur, descriptor, c -> false);
    }

    public @Nullable Node root() {
        return root;
    }

    public NodeStore store() {
        return store;
    }

    public TupleDescriptor descriptor() {
        return descriptor;
    }
}
