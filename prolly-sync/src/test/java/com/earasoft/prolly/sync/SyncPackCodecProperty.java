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
package com.earasoft.prolly.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Phase 7 Step 25 of {@code the upstream test strategy} (S-9), sub-property 1 of 3 — the {@code
 * SyncPack} wire codec <b>round-trips by content</b>. Generalizes {@code SyncPackCodecTest}'s three
 * worked examples to a property over generated packs: {@code parse(serialize(pack))} reproduces
 * every chunk's bytes and every commit's fields, across chunk counts / sizes (including empty),
 * commit counts, parent-list shapes, and message / author strings.
 *
 * <p><b>Why a hand-written comparison, not {@code equals}.</b> {@code SyncPack} and {@code
 * SyncCommitEntry} carry {@code byte[]} fields (chunk bytes, the meta-tree hash, parent hashes),
 * and a record's generated {@code equals} uses <i>reference</i> equality for arrays — so {@code
 * Entry.equals} / {@code List.equals} would pass on identity, not content. The assertion therefore
 * walks the structure and compares every {@code byte[]} with {@code Arrays.equals}, which is what
 * "round-trips by content" actually means.
 *
 * <p>Generators stay inside what the wire is defined over: 20-byte hashes (the codec reads a fixed
 * 20-byte claimed hash per chunk and verifies it — content-addressing), second-precision timestamps
 * in a realistic range (so the round-trip is exact regardless of whether the wire stores seconds or
 * millis), and printable-ASCII messages / authors (the structural round-trip, not a UTF-8 surrogate
 * edge case — that and tamper / truncation rejection stay in {@code SyncPackCodecTest}).
 */
class SyncPackCodecProperty {

    @Property(tries = 200)
    void serialize_then_parse_reproduces_the_pack_by_content(@ForAll @From("packs") SyncPack pack) {
        SyncPack back = SyncPackCodec.parse(SyncPackCodec.serialize(pack));

        assertEquals(pack.chunks().size(), back.chunks().size(), "chunk count");
        for (int i = 0; i < pack.chunks().size(); i++) {
            assertArrayEquals(pack.chunks().get(i), back.chunks().get(i), "chunk " + i + " bytes");
        }

        assertEquals(pack.commits().size(), back.commits().size(), "commit count");
        for (int i = 0; i < pack.commits().size(); i++) {
            SyncCommitEntry x = pack.commits().get(i);
            SyncCommitEntry y = back.commits().get(i);
            assertEquals(x.timestamp(), y.timestamp(), "commit " + i + " timestamp");
            assertArrayEquals(x.metaTreeHash(), y.metaTreeHash(), "commit " + i + " metaTreeHash");
            assertEquals(x.parents().size(), y.parents().size(), "commit " + i + " parent count");
            for (int j = 0; j < x.parents().size(); j++) {
                assertArrayEquals(
                        x.parents().get(j), y.parents().get(j), "commit " + i + " parent " + j);
            }
            assertEquals(x.message(), y.message(), "commit " + i + " message");
            assertEquals(x.author(), y.author(), "commit " + i + " author");
        }
    }

    @Provide
    Arbitrary<SyncPack> packs() {
        Arbitrary<List<byte[]>> chunks = bytes(0, 32).list().ofMaxSize(8);
        Arbitrary<SyncCommitEntry> entry =
                Combinators.combine(
                                Arbitraries.longs()
                                        .between(
                                                0L,
                                                2_000_000_000L), // epoch second, realistic range
                                hash20(), // metaTreeHash (20 bytes, as the wire reads)
                                hash20().list().ofMaxSize(3), // parents
                                Arbitraries.strings()
                                        .withCharRange(' ', '~')
                                        .ofMaxLength(24), // message (printable ASCII)
                                Arbitraries.strings()
                                        .withCharRange(' ', '~')
                                        .ofMaxLength(12) // author
                                )
                        .as(
                                (sec, mh, parents, msg, auth) ->
                                        new SyncCommitEntry(
                                                Instant.ofEpochSecond(sec),
                                                mh, // id — any stable 20 bytes; wire-carried
                                                // verbatim
                                                mh,
                                                parents,
                                                msg,
                                                auth));
        return Combinators.combine(chunks, entry.list().ofMaxSize(5)).as(SyncPack::new);
    }

    private static Arbitrary<byte[]> bytes(int min, int max) {
        return Arbitraries.bytes()
                .list()
                .ofMinSize(min)
                .ofMaxSize(max)
                .map(SyncPackCodecProperty::toBytes);
    }

    private static Arbitrary<byte[]> hash20() {
        return Arbitraries.bytes().list().ofSize(20).map(SyncPackCodecProperty::toBytes);
    }

    private static byte[] toBytes(List<Byte> list) {
        byte[] b = new byte[list.size()];
        for (int i = 0; i < b.length; i++) {
            b[i] = list.get(i);
        }
        return b;
    }
}
