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
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 *
 *
 * <h3>ParallelReachabilityWalker Equivalence Test</h3>
 *
 * <p>Asserts that {@link ParallelReachabilityWalker} produces an identical reachable-hash set to
 * the canonical serial {@link ReachabilityWalker}. The parallel walker uses a {@code ForkJoinPool}
 * and a {@code ConcurrentHashMap.newKeySet()} de-dup; if the dedup ever raced a fork (e.g. the
 * {@code !reachable.add(hex) -> return} short-circuit had its check-then-act split), nodes could be
 * visited twice or, worse, missed.
 *
 * <p><b>The Gap:</b> {@code ParallelReachabilityWalker} had zero direct test references — only used
 * indirectly via {@code GarbageCollector}. A regression in the parallel walker would degrade GC
 * silently (sweep would either miss live nodes or quadratic-walk the tree).
 *
 * <p><b>Oracles:</b>
 *
 * <ol>
 *   <li>Reachable set from parallel walker == reachable set from serial walker on the same tree.
 *   <li>The reachable set covers every node we know is on disk for the tree (computed via a hand
 *       walk in this test) — neither walker has missed any node.
 *   <li>Walking the same root twice from the same parallel-walker instance does not double-count
 *       (idempotent on repeated invocation).
 * </ol>
 */
public class ParallelReachabilityWalkerTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- ParallelReachabilityWalker Equivalence Test ---");
        Path tempDir = Files.createTempDirectory("prolly-parallel-walker");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            // Build a multi-level tree.
            byte[] padding = new byte[256];
            Arrays.fill(padding, (byte) 0x33);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 5000; i++) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("k-%07d", i).getBytes());
                edits.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(), MemorySegment.ofArray(padding)));
            }
            Node root = mutator.applyMutations(null, edits.iterator());
            byte[] rootHash = store.write(root.segment());

            // Hand walk: enumerate every node hash reachable from the root.
            Set<String> handWalk = new HashSet<>();
            collectAllReachable(store, rootHash, handWalk);
            System.out.println("Built tree, hand-walked " + handWalk.size() + " nodes.");

            // Oracle 1 + 2: serial walker matches hand walk.
            ReachabilityWalker serial = new ReachabilityWalker(store);
            serial.walk(rootHash);
            Set<String> serialReachable = serial.getReachableHashes().toHexSet();
            if (!serialReachable.equals(handWalk)) {
                throw new RuntimeException(
                        "Serial walker disagrees with hand walk: missing="
                                + diff(handWalk, serialReachable).size()
                                + " extra="
                                + diff(serialReachable, handWalk).size());
            }
            System.out.println("Serial walker matches hand walk. (1/3)");

            // Oracle 1: parallel walker matches serial walker.
            ParallelReachabilityWalker parallel = new ParallelReachabilityWalker(store);
            parallel.walk(rootHash);
            Set<String> parallelReachable = parallel.getReachableHashes().toHexSet();
            if (!parallelReachable.equals(serialReachable)) {
                throw new RuntimeException(
                        "Parallel walker disagrees with serial: missing="
                                + diff(serialReachable, parallelReachable).size()
                                + " extra="
                                + diff(parallelReachable, serialReachable).size());
            }
            System.out.println(
                    "Parallel walker matches serial walker over "
                            + parallelReachable.size()
                            + " nodes. (2/3)");

            // Oracle 3: re-walking the same root is idempotent.
            int sizeBefore = parallelReachable.size();
            parallel.walk(rootHash);
            int sizeAfter = parallel.getReachableHashes().size();
            if (sizeBefore != sizeAfter) {
                throw new RuntimeException(
                        "Parallel walker double-counted on second walk: "
                                + sizeBefore
                                + " -> "
                                + sizeAfter);
            }
            System.out.println("Parallel walker is idempotent on repeated walk. (3/3)");

            System.out.println("--- ParallelReachabilityWalker Equivalence Test PASSED ---");
        }
    }

    private static void collectAllReachable(NodeStore store, byte[] hash, Set<String> out) {
        if (hash == null) return;
        String hex = HashUtils.toHex(hash);
        if (!out.add(hex)) return;
        store.read(hash)
                .ifPresent(
                        seg -> {
                            Node n = Node.fromBytes(seg);
                            if (!n.isLeaf()) {
                                for (int i = 0; i < n.count(); i++) {
                                    collectAllReachable(store, n.getValue(i), out);
                                }
                            }
                        });
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> r = new HashSet<>(a);
        r.removeAll(b);
        return r;
    }
}
