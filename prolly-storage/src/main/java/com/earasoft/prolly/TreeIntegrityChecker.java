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
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 *
 *
 * <h3>Recursive Tree Integrity Verifier</h3>
 *
 * <p>Traverses an entire tree and validates that every child hash correctly matches its content.
 */
public class TreeIntegrityChecker {
    private final NodeStore store;

    public TreeIntegrityChecker(NodeStore store) {
        this.store = store;
    }

    public void verify(byte[] rootHash) {
        walkAndVerify(rootHash, -1);
    }

    private void walkAndVerify(byte[] hash, int expectedLevel) {
        Optional<MemorySegment> data = store.read(hash);
        if (data.isEmpty()) throw new RuntimeException("Missing node");

        byte[] actualHash = HashUtils.hash(data.get().asByteBuffer());
        if (!Arrays.equals(hash, actualHash)) throw new RuntimeException("Hash mismatch");

        // data is present (guarded above) → fromBytes returns a real node, not null.
        Node node = Objects.requireNonNull(Node.fromBytes(data.get()));
        if (expectedLevel != -1 && node.level() != expectedLevel)
            throw new RuntimeException("Level mismatch");

        if (!node.isLeaf()) {
            for (int i = 0; i < node.count(); i++)
                // internal-node child value (a chunk hash) is always present
                walkAndVerify(Objects.requireNonNull(node.getValue(i)), node.level() - 1);
        }
    }
}
