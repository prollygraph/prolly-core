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
package com.earasoft.prolly.pool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeCache;
import com.dolthub.prolly.TreeMutator;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Cross-thread use-after-free coverage (plans/off-heap-use-after-free-tests.md Phase 5 Step 17, H5)
 * — the production-shared-pool regime the zero-copy future would run: one {@link DirectBufferPool}
 * (a shared {@code Arena.ofShared}) and one {@link NodeCache} reached concurrently by many threads.
 *
 * <p>This is a deterministic stress test, not a poison differential: it asserts <em>invariants</em>
 * (exclusive handout, no torn read, content-correct cache hits, closed-arena access throws) that
 * hold regardless of interleaving, so it fails on corruption and never flakes on timing. It
 * complements the existing {@code PoolStressTest} ({@code main}-method borrow/release churn that
 * only checks sizes) with the three things that test does not: content exclusivity under
 * concurrency, the free-while-read race (a thread closing the arena while another reads), and the
 * lock-free cache under contention.
 */
class CrossThreadPoolCacheUseAfterFreeTest {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    /**
     * H5 — a borrowed segment is exclusively the borrower's between borrow and release, so under
     * heavy concurrency no two threads ever see the same live block. Each thread fills its segment
     * with a thread-unique tag and reads it straight back: a cross-thread bleed (the pool handing
     * one live segment to two threads) would surface as a foreign tag. Also pins the concurrent
     * counters stay exact.
     */
    @Test
    void concurrentBorrowWriteReadReleaseHandsOutExclusiveSegments() throws Exception {
        final int threads = 8;
        final int iters = 4000;
        try (DirectBufferPool pool = new DirectBufferPool()) {
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final byte tag = (byte) (t + 1);
                futures.add(
                        ex.submit(
                                () -> {
                                    start.await();
                                    for (int i = 0; i < iters; i++) {
                                        MemorySegment seg = pool.borrow(1024);
                                        seg.fill(tag);
                                        for (long off = 0; off < seg.byteSize(); off += 97) {
                                            if (seg.get(BYTE, off) != tag) {
                                                throw new AssertionError(
                                                        "cross-thread segment bleed: read "
                                                                + seg.get(BYTE, off)
                                                                + " expected own tag "
                                                                + tag);
                                            }
                                        }
                                        pool.release(seg);
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(); // propagates any worker AssertionError as the test failure
            }
            ex.shutdown();
            assertTrue(ex.awaitTermination(30, TimeUnit.SECONDS));
            assertEquals((long) threads * iters, pool.getBorrowedCount());
            assertEquals((long) threads * iters, pool.getReleasedCount());
        }
    }

    /**
     * H5 — the core free-while-read race: one thread closes the pool's shared arena while another
     * reads a borrowed segment. {@code Arena.ofShared} makes this safe — a concurrent access either
     * completes (reads the real bytes) or throws {@link IllegalStateException} once the close
     * lands; it never observes torn/garbage memory. The deterministic post-condition (access after
     * a cross-thread close throws) is pinned unconditionally after the join.
     */
    @Test
    void closingArenaWhileAnotherThreadReadsNeverTearsTheRead() throws Exception {
        DirectBufferPool pool = new DirectBufferPool();
        MemorySegment seg = pool.borrow(4096);
        final byte pattern = (byte) 0x5A;
        seg.fill(pattern);

        AtomicBoolean tornRead = new AtomicBoolean(false);
        CountDownLatch readerReady = new CountDownLatch(1);

        Thread reader =
                new Thread(
                        () -> {
                            readerReady.countDown();
                            try {
                                for (int i = 0; i < 200_000_000; i++) {
                                    byte b = seg.get(BYTE, (i * 137L) % 4096);
                                    if (b != pattern) {
                                        // A non-pattern read WITHOUT an IllegalStateException is a
                                        // torn
                                        // read of freed/garbage memory — the hazard this rules out.
                                        tornRead.set(true);
                                        return;
                                    }
                                }
                            } catch (IllegalStateException expectedAfterClose) {
                                // The close landed mid-read: Panama's net, not a corruption.
                            }
                        });
        reader.start();
        readerReady.await();
        pool.close(); // frees the shared arena while the reader may be mid-read
        reader.join();

        assertFalse(
                tornRead.get(),
                "a read concurrent with the arena close must read the real bytes or throw — never tear");
        assertThrows(
                IllegalStateException.class,
                () -> seg.get(BYTE, 0),
                "after a cross-thread close, accessing the segment must throw (the close is globally visible)");
    }

    /**
     * H5 — the shared lock-free {@link NodeCache} under contention: many threads put + get
     * content-addressed nodes concurrently. A budget large enough to hold them all means every
     * get-after-put hits, and a hit must return the node whose content matches the key — never a
     * foreign node, never corrupt bytes. Pins the lock-free read path the cache's javadoc claims
     * (shared across every connection thread).
     */
    @Test
    void nodeCacheConcurrentGetPutReturnsContentCorrectNodes() throws Exception {
        final int distinct = 16;
        byte[][] keys = new byte[distinct][];
        byte[][] chunks = new byte[distinct][];
        Node[] nodes = new Node[distinct];
        for (int i = 0; i < distinct; i++) {
            keys[i] = new byte[32];
            java.util.Arrays.fill(keys[i], (byte) i); // distinct synthetic content-hash keys
            chunks[i] = chunkFor(i);
            nodes[i] = Node.fromBytes(MemorySegment.ofArray(chunks[i]));
        }
        NodeCache cache = new NodeCache(64L * 1024 * 1024); // big budget → no eviction confound

        final int threads = 8;
        final int iters = 2000;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            futures.add(
                    ex.submit(
                            () -> {
                                start.await();
                                int x = seed * 31 + 7;
                                for (int i = 0; i < iters; i++) {
                                    x = x * 1103515245 + 12345; // cheap deterministic spread
                                    int idx = Math.floorMod(x, distinct);
                                    cache.put(keys[idx], nodes[idx]);
                                    Optional<Node> got = cache.get(keys[idx]);
                                    if (got.isPresent()) {
                                        byte[] back = got.get().bytes();
                                        if (!java.util.Arrays.equals(back, chunks[idx])) {
                                            throw new AssertionError(
                                                    "NodeCache returned a content-mismatched node for key "
                                                            + idx);
                                        }
                                    }
                                }
                                return null;
                            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        ex.shutdown();
        assertTrue(ex.awaitTermination(30, TimeUnit.SECONDS));

        // Every key was put (big budget → retained): a final get must hit + match content.
        for (int i = 0; i < distinct; i++) {
            Optional<Node> got = cache.get(keys[i]);
            assertTrue(got.isPresent(), "key " + i + " must be cached after the concurrent run");
            assertArrayEquals(
                    chunks[i],
                    got.get().bytes(),
                    "cached node content must be intact after concurrent get/put");
        }
    }

    /** A real, distinct content-addressed node's serialized bytes for index {@code i}. */
    private static byte[] chunkFor(int i) {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, ("k" + i).getBytes(StandardCharsets.UTF_8));
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            Node root =
                    m.applyMutations(
                            null,
                            List.of(
                                            new TreeMutator.Mutation(
                                                    tb.build().segment(),
                                                    MemorySegment.ofArray(
                                                            ("v" + i)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8))))
                                    .iterator());
            return root.bytes();
        }
    }
}
