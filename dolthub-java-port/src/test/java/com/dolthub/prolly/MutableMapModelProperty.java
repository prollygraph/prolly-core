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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.ActionChainArbitrary;
import net.jqwik.api.state.Transformer;

/**
 * Phase 1 Step 1 of {@code plans/model-based-testing-rollout.md} — <b>stateful model-based</b>
 * property for {@link MutableMap}, the per-transaction write overlay (the same ActionChain
 * discipline that found a file-descriptor leak in {@link SpillableSortedBuffer}, applied one layer
 * up).
 *
 * <p>Each generated {@link ActionChain} is a long random interleaving of put / delete / get /
 * <b>flush</b> over a single {@code MutableMap}, run in lockstep against a {@link TreeMap} oracle
 * of the logical contents. The map starts over an <b>empty</b> base, so {@code flush} (which
 * persists the edits to a new {@link StaticMap} and re-bases the overlay) is what exercises the
 * states a flat op-list can't reach: read-your-writes <i>over a flushed base</i>, a tombstone
 * shadowing a base key, and base accumulation across several flushes (each flush builds the tree
 * from {prior base + new edits}). After every flush the oracle is unchanged but every key is
 * re-read from the freshly-built base — so a tree-build/persist bug surfaces too.
 *
 * <p>Tuned like the buffer property: a tiny key alphabet (heavy overwrite + delete churn) so
 * last-write-wins and delete-shadowing across the overlay/base boundary are hammered constantly.
 */
class MutableMapModelProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    // ~12 keys (a..c, length 1–2) → heavy collisions across the overlay + base.
    private static final Arbitrary<String> KEYS =
            Arbitraries.strings().withCharRange('a', 'c').ofMinLength(1).ofMaxLength(2);
    private static final Arbitrary<String> VALS =
            Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(6);

    @Property(tries = 500)
    void mutableMapMatchesModelAcrossActionChains(@ForAll("chains") ActionChain<Model> chain) {
        chain.run();
    }

    @Provide
    ActionChainArbitrary<Model> chains() {
        return ActionChain.startWith(Model::new)
                // weighted toward mutation; flush is rarer (it rebuilds the tree + re-bases the
                // overlay)
                .withAction(put())
                .withAction(put())
                .withAction(put())
                .withAction(delete())
                .withAction(delete())
                .withAction(get())
                .withAction(get())
                .withAction(flush())
                .withMaxTransformations(120);
    }

    private Action.Independent<Model> put() {
        return () ->
                Combinators.combine(KEYS, VALS)
                        .as((k, v) -> Transformer.mutate("put " + k + "=" + v, m -> m.put(k, v)));
    }

    private Action.Independent<Model> delete() {
        return () -> KEYS.map(k -> Transformer.mutate("del " + k, m -> m.delete(k)));
    }

    private Action.Independent<Model> get() {
        return () -> KEYS.map(k -> Transformer.mutate("get " + k, m -> m.assertGet(k)));
    }

    private Action.Independent<Model> flush() {
        return () -> Arbitraries.just(Transformer.mutate("flush", Model::flushAndReverify));
    }

    /**
     * The {@link MutableMap} under test (replaced on flush) paired with its {@link TreeMap} oracle.
     */
    static final class Model {
        final InMemoryNodeStore store = new InMemoryNodeStore();
        final HeapBufferPool pool = new HeapBufferPool();
        MutableMap mm;
        final TreeMap<String, String> ref =
                new TreeMap<>(); // logical contents; a delete REMOVES the key

        Model() {
            mm = new MutableMap(new StaticMap(store, null, DESC), store, DESC, pool);
        }

        void put(String k, String v) {
            mm.put(key(k), MemorySegment.ofArray(v.getBytes(StandardCharsets.UTF_8)));
            ref.put(k, v);
            assertGet(k);
        }

        void delete(String k) {
            mm.delete(key(k));
            ref.remove(k);
            assertGet(k);
        }

        void assertGet(String k) {
            Optional<MemorySegment> got = mm.get(key(k));
            if (ref.containsKey(k)) {
                assertTrue(got.isPresent(), "present key " + k);
                assertEquals(ref.get(k), str(got.get()), "value of " + k);
            } else {
                assertFalse(got.isPresent(), "absent/deleted key " + k);
            }
        }

        /**
         * Persist the edits to a new base and re-base the overlay; logical contents must survive
         * unchanged and read back from the freshly-built tree.
         */
        void flushAndReverify() {
            StaticMap next = mm.flush();
            mm = new MutableMap(next, store, DESC, pool);
            for (String k : ref.keySet())
                assertGet(k); // every key persisted + reads from the new base
            assertGet("~never"); // an absent key still reads empty after a flush
        }

        private MemorySegment key(String s) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, s.getBytes(StandardCharsets.UTF_8));
            return tb.build().segment();
        }
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }
}
