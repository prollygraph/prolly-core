/*
 * Copyright 2026 Earasoft
 * Copyright 2021 Dolthub, Inc.
 *
 * Derived from Dolt's design, adapted for Java by Earasoft.
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

import com.earasoft.prolly.gc.ChunkSet;
import com.earasoft.prolly.gc.PackedChunkSet;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/**
 * Collects every node hash reachable from a given root by walking the content-addressed tree.
 *
 * <p>Given a data-tree root, the walker reads that node, records its hash, and follows each child
 * reference, repeating until it has visited the whole subtree — the set of hashes it returns is
 * exactly the chunks that root depends on. This is the foundation of garbage collection: collecting
 * the reachable set from every branch head's commit and its data tree gives the live set, and any
 * stored hash outside that set is unreferenced and safe to delete.
 *
 * @apiNote {@link #walk(byte[])} traverses from a root and accumulates into the walker's reachable
 *     set; {@code getReachableHashes()} returns what has been collected. The walk is
 *     <b>iterative</b> — it uses an explicit work stack rather than recursion — so a deep tree
 *     cannot overflow the Java call stack (recursion depth would scale with tree height). One
 *     walker accumulates across multiple {@code walk} calls, so a caller can fold many roots into a
 *     single reachable set before sweeping. The caller, not this class, decides which roots are
 *     live: it walks <em>only</em> what it is given, so any auxiliary root the caller forgets to
 *     pass is treated as unreachable — see {@code GarbageCollector}'s reachability-contract
 *     warning.
 * @implNote <b>Collaborators:</b> {@link NodeStore} (read each node's bytes to discover its child
 *     hashes), {@link Node} (decode child references). <b>Dependents:</b> in {@code
 *     prolly-storage}, {@code GarbageCollector}'s mark phase (the sole production caller) and any
 *     parallel reachability variant built on the same contract.
 */
public class ReachabilityWalker {
    private final NodeStore store;
    private final ChunkSet reachable = new PackedChunkSet();

    public ReachabilityWalker(NodeStore store) {
        this.store = store;
    }

    public void walk(byte[] rootHash) {
        if (rootHash == null) return;
        Deque<byte[]> stack = new ArrayDeque<>();
        stack.push(rootHash);
        while (!stack.isEmpty()) {
            byte[] hash = stack.pop();
            if (hash == null) continue;
            if (!reachable.add(hash)) continue;

            Optional<MemorySegment> data = store.read(hash);
            if (data.isEmpty()) continue;
            // data is present (guarded above) and a stored chunk is a real node — fromBytes returns
            // null only for a null input, which this is not.
            Node node = Objects.requireNonNull(Node.fromBytes(data.get()));
            if (!node.isLeaf()) {
                for (int i = 0; i < node.count(); i++) {
                    byte[] child = node.getValue(i);
                    if (child != null) stack.push(child);
                }
            }
        }
    }

    public ChunkSet getReachableHashes() {
        return reachable;
    }
}
