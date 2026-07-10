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
package com.earasoft.prolly.multistore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Sequential invariants of the pinned, self-reopening, LRU-bounded registry (Step 0.2c / R-1..R-5
 * of the upstream versioning-service plan). The load-bearing one is R-4: a <em>pinned</em> resource
 * is never evicted (closed) out from under its holder. The pin-vs-evict <em>concurrency</em> proof
 * is a separate increment (Lincheck/jcstress); this class pins the single-threaded semantics first.
 */
class PinnedLruRegistryTest {

    /** A fake resource that records whether it was closed. */
    static final class Tracked {
        final String id;
        volatile boolean closed = false;

        Tracked(String id) {
            this.id = id;
        }
    }

    private final AtomicInteger opens = new AtomicInteger();
    private final Function<String, Tracked> factory =
            id -> {
                opens.incrementAndGet();
                return new Tracked(id);
            };
    private final Consumer<Tracked> close = t -> t.closed = true;

    private PinnedLruRegistry<Tracked> registry(int warmSetSize, String... repos) {
        PinnedLruRegistry<Tracked> r = new PinnedLruRegistry<>(factory, close, warmSetSize);
        for (String repo : repos) {
            r.register(repo);
        }
        return r;
    }

    @Test
    void acquire_opensLazily_andPins() {
        PinnedLruRegistry<Tracked> r = registry(4, "A");
        assertEquals(0, opens.get(), "nothing opens until acquired (R-5 lazy)");
        try (var pin = r.acquire("A")) {
            assertEquals(1, opens.get());
            assertEquals("A", pin.resource().id);
            assertTrue(r.isWarm("A"));
            assertEquals(1, r.pinCount("A"));
        }
        assertEquals(0, r.pinCount("A"), "close releases the pin");
    }

    @Test
    void secondAcquire_reusesWarm_andStacksPins() {
        PinnedLruRegistry<Tracked> r = registry(4, "A");
        try (var p1 = r.acquire("A");
                var p2 = r.acquire("A")) {
            assertEquals(1, opens.get(), "warm hit — factory not called again");
            assertSame(p1.resource(), p2.resource());
            assertEquals(2, r.pinCount("A"));
        }
        assertEquals(0, r.pinCount("A"));
    }

    @Test
    void pinnedEntry_isNeverEvicted_evenOverCap() {
        PinnedLruRegistry<Tracked> r = registry(1, "A", "B");
        try (var pinA = r.acquire("A")) { // pin A
            try (var pinB = r.acquire("B")) { // over cap, but A is pinned
                assertFalse(pinA.resource().closed, "R-4: a pinned resource is NOT evicted");
                assertFalse(pinB.resource().closed);
                assertEquals(2, r.warmSize(), "soft over-cap while everything is pinned");
            }
        }
    }

    @Test
    void releasedEntry_isEvicted_whenOverCap() {
        PinnedLruRegistry<Tracked> r = registry(1, "A", "B");
        Tracked a;
        try (var pinA = r.acquire("A")) {
            a = pinA.resource();
        } // acquire + release A
        try (var pinB = r.acquire("B")) { // over cap; A unpinned -> evicted
            assertTrue(a.closed, "R-2: the evicted bundle is closed");
            assertFalse(r.isWarm("A"));
            assertEquals(1, r.warmSize());
        }
    }

    @Test
    void softOverCap_isReclaimed_onRelease() {
        PinnedLruRegistry<Tracked> r = registry(1, "A", "B");
        try (var pinB = r.acquire("B")) {
            var pinA = r.acquire("A"); // over cap, both pinned -> soft over-cap (size 2)
            assertEquals(2, r.warmSize());
            pinA.close(); // A now unpinned -> reclaimed to cap
            assertFalse(r.isWarm("A"));
            assertEquals(1, r.warmSize());
        }
    }

    @Test
    void evictedRepo_reopensFromFactory_onNextAcquire() {
        PinnedLruRegistry<Tracked> r = registry(1, "A", "B");
        try (var p = r.acquire("A")) {
            /* open A (opens=1) */
        }
        try (var p = r.acquire("B")) {
            /* open B (opens=2), evicts A */
        }
        assertFalse(r.isWarm("A"));
        try (var p = r.acquire("A")) { // R-3: re-open from disk on miss
            assertEquals(3, opens.get(), "A re-opened after eviction");
        }
    }

    @Test
    void acquire_unregistered_throws() {
        PinnedLruRegistry<Tracked> r = registry(4, "A");
        assertThrows(RepoNotFoundException.class, () -> r.acquire("ghost"));
    }

    @Test
    void pinCount_mustReachZero_beforeEvictable() {
        PinnedLruRegistry<Tracked> r = registry(1, "A", "B");
        var p1 = r.acquire("A");
        var p2 = r.acquire("A"); // pinCount[A] = 2
        try (var pinB = r.acquire("B")) {
            assertFalse(p1.resource().closed, "still pinned (count 2) -> not evicted");
            p1.close(); // count 1, still pinned
            assertFalse(r.acquire("A").resource().closed);
        }
        p2.close();
    }

    /**
     * R-4 under concurrency — the load-bearing, data-corruption-class invariant. Deterministically
     * forces the evict-in-use window: the holder pins A and blocks; the evictor then acquires B
     * (cap 1 ⇒ eviction pressure on A) and signals; the holder observes A <em>while still holding
     * the pin</em>. A pin-ignoring registry closes A here — negative control verified 2026-06-05:
     * with the pin check removed from the eviction scan, this assertion fails; with it, A survives.
     * (A Lincheck model-check of the same property did not expose the hold-across-switch window for
     * this acquire/hold/release shape, so this deterministic interleaving is the trustworthy
     * proof.)
     */
    @Test
    void pinnedResource_isNeverClosed_byAConcurrentEvict() throws InterruptedException {
        PinnedLruRegistry<Tracked> r = registry(1, "A", "B");
        CountDownLatch pinned = new CountDownLatch(1);
        CountDownLatch evictionDone = new CountDownLatch(1);
        AtomicBoolean closedWhilePinned = new AtomicBoolean(false);

        Thread holder =
                new Thread(
                        () -> {
                            try (var pin = r.acquire("A")) {
                                pinned.countDown();
                                evictionDone.await();
                                if (pin.resource().closed) {
                                    closedWhilePinned.set(true);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
        Thread evictor =
                new Thread(
                        () -> {
                            try {
                                pinned.await();
                                try (var pin =
                                        r.acquire(
                                                "B")) { // cap 1: eviction pressure on the pinned A
                                    assertFalse(pin.resource().closed);
                                }
                                evictionDone.countDown();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });

        holder.start();
        evictor.start();
        holder.join(5000);
        evictor.join(5000);

        assertFalse(
                closedWhilePinned.get(),
                "R-4: a pinned resource must never be closed by a concurrent evict");
    }

    @Test
    void closeAll_closesEveryWarmEntry_evenPinned_andEmptiesTheWarmSet() {
        PinnedLruRegistry<Tracked> r = registry(4, "A", "B");
        Tracked b;
        try (var pinB = r.acquire("B")) {
            b = pinB.resource();
        } // B released — unpinned but warm
        try (var pinA = r.acquire("A")) {
            Tracked a = pinA.resource();
            r.closeAll(); // shutdown while A is still pinned (a leak being cleaned)
            assertTrue(a.closed, "closeAll closes a pinned entry too — shutdown semantics");
            assertTrue(b.closed, "an unpinned warm entry is closed too");
            assertEquals(0, r.warmSize());
        }
    }

    @Test
    void closeAll_thenAcquire_reopensFromTheFactory() {
        PinnedLruRegistry<Tracked> r = registry(4, "A");
        Tracked first = r.acquire("A").resource();
        r.closeAll();
        try (var pin = r.acquire("A")) {
            assertTrue(first.closed);
            assertFalse(pin.resource().closed, "registered ids survive closeAll; acquire re-opens");
            assertEquals(2, opens.get());
        }
    }
}
