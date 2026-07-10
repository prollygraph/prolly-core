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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link NodeCache} (Caffeine-backed, ADR-0040). The cache is on the hot read path —
 * every {@code getStatements()} iteration touches it. The bound is a <b>byte budget</b> (read-path
 * plan D-7), not an entry count, because a {@link Node} carries its 4–16 KiB segment.
 *
 * <p>These tests pin the <b>invariants a node cache must hold</b> — roundtrip, bounded weight,
 * telemetry, disabled budget — NOT eviction order. Eviction is Caffeine's Window-TinyLFU: async and
 * approximate, deliberately not exact-LRU (the read-path experiment series + ADR-0040 explain why),
 * so exact-eviction-order assertions would be testing Caffeine's policy, which Caffeine's own suite
 * + the real-Sail experiment #3 already validate.
 */
class NodeCacheTest {

    /** Byte size of one fake node — the unit the budgets below are expressed in. */
    private static final long NB = fakeNode(0).segment().byteSize();

    private static byte[] h(int seed) {
        byte[] out = new byte[20];
        out[0] = (byte) seed;
        out[1] = (byte) (seed >>> 8);
        return out;
    }

    private static Node fakeNode(int marker) {
        // A real single-entry leaf built via the PRODUCTION path (FlatbufferNodeSerializer +
        // Node.fromBytes) — tests exercise the primitive production ships (the
        // test-the-production-primitive convention; plan subtree-count-contract D-3). The marker
        // varies the key bytes so distinct markers yield distinct nodes; the cache exercises only
        // identity + segment size, both of which the real node provides. NB derives from this
        // node's actual byte size, so the budget math is size-agnostic.
        try (HeapBufferPool pool = new HeapBufferPool()) {
            byte[] key = {(byte) marker, (byte) (marker >>> 8), (byte) (marker >>> 16)};
            byte[] bytes =
                    new FlatbufferNodeSerializer()
                            .serialize(
                                    0,
                                    java.util.List.of(
                                            new TreeMutator.PendingItem(
                                                    java.lang.foreign.MemorySegment.ofArray(key),
                                                    java.lang.foreign.MemorySegment.ofArray(
                                                            new byte[] {1}),
                                                    1L)));
            return java.util.Objects.requireNonNull(
                    Node.fromBytes(java.lang.foreign.MemorySegment.ofArray(bytes)));
        }
    }

    @Test
    void empty_cache_returns_empty() {
        NodeCache c = new NodeCache(NB * 10);
        assertFalse(c.get(h(0)).isPresent());
        assertEquals(0, c.bytes());
    }

    @Test
    void put_then_get_roundtrip() {
        NodeCache c = new NodeCache(NB * 10);
        Node n = fakeNode(0x42);
        c.put(h(1), n);
        Optional<Node> got = c.get(h(1));
        assertTrue(got.isPresent());
        assertSame(
                n,
                got.get(),
                "cache must return the SAME instance — copies would defeat its purpose");
    }

    @Test
    void get_with_unknown_hash_returns_empty() {
        NodeCache c = new NodeCache(NB * 10);
        c.put(h(1), fakeNode(1));
        assertFalse(c.get(h(99)).isPresent());
    }

    @Test
    void put_with_same_hash_overwrites_and_does_not_double_count() {
        NodeCache c = new NodeCache(NB * 10);
        c.put(h(1), fakeNode(1));
        c.put(h(1), fakeNode(2));
        assertTrue(c.get(h(1)).isPresent());
        assertEquals(NB, c.bytes(), "overwriting a key must not double-count its bytes");
    }

    @Test
    void byte_budget_bounds_total_held() {
        NodeCache c = new NodeCache(NB * 3); // room for ~3 nodes
        for (int i = 0; i < 100; i++) c.put(h(i), fakeNode(i));
        assertTrue(
                c.bytes() <= NB * 3,
                "held bytes must stay within the budget; was " + c.bytes() + " > " + (NB * 3));
        assertTrue(c.bytes() > 0, "some entries must remain cached");
    }

    @Test
    void zero_budget_disables_caching() {
        NodeCache c = new NodeCache(0);
        c.put(h(1), fakeNode(1));
        assertFalse(c.get(h(1)).isPresent(), "budget 0 = off: nothing is cached");
        assertEquals(0, c.bytes());
    }

    @Test
    void hits_and_misses_are_counted() {
        NodeCache c = new NodeCache(NB * 10);
        assertFalse(c.get(h(1)).isPresent()); // miss
        c.put(h(1), fakeNode(1));
        assertTrue(c.get(h(1)).isPresent()); // hit
        assertEquals(1, c.hits(), "one hit recorded");
        assertEquals(1, c.misses(), "one miss recorded");
    }
}
