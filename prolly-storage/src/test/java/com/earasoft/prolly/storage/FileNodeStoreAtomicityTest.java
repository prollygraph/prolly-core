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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HashUtils;
import com.earasoft.prolly.ErrorInjectingNodeStore;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Write atomicity of {@link FileNodeStore}: the temp-file + {@code ATOMIC_MOVE} means the final
 * chunk path is only ever <em>complete or absent</em>, never torn.
 *
 * <p>The kernel's {@code rename} atomicity itself is not unit-testable, so instead of trying to
 * interrupt a synchronous write we recreate the exact <b>post-crash filesystem state</b> — a stray
 * {@code .tmp-*} left in the fan-out directory with the final path absent, i.e. "the write died
 * after writing its temp but before the rename" — and assert the store treats it as a chunk that
 * was never written (read is empty) and that a retry still lands the real chunk. A leftover temp is
 * harmless garbage; a torn chunk at the final path would be corruption. (Note: this deliberately
 * does <em>not</em> inject into {@code FileNodeStore.write} — {@link ErrorInjectingNodeStore} is a
 * decorator that throws <em>before</em> delegating, so it cannot fail between temp-write and
 * rename; it is used below at the level it can act, an aborted write <em>sequence</em>.)
 */
final class FileNodeStoreAtomicityTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Path chunkPath(Path root, byte[] hash) {
        String hex = HashUtils.toHex(hash);
        return root.resolve(hex.substring(0, 2)).resolve(hex.substring(2));
    }

    private static List<Path> regularFiles(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void aSuccessfulWriteLandsTheFinalFileWholeAndLeavesNoTemp(@TempDir Path root)
            throws Exception {
        try (FileNodeStore store = new FileNodeStore(root)) {
            byte[] data = bytes("whole and correct");
            byte[] hash = store.write(data);

            // The bytes AT the final path are the exact payload — never a partial file.
            assertArrayEquals(
                    data, Files.readAllBytes(chunkPath(root, hash)), "final file is byte-exact");

            // The happy path consumes its temp: the only file present is the chunk itself.
            List<Path> files = regularFiles(root);
            assertEquals(1, files.size(), "a successful write leaves exactly the chunk, no temp");
            assertFalse(
                    files.get(0).getFileName().toString().startsWith(".tmp-"),
                    "no .tmp- leftover on the success path");
        }
    }

    @Test
    void aCrashAfterTempWriteLeavesTheFinalAbsent_readIsEmpty(@TempDir Path root) throws Exception {
        try (FileNodeStore store = new FileNodeStore(root)) {
            byte[] data = bytes("the write that crashed");
            byte[] hash = HashUtils.hash(data);

            // Simulate a crash after temp-write, before rename: a stray temp in the fan-out dir,
            // the final path absent.
            Path fanOut = chunkPath(root, hash).getParent();
            Files.createDirectories(fanOut);
            Files.write(fanOut.resolve(".tmp-crashed"), bytes("half-written garbage"));

            assertFalse(Files.exists(chunkPath(root, hash)), "precondition: final path is absent");
            assertFalse(
                    store.read(hash).isPresent(),
                    "a chunk whose write died before rename must read empty, never the torn temp");
        }
    }

    @Test
    void aLeftoverTempDoesNotBlockRewritingTheRealChunk(@TempDir Path root) throws Exception {
        try (FileNodeStore store = new FileNodeStore(root)) {
            byte[] data = bytes("retry me after a crash");
            byte[] hash = HashUtils.hash(data);

            Path fanOut = chunkPath(root, hash).getParent();
            Files.createDirectories(fanOut);
            Files.write(fanOut.resolve(".tmp-crashed"), bytes("orphan garbage"));

            // The retry writes the real chunk despite the orphaned temp sitting beside it.
            byte[] written = store.write(data);
            assertArrayEquals(hash, written, "retry returns the content hash");
            assertArrayEquals(
                    data,
                    store.read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    "the real chunk is readable after the retry");
            assertArrayEquals(
                    data, Files.readAllBytes(chunkPath(root, hash)), "and it is byte-exact");
        }
    }

    @Test
    void anAbortedWriteSequenceLeavesEveryCommittedChunkIntactAndNothingTorn(@TempDir Path root)
            throws Exception {
        FileNodeStore file = new FileNodeStore(root);
        ErrorInjectingNodeStore injected = new ErrorInjectingNodeStore(file);
        injected.injectErrorAfter(
                4); // checkError() decrements then throws at 0 -> the 4th write throws.

        byte[][] payloads = {bytes("c0"), bytes("c1"), bytes("c2"), bytes("c3-never-lands")};
        List<byte[]> committed = new ArrayList<>();
        boolean aborted = false;
        for (byte[] p : payloads) {
            try {
                committed.add(injected.write(p));
            } catch (RuntimeException expected) {
                aborted = true;
                break;
            }
        }
        assertTrue(aborted, "the injected failure must abort the sequence");
        assertEquals(3, committed.size(), "exactly the three pre-failure writes committed");

        // Every committed chunk is atomically present + correct in the underlying FileNodeStore.
        for (int i = 0; i < committed.size(); i++) {
            assertArrayEquals(
                    payloads[i],
                    file.read(committed.get(i)).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    "committed chunk " + i + " must be intact after the abort");
        }
        // The aborted chunk never reached the store — absent, not torn.
        assertFalse(
                file.read(HashUtils.hash(payloads[3])).isPresent(),
                "the aborted chunk must be absent");
        // No torn/temp file leaked anywhere under the root.
        for (Path f : regularFiles(root)) {
            assertFalse(
                    f.getFileName().toString().startsWith(".tmp-"),
                    "an aborted sequence must not leak a temp: " + f);
        }
    }
}
