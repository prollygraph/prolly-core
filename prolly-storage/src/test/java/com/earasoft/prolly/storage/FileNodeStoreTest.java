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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The <em>filesystem-specific</em> behaviour of {@link FileNodeStore}: the on-disk fan-out layout,
 * the one-file-per-distinct-chunk observation, and persistence across a reopen. The
 * backend-independent content-addressable-storage invariants (round-trip, content hash, missing →
 * empty, empty/large payloads, batch) are covered generically by {@link NodeStoreContractTest},
 * which runs its battery against the {@code FILE} kind too.
 */
final class FileNodeStoreTest {

    @TempDir Path root;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static long fileCount(Path dir) throws Exception {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void writeIsAtTheTwoCharFanOutPath() {
        try (FileNodeStore store = new FileNodeStore(root)) {
            byte[] hash = store.write(bytes("fan out"));
            String hex = HashUtils.toHex(hash);
            Path expected = root.resolve(hex.substring(0, 2)).resolve(hex.substring(2));
            assertTrue(Files.exists(expected), "chunk must live at <root>/<hex[0:2]>/<hex[2:]>");
        }
    }

    @Test
    void identicalBytesDedupToExactlyOneFile() throws Exception {
        try (FileNodeStore store = new FileNodeStore(root)) {
            byte[] data = bytes("dedup me");
            store.write(data);
            store.write(data);
            assertEquals(
                    1, fileCount(root), "same bytes written twice must produce exactly one file");
        }
    }

    @Test
    void distinctBytesProduceDistinctFiles() throws Exception {
        try (FileNodeStore store = new FileNodeStore(root)) {
            store.write(bytes("a"));
            store.write(bytes("b"));
            store.write(bytes("c"));
            assertEquals(3, fileCount(root), "three distinct payloads -> three files");
        }
    }

    @Test
    void aReopenedStoreReadsWhatAPriorStoreWrote() {
        byte[] hash;
        try (FileNodeStore first = new FileNodeStore(root)) {
            hash = first.write(bytes("durable across open"));
        }
        try (FileNodeStore reopened = new FileNodeStore(root)) {
            assertArrayEquals(
                    bytes("durable across open"),
                    reopened.read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    "a fresh store on the same root sees prior chunks (files are the state)");
        }
    }
}
