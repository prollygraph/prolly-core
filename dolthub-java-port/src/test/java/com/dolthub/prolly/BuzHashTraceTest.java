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
import java.util.*;

public class BuzHashTraceTest {
    public static void main(String[] args) throws Exception {
        HeapBufferPool pool = new HeapBufferPool();
        InMemoryNodeStore store = new InMemoryNodeStore();
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
        TreeMutator mutator = new TreeMutator(store, desc, pool);

        List<TreeMutator.Mutation> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            byte[] key = String.format("k%05d", i).getBytes();
            byte[] val = ("v" + i).getBytes();
            entries.add(
                    new TreeMutator.Mutation(buildTuple(pool, key), MemorySegment.ofArray(val)));
        }

        // Scenario A
        System.out.println("A_START");
        Node rootA = mutator.applyMutations(null, entries.iterator());
        byte[] hashA = store.write(rootA.segment());
        System.out.println("A_HASH " + toHex(hashA));

        // Scenario C
        System.out.println("C_START");
        Node rootC = null;
        for (int i = 0; i < entries.size(); i += 5) {
            rootC = mutator.applyMutations(rootC, entries.subList(i, i + 5).iterator());
        }
        byte[] hashC = store.write(rootC.segment());
        System.out.println("C_HASH " + toHex(hashC));
    }

    private static MemorySegment buildTuple(HeapBufferPool pool, byte[] key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key);
        return tb.build().segment();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
