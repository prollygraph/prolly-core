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
package com.earasoft.prolly.gc;

import com.dolthub.prolly.NodeStore;

/**
 * A substrate's claim on chunks the engine commit graph cannot see — the extension point that
 * closes the garbage collector's aux-root gap (ADR-0074).
 *
 * @apiNote A co-tenant substrate (one that stores its own roots in a shared {@link NodeStore}
 *     outside the engine {@code Commit} graph — the upstream RDF face's {@code RootMetaTree} family
 *     is the canonical case) registers one of these with {@code GarbageCollector} (prolly-storage —
 *     downstream of this module, so not linkable); the mark phase unions every contributor's set
 *     with its own commit-graph walk before sweeping. The contributor returns its ENTIRE live
 *     closure as hex content-addresses — the substrate owns its closure logic; the engine unions,
 *     never interprets.
 * @implNote <b>This is safety-critical code.</b> Under-reporting is deletion of live data — the
 *     same trust class as the sweep itself. Every implementation must be pinned by a test that
 *     garbage-collects a real store of its substrate and proves the substrate still reads
 *     everything. Lives in the engine CORE (not the RocksDB substrate module) because it speaks
 *     only core types — a substrate on any {@link NodeStore} implements it without dragging a
 *     storage backend; the collector in the storage module consumes it. Called with the
 *     garbage-collection write lock held: implementations must not write to the store or take locks
 *     that a writer holding {@code gcLock().readLock()} could be blocked on.
 */
@FunctionalInterface
public interface GcReachabilityContributor {

    /**
     * Every chunk (hex content-address) this substrate holds live outside the engine commit graph —
     * roots AND their full transitive closures.
     */
    ChunkSet reachable(NodeStore store);
}
