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
package com.earasoft.prolly.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HashUtils;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

/**
 * Concurrency of {@link FileNodeStore}. Its thread-safety is <em>not</em> a JVM-memory-model
 * property — the store has no shared mutable JVM state (final {@code root}/{@code durability}, a
 * per-thread {@code ThreadLocal} batch) — so this is the right tool, not jcstress: it stresses the
 * <b>filesystem</b> race that the content-address + atomic-rename design must survive. Under heavy
 * same-chunk contention plus distinct writes from many threads, the store must end with exactly the
 * distinct chunks, each byte-correct, no leaked temp, no exception. The {@code @RepeatedTest} runs
 * it many times to widen the interleaving coverage.
 */
final class FileNodeStoreConcurrencyTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @RepeatedTest(10)
    void concurrentWritersOfOverlappingAndDistinctChunksEndConsistent(@TempDir Path root)
            throws Exception {
        int threads = 8;
        int sharedCount = 20; // written by EVERY thread -> heavy same-chunk contention.
        int uniquePerThread = 10;

        List<byte[]> shared = new ArrayList<>();
        for (int i = 0; i < sharedCount; i++) {
            shared.add(bytes("shared-" + i));
        }

        FileNodeStore store = new FileNodeStore(root); // default BATCH.
        Map<String, byte[]> expected = new ConcurrentHashMap<>(); // hex-hash -> its payload.
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            int tid = t;
            pool.submit(
                    () -> {
                        try {
                            startTogether.await(); // maximise the race — all threads fire at once.
                            store.beginWriteBatch(); // per-thread batch (ThreadLocal) must not
                            // interfere.
                            List<byte[]> ordering = new ArrayList<>(shared);
                            Collections.shuffle(
                                    ordering,
                                    new Random(tid)); // each thread races in a different order.
                            for (byte[] p : ordering) {
                                record(store.write(p), p, expected);
                            }
                            for (int j = 0; j < uniquePerThread; j++) {
                                byte[] p = bytes("t" + tid + "-u" + j);
                                record(store.write(p), p, expected);
                            }
                            store.endWriteBatch();
                        } catch (Throwable e) {
                            errors.add(e);
                        }
                    });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "writers must finish");
        assertTrue(errors.isEmpty(), "no writer may throw: " + errors);

        int distinct = sharedCount + threads * uniquePerThread;
        assertEquals(distinct, expected.size(), "sanity: distinct payload count");

        List<Path> files = regularFiles(root);
        assertEquals(
                distinct, files.size(), "exactly one file per distinct chunk — no dupes, no temps");
        for (Path f : files) {
            assertTrue(
                    !f.getFileName().toString().startsWith(".tmp-"),
                    "no leaked temp under contention: " + f);
        }
        for (Map.Entry<String, byte[]> e : expected.entrySet()) {
            byte[] hash = HashUtils.fromHex(e.getKey());
            assertArrayEquals(
                    e.getValue(),
                    store.read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    "every chunk reads back byte-correct after the contention");
        }
        store.close();
    }

    private static void record(byte[] hash, byte[] payload, Map<String, byte[]> expected) {
        expected.put(HashUtils.toHex(hash), payload);
    }

    private static List<Path> regularFiles(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }
}
