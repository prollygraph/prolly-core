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

/**
 * The ordered-iteration contract over a prolly tree: bidirectional stepping ({@link #next}/{@link
 * #prev}), key {@link #seek}, and zero-copy access to the current entry.
 *
 * <p><b>Why an interface:</b> readers should not care whether they are walking a whole tree, a
 * prefix-bounded range, or some future filtered view — {@code StaticMap} hands out a {@code
 * MapIterator} and the caller's loop is the same for all of them.
 *
 * @apiNote Iterators start <em>unpositioned</em>: the first {@link #next()} (or {@link #prev()})
 *     positions on the first (or last) in-range entry and returns whether one exists; thereafter
 *     each call steps and reports validity. {@link #key()}/{@link #value()} are only legal while
 *     positioned on a valid entry (implementations fail fast otherwise) and return zero-copy
 *     segments into node bytes — copy them if they must outlive the iteration. Iterators read one
 *     immutable tree snapshot; there is no invalidation-by-mutation to worry about (a mutation
 *     builds a new tree).
 * @implNote <b>Collaborators / implementations:</b> {@link TreeIter} (the cursor-backed
 *     implementation — the only production one). <b>Dependents:</b> {@code StaticMap}'s
 *     iteration/scan surface and, through it, every range read in the upstream codecs and the RDF
 *     index layers.
 */
public interface MapIterator {
    /** Step forward; on the first call, position on the first entry. Returns validity. */
    boolean next();

    /** Step backward; on the first call, position on the last entry. Returns validity. */
    boolean prev();

    /**
     * Reposition at the smallest entry {@code >= key} (implementations may not support it — a
     * prefix-scan iterator has no root to re-descend from and throws).
     */
    void seek(MemorySegment key);

    /** The current entry's key (zero-copy; only while positioned on a valid entry). */
    MemorySegment key();

    /** The current entry's value (zero-copy; only while positioned on a valid entry). */
    MemorySegment value();
}
