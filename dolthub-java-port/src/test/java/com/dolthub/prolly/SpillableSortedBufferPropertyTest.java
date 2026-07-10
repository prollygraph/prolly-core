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

import java.io.IOException;
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
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Heavy property-based coverage for {@link SpillableSortedBuffer} (Step 4b of {@code
 * plans/prolly-bulk-load.md}). The buffer is observably a sorted last-write-wins {@code key→value}
 * map with tombstones; spilling to disk must not change anything the consumer sees. So: apply a
 * random sequence of puts/deletes over a small key alphabet (forcing overwrites + tombstones) at a
 * random spill threshold (spilling and non-spilling), then assert {@link
 * SpillableSortedBuffer#merged()}, {@link SpillableSortedBuffer#get}, and {@link
 * SpillableSortedBuffer#containsKey} all equal a reference {@link TreeMap}. Run under <b>two
 * comparators</b> — plain byte-lex and a non-byte order (length-then-lex) — so any path that
 * accidentally compares raw bytes instead of the supplied comparator (run write/read, the sparse
 * index, the k-way merge) is caught.
 */
class SpillableSortedBufferPropertyTest {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    /** Unsigned byte-lexicographic. */
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

    /**
     * A deliberately NON-byte order: shorter keys first, then byte-lex. Injective (a total order on
     * distinct keys), so it never collapses distinct keys — but it sorts differently from {@link
     * #LEX}, proving the buffer uses the comparator everywhere, not byte order.
     */
    private static final Comparator<MemorySegment> LEN_LEX =
            (a, b) -> {
                int c = Long.compare(a.byteSize(), b.byteSize());
                return c != 0 ? c : LEX.compare(a, b);
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

    record Op(String key, byte[] value) {} // value == null → delete (tombstone)

    @Provide
    Arbitrary<List<Op>> opLists() {
        Arbitrary<String> keys =
                Arbitraries.strings().withCharRange('a', 'e').ofMinLength(1).ofMaxLength(3);
        Arbitrary<byte[]> value =
                Arbitraries.bytes().array(byte[].class).ofMaxSize(48).injectNull(0.25);
        return Combinators.combine(keys, value).as(Op::new).list().ofMaxSize(400);
    }

    @Property(tries = 800)
    void matchesOracle_byteLex(
            @ForAll @From("opLists") List<Op> ops,
            @ForAll @IntRange(min = 16, max = 4096) int threshold)
            throws IOException {
        assertMatchesOracle(LEX, ops, threshold);
    }

    @Property(tries = 600)
    void matchesOracle_lengthThenLex(
            @ForAll @From("opLists") List<Op> ops,
            @ForAll @IntRange(min = 16, max = 4096) int threshold)
            throws IOException {
        assertMatchesOracle(LEN_LEX, ops, threshold);
    }

    private void assertMatchesOracle(Comparator<MemorySegment> cmp, List<Op> ops, int threshold)
            throws IOException {
        Path dir = Files.createTempDirectory("ssb-prop");
        try (SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(cmp, IDENTITY, threshold, dir)) {
            TreeMap<MemorySegment, byte[]> ref = new TreeMap<>(cmp); // null value = tombstone
            for (Op op : ops) {
                buf.put(
                        seg(op.key()),
                        op.value() == null ? null : MemorySegment.ofArray(op.value()));
                ref.put(seg(op.key()), op.value());
            }

            // (1) merged() == reference: same order, last-write-wins, tombstones as null
            List<String> got = new ArrayList<>();
            for (Iterator<SpillableSortedBuffer.Entry<MemorySegment>> it = buf.merged();
                    it.hasNext(); ) {
                SpillableSortedBuffer.Entry<MemorySegment> e = it.next();
                got.add(str(e.key()) + "=" + hex(e.value()));
            }
            List<String> expected =
                    ref.entrySet().stream()
                            .map(e -> str(e.getKey()) + "=" + hexBytes(e.getValue()))
                            .toList();
            assertEquals(expected, got, "merged stream must equal the reference");

            // (2) get() / containsKey() == reference for every alphabet key + a few absent
            for (String k : probeKeys()) {
                MemorySegment ks = seg(k);
                assertEquals(ref.containsKey(ks), buf.containsKey(ks), "containsKey " + k);
                assertArrayEquals(ref.get(ks), bytesOrNull(buf.get(ks)), "get " + k);
            }
        } finally {
            rmrf(dir);
        }
    }

    private static List<String> probeKeys() {
        List<String> ks = new ArrayList<>();
        char[] cs = {'a', 'b', 'c', 'd', 'e'};
        for (char a : cs) ks.add("" + a);
        for (char a : cs) for (char b : cs) ks.add("" + a + b);
        for (char a : cs) for (char b : cs) for (char c : cs) ks.add("" + a + b + c);
        ks.add("f");
        ks.add("zz");
        ks.add(""); // absent keys
        return ks;
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

    private static String hex(MemorySegment s) {
        return s == null ? "DEL" : hexBytes(s.toArray(BYTE));
    }

    private static String hexBytes(byte[] b) {
        if (b == null) return "DEL";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void rmrf(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
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
