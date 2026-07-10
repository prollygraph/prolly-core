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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.InMemoryManifest;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.ProllyCorruptionException;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.FileNodeStore;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * The corruption net (R-8) over a {@link FileNodeStore}: the filesystem-store analogue of {@link
 * CorruptionDetectionProperty} (which tampers a RocksDB value via {@code db().put}). Here the
 * tamper vector is the one a filesystem store actually faces — a chunk <b>file</b> whose bytes are
 * changed underneath it by bitrot, a bad {@code rsync}, or an operator edit — so the test flips a
 * byte of the committed data-root chunk's real on-disk file and confirms the store composes with
 * the existing integrity layer: {@link IntegrityVerifyingNodeStore} rejects the tampered chunk on
 * read (the raw {@code FileNodeStore} returns the bytes; the wrapper re-hashes and detects hash ≠
 * content), and {@link TreeIntegrityChecker} reports it on a verify walk. Sweeps generated trees ×
 * a random byte position, pinning that a content-addressed filesystem store never silently serves
 * bytes whose hash ≠ their filename.
 */
class FileNodeStoreCorruptionProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private final List<Path> tempDirs = new ArrayList<>();

    @Provide
    Arbitrary<Set<Integer>> keyIds() {
        return Arbitraries.integers().between(0, 600).set().ofMinSize(1).ofMaxSize(250);
    }

    @AfterTry
    void cleanup() {
        for (Path dir : tempDirs) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                        // best-effort temp cleanup
                                    }
                                });
            } catch (IOException ignored) {
                // best-effort temp cleanup
            }
        }
        tempDirs.clear();
    }

    @Property(tries = 25)
    void anyByteFlipInTheOnDiskRootChunkIsDetected(
            @ForAll @From("keyIds") Set<Integer> ids,
            @ForAll @IntRange(min = 0, max = 100_000) int posSeed)
            throws Exception {
        Path dir = Files.createTempDirectory("file-corrupt-");
        tempDirs.add(dir);
        Path storeRoot = dir.resolve("store");
        try (DirectBufferPool pool = new DirectBufferPool()) {
            FileNodeStore file = new FileNodeStore(storeRoot);
            Database db = new Database(file, new InMemoryManifest(), "corrupt-repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            MutableMap mm = new MutableMap(db.getBranch("main"), file, DESC, pool);
            for (int id : ids) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, ("k" + id).getBytes());
                mm.put(tb.build().segment(), MemorySegment.ofArray(("v" + id).getBytes()));
            }
            db.commit("main", mm.flush(), null, "sim", "seed");
            byte[] dataRoot = db.getHead("main").getRootValueHash();
            if (dataRoot == null) {
                return; // empty tree (shouldn't happen: ids non-empty)
            }

            // A freshly committed, uncorrupted tree verifies through the filesystem store.
            assertDoesNotThrow(
                    () -> new TreeIntegrityChecker(file).verify(dataRoot),
                    "a valid tree must verify over FileNodeStore");

            // Tamper the actual on-disk chunk FILE — the filesystem store's real corruption vector.
            Path chunk = chunkPath(storeRoot, dataRoot);
            byte[] original = Files.readAllBytes(chunk);
            byte[] corrupt = original.clone();
            int pos = Math.floorMod(posSeed, corrupt.length);
            corrupt[pos] ^= 0x5A; // XOR non-zero -> guaranteed different byte -> different hash
            Files.write(chunk, corrupt);

            // Both detection paths fail fast — the store never serves the tampered bytes as valid.
            IntegrityVerifyingNodeStore verifying = new IntegrityVerifyingNodeStore(file);
            assertThrows(
                    ProllyCorruptionException.class,
                    () -> verifying.read(dataRoot),
                    "IntegrityVerifyingNodeStore must reject the tampered chunk file (pos="
                            + pos
                            + ")");
            assertThrows(
                    RuntimeException.class,
                    () -> new TreeIntegrityChecker(file).verify(dataRoot),
                    "TreeIntegrityChecker must report the corrupted node (pos=" + pos + ")");
        }
    }

    /**
     * The on-disk path of a chunk: {@code <root>/<hex[0:2]>/<hex[2:40]>} (FileNodeStore's layout).
     */
    private static Path chunkPath(Path root, byte[] hash) {
        String hex = HashUtils.toHex(hash);
        return root.resolve(hex.substring(0, 2)).resolve(hex.substring(2));
    }
}
