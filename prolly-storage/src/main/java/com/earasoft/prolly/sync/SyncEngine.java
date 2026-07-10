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

import com.dolthub.prolly.*;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import java.util.*;

/**
 * Copies the chunks reachable from a remote root into a local store, transferring only what the
 * local store is missing.
 *
 * <p>Because every node is addressed by the hash of its content, two stores that hold the same hash
 * hold byte-identical subtrees. {@link #pull} walks the remote tree top-down and stops at any hash
 * already present locally — that node and its whole subtree are known to match, so they need not be
 * fetched. Only the chunks that genuinely differ cross the wire, which is what makes
 * content-addressed synchronization cheap between near-identical histories: the shared prefix of
 * two trees is skipped wholesale.
 *
 * @apiNote {@link #pull} takes a remote root hash and makes every chunk beneath it present locally.
 *     Children are written before their parent (bottom-up), so the local store is never left
 *     referencing a chunk it does not yet hold — a crash mid-pull leaves a consistent partial
 *     store, not a dangling parent. A null root is a no-op.
 * @implNote <b>Collaborators:</b> two {@link NodeStore}s — {@code local} (the destination) and
 *     {@code remote} (the source, often a {@link RemoteNodeStoreClient}) — and {@link Node} (decode
 *     child references to recurse). <b>Dependents:</b> the distributed push / pull / fetch sync
 *     surface built on top of this in-process engine.
 */
public class SyncEngine {
    private final NodeStore local;
    private final NodeStore remote;

    public SyncEngine(NodeStore local, NodeStore remote) {
        this.local = local;
        this.remote = remote;
    }

    /**
     * Pulls all nodes reachable from the remoteRoot into the local store. Uses the Merkle property
     * to skip subtrees already present locally.
     */
    public void pull(byte[] remoteRootHash) {
        if (remoteRootHash == null) return;
        recursivePull(remoteRootHash);
    }

    private void recursivePull(byte[] hash) {
        // If we already have it locally, we and all our children are guaranteed to be present.
        if (local.read(hash).isPresent()) return;

        // Fetch from remote
        var data =
                remote.read(hash)
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                "Missing node on remote: " + toHex(hash)));

        // data came from orElseThrow above (non-null) → fromBytes returns a real node.
        Node node = Objects.requireNonNull(Node.fromBytes(data));
        if (!node.isLeaf()) {
            for (int i = 0; i < node.count(); i++) {
                recursivePull(Objects.requireNonNull(node.getValue(i)));
            }
        }

        // Write locally after children are pulled (Bottom-up integrity)
        local.write(data);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
