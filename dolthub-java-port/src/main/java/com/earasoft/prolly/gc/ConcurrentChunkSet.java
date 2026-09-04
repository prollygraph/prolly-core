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

import java.util.function.Consumer;

/**
 * A thread-safe {@link ChunkSet} for a walk that runs on more than one thread.
 *
 * @apiNote {@link #add} remains an atomic test-and-set, which is the property a concurrent walk
 *     depends on: exactly one thread may be told a hash was absent, or two threads descend the same
 *     subtree and the walk does redundant work (correct, but wasteful).
 * @implNote <b>Why a lock and not a lock-free table.</b> The only caller is {@code
 *     ParallelReachabilityWalker}, whose threads spend nearly all of their time inside {@code
 *     NodeStore.read} — a RocksDB get, hundreds of microseconds against a few hundred nanoseconds
 *     of set work. A lock-free open-addressed table would buy throughput this caller cannot
 *     observe, at the cost of the subtlest concurrency code in the module. If a profile ever shows
 *     this monitor contended, stripe it by a trailing key byte before reaching for anything
 *     cleverer.
 *     <p><b>Collaborators:</b> delegates to {@link PackedChunkSet}; used by {@code
 *     ParallelReachabilityWalker}.
 */
public final class ConcurrentChunkSet implements ChunkSet {

    private final PackedChunkSet delegate;

    public ConcurrentChunkSet() {
        this.delegate = new PackedChunkSet();
    }

    public ConcurrentChunkSet(int expected) {
        this.delegate = new PackedChunkSet(expected);
    }

    @Override
    public synchronized boolean add(byte[] hash) {
        return delegate.add(hash);
    }

    @Override
    public synchronized boolean contains(byte[] hash) {
        return delegate.contains(hash);
    }

    @Override
    public synchronized int size() {
        return delegate.size();
    }

    @Override
    public synchronized void forEach(Consumer<byte[]> sink) {
        delegate.forEach(sink);
    }
}
