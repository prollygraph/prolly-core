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
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * jqwik fuzz of the {@link FileNodeStore} invariants the fixed-payload {@link
 * NodeStoreContractTest} and {@code FileNodeStoreTest} pin only by example: for <em>arbitrary</em>
 * bytes the store round-trips exactly, deduplicates to one file per distinct payload no matter how
 * often rewritten, and lays every chunk out at a well-formed {@code <2-hex>/<38-hex>} loose-object
 * path equal to the content hash.
 */
class FileNodeStoreProperty {

    private static final Pattern LOOSE_OBJECT_PATH = Pattern.compile("[0-9a-f]{2}/[0-9a-f]{38}");

    private final List<Path> tempDirs = new ArrayList<>();

    private Path freshRoot() throws IOException {
        Path dir = Files.createTempDirectory("fns-prop-");
        tempDirs.add(dir);
        return dir;
    }

    @AfterTry
    void cleanup() {
        for (Path dir : tempDirs) {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                        // best-effort cleanup of a temp tree.
                                    }
                                });
            } catch (IOException ignored) {
                // dir already gone / unreadable — nothing to clean.
            }
        }
        tempDirs.clear();
    }

    @Provide
    Arbitrary<byte[]> payload() {
        return Arbitraries.bytes().array(byte[].class).ofMaxSize(2048);
    }

    @Provide
    Arbitrary<List<byte[]>> payloadList() {
        return Arbitraries.bytes().array(byte[].class).ofMaxSize(512).list().ofMaxSize(24);
    }

    @Property(tries = 100)
    void roundTripsAnyBytes(@ForAll @From("payload") byte[] data) throws IOException {
        try (FileNodeStore store = new FileNodeStore(freshRoot())) {
            byte[] hash = store.write(data);
            assertArrayEquals(HashUtils.hash(data), hash, "write must return the content hash");
            assertArrayEquals(
                    data,
                    store.read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    "read must round-trip the exact bytes");
        }
    }

    @Property(tries = 100)
    void oneFilePerDistinctPayloadNoMatterHowOftenRewritten(
            @ForAll @From("payloadList") List<byte[]> payloads) throws IOException {
        Path root = freshRoot();
        Set<String> distinct = new LinkedHashSet<>();
        try (FileNodeStore store = new FileNodeStore(root)) {
            for (byte[] p : payloads) {
                store.write(p);
                store.write(p); // the second write of each must dedup — never a new file.
                distinct.add(HashUtils.toHex(HashUtils.hash(p)));
            }
        }
        try (Stream<Path> walk = Files.walk(root)) {
            long files = walk.filter(Files::isRegularFile).count();
            assertEquals(
                    distinct.size(),
                    files,
                    "one file per distinct payload, regardless of rewrites");
        }
    }

    @Property(tries = 100)
    void everyChunkPathIsTheContentHashInFanOutForm(@ForAll @From("payload") byte[] data)
            throws IOException {
        Path root = freshRoot();
        byte[] hash;
        try (FileNodeStore store = new FileNodeStore(root)) {
            hash = store.write(data);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            assertEquals(1, files.size(), "one payload -> one chunk file");
            String rel = root.relativize(files.get(0)).toString().replace('\\', '/');
            assertTrue(
                    LOOSE_OBJECT_PATH.matcher(rel).matches(),
                    "chunk path must be <2-hex>/<38-hex>: " + rel);
            assertEquals(
                    HashUtils.toHex(hash),
                    rel.replace("/", ""),
                    "path must equal the content hash");
        }
    }
}
