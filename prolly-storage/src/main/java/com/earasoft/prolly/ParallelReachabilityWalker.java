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
package com.earasoft.prolly;

import com.dolthub.prolly.*;
import com.earasoft.prolly.gc.ChunkSet;
import com.earasoft.prolly.gc.ConcurrentChunkSet;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

/**
 *
 *
 * <h3>Parallel DAG Walker</h3>
 *
 * <p>A high-performance implementation of {@link ReachabilityWalker} that uses Java's <code>
 * ForkJoinPool</code> to walk large Merkle trees using multiple CPU cores.
 */
public class ParallelReachabilityWalker {
    private final NodeStore store;
    private final ChunkSet reachable = new ConcurrentChunkSet();

    public ParallelReachabilityWalker(NodeStore store) {
        this.store = store;
    }

    /**
     * Walk the Merkle tree rooted at {@code rootHash}, recording every reachable chunk hash.
     *
     * @implNote Runs the recursive walk on the shared {@link ForkJoinPool#commonPool()} rather than
     *     a dedicated pool. A dedicated {@code new ForkJoinPool()} stored in a field would leak its
     *     worker threads unless the walker were made {@code AutoCloseable} and every caller closed
     *     it — and this class has no such lifecycle. The common pool owns no per-walker resource,
     *     so there is nothing to leak. The walk's {@link NodeStore#read} calls are short (a RocksDB
     *     point-get, cache-mostly), so running them on the common pool is acceptable for this
     *     infrequent operation; if the garbage collector is ever wired to run large walks under
     *     concurrent application load, introduce a dedicated, {@code close()}-able pool here for
     *     isolation (and give every caller the try-with-resources to match).
     */
    public void walk(byte[] rootHash) {
        ForkJoinPool.commonPool().invoke(new WalkTask(rootHash));
    }

    public ChunkSet getReachableHashes() {
        return reachable;
    }

    private class WalkTask extends RecursiveAction {
        private final byte[] hash;

        WalkTask(byte[] hash) {
            this.hash = hash;
        }

        @Override
        protected void compute() {
            if (hash == null) return;
            if (!reachable.add(hash)) return;

            store.read(hash)
                    .ifPresent(
                            data -> {
                                // data is present (ifPresent) → fromBytes returns a real node.
                                Node node = Objects.requireNonNull(Node.fromBytes(data));
                                if (!node.isLeaf()) {
                                    // Build subtasks and invokeAll so the parent does not
                                    // return before children complete. Previously called
                                    // fork() without join(), which caused walk() to return
                                    // with most descendants unmarked — see
                                    // ParallelReachabilityWalkerTest oracle 1.
                                    List<WalkTask> children = new ArrayList<>(node.count());
                                    for (int i = 0; i < node.count(); i++) {
                                        children.add(
                                                new WalkTask(
                                                        Objects.requireNonNull(node.getValue(i))));
                                    }
                                    invokeAll(children);
                                }
                            });
        }
    }
}
