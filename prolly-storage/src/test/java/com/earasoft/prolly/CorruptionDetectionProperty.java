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
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.ProllyCorruptionException;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
 * Phase 3 Step 19 of the upstream test-strategy plan — corruption detection (R-8), generalized. The
 * landed `DeterministicSimulationFaultTest` flips byte 0 of the head chunk;
 * `DataIntegrityCorruptionTest` corrupts one fixed way. This sweeps <b>generated trees × a random
 * byte position</b>: a valid tree's root passes `TreeIntegrityChecker`, and flipping <i>any</i>
 * byte of the committed data-root chunk is detected fail-fast by both {@link
 * com.earasoft.prolly.IntegrityVerifyingNodeStore} (on read) and {@link TreeIntegrityChecker}
 * (re-hash → "Hash mismatch"). Pins R-8: a content-addressed store never serves bytes whose hash ≠
 * their key.
 */
class CorruptionDetectionProperty {

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
                                    }
                                });
            } catch (IOException ignored) {
            }
        }
        tempDirs.clear();
    }

    @Property(tries = 25)
    void anyByteFlipInTheRootChunkIsDetected(
            @ForAll @From("keyIds") Set<Integer> ids,
            @ForAll @IntRange(min = 0, max = 100_000) int posSeed)
            throws Exception {
        Path dir = Files.createTempDirectory("rdf-corrupt-");
        tempDirs.add(dir);
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
            Database db = new Database(rocks, "corrupt-repo", DESC, pool);
            db.createBranch("main", "EMPTY");
            MutableMap mm = new MutableMap(db.getBranch("main"), rocks, DESC, pool);
            for (int id : ids) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, ("k" + id).getBytes());
                mm.put(tb.build().segment(), MemorySegment.ofArray(("v" + id).getBytes()));
            }
            db.commit("main", mm.flush(), null, "sim", "seed");
            byte[] dataRoot = db.getHead("main").getRootValueHash();
            if (dataRoot == null) return; // empty tree (shouldn't happen: ids non-empty)

            // A valid tree passes the integrity checker (level-decrease +
            // child-hash hold over the whole walk).
            assertDoesNotThrow(
                    () -> new TreeIntegrityChecker(rocks).verify(dataRoot),
                    "a freshly committed, uncorrupted tree must verify");

            // Flip one byte at a pseudo-random position of the data-root chunk.
            byte[] original = rocks.read(dataRoot).orElseThrow().toArray(ValueLayout.JAVA_BYTE);
            byte[] corrupt = original.clone();
            int pos = Math.floorMod(posSeed, corrupt.length);
            corrupt[pos] ^= 0x5A; // XOR with non-zero → guaranteed different byte → different hash
            rocks.db().put(dataRoot, corrupt);

            // Both detection paths must fail fast — never serve the tampered bytes.
            IntegrityVerifyingNodeStore verifying = new IntegrityVerifyingNodeStore(rocks);
            assertThrows(
                    ProllyCorruptionException.class,
                    () -> verifying.read(dataRoot),
                    "IntegrityVerifyingNodeStore must reject a corrupted chunk on read (pos="
                            + pos
                            + ")");
            assertThrows(
                    RuntimeException.class,
                    () -> new TreeIntegrityChecker(rocks).verify(dataRoot),
                    "TreeIntegrityChecker must report the corrupted node (pos=" + pos + ")");
        }
    }
}
