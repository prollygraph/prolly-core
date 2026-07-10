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
package com.earasoft.prolly.concurrency;

import com.dolthub.prolly.FlatbufferNodeSerializer;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeCache;
import com.dolthub.prolly.TreeMutator;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * jcstress memory-model harness: {@link NodeCache} publishes a freshly-built {@link Node} safely.
 * Step 22 of the upstream core-engine test-strategy plan — the {@code NodeCache} cell (the cheap,
 * clean publication micro-test the Step-22 wrap-up named as the next add).
 *
 * <p>The cache is a {@code Collections.synchronizedMap(LinkedHashMap)}; the contract is that a
 * racing reader sees either the value absent (the {@code put} has not been published yet) or a
 * <b>fully-constructed</b> {@code Node} — never a Node whose constructor fields are still at their
 * defaults. The writer builds a <i>fresh</i> Node each iteration so its construction genuinely
 * races the read; the reader, on a hit, checks the parsed {@code count}/{@code level} — a
 * partially-published Node would read them as something other than the expected {@code (1, 0)},
 * which is {@link Expect#FORBIDDEN}. The synchronized map's release/acquire edge is what makes that
 * impossible; this pins it (and would catch a regression to a plain map).
 */
@JCStressTest
@Outcome(
        id = "0",
        expect = Expect.ACCEPTABLE,
        desc = "reader ran before the put was published — absent")
@Outcome(
        id = "1",
        expect = Expect.ACCEPTABLE,
        desc = "reader saw the fully-published Node (count/level correct)")
@Outcome(
        id = "-1",
        expect = Expect.FORBIDDEN,
        desc = "reader saw a partially-constructed Node — publication unsafe")
@State
public class NodeCacheWriteVisibilityJcstress {

    private final NodeCache cache = new NodeCache(1 << 20); // 1 MiB byte budget
    private final byte[] nodeBytes = buildLeaf();
    private final byte[] hash = HashUtils.hash(nodeBytes);

    /**
     * A minimal valid single-item leaf node (level 0, count 1), built via the PRODUCTION serializer
     * (test-the-production-primitive; plan subtree-count-contract D-3).
     */
    private static byte[] buildLeaf() {
        byte[] key = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] val = {9, 10, 11, 12, 13, 14, 15, 16};
        try (HeapBufferPool pool = new HeapBufferPool()) {
            return new FlatbufferNodeSerializer()
                    .serialize(
                            0,
                            List.of(
                                    new TreeMutator.PendingItem(
                                            MemorySegment.ofArray(key),
                                            MemorySegment.ofArray(val),
                                            1L)));
        }
    }

    @Actor
    public void writer() {
        // Build a FRESH Node (production parse path) and publish it — its construction races the
        // reader.
        cache.put(hash, Objects.requireNonNull(Node.fromBytes(MemorySegment.ofArray(nodeBytes))));
    }

    @Actor
    public void reader(I_Result r) {
        Optional<Node> n = cache.get(hash);
        if (n.isEmpty()) {
            r.r1 = 0; // not yet published — fine
            return;
        }
        Node node = n.get();
        r.r1 = (node.count() == 1 && node.level() == 0) ? 1 : -1;
    }
}
