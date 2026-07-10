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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.TreeIntegrityChecker;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Phase 6 Step 26 of the upstream test-strategy plan — sync convergence + Merkle audit (R-7),
 * through the real {@link SyncEngine}. Over generated trees: build a tree in a "remote" store, pull
 * it into an empty "local" store, and assert
 *
 * <ul>
 *   <li><b>convergence</b>: local now holds the remote root hash, and the local root bytes are
 *       byte-identical to the remote's (content addressing);
 *   <li><b>Merkle integrity</b>: a `TreeIntegrityChecker` walk of the pulled tree in the local
 *       store finds <i>no interior node with a missing child</i> and every node hash-verifies
 *       (pull's bottom-up write order guarantees a parent is only written after all its children);
 *   <li><b>data equality</b>: the local tree materializes the same key→value set that was inserted
 *       remotely.
 * </ul>
 *
 * Generalizes the deterministic `ClusterSyncConsistencyTest` / `SyncE2ETest` to generated inputs.
 * In-memory stores, no mocks.
 */
class SyncConvergenceProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Provide
    Arbitrary<Set<Integer>> keyIds() {
        return Arbitraries.integers().between(0, 800).set().ofMinSize(1).ofMaxSize(400);
    }

    @Property(tries = 40)
    void pullIntoEmptyLocalConvergesAndIsMerkleConsistent(
            @ForAll @From("keyIds") Set<Integer> ids) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            InMemoryNodeStore remote = new InMemoryNodeStore();

            // Build the remote tree.
            Map<String, String> oracle = new LinkedHashMap<>();
            MutableMap mm = new MutableMap(new StaticMap(remote, null, DESC), remote, DESC, pool);
            for (int id : ids) {
                String k = "k" + id, v = "v" + id;
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, k.getBytes());
                mm.put(tb.build().segment(), MemorySegment.ofArray(v.getBytes()));
                oracle.put(k, v);
            }
            StaticMap remoteMap = mm.flush();
            Node remoteRoot = remoteMap.root();
            if (remoteRoot == null) return; // empty (ids is non-empty, so won't happen)
            byte[] rootHash = HashUtils.hash(remoteRoot.bytes());

            // Pull into a fresh, empty local store.
            InMemoryNodeStore local = new InMemoryNodeStore();
            new SyncEngine(local, remote).pull(rootHash);

            // Convergence: local has the root, byte-identical to remote's.
            assertArrayEquals(
                    remote.read(rootHash).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    local.read(rootHash).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    "local root chunk must be byte-identical to remote's after pull");

            // Merkle audit: walking the pulled tree in LOCAL finds no missing
            // child and every node hash-verifies.
            assertDoesNotThrow(
                    () -> new TreeIntegrityChecker(local).verify(rootHash),
                    "pulled tree must be fully present + hash-consistent in the local store");

            // Data equality: local materializes the same key set.
            Node localRoot = Node.fromBytes(local.read(rootHash).orElseThrow());
            Map<String, String> got = new LinkedHashMap<>();
            MapIterator it = new StaticMap(local, localRoot, DESC).iter();
            while (it.next()) {
                got.put(
                        new String(new Tuple(it.key()).getField(0)),
                        new String(it.value().toArray(ValueLayout.JAVA_BYTE)));
            }
            assertEquals(oracle, got, "local tree must materialize the remote's key set");
        }
    }
}
