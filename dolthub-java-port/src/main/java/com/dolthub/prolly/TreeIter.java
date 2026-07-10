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
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The production {@link MapIterator}: drives an internal {@link Cursor} and turns its
 * advance/retreat mechanics into the positioned-iteration contract, with an optional stop predicate
 * bounding the scan (how prefix scans end without knowing their last key up front).
 *
 * <p><b>Why the stop predicate instead of an end key:</b> a range's end is often "the first key
 * that no longer matches" (a prefix, a bound) — a predicate over the cursor expresses that directly
 * and is evaluated per step, so the iterator never over-reads.
 *
 * @apiNote Two construction modes: root-aware (store + root + descriptor — supports {@link #seek})
 *     and prefix-scan (a pre-positioned start cursor only — {@link #seek} throws {@link
 *     UnsupportedOperationException} because there is no root to re-descend from). Single-threaded,
 *     like the {@link Cursor} it drives. First {@code next()}/{@code prev()} validates the start
 *     position against the stop predicate before any stepping (so an already-out-of-range start
 *     yields an empty iteration, not one bogus entry).
 * @implNote <b>Collaborators:</b> {@link Cursor} (the tree-walking engine — this class adds only
 *     start/stop/validity bookkeeping), {@link NodeStore}/{@link Node}/{@link TupleDescriptor}
 *     (held solely to build a fresh cursor on {@link #seek}). <b>Dependents:</b> {@link
 *     StaticMap}'s scan surface (its iterator/range methods construct these).
 */
public class TreeIter implements MapIterator {
    private final @Nullable NodeStore store;
    private final @Nullable Node root;
    private final @Nullable TupleDescriptor descriptor;
    private @Nullable Cursor cursor;
    private Predicate<Cursor> stopPredicate;
    private boolean valid = false;
    private boolean started = false;

    public TreeIter(
            @Nullable NodeStore store,
            @Nullable Node root,
            @Nullable Cursor cursor,
            @Nullable TupleDescriptor descriptor,
            Predicate<Cursor> stopPredicate) {
        this.store = store;
        this.root = root;
        this.cursor = cursor;
        this.descriptor = descriptor;
        this.stopPredicate = stopPredicate;
    }

    /** Special constructor for prefix scans. */
    public TreeIter(Cursor startCursor, Predicate<Cursor> stopPredicate) {
        this.store = null;
        this.root = null;
        this.descriptor = null;
        this.cursor = startCursor;
        this.stopPredicate = stopPredicate;
    }

    @Override
    public boolean next() {
        if (!started) {
            started = true;
            valid = cursor != null && cursor.isValid() && !stopPredicate.test(cursor);
            return valid;
        }
        if (!valid) return false;
        if (cursor == null) return false; // valid implies a cursor; this narrows it for the engine
        if (cursor.advance()) {
            valid = !stopPredicate.test(cursor);
        } else {
            valid = false;
        }
        return valid;
    }

    @Override
    public boolean prev() {
        if (!started) {
            started = true;
            valid = cursor != null && cursor.isValid() && !stopPredicate.test(cursor);
            return valid;
        }
        if (!valid) return false;
        if (cursor == null) return false; // valid implies a cursor; this narrows it for the engine
        if (cursor.retreat()) {
            valid = !stopPredicate.test(cursor);
        } else {
            valid = false;
        }
        return valid;
    }

    @Override
    public void seek(MemorySegment key) {
        if (store == null || root == null || descriptor == null)
            throw new UnsupportedOperationException("Seek requires root-aware iterator");
        this.cursor = Cursor.atKey(store, root, key, descriptor);
        this.started = false;
        this.valid = cursor.isValid() && !stopPredicate.test(cursor);
    }

    @Override
    public MemorySegment key() {
        return Objects.requireNonNull(cursor, "key() requires a positioned cursor").currentKey();
    }

    @Override
    public MemorySegment value() {
        return Objects.requireNonNull(cursor, "value() requires a positioned cursor")
                .currentValue();
    }
}
