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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.ActionChainArbitrary;
import net.jqwik.api.state.Transformer;

/**
 * Phase 1 of the upstream monorepo's the upstream spillable-sorted-buffer-testing plan —
 * <b>stateful model-based</b> property for {@link SpillableSortedBuffer}, the lever for the
 * super-rare <i>interleaving / lifecycle</i> bugs a flat op-list property structurally cannot reach
 * (get between two spills, a full {@code merged} sweep then more puts, {@code clear} then reuse
 * then spill then get). Each generated {@link ActionChain} is a long random interleaving of put /
 * delete / get / contains / merged-equals / clear over a single buffer, run in lockstep against a
 * {@link TreeMap} model; every observer asserts the buffer matches the model <i>at that point in
 * the chain</i>, so a state corruption surfaces the moment it happens.
 *
 * <p>Tuned to hunt rare bugs: a tiny key alphabet (heavy overwrite + tombstone churn →
 * last-write-wins across runs is exercised constantly) and a tiny spill threshold (the buffer is
 * almost always in the spilling regime, so observers interleave with multi-run merged/lookup
 * state). Long chains × many tries explore far more distinct states than a comparable number of
 * flat trials.
 */
class SpillableSortedBufferModelProperty {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final long SPILL_THRESHOLD = 64; // tiny → almost always spilling

    private static final Comparator<MemorySegment> LEX =
            (a, b) -> {
                long n = Math.min(a.byteSize(), b.byteSize());
                for (long i = 0; i < n; i++) {
                    int x = Byte.toUnsignedInt(a.get(BYTE, i)),
                            y = Byte.toUnsignedInt(b.get(BYTE, i));
                    if (x != y) return Integer.compare(x, y);
                }
                return Long.compare(a.byteSize(), b.byteSize());
            };
    private static final SpillableSortedBuffer.KeyCodec<MemorySegment> IDENTITY =
            new SpillableSortedBuffer.KeyCodec<>() {
                @Override
                public MemorySegment toBytes(MemorySegment key) {
                    return key;
                }

                @Override
                public MemorySegment fromBytes(MemorySegment bytes) {
                    return bytes;
                }
            };

    // ~12 keys (a..c, length 1–2) → heavy collisions; ASCII a–c so String order == LEX over the
    // UTF-8 bytes.
    private static final Arbitrary<String> KEYS =
            Arbitraries.strings().withCharRange('a', 'c').ofMinLength(1).ofMaxLength(2);
    private static final Arbitrary<byte[]> VALS =
            Arbitraries.bytes().array(byte[].class).ofMaxSize(40);

    private Path tempDir;

    @BeforeTry
    void mkTemp() throws IOException {
        tempDir = Files.createTempDirectory("ssb-chain");
    }

    @AfterTry
    void rmTemp() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (Stream<Path> s = Files.walk(tempDir)) {
            s.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
        }
    }

    @Property(tries = 600)
    void bufferMatchesModelAcrossActionChains(@ForAll("chains") ActionChain<Model> chain) {
        chain.run();
    }

    /**
     * The SAME chains with the presence index ON. Legitimate here by the index's contract: LEX
     * equality holds exactly for byte-identical segments, so comparator equality implies
     * byte-equality and an index miss must be a true absent. Any divergence from the model under
     * any interleaving (spill, partial merge, clear-then-reuse, tombstones) is an index bug.
     */
    @Property(tries = 600)
    void bufferWithPresenceIndexMatchesModelAcrossActionChains(
            @ForAll("presenceChains") ActionChain<Model> chain) {
        chain.run();
    }

    @Provide
    ActionChainArbitrary<Model> presenceChains() {
        return chainsWith(() -> new Model(SPILL_THRESHOLD, tempDir, true));
    }

    @Provide
    ActionChainArbitrary<Model> chains() {
        return chainsWith(() -> new Model(SPILL_THRESHOLD, tempDir, false));
    }

    private ActionChainArbitrary<Model> chainsWith(java.util.function.Supplier<Model> fresh) {
        return ActionChain.startWith(fresh::get)
                // weighted toward mutation so the buffer keeps growing + spilling; clear/merged are
                // rarer
                .withAction(put())
                .withAction(put())
                .withAction(put())
                .withAction(delete())
                .withAction(delete())
                .withAction(get())
                .withAction(get())
                .withAction(contains())
                .withAction(mergedEquals())
                .withAction(partialMerge())
                .withAction(clear())
                .withMaxTransformations(200);
    }

    private Action.Independent<Model> put() {
        return () ->
                Combinators.combine(KEYS, VALS)
                        .as((k, v) -> Transformer.mutate("put " + k, m -> m.put(k, v)));
    }

    private Action.Independent<Model> delete() {
        return () -> KEYS.map(k -> Transformer.mutate("del " + k, m -> m.put(k, null)));
    }

    private Action.Independent<Model> get() {
        return () -> KEYS.map(k -> Transformer.mutate("get " + k, m -> m.assertGet(k)));
    }

    private Action.Independent<Model> contains() {
        return () -> KEYS.map(k -> Transformer.mutate("has " + k, m -> m.assertContains(k)));
    }

    private Action.Independent<Model> mergedEquals() {
        return () -> Arbitraries.just(Transformer.mutate("merged", Model::assertMerged));
    }

    private Action.Independent<Model> clear() {
        return () -> Arbitraries.just(Transformer.mutate("clear", Model::clearAndAssertEmpty));
    }

    private Action.Independent<Model> partialMerge() {
        return () ->
                Arbitraries.integers()
                        .between(0, 8)
                        .map(
                                n ->
                                        Transformer.mutate(
                                                "partialMerge " + n,
                                                m -> m.partialMergeThenVerify(n)));
    }

    /**
     * The system-under-test paired with its {@link TreeMap} oracle; every mutator re-checks
     * consistency.
     */
    static final class Model {
        final SpillableSortedBuffer<MemorySegment> buf;
        final TreeMap<String, byte[]> ref = new TreeMap<>(); // null value = tombstone
        final Path dir;

        Model(long threshold, Path dir, boolean presenceIndex) {
            this.dir = dir;
            buf = new SpillableSortedBuffer<>(LEX, IDENTITY, threshold, dir, presenceIndex);
        }

        /**
         * A partial {@code merged()} consume is a READ: closing it early must release the run
         * readers (no fd leak) and leave the buffer's contents unchanged (a subsequent full merged
         * still equals the model).
         */
        void partialMergeThenVerify(int n) {
            try (SpillableSortedBuffer.CloseableEntryIterator<MemorySegment> it = buf.merged()) {
                for (int i = 0; i < n && it.hasNext(); i++) it.next();
            }
            assertMerged();
        }

        void put(String k, byte[] v) {
            buf.put(seg(k), v == null ? null : MemorySegment.ofArray(v));
            ref.put(k, v);
            assertGet(k);
            assertContains(k);
        }

        void assertGet(String k) {
            assertArrayEquals(ref.get(k), bytesOrNull(buf.get(seg(k))), "get " + k);
        }

        void assertContains(String k) {
            assertEquals(ref.containsKey(k), buf.containsKey(seg(k)), "containsKey " + k);
        }

        void assertMerged() {
            List<String> got = new ArrayList<>();
            for (Iterator<SpillableSortedBuffer.Entry<MemorySegment>> it = buf.merged();
                    it.hasNext(); ) {
                SpillableSortedBuffer.Entry<MemorySegment> e = it.next();
                got.add(str(e.key()) + "=" + hex(e.value()));
            }
            List<String> expected =
                    ref.entrySet().stream().map(e -> e.getKey() + "=" + hex(e.getValue())).toList();
            assertEquals(expected, got, "merged stream must equal the model");
        }

        void clearAndAssertEmpty() {
            buf.clear();
            ref.clear();
            assertTrue(buf.isEmpty(), "empty after clear");
            assertEquals(0, countRunFiles(dir), "spilled run files gone after clear");
            // The buffer stays reusable — the chain keeps acting on it after this.
        }
    }

    /** Step 2 terminal-lifecycle: {@code close()} deletes the spilled run files. */
    @Example
    void closeDeletesRunFiles() throws IOException {
        Path dir = Files.createTempDirectory("ssb-close");
        try {
            SpillableSortedBuffer<MemorySegment> buf =
                    new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir);
            for (int i = 0; i < 500; i++) {
                buf.put(
                        seg("k" + i),
                        MemorySegment.ofArray(("v" + i).getBytes(StandardCharsets.UTF_8)));
            }
            assertTrue(countRunFiles(dir) > 0, "tiny threshold must spill run files before close");
            buf.close();
            assertEquals(0, countRunFiles(dir), "no .run files after close");
        } finally {
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            }
        }
    }

    private static long countRunFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".run")).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(BYTE), StandardCharsets.UTF_8);
    }

    private static byte[] bytesOrNull(MemorySegment s) {
        return s == null ? null : s.toArray(BYTE);
    }

    private static String hex(byte[] b) {
        if (b == null) return "DEL";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String hex(MemorySegment s) {
        return s == null ? "DEL" : hex(s.toArray(BYTE));
    }
}
