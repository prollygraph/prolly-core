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
package com.earasoft.prolly.sync;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeStore;
import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The generic prolly-tree Merkle reachability walk — every node chunk reachable from a tree root,
 * with subtree pruning via an excluded set. This is the substrate-agnostic core the whole sync
 * layer shares: {@code DatabasePackSync} walks a plain {@code Database} commit's data root with it,
 * and an upstream face's {@code ChunkReachability} delegates its per-table walks here after
 * resolving roots from its {@code RootMetaTree} (the split of extract-prolly-sync-module — the
 * generic DFS moved with the pack protocol; the RootMetaTree entry point stayed upstream).
 *
 * <p>The walk throws {@link IllegalStateException} if a chunk the tree <em>references</em> is
 * absent — i.e. the store is torn or a fetch was incomplete. ({@code @throws} belongs on methods;
 * stated as prose here because this is the class-level contract for every walk entry point.)
 */
public final class DataTreeReachability {

    private DataTreeReachability() {}

    /**
     * Hex hashes of every tree chunk reachable from {@code rootHash}, minus anything in {@code
     * excluded} (a Merkle skip — an excluded hash prunes its whole subtree).
     */
    public static Set<String> fromRoot(NodeStore store, byte[] rootHash, Set<String> excluded) {
        Set<String> out = new HashSet<>();
        collectInto(store, rootHash, out, excluded);
        return out;
    }

    /**
     * The raw DFS — collect reachable node hashes into {@code out}, pruning {@code excluded}.
     * Exposed (rather than only {@link #fromRoot}) so a caller walking several roots into one
     * accumulating set — an upstream reachability walker's per-table loop — shares the seen set
     * across roots instead of merging per-root copies.
     */
    public static void collectInto(
            NodeStore store, byte[] hash, Set<String> out, Set<String> excluded) {
        String hex = HashUtils.toHex(hash);
        if (excluded.contains(hex) || !out.add(hex)) {
            return;
        }
        MemorySegment seg =
                store.read(hash)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "chunk missing from store: " + hex));
        Node node = Objects.requireNonNull(Node.fromBytes(seg));
        if (!node.isLeaf()) {
            for (int i = 0; i < node.count(); i++) {
                collectInto(store, Objects.requireNonNull(node.getValue(i)), out, excluded);
            }
        }
    }
}
