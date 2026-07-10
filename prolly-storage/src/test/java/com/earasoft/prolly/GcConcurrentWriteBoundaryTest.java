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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.TreeMutator;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Step 22 of the upstream test-strategy plan (invariant R-4): the <b>GC↔concurrent-write
 * boundary</b>.
 *
 * <p>{@link GarbageCollector#collect()} holds {@code Database.gcLock().writeLock()} across
 * mark+sweep; a commit holds {@code gcLock().readLock()} (so the two are mutually exclusive). This
 * test drives the boundary <i>deterministically</i> — via the {@code betweenMarkAndSweep} test seam
 * + latches, not sleeps — by pausing GC between mark and sweep and racing a commit against it.
 *
 * <p>{@link #gcWriteLockExcludesReadLockDuringMarkSweep()} pins the exclusion that <i>does</i>
 * hold. {@link #concurrentCommitMustNotLoseChunksToGc()} probes the end-to-end invariant (a
 * concurrent commit's data must survive the sweep).
 */
class GcConcurrentWriteBoundaryTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final String BRANCH = "main";

    /**
     * The exclusion that holds: while GC is paused between mark and sweep holding the write lock,
     * no thread can acquire the read lock a commit needs. Deterministic (a {@code tryLock} probe),
     * no sleep.
     */
    @Test
    void gcWriteLockExcludesReadLockDuringMarkSweep() throws Exception {
        Path dir = Files.createTempDirectory("prolly-gc-excl");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "gc-excl-repo", DESC, pool);
            db.createBranch(BRANCH, "EMPTY");
            commit(db, store, pool, "k0", "v0");

            GarbageCollector gc = new GarbageCollector(db, store);
            CountDownLatch inGap = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            gc.betweenMarkAndSweep =
                    () -> {
                        inGap.countDown();
                        await(release);
                    };

            Thread gcThread = new Thread(gc::collect, "gc");
            gcThread.start();
            inGap.await(); // GC has marked and holds the write lock, paused before sweep.

            assertFalse(
                    db.gcLock().readLock().tryLock(),
                    "while GC holds the write lock across mark+sweep, the read lock a commit "
                            + "needs must be excluded");

            release.countDown();
            gcThread.join();
            // After GC releases, the read lock is acquirable again.
            assertTrue(
                    db.gcLock().readLock().tryLock(), "read lock must be free after GC completes");
            db.gcLock().readLock().unlock();
        }
    }

    /**
     * End-to-end R-4: a commit racing a concurrent GC must never lose its chunks to the sweep. GC
     * marks (reachable EXCLUDES the new commit), pauses; a writer commits a new key; GC sweeps;
     * assert the new key — and all prior keys — survive.
     */
    // FIXED 2026-06-01 (docs/write-ups/gc-concurrent-write-flush-window.md): the commit helpers
    // below now use
    // the
    // GC-safe Database.commit(branch, MutableMap, …) overload, which flushes UNDER the gcLock read
    // lock —
    // so a concurrent GC can no longer sweep a writer's chunks between flush and the manifest
    // update.
    // This test is now the regression gate for that fix (was @Disabled while the bug was open).
    @Test
    void concurrentCommitMustNotLoseChunksToGc() throws Exception {
        Path dir = Files.createTempDirectory("prolly-gc-write-boundary");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "gc-boundary-repo", DESC, pool);
            db.createBranch(BRANCH, "EMPTY");
            // C0: reachable keys that must survive GC.
            for (int i = 0; i < 3; i++) commit(db, store, pool, "k" + i, "v" + i);

            // Plant an unreachable orphan so the sweep has real work — proves the sweep ran.
            byte[] orphan = plantOrphan(store, pool);
            assertTrue(store.read(orphan).isPresent(), "orphan must exist before GC");

            GarbageCollector gc = new GarbageCollector(db, store);
            CountDownLatch inGap = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            gc.betweenMarkAndSweep =
                    () -> {
                        inGap.countDown();
                        await(release);
                    };

            Thread gcThread = new Thread(gc::collect, "gc");
            gcThread.start();
            inGap.await(); // GC marked (reachable excludes the new key) and is paused before sweep.

            // A concurrent commit of a NEW *multi-level* tree (many keys, so flush() writes
            // interior+leaf chunks that commit's root-only re-write does NOT restore). It must
            // not lose those chunks to the imminent sweep.
            int n = 3000;
            AtomicBoolean committed = new AtomicBoolean(false);
            AtomicReference<Throwable> writerErr = new AtomicReference<>();
            Thread writer =
                    new Thread(
                            () -> {
                                try {
                                    committed.set(commitMany(db, store, pool, n));
                                } catch (Throwable t) {
                                    writerErr.set(t);
                                }
                            },
                            "writer");
            writer.start();

            // Deterministically let the writer reach its chunk-write/commit before the sweep:
            // spin until it has either committed or is queued on the gcLock.
            while (!committed.get() && writerErr.get() == null && !db.gcLock().hasQueuedThreads())
                Thread.onSpinWait();

            release.countDown(); // GC sweeps now (deletes unreachable), then unlocks.
            gcThread.join();
            writer.join();

            if (writerErr.get() != null) throw new AssertionError("writer threw", writerErr.get());
            assertTrue(committed.get(), "the concurrent commit must complete");
            assertTrue(store.read(orphan).isEmpty(), "GC sweep must have collected the orphan");

            TreeMap<String, String> head = readBranch(db, BRANCH);
            for (int i = 0; i < 3; i++)
                assertEquals("v" + i, head.get("k" + i), "C0 key k" + i + " lost to GC");
            for (int i = 0; i < 3000; i++)
                assertEquals(
                        "nv" + i,
                        head.get(String.format("n%05d", i)),
                        "concurrently-committed key n"
                                + i
                                + " missing → its chunk was swept (R-4 exclusion broken)");
        }
    }

    /**
     * Phase-2 sibling: a {@code merge} racing a concurrent GC. {@code MergeEngine} writes the
     * merged tree's chunks to the store, so the same flush-before-lock window applied — now closed
     * by the read-lock wrap in {@code Database.merge}. A union merge of two disjoint branches
     * produces a NEW multi-level tree (unreachable from either head at mark time); it must survive
     * the sweep.
     */
    @Test
    void mergeMustNotLoseChunksToGc() throws Exception {
        Path dir = Files.createTempDirectory("prolly-gc-merge-boundary");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "gc-merge-repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            populate(db, store, pool, "main", "c", 500); // common base
            db.createBranch("A", "main");
            populate(db, store, pool, "A", "a", 1000); // ours: c + a
            db.createBranch("B", "main");
            populate(db, store, pool, "B", "b", 1000); // theirs: c + b

            GarbageCollector gc = new GarbageCollector(db, store);
            CountDownLatch inGap = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            gc.betweenMarkAndSweep =
                    () -> {
                        inGap.countDown();
                        await(release);
                    };

            Thread gcThread = new Thread(gc::collect, "gc");
            gcThread.start();
            inGap.await(); // marked main/A/B; the union merge tree does not exist yet.

            AtomicReference<Throwable> err = new AtomicReference<>();
            AtomicBoolean merged = new AtomicBoolean(false);
            Thread writer =
                    new Thread(
                            () -> {
                                try {
                                    db.merge("A", "B", "t", "union");
                                    merged.set(true);
                                } catch (Throwable t) {
                                    err.set(t);
                                }
                            },
                            "merger");
            writer.start();
            while (!merged.get() && err.get() == null && !db.gcLock().hasQueuedThreads())
                Thread.onSpinWait();

            release.countDown();
            gcThread.join();
            writer.join();

            if (err.get() != null) throw new AssertionError("merge threw", err.get());
            assertTrue(merged.get(), "merge must complete");
            TreeMap<String, String> head = readBranch(db, "A"); // ours got the merged tree
            for (int i = 0; i < 500; i++)
                assertEquals(
                        "v" + i, head.get(String.format("c%05d", i)), "base key c" + i + " lost");
            for (int i = 0; i < 1000; i++)
                assertEquals(
                        "v" + i, head.get(String.format("a%05d", i)), "ours key a" + i + " lost");
            for (int i = 0; i < 1000; i++)
                assertEquals(
                        "v" + i,
                        head.get(String.format("b%05d", i)),
                        "theirs key b" + i + " lost to GC (merge chunks swept)");
        }
    }

    // --- helpers ---

    private static void populate(
            Database db,
            RocksNodeStore store,
            DirectBufferPool pool,
            String branch,
            String prefix,
            int n)
            throws Exception {
        MutableMap mm = new MutableMap(db.getBranch(branch), store, DESC, pool);
        for (int i = 0; i < n; i++) {
            mm.put(
                    keyTuple(pool, String.format("%s%05d", prefix, i)),
                    MemorySegment.ofArray(("v" + i).getBytes(StandardCharsets.UTF_8)));
        }
        db.commit(branch, mm, db.getHeadHash(branch).orElse(null), "t", "pop-" + prefix);
    }

    private static boolean commit(
            Database db, RocksNodeStore store, DirectBufferPool pool, String key, String val)
            throws Exception {
        MutableMap mm = new MutableMap(db.getBranch(BRANCH), store, DESC, pool);
        mm.put(keyTuple(pool, key), MemorySegment.ofArray(val.getBytes(StandardCharsets.UTF_8)));
        return db.commit(
                BRANCH,
                mm,
                db.getHeadHash(BRANCH).orElse(null),
                "t",
                "c-" + key); // GC-safe overload
    }

    private static boolean commitMany(
            Database db, RocksNodeStore store, DirectBufferPool pool, int n) throws Exception {
        MutableMap mm = new MutableMap(db.getBranch(BRANCH), store, DESC, pool);
        for (int i = 0; i < n; i++) {
            mm.put(
                    keyTuple(pool, String.format("n%05d", i)),
                    MemorySegment.ofArray(("nv" + i).getBytes(StandardCharsets.UTF_8)));
        }
        return db.commit(
                BRANCH, mm, db.getHeadHash(BRANCH).orElse(null), "t", "c-many"); // GC-safe overload
    }

    private static byte[] plantOrphan(RocksNodeStore store, DirectBufferPool pool) {
        List<TreeMutator.Mutation> muts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            muts.add(
                    new TreeMutator.Mutation(
                            keyTuple(pool, "orphan-" + String.format("%04d", i)),
                            MemorySegment.ofArray(("o" + i).getBytes(StandardCharsets.UTF_8))));
        }
        Node root = new TreeMutator(store, DESC, pool).applyMutations(null, muts.iterator());
        return store.write(root.segment()); // written, referenced by nothing
    }

    private static TreeMap<String, String> readBranch(Database db, String branch) {
        TreeMap<String, String> out = new TreeMap<>();
        MapIterator it = db.getBranch(branch).iter();
        while (it.next()) {
            out.put(
                    new String(new Tuple(it.key()).getField(0), StandardCharsets.UTF_8),
                    new String(it.value().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static MemorySegment keyTuple(DirectBufferPool pool, String key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
