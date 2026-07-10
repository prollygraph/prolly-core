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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeStore;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Phase 6 Step 27 of the upstream test-strategy plan — interrupted/partial sync recovery (R-7),
 * through the real {@link SyncEngine}.
 *
 * <ul>
 *   <li><b>Consistent prefix (no orphaned parent):</b> a remote that throws after K transferred
 *       nodes leaves the local store containing only <i>complete subtrees</i> — every node present
 *       has all its children present. This follows from pull's bottom-up write order (a parent is
 *       written only after every child).
 *   <li><b>Recovery:</b> a re-pull against the healthy remote completes and the local tree
 *       converges (Merkle-verifies) + materializes the data.
 *   <li><b>Idempotent dedup:</b> re-pulling an already-complete tree writes zero new nodes
 *       (`recursivePull` short-circuits on a present hash).
 * </ul>
 */
class SyncRecoveryProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Provide
    Arbitrary<Set<Integer>> keyIds() {
        return Arbitraries.integers().between(0, 600).set().ofMinSize(10).ofMaxSize(400);
    }

    @Property(tries = 40)
    void interruptedPullLeavesConsistentPrefixAndRePullConverges(
            @ForAll @From("keyIds") Set<Integer> ids,
            @ForAll @IntRange(min = 1, max = 60) int failAfter) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            InMemoryNodeStore remote = new InMemoryNodeStore();
            Map<String, String> oracle = new LinkedHashMap<>();
            MutableMap mm = new MutableMap(new StaticMap(remote, null, DESC), remote, DESC, pool);
            for (int id : ids) {
                String k = "k" + id, v = "v" + id;
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, k.getBytes());
                mm.put(tb.build().segment(), MemorySegment.ofArray(v.getBytes()));
                oracle.put(k, v);
            }
            Node remoteRoot = mm.flush().root();
            if (remoteRoot == null) return;
            byte[] rootHash = HashUtils.hash(remoteRoot.bytes());

            Recording local = new Recording();

            // Interrupt the first pull after `failAfter` remote reads.
            try {
                new SyncEngine(local, new Failing(remote, failAfter)).pull(rootHash);
            } catch (RuntimeException interrupted) {
                // expected when failAfter < total node count
            }

            // No orphaned parent: every written node's children are also written.
            for (String hHex : local.written) {
                byte[] h = fromHex(hHex);
                Node n = Node.fromBytes(local.read(h).orElseThrow());
                if (!n.isLeaf()) {
                    for (int i = 0; i < n.count(); i++) {
                        assertTrue(
                                local.written.contains(toHex(n.getValue(i))),
                                "interrupted pull left an orphaned parent (child missing locally)");
                    }
                }
            }

            // Recovery: re-pull against the healthy remote converges.
            new SyncEngine(local, remote).pull(rootHash);
            assertDoesNotThrow(
                    () -> new TreeIntegrityChecker(local).verify(rootHash),
                    "re-pull must complete + converge to a Merkle-consistent tree");
            Map<String, String> got = new LinkedHashMap<>();
            MapIterator it =
                    new StaticMap(local, Node.fromBytes(local.read(rootHash).orElseThrow()), DESC)
                            .iter();
            while (it.next()) {
                got.put(
                        new String(new Tuple(it.key()).getField(0)),
                        new String(it.value().toArray(ValueLayout.JAVA_BYTE)));
            }
            assertEquals(oracle, got, "recovered local tree must materialize the remote key set");

            // Idempotent dedup: a third pull writes nothing new.
            local.writeCount.set(0);
            new SyncEngine(local, remote).pull(rootHash);
            assertEquals(
                    0,
                    local.writeCount.get(),
                    "re-pulling an already-complete tree must write zero new nodes");
        }
    }

    // ---- store wrappers --------------------------------------------------

    /** Delegates to an in-memory store; records every written hash + a counter. */
    private static final class Recording implements NodeStore {
        final InMemoryNodeStore delegate = new InMemoryNodeStore();
        final Set<String> written = new LinkedHashSet<>();
        final AtomicInteger writeCount = new AtomicInteger();

        @Override
        public Optional<MemorySegment> read(byte[] hash) {
            return delegate.read(hash);
        }

        @Override
        public byte[] write(MemorySegment data) {
            return record(delegate.write(data));
        }

        @Override
        public byte[] write(byte[] data) {
            return record(delegate.write(data));
        }

        private byte[] record(byte[] hash) {
            written.add(toHex(hash));
            writeCount.incrementAndGet();
            return hash;
        }
    }

    /** A remote that throws after K successful reads — simulates a dropped transfer mid-pull. */
    private static final class Failing implements NodeStore {
        private final NodeStore delegate;
        private final int failAfter;
        private final AtomicInteger reads = new AtomicInteger();

        Failing(NodeStore delegate, int failAfter) {
            this.delegate = delegate;
            this.failAfter = failAfter;
        }

        @Override
        public Optional<MemorySegment> read(byte[] hash) {
            if (reads.incrementAndGet() > failAfter) {
                throw new RuntimeException(
                        "simulated transfer failure after " + failAfter + " reads");
            }
            return delegate.read(hash);
        }

        @Override
        public byte[] write(MemorySegment data) {
            return delegate.write(data);
        }

        @Override
        public byte[] write(byte[] data) {
            return delegate.write(data);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] fromHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
