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

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@link FileNodeStore.Durability} wiring. fsync durability itself is not unit-observable (it
 * takes a real power-loss to distinguish fsync'd from page-cached), so the net verifies (a) the
 * default mode, and (b) that each mode drives a {@code begin}/{@code write}/{@code endWriteBatch}
 * span whose chunks are all readable by a <em>fresh</em> store opened on the same root — the "a new
 * process reads the committed state" crash-recovery integration, which exercises every mode's
 * write/fsync code path. (Uses {@code @TestFactory} over the modes to match {@code
 * NodeStoreContractTest} — this module does not depend on junit-jupiter-params.)
 */
final class FileNodeStoreDurabilityTest {

    private static byte[] chunk(FileNodeStore.Durability mode, int i) {
        return ("durable-" + mode + "-" + i).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void defaultDurabilityIsBatch(@TempDir Path root) {
        try (FileNodeStore store = new FileNodeStore(root)) {
            assertEquals(
                    FileNodeStore.Durability.BATCH, store.durability(), "default mode is BATCH");
        }
    }

    @TestFactory
    Stream<DynamicTest> everyModeCommitsABatchThatAFreshStoreCanRead(@TempDir Path baseDir) {
        return Stream.of(FileNodeStore.Durability.values())
                .map(
                        mode ->
                                DynamicTest.dynamicTest(
                                        "mode: " + mode,
                                        () ->
                                                commitBatchThenReopen(
                                                        mode, baseDir.resolve(mode.name()))));
    }

    private void commitBatchThenReopen(FileNodeStore.Durability mode, Path root) throws Exception {
        Files.createDirectories(root);
        int n = 40;
        byte[][] hashes = new byte[n][];
        try (FileNodeStore store = new FileNodeStore(root, mode)) {
            store.beginWriteBatch();
            for (int i = 0; i < n; i++) {
                hashes[i] = store.write(chunk(mode, i));
            }
            store.endWriteBatch(); // BATCH flushes deferred chunks here; EACH already flushed;
            // NONE no-ops.
        }
        // A fresh store (a new "process") on the same root must see every committed chunk,
        // byte-exact.
        try (FileNodeStore reopened = new FileNodeStore(root, mode)) {
            for (int i = 0; i < n; i++) {
                assertArrayEquals(
                        chunk(mode, i),
                        reopened.read(hashes[i]).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                        "chunk " + i + " must persist across a reopen under " + mode);
            }
        }
    }
}
