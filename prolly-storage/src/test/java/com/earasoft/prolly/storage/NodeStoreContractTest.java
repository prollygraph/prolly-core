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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeCache;
import com.dolthub.prolly.NodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * I-7-adjacent: one shared {@link NodeStore} contract battery, run against every implementation
 * (plans/core-engine-test-strategy.md Step 14). Closes the "only in-memory + one RocksDB path
 * tested" gap — before this, each store had its own ad-hoc test and the shared-column-family and
 * cache-wrapped paths were exercised only transitively.
 *
 * <p>Every implementation must honour the same content-addressable-storage contract:
 *
 * <ul>
 *   <li><b>Content addressing</b>: {@code write(data)} returns {@code hash(data)}.
 *   <li><b>Read-after-write</b>: a written chunk reads back byte-identical, via both the {@code
 *       byte[]} and {@code MemorySegment} write overloads.
 *   <li><b>Idempotence</b>: writing the same bytes twice yields the same address and one logical
 *       chunk.
 *   <li><b>Missing hash → empty</b>: reading an unknown address is {@code Optional.empty()}, never
 *       a throw or a wrong chunk.
 *   <li><b>Batch durability</b>: chunks written between {@code beginWriteBatch} and {@code
 *       endWriteBatch} are all readable after the batch ends.
 * </ul>
 */
class NodeStoreContractTest {
    static {
        RocksDB.loadLibrary();
    }

    private static final List<String> FLAT_CFS =
            List.of("dict-fwd", "dict-rev", "spoc", "posc", "ospc", "cspo", "ns");

    /** The store configurations the contract must hold for. */
    enum Kind {
        IN_MEMORY,
        FILE,
        ROCKS_STANDALONE,
        ROCKS_SHARED_CF,
        ROCKS_WITH_CACHE
    }

    /** A live store plus the closeable that releases all of its resources. */
    private record Fixture(NodeStore store, AutoCloseable closeable) {}

    private static Fixture open(Kind kind, Path dir) throws Exception {
        switch (kind) {
            case IN_MEMORY -> {
                InMemoryNodeStore s = new InMemoryNodeStore();
                return new Fixture(s, s);
            }
            case FILE -> {
                FileNodeStore s = new FileNodeStore(dir.resolve("file"));
                return new Fixture(s, s);
            }
            case ROCKS_STANDALONE -> {
                RocksNodeStore s = new RocksNodeStore(dir.resolve("standalone").toString());
                return new Fixture(s, s);
            }
            case ROCKS_SHARED_CF -> {
                SharedRocksDb shared =
                        SharedRocksDb.open(dir.resolve("shared").toString(), FLAT_CFS);
                RocksNodeStore s = new RocksNodeStore(shared.db(), shared.chunkStoreColumnFamily());
                return new Fixture(
                        s,
                        () -> {
                            s.close();
                            shared.close();
                        });
            }
            case ROCKS_WITH_CACHE -> {
                RocksNodeStore s = new RocksNodeStore(dir.resolve("cache").toString());
                s.setNodeCache(new NodeCache(1 << 20)); // 1 MiB byte budget
                return new Fixture(s, s);
            }
            default -> throw new IllegalStateException(kind.name());
        }
    }

    @TestFactory
    Stream<DynamicTest> nodeStoreContractHoldsForEveryImplementation(@TempDir Path baseDir) {
        return Stream.of(Kind.values())
                .map(
                        kind ->
                                DynamicTest.dynamicTest(
                                        "contract: " + kind, () -> runContract(kind, baseDir)));
    }

    private void runContract(Kind kind, Path baseDir) throws Exception {
        Path dir = baseDir.resolve(kind.name());
        Files.createDirectories(dir);
        Fixture f = open(kind, dir);
        try {
            NodeStore store = f.store();

            // 1. Content addressing: write returns the SHA-512/20 of the bytes.
            byte[] data = ("node-store contract: " + kind).getBytes(StandardCharsets.UTF_8);
            byte[] hash = store.write(data);
            assertArrayEquals(
                    HashUtils.hash(data), hash, kind + ": write must return the content hash");

            // 2. Read-after-write, byte[] overload.
            Optional<MemorySegment> got = store.read(hash);
            assertTrue(got.isPresent(), kind + ": written chunk must read back");
            assertArrayEquals(
                    data,
                    got.get().toArray(ValueLayout.JAVA_BYTE),
                    kind + ": chunk must read back byte-identical");

            // 3. Read-after-write, MemorySegment overload → same address + bytes.
            byte[] data2 = ("segment overload: " + kind).getBytes(StandardCharsets.UTF_8);
            byte[] hash2 = store.write(MemorySegment.ofArray(data2));
            assertArrayEquals(
                    HashUtils.hash(data2),
                    hash2,
                    kind + ": MemorySegment write must also be content-addressed");
            assertArrayEquals(
                    data2,
                    store.read(hash2).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    kind + ": MemorySegment-written chunk must read back");

            // 4. Idempotence: same bytes → same address, still one logical chunk.
            byte[] hashAgain = store.write(data);
            assertArrayEquals(hash, hashAgain, kind + ": re-writing the same bytes is idempotent");
            assertArrayEquals(
                    data,
                    store.read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE),
                    kind + ": chunk still intact after idempotent re-write");

            // 5. Missing hash → empty (not a throw, not a wrong chunk).
            byte[] absent = new byte[20];
            absent[0] = 0x42;
            absent[19] = 0x77;
            assertFalse(store.read(absent).isPresent(), kind + ": unknown address must be empty");

            // 6. Batch durability: writes between begin/end are all readable
            //    AFTER the batch ends (the contract allows them to be invisible
            //    until then, so we only read post-end).
            List<byte[]> batchHashes = new ArrayList<>();
            store.beginWriteBatch();
            for (int i = 0; i < 8; i++) {
                byte[] chunk = ("batch chunk " + kind + " #" + i).getBytes(StandardCharsets.UTF_8);
                batchHashes.add(store.write(chunk));
            }
            store.endWriteBatch();
            for (int i = 0; i < batchHashes.size(); i++) {
                byte[] expected =
                        ("batch chunk " + kind + " #" + i).getBytes(StandardCharsets.UTF_8);
                Optional<MemorySegment> read = store.read(batchHashes.get(i));
                assertTrue(
                        read.isPresent(),
                        kind + ": batched chunk " + i + " must persist after endWriteBatch");
                assertArrayEquals(
                        expected,
                        read.get().toArray(ValueLayout.JAVA_BYTE),
                        kind + ": batched chunk " + i + " must read back byte-identical");
            }
        } finally {
            f.closeable().close();
        }
    }
}
