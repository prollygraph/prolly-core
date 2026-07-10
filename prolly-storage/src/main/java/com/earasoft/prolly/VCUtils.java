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
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 *
 *
 * <h3>Version Control Utilities</h3>
 *
 * <p>Implements high-level Git-like operations: Bisect and Blame.
 */
public class VCUtils {
    private final Database db;
    private final NodeStore store;
    private final TupleDescriptor descriptor;

    public VCUtils(Database db, NodeStore store, TupleDescriptor descriptor) {
        this.db = db;
        this.store = store;
        this.descriptor = descriptor;
    }

    /**
     *
     *
     * <h3>Blame Engine</h3>
     *
     * <p>For a given key, identifies the commit that last modified it.
     */
    public @Nullable Commit blame(String branch, MemorySegment key) {
        Commit current = db.getHead(branch);
        Commit lastCommit = null;
        byte[] lastVal = null;

        while (current != null) {
            // An empty-tree commit (every row deleted) has a null root; treat
            // it as an empty StaticMap rather than calling store.read(null).
            byte[] rootHash = current.getRootValueHash();
            Node rootNode =
                    rootHash == null
                            ? null
                            : store.read(rootHash).map(Node::fromBytes).orElse(null);
            StaticMap sm = new StaticMap(store, rootNode, descriptor);
            Optional<MemorySegment> val = sm.get(key);

            if (val.isPresent()) {
                byte[] currentVal = val.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                if (lastVal == null || Arrays.equals(lastVal, currentVal)) {
                    // First sighting OR value matches HEAD's value — advance lastCommit
                    // to this older commit. The OLDEST commit in this contiguous
                    // matching run is the one that introduced the current value.
                    lastVal = currentVal;
                    lastCommit = current;
                } else {
                    // Divergence found: lastCommit points at the introducing commit.
                    return lastCommit;
                }
            } else {
                // Key didn't exist in this commit, so it was added in lastCommit.
                return lastCommit;
            }

            if (current.getParents().isEmpty()) break;
            current = loadCommit(current.getParents().get(0));
        }
        return lastCommit;
    }

    /**
     *
     *
     * <h3>Bisect Engine</h3>
     *
     * <p>Finds the first "bad" commit between good and bad using binary search.
     */
    public Commit bisect(byte[] goodHash, byte[] badHash, Predicate<Commit> isBad) {
        List<Commit> history = getHistoryPath(goodHash, badHash);
        int low = 0;
        int high = history.size() - 1;
        Commit firstBad = history.get(high);

        while (low <= high) {
            int mid = (low + high) >>> 1;
            Commit midCommit = history.get(mid);
            if (isBad.test(midCommit)) {
                firstBad = midCommit;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return firstBad;
    }

    private List<Commit> getHistoryPath(byte[] good, byte[] bad) {
        List<Commit> path = new ArrayList<>();
        Commit curr = loadCommit(bad);
        while (curr != null) {
            path.add(curr);
            byte[] h = db.store().write(curr.serialize());
            if (Arrays.equals(h, good)) break;

            if (curr.getParents().isEmpty()) break;
            curr = loadCommit(curr.getParents().get(0));
        }
        Collections.reverse(path);
        return path;
    }

    private @Nullable Commit loadCommit(byte[] hash) {
        return store.read(hash)
                .map(
                        seg ->
                                Commit.deserialize(
                                        seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)))
                .orElse(null);
    }
}
