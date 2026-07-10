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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.ActionChainArbitrary;
import net.jqwik.api.state.Transformer;

/**
 * Phase 1 Step 2 of {@code plans/model-based-testing-rollout.md} — <b>stateful model-based</b>
 * property for {@link Cursor}, the positioned tree iterator. Where {@code InvCursorProperty} pins
 * the <i>seek</i> surface over many tree shapes with whole forward/reverse walks, this pins the
 * <b>interleaved navigation</b> a flat walk can't reach: random sequences of {@code atStart / atEnd
 * / atKey / advance / retreat} over one multi-leaf tree, with the cursor's exact position + value +
 * validity checked against an integer-indexed sorted-list model <i>after every move</i> — so an
 * off-by-one at a leaf boundary, or a wrong return/validity at the before-start / past-end edges,
 * surfaces the moment it happens.
 *
 * <p><b>Design notes — why "interleaved navigation over a fixed multi-leaf tree":</b>
 *
 * <ul>
 *   <li><b>Interleaved</b> — the chain is a <i>random ordering</i> of moves ({@code advance}/{@code
 *       retreat}/{@code atStart}/{@code atEnd}/{@code atKey}), not a single forward-or-reverse
 *       sweep. The bugs this reaches are <i>order-dependent</i> and structurally invisible to a
 *       one-direction walk: advance to past-end then {@code retreat} back; {@code atKey} then
 *       immediately {@code retreat} across a leaf boundary; {@code atEnd} then {@code advance}
 *       (must stay past-end); {@code retreat} to before-start then {@code advance} (must return to
 *       entry 0). A flat walk visits each position once in one direction and can never produce
 *       these transitions.
 *   <li><b>Navigation</b> — the model is a single <i>position index</i> into a sorted list, so the
 *       assertion is the cursor's <i>exact position</i> (value + validity + the {@code
 *       advance}/{@code retreat} boolean) after <i>every</i> move, not just the contents of a whole
 *       iteration. It tests {@link Cursor} as a stateful positioned object — where it <i>is</i> —
 *       rather than what a full scan emits.
 *   <li><b>Fixed multi-leaf tree</b> — one immutable ~1200-key tree, deliberately multi-level so
 *       that {@code advance}/{@code retreat} cross leaf boundaries (the {@code parent.advance()}
 *       recursion) and seeks descend through internal nodes. The tree is <i>fixed</i> on purpose:
 *       tree-<i>shape</i> variation is {@code InvCursorProperty}'s job (it varies the tree to pin
 *       seek correctness across shapes), so here the random, shrinkable dimension is the <i>move
 *       sequence</i>. A fixed multi-leaf tree suffices because the navigation logic depends on
 *       multi-leaf <i>structure</i> (boundary crossings, internal descent), not on the specific
 *       keys — and only the cursor moves, never the tree, so it is safely shared static.
 * </ul>
 *
 * <p>Boundary model (derived from {@link Cursor#advance}/{@link Cursor#retreat}): positions are
 * {@code -1} (before-start), {@code 0..N-1} (valid), {@code N} (past-end). {@code advance} from
 * {@code -1} lands on {@code 0}; from {@code N-1} steps to past-end (returns false); from past-end
 * stays. {@code retreat} is symmetric (from past-end steps back to {@code N-1}). {@code
 * advance}/{@code retreat} return true iff the resulting position is valid.
 */
class CursorModelProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final HeapBufferPool POOL = new HeapBufferPool();

    // One fixed, multi-leaf tree shared across all chains (it is immutable; only the cursor moves).
    private static final InMemoryNodeStore STORE = new InMemoryNodeStore();
    private static final Node ROOT;
    private static final List<String> KEYS = new ArrayList<>(); // sorted ascending
    private static final Map<String, String> VALUES = new HashMap<>();
    private static final int N;

    static {
        TreeMutator tm = new TreeMutator(STORE, DESC, POOL);
        List<TreeMutator.Mutation> muts = new ArrayList<>();
        for (int i = 0; i < 1200; i++) { // 1200 keys → guaranteed multi-level (internal root)
            String k = "k" + String.format("%04d", i);
            String v = "v" + i;
            KEYS.add(k);
            VALUES.put(k, v);
            muts.add(
                    new TreeMutator.Mutation(
                            keySeg(k), MemorySegment.ofArray(v.getBytes(StandardCharsets.UTF_8))));
        }
        ROOT = tm.applyMutations(null, muts.iterator()); // KEYS already sorted (fixed-width)
        N = KEYS.size();
    }

    @Property(tries = 500)
    void cursorMatchesModelAcrossActionChains(@ForAll("chains") ActionChain<Model> chain) {
        chain.run();
    }

    /**
     * Every seek descends through internal nodes, reading each child from the store. A child absent
     * from the store must raise a clear {@link IllegalStateException} (not an NPE) — the {@code
     * orElseThrow} on each descent. Exercised by pointing a seek at an <b>empty</b> store while
     * reusing the real (internal) ROOT, so the first child read misses.
     */
    @Example
    void descendingIntoAMissingChildThrowsIllegalState() {
        org.junit.jupiter.api.Assertions.assertTrue(
                ROOT.level() > 0, "ROOT must be internal for this to exercise a descent");
        InMemoryNodeStore empty =
                new InMemoryNodeStore(); // ROOT's children live in STORE, not here
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> Cursor.atStart(empty, ROOT), "atStart");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> Cursor.atEnd(empty, ROOT), "atEnd");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> Cursor.atKey(empty, ROOT, keySeg("k0100"), DESC),
                "atKey");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> Cursor.atRawKey(empty, ROOT, keySeg("k0100")),
                "atRawKey");
    }

    @Provide
    ActionChainArbitrary<Model> chains() {
        return ActionChain.startWith(Model::new)
                .withAction(advance())
                .withAction(advance())
                .withAction(advance())
                .withAction(retreat())
                .withAction(retreat())
                .withAction(retreat())
                .withAction(atKey())
                .withAction(atStart())
                .withAction(atEnd())
                .withMaxTransformations(200);
    }

    private Action.Independent<Model> advance() {
        return () -> Arbitraries.just(Transformer.mutate("advance", Model::advance));
    }

    private Action.Independent<Model> retreat() {
        return () -> Arbitraries.just(Transformer.mutate("retreat", Model::retreat));
    }

    private Action.Independent<Model> atStart() {
        return () -> Arbitraries.just(Transformer.mutate("atStart", Model::atStart));
    }

    private Action.Independent<Model> atEnd() {
        return () -> Arbitraries.just(Transformer.mutate("atEnd", Model::atEnd));
    }

    /**
     * Probe keys: present (0..299), absent-above (300..320), plus below-min / prefix / exact edges.
     */
    private Action.Independent<Model> atKey() {
        Arbitrary<String> probe =
                Arbitraries.oneOf(
                        Arbitraries.integers()
                                .between(0, 1220)
                                .map(i -> "k" + String.format("%04d", i)),
                        Arbitraries.of("a", "k", "zzzz", "k0000", "k1199"));
        return () -> probe.map(k -> Transformer.mutate("atKey " + k, m -> m.atKey(k)));
    }

    /** Cursor under test + an integer position into the sorted key list. */
    static final class Model {
        Cursor cur = Cursor.atStart(STORE, ROOT);
        int pos = 0;

        void advance() {
            int newPos = (pos == -1) ? 0 : Math.min(pos + 1, N); // 0..N-1 → +1; past-end stays
            boolean expect = newPos >= 0 && newPos < N;
            boolean got = cur.advance();
            pos = newPos;
            assertEquals(expect, got, "advance() return moving to pos " + pos);
            assertState("after advance");
        }

        void retreat() {
            int newPos =
                    (pos == N) ? N - 1 : Math.max(pos - 1, -1); // past-end → N-1; 0 → -1; -1 stays
            boolean expect = newPos >= 0 && newPos < N;
            boolean got = cur.retreat();
            pos = newPos;
            assertEquals(expect, got, "retreat() return moving to pos " + pos);
            assertState("after retreat");
        }

        void atStart() {
            cur = Cursor.atStart(STORE, ROOT);
            pos = 0;
            assertState("after atStart");
        }

        void atEnd() {
            cur = Cursor.atEnd(STORE, ROOT);
            pos = N - 1;
            assertState("after atEnd");
        }

        void atKey(String k) {
            cur = Cursor.atKey(STORE, ROOT, keySeg(k), DESC);
            pos = lowerBound(k); // first index with KEYS[i] >= k, else N
            assertState("after atKey " + k);
        }

        private void assertState(String where) {
            boolean valid = pos >= 0 && pos < N;
            assertEquals(valid, cur.isValid(), "isValid at pos " + pos + " " + where);
            if (valid) {
                assertArrayEquals(
                        keySeg(KEYS.get(pos)).toArray(BYTE),
                        cur.currentKey().toArray(BYTE),
                        "key at pos " + pos + " " + where);
                assertEquals(
                        VALUES.get(KEYS.get(pos)),
                        str(cur.currentValue()),
                        "value at pos " + pos + " " + where);
            }
        }
    }

    private static int lowerBound(String k) {
        int p = 0;
        while (p < N && KEYS.get(p).compareTo(k) < 0) p++;
        return p;
    }

    private static MemorySegment keySeg(String s) {
        TupleBuilder tb = new TupleBuilder(POOL);
        tb.putField(0, s.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(BYTE), StandardCharsets.UTF_8);
    }
}
