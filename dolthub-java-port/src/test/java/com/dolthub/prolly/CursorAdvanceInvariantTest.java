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
package com.dolthub.prolly;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 *
 * <h3>Cursor Advance / Retreat Invariant Test</h3>
 *
 * <p>Pins the navigation contract of {@link Cursor}: for any in-range key {@code k_i}, {@code
 * seek(k_i) + advance()} must return {@code k_{i+1}}, and {@code seek(k_i) + retreat()} must return
 * {@code k_{i-1}} — <b>across chunk boundaries</b>. A regression here is silent data loss during
 * scans.
 *
 * <p><b>The Gap:</b> {@code MainTest} and {@code TreeIntegrityTest} exercise full forward
 * iteration, but neither pins post-{@code advance} or post-{@code retreat} keys against a known
 * sequence after a targeted {@code atKey} seek. Off-by-ones in the recursive parent-advance path
 * (lines 140–179 of {@code Cursor.java}) only surface near pivot keys, so a full scan from index 0
 * will not detect them.
 *
 * <p><b>The corpus is sized to span multiple levels:</b> 5000 string keys with payloads padded to
 * ~256 bytes each force the splitter to emit multiple leaf chunks, which in turn force at least one
 * internal level. This guarantees that some {@code seek + advance} pairs cross both leaf and
 * internal-node boundaries, exercising the recursive {@code parent.advance()} path inside {@code
 * Cursor#advance()}.
 *
 * <p><b>Three Oracles:</b>
 *
 * <ol>
 *   <li><b>Forward chain:</b> for every {@code i} in {@code [0, N-1)}, {@code seek(k_i) +
 *       advance()} returns {@code k_{i+1}}.
 *   <li><b>Backward chain:</b> for every {@code i} in {@code (0, N-1]}, {@code seek(k_i) +
 *       retreat()} returns {@code k_{i-1}}.
 *   <li><b>Edge:</b> {@code advance} from the last key reports invalid; {@code retreat} from the
 *       first key reports invalid.
 * </ol>
 */
public class CursorAdvanceInvariantTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Cursor Advance / Retreat Invariant Test ---");
        Path tempDir = Files.createTempDirectory("prolly-cursor-invariant");

        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            int N = 5000;
            byte[] padding = new byte[256];
            Arrays.fill(padding, (byte) 0x42);

            List<byte[]> keys = new ArrayList<>(N);
            List<TreeMutator.Mutation> edits = new ArrayList<>(N);
            for (int i = 0; i < N; i++) {
                byte[] k = String.format("k-%07d", i).getBytes();
                keys.add(k);
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, k);
                edits.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(), MemorySegment.ofArray(padding)));
            }
            Node root = mutator.applyMutations(null, edits.iterator());
            int height = treeHeight(store, root);
            System.out.println(
                    "Built tree of "
                            + N
                            + " keys, height = "
                            + height
                            + " (level0..level"
                            + (height - 1)
                            + ").");
            if (height < 2) {
                throw new RuntimeException("Test requires multi-level tree; got height " + height);
            }

            // Oracle 1: forward chain
            System.out.print("Forward chain seek(k_i)+advance==k_{i+1}... ");
            for (int i = 0; i < N - 1; i++) {
                MemorySegment seekKey = wrapKey(pool, keys.get(i));
                Cursor cur = Cursor.atKey(store, root, seekKey, desc);
                if (!cur.advance()) {
                    throw new RuntimeException("advance() unexpectedly invalid at i=" + i);
                }
                byte[] got = currentKeyField(cur);
                if (!Arrays.equals(got, keys.get(i + 1))) {
                    throw new RuntimeException(
                            "FWD mismatch at i="
                                    + i
                                    + ": expected "
                                    + new String(keys.get(i + 1))
                                    + " got "
                                    + new String(got));
                }
            }
            System.out.println("PASS (" + (N - 1) + " transitions).");

            // Oracle 2: backward chain
            System.out.print("Backward chain seek(k_i)+retreat==k_{i-1}... ");
            for (int i = 1; i < N; i++) {
                MemorySegment seekKey = wrapKey(pool, keys.get(i));
                Cursor cur = Cursor.atKey(store, root, seekKey, desc);
                if (!cur.retreat()) {
                    throw new RuntimeException("retreat() unexpectedly invalid at i=" + i);
                }
                byte[] got = currentKeyField(cur);
                if (!Arrays.equals(got, keys.get(i - 1))) {
                    throw new RuntimeException(
                            "BWD mismatch at i="
                                    + i
                                    + ": expected "
                                    + new String(keys.get(i - 1))
                                    + " got "
                                    + new String(got));
                }
            }
            System.out.println("PASS (" + (N - 1) + " transitions).");

            // Oracle 3: edges
            System.out.print("Edge: advance past last and retreat past first... ");
            Cursor lastCur = Cursor.atKey(store, root, wrapKey(pool, keys.get(N - 1)), desc);
            if (lastCur.advance()) {
                throw new RuntimeException("advance() past last key returned true");
            }
            Cursor firstCur = Cursor.atKey(store, root, wrapKey(pool, keys.get(0)), desc);
            if (firstCur.retreat()) {
                throw new RuntimeException("retreat() past first key returned true");
            }
            System.out.println("PASS.");

            System.out.println("--- Cursor Advance / Retreat Invariant Test PASSED ---");
        }
    }

    /** Counts internal levels by walking the leftmost spine. */
    private static int treeHeight(NodeStore store, Node root) {
        int h = 1;
        Node cur = root;
        while (!cur.isLeaf()) {
            byte[] childHash = cur.getValue(0);
            cur = store.read(childHash).map(Node::fromBytes).orElseThrow();
            h++;
        }
        return h;
    }

    private static MemorySegment wrapKey(HeapBufferPool pool, byte[] k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k);
        return tb.build().segment();
    }

    private static byte[] currentKeyField(Cursor cur) {
        Tuple t = new Tuple(cur.currentKey());
        return t.getField(0);
    }
}
