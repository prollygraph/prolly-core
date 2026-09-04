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
package com.earasoft.prolly.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The mark set that decides what a collection keeps.
 *
 * <p>Every failure mode here is silent and unrecoverable in the same direction: if {@link
 * PackedChunkSet#add} wrongly reports a hash as already present, the walk prunes a subtree it never
 * visited, those chunks are never marked, and the sweep deletes live data. So the load-bearing case
 * is not "does it store things" — it is {@link
 * #keys_sharing_the_slot_index_do_not_shadow_each_other}, because the slot index is only the first
 * four bytes of the key and everything past that rests on the full-width comparison.
 */
class PackedChunkSetTest {

    private static byte[] hash(int seed) {
        byte[] h = new byte[ChunkSet.HASH_LEN];
        new Random(seed).nextBytes(h);
        return h;
    }

    @Test
    void an_empty_set_holds_nothing() {
        PackedChunkSet s = new PackedChunkSet();
        assertEquals(0, s.size());
        assertTrue(s.isEmpty());
        assertFalse(s.contains(hash(1)));
    }

    @Test
    void add_reports_absent_once_and_present_thereafter() {
        PackedChunkSet s = new PackedChunkSet();
        byte[] h = hash(7);
        assertTrue(s.add(h), "first add must report the hash was absent");
        assertFalse(
                s.add(h), "second add must report it was present — this is the walk's terminator");
        assertEquals(1, s.size());
        assertTrue(s.contains(h));
    }

    /**
     * The one that matters. {@code slotOf} uses only bytes 0..3, so two hashes agreeing there
     * collide by construction; only the full 20-byte comparison keeps them distinct. If this fails,
     * a walk silently skips a subtree and a sweep deletes live chunks.
     */
    @Test
    void keys_sharing_the_slot_index_do_not_shadow_each_other() {
        PackedChunkSet s = new PackedChunkSet();
        byte[] a = new byte[ChunkSet.HASH_LEN];
        byte[] b = new byte[ChunkSet.HASH_LEN];
        for (int i = 0; i < 4; i++) {
            a[i] = b[i] = (byte) 0xAB; // identical slot index
        }
        a[ChunkSet.HASH_LEN - 1] = 1; // differ in the LAST byte only
        b[ChunkSet.HASH_LEN - 1] = 2;

        assertTrue(s.add(a));
        assertTrue(s.add(b), "a colliding-but-distinct key must be added, not silently absorbed");
        assertEquals(2, s.size());
        assertTrue(s.contains(a));
        assertTrue(s.contains(b));

        byte[] c = b.clone();
        c[ChunkSet.HASH_LEN - 1] = 3;
        assertFalse(s.contains(c), "a third key on the same slot must not be reported present");
    }

    /**
     * The two ends of the contract are deliberately different, and both are right. A short key is
     * correctly ABSENT from a set of 20-byte keys, so {@code contains} answers false rather than
     * throwing. Adding one would corrupt the table, so {@code add} refuses.
     */
    @Test
    void a_short_key_is_absent_on_lookup_and_refused_on_insert() {
        PackedChunkSet s = new PackedChunkSet();
        byte[] tooShort = new byte[ChunkSet.HASH_LEN - 1];
        assertFalse(s.contains(tooShort), "a short key cannot be a member");
        assertFalse(s.contains(null), "null cannot be a member");
        assertThrows(IllegalArgumentException.class, () -> s.add(tooShort));
        assertThrows(IllegalArgumentException.class, () -> s.add(null));
        assertEquals(0, s.size(), "a refused insert must leave the set untouched");
    }

    @Test
    void it_survives_rehashing_without_losing_a_key() {
        // Start far below the target so the table grows repeatedly.
        PackedChunkSet s = new PackedChunkSet(16);
        Set<String> mirror = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            byte[] h = hash(i);
            assertTrue(s.add(h), "distinct seeds must yield distinct hashes");
            mirror.add(com.dolthub.prolly.HashUtils.toHex(h));
        }
        assertEquals(20_000, s.size());
        for (int i = 0; i < 20_000; i++) {
            assertTrue(s.contains(hash(i)), "key " + i + " lost across a rehash");
        }
        assertEquals(mirror, s.toHexSet(), "the packed set must agree with a plain HashSet oracle");
    }

    @Test
    void forEach_visits_every_member_exactly_once() {
        PackedChunkSet s = new PackedChunkSet();
        for (int i = 0; i < 500; i++) {
            s.add(hash(i));
        }
        AtomicInteger seen = new AtomicInteger();
        Set<String> distinct = new HashSet<>();
        s.forEach(
                h -> {
                    seen.incrementAndGet();
                    distinct.add(com.dolthub.prolly.HashUtils.toHex(h));
                });
        assertEquals(500, seen.get());
        assertEquals(500, distinct.size());
    }

    @Test
    void addAll_agrees_whether_the_source_is_packed_or_not() {
        PackedChunkSet source = new PackedChunkSet();
        for (int i = 0; i < 300; i++) {
            source.add(hash(i));
        }

        PackedChunkSet viaSpecialisedPath = new PackedChunkSet();
        viaSpecialisedPath.addAll(source); // instanceof PackedChunkSet — the allocation-free path

        // A deliberately different implementation, so the default forEach path is exercised too.
        ChunkSet plain =
                new ChunkSet() {
                    private final Set<String> backing = new HashSet<>();

                    @Override
                    public boolean add(byte[] h) {
                        return backing.add(com.dolthub.prolly.HashUtils.toHex(h));
                    }

                    @Override
                    public boolean contains(byte[] h) {
                        return backing.contains(com.dolthub.prolly.HashUtils.toHex(h));
                    }

                    @Override
                    public int size() {
                        return backing.size();
                    }

                    @Override
                    public void forEach(java.util.function.Consumer<byte[]> sink) {
                        backing.forEach(
                                hex -> sink.accept(com.dolthub.prolly.HashUtils.fromHex(hex)));
                    }
                };
        plain.addAll(source);
        PackedChunkSet viaDefaultPath = new PackedChunkSet();
        viaDefaultPath.addAll(plain);

        assertEquals(300, viaSpecialisedPath.size());
        assertEquals(300, viaDefaultPath.size());
        assertEquals(source.toHexSet(), viaSpecialisedPath.toHexSet());
        assertEquals(source.toHexSet(), viaDefaultPath.toHexSet());
    }

    @Test
    void the_empty_constant_excludes_nothing_and_refuses_writes() {
        assertEquals(0, ChunkSet.EMPTY.size());
        assertTrue(ChunkSet.EMPTY.isEmpty());
        assertFalse(ChunkSet.EMPTY.contains(hash(1)));
        assertTrue(ChunkSet.EMPTY.toHexSet().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> ChunkSet.EMPTY.add(hash(1)));
    }

    /**
     * A concurrent walk depends on exactly one thread being told a hash was absent — that is what
     * stops two workers descending the same subtree, and on a shared set it is the only thing.
     */
    @Test
    void concurrent_add_reports_absent_exactly_once_per_hash() throws Exception {
        ConcurrentChunkSet s = new ConcurrentChunkSet();
        int threads = 8;
        int keys = 2_000;
        AtomicInteger wonTheRace = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(
                            () -> {
                                try {
                                    go.await();
                                    for (int i = 0; i < keys; i++) {
                                        if (s.add(hash(i))) {
                                            wonTheRace.incrementAndGet();
                                        }
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    done.countDown();
                                }
                            })
                    .start();
        }
        go.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish");
        assertEquals(keys, s.size());
        assertEquals(keys, wonTheRace.get(), "every hash must be claimed by exactly one thread");
    }
}
