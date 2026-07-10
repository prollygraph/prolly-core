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

import java.lang.foreign.MemorySegment;

/**
 * The boundary-detection seam of content-defined chunking (the upstream SPOC
 * boundary-function-adoption plan, D-1): decides where prolly-tree node boundaries fall as entries
 * stream through the tree builder. Extracted from {@link RollingHashSplitter}'s exact surface so
 * alternative boundary functions (measured in docs/write-ups/chunker-boundary-detection-study.md)
 * can be injected per tree.
 *
 * @apiNote The contract every implementation must honor (the properties the geometry + determinism
 *     gates pin): <b>deterministic</b> — identical entry streams yield identical boundaries;
 *     <b>bounded</b> — no chunk below the implementation's minimum (except a final partial) or
 *     above its maximum; consulted only BETWEEN entries ({@link #crossedBoundary} after each {@link
 *     #append}), so decisions are per-entry. Implementations are single-threaded per tree build and
 *     reused across chunks via {@link #reset}.
 */
public interface BoundarySplitter {

    /** Feed one entry's key + value bytes. */
    void append(MemorySegment key, MemorySegment value);

    /** True when a boundary falls after the most recently appended entry. */
    boolean crossedBoundary();

    /** Bytes appended since the last {@link #reset}. */
    int offset();

    /** Start a new chunk. */
    void reset();

    /**
     * Creates the splitter for one tree level — per-level instances keep boundaries from aligning
     * vertically across heights (each level seeds differently).
     */
    @FunctionalInterface
    interface Factory {
        BoundarySplitter create(int level);
    }

    /** The production default: the {@link RollingHashSplitter} rolling-hash boundary function. */
    Factory ROLLING_HASH = RollingHashSplitter::new;
}
