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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@link Database#receiveSyncPack} sink primitive, pinned module-locally (the
 * cross-module-test-metric rule: the sync-layer round-trip lives upstream, but the substrate's own
 * branches need a direct test here): a valid pack advances the ref by compare-and-set; a stale
 * expectation is rejected without clobbering; a torn pack (new head not present after the chunk
 * writes) fails fast and never publishes a dangling ref.
 */
class DatabaseReceiveSyncPackTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private DirectBufferPool pool;
    private RocksNodeStore rocksA;
    private RocksNodeStore rocksB;
    private Database a;
    private Database b;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        pool = new DirectBufferPool();
        rocksA = new RocksNodeStore(dir.resolve("a").toString());
        rocksB = new RocksNodeStore(dir.resolve("b").toString());
        a = new Database(rocksA, "src", DESC, pool);
        b = new Database(rocksB, "dst", DESC, pool);
        a.createBranch("main", "EMPTY");
        b.createBranch("main", "EMPTY");
    }

    @AfterEach
    void close() {
        if (rocksA != null) rocksA.close();
        if (rocksB != null) rocksB.close();
        if (pool != null) pool.close();
    }

    private byte[] commitOnA(String key, String value) {
        byte[] parent = a.getHeadHash("main").orElse(null);
        StaticMap base =
                parent == null ? new StaticMap(a.store(), null, DESC) : a.getBranch("main");
        MutableMap mm = new MutableMap(base, a.store(), DESC, pool);
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes(StandardCharsets.UTF_8));
        mm.put(tb.build().segment(), MemorySegment.ofArray(value.getBytes(StandardCharsets.UTF_8)));
        assertTrue(a.commit("main", mm, parent, "t", "put " + key));
        return a.getHeadHash("main").orElseThrow();
    }

    /** Every chunk (tree nodes + commit blobs) reachable on A's chain — a hand-rolled full pack. */
    private List<byte[]> fullPackOfA() {
        List<byte[]> chunks = new ArrayList<>();
        byte[] h = a.getHeadHash("main").orElseThrow();
        while (h != null) {
            chunks.add(read(h));
            com.dolthub.prolly.Commit c = com.dolthub.prolly.Commit.deserialize(read(h));
            if (c.getRootValueHash() != null) {
                collectTree(c.getRootValueHash(), chunks);
            }
            h = c.getParents().isEmpty() ? null : c.getParents().get(0);
        }
        return chunks;
    }

    private void collectTree(byte[] hash, List<byte[]> out) {
        byte[] bytes = read(hash);
        out.add(bytes);
        com.dolthub.prolly.Node node =
                com.dolthub.prolly.Node.fromBytes(MemorySegment.ofArray(bytes));
        if (node != null && !node.isLeaf()) {
            for (int i = 0; i < node.count(); i++) {
                collectTree(node.getValue(i), out);
            }
        }
    }

    private byte[] read(byte[] hash) {
        return a.store().read(hash).orElseThrow().toArray(ValueLayout.JAVA_BYTE);
    }

    @Test
    void validPack_advancesTheRef_staleCasRejected_tornPackFailsFast() {
        commitOnA("k1", "v1");
        byte[] headA = commitOnA("k2", "v2");
        List<byte[]> pack = fullPackOfA();

        // Happy path: chunks land, ref advances by compare-and-set from empty.
        assertTrue(b.receiveSyncPack(pack, "main", headA, null));
        assertArrayEquals(headA, b.getHeadHash("main").orElseThrow());

        // Stale expectation: rejected, head unchanged (idempotent chunk re-writes are fine).
        assertFalse(b.receiveSyncPack(pack, "main", headA, null));
        assertArrayEquals(headA, b.getHeadHash("main").orElseThrow());

        // Torn pack: the claimed new head is absent -> fail fast, ref never moves.
        byte[] fake = HashUtils.hash("not-a-commit".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                IllegalStateException.class,
                () -> b.receiveSyncPack(List.of(), "main", fake, headA));
        assertArrayEquals(headA, b.getHeadHash("main").orElseThrow());
    }
}
