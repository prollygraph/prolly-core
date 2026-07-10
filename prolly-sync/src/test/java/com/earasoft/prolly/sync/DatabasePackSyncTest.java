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
import com.earasoft.prolly.Database;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The upstream sync resume gate (the upstream document-store sync plan, findings #8b/#8c): the
 * {@code Database}-level pack round-trip must reproduce the sender's branch HEAD hash
 * <b>byte-for-byte</b> on the receiver — if the closure had used {@code CommitLog}'s RDF-style
 * entry hashing instead of core-{@code Commit} content addresses, the heads would diverge (the
 * silent-corruption trap the deferral named). Also pins: the all-parents closure carries a merge
 * commit's second-parent lineage (the {@code CommitWalk.firstParent} trap), the incremental pack
 * Merkle-prunes what the receiver holds, the compare-and-set rejects stale expectations, and a torn
 * pack fails fast rather than publishing a dangling ref.
 */
class DatabasePackSyncTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final String BRANCH = "main";

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
        a = new Database(rocksA, "sync-a", DESC, pool);
        b = new Database(rocksB, "sync-b", DESC, pool);
        a.createBranch(BRANCH, "EMPTY");
        b.createBranch(BRANCH, "EMPTY");
    }

    @AfterEach
    void close() {
        if (rocksA != null) rocksA.close();
        if (rocksB != null) rocksB.close();
        if (pool != null) pool.close();
    }

    private byte[] put(Database db, String branch, String key, String value) {
        byte[] parent = db.getHeadHash(branch).orElse(null);
        StaticMap base =
                parent == null ? new StaticMap(db.store(), null, DESC) : db.getBranch(branch);
        MutableMap mm = new MutableMap(base, db.store(), DESC, pool);
        mm.put(keyTuple(key), MemorySegment.ofArray(value.getBytes(StandardCharsets.UTF_8)));
        assertTrue(db.commit(branch, mm, parent, "t", "put " + key));
        return db.getHeadHash(branch).orElseThrow();
    }

    private MemorySegment keyTuple(String key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    private String readValue(Database db, String key) {
        return new String(
                db.getBranch(BRANCH)
                        .get(keyTuple(key))
                        .orElseThrow()
                        .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                StandardCharsets.UTF_8);
    }

    @Test
    void fullClone_reproducesTheHeadHashByteForByte() {
        put(a, BRANCH, "k1", "v1");
        put(a, BRANCH, "k2", "v2");
        byte[] headA = put(a, BRANCH, "k3", "v3");

        DatabasePackSync.PackAndHead built = DatabasePackSync.buildPack(a, BRANCH, Set.of());
        assertArrayEquals(headA, built.head().orElseThrow());
        assertTrue(DatabasePackSync.apply(b, BRANCH, built.pack(), headA, null));

        // THE invariant (finding #8b): the receiver's head IS the sender's, content-addressed.
        assertArrayEquals(headA, b.getHeadHash(BRANCH).orElseThrow());
        // And the data is readable through the received tree.
        assertEquals("v2", readValue(b, "k2"));
        // The whole 3-commit chain is walkable on B (parents present as chunks).
        assertEquals(3, chainLength(b, headA));
    }

    @Test
    void mergeCommit_secondParentLineageIsInThePack() {
        put(a, BRANCH, "m1", "v1");
        put(a, BRANCH, "m2", "v2");
        a.createBranch("side", BRANCH);
        byte[] sideHead = put(a, "side", "s1", "sv");
        put(a, BRANCH, "m3", "v3");
        a.merge(BRANCH, "side", "t", "merge side");
        byte[] headA = a.getHeadHash(BRANCH).orElseThrow();
        assertEquals(2, readCommit(a, headA).getParents().size(), "merge head has two parents");

        DatabasePackSync.PackAndHead built = DatabasePackSync.buildPack(a, BRANCH, Set.of());
        assertTrue(DatabasePackSync.apply(b, BRANCH, built.pack(), headA, null));
        assertArrayEquals(headA, b.getHeadHash(BRANCH).orElseThrow());
        // The #8c pin: the SIDE commit (reachable only via the second parent) arrived — a
        // firstParent-style closure would have dropped it.
        assertTrue(b.store().read(sideHead).isPresent(), "side-branch commit missing from pack");
        assertEquals("sv", readValue(b, "s1"));
    }

    @Test
    void incrementalPack_prunesWhatTheReceiverHolds_andCasGuards() {
        put(a, BRANCH, "k1", "v1");
        byte[] oldHead = put(a, BRANCH, "k2", "v2");
        DatabasePackSync.PackAndHead full0 = DatabasePackSync.buildPack(a, BRANCH, Set.of());
        assertTrue(DatabasePackSync.apply(b, BRANCH, full0.pack(), oldHead, null));

        put(a, BRANCH, "k3", "v3");
        byte[] newHead = put(a, BRANCH, "k4", "v4");
        DatabasePackSync.PackAndHead full = DatabasePackSync.buildPack(a, BRANCH, Set.of());
        DatabasePackSync.PackAndHead incremental =
                DatabasePackSync.buildPack(a, BRANCH, Set.of(HashUtils.toHex(oldHead)));
        assertTrue(
                incremental.pack().chunks().size() < full.pack().chunks().size(),
                "incremental pack must be Merkle-pruned: "
                        + incremental.pack().chunks().size()
                        + " vs full "
                        + full.pack().chunks().size());

        assertTrue(DatabasePackSync.apply(b, BRANCH, incremental.pack(), newHead, oldHead));
        assertArrayEquals(newHead, b.getHeadHash(BRANCH).orElseThrow());
        assertEquals("v4", readValue(b, "k4"));
        assertEquals("v1", readValue(b, "k1"));

        // A stale compare-and-set expectation is rejected, not clobbered.
        assertFalse(DatabasePackSync.apply(b, BRANCH, incremental.pack(), newHead, oldHead));
    }

    @Test
    void tornPack_failsFast_neverPublishesADanglingRef() {
        byte[] headA = put(a, BRANCH, "k1", "v1");
        SyncPack empty = new SyncPack(List.of(), List.of());
        assertThrows(
                IllegalStateException.class,
                () -> DatabasePackSync.apply(b, BRANCH, empty, headA, null));
        assertTrue(b.getHeadHash(BRANCH).isEmpty(), "the ref must not have moved");
    }

    private com.dolthub.prolly.Commit readCommit(Database db, byte[] hash) {
        return com.dolthub.prolly.Commit.deserialize(
                db.store()
                        .read(hash)
                        .orElseThrow()
                        .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }

    /** Chain length following first parents (linear chains here). */
    private int chainLength(Database db, byte[] head) {
        int n = 0;
        byte[] h = head;
        while (h != null) {
            n++;
            var c = readCommit(db, h);
            h = c.getParents().isEmpty() ? null : c.getParents().get(0);
        }
        return n;
    }

    // ---- integrate (Step 7): pull-side fast-forward-only integration ----

    @Test
    void integrate_fastForwards_createsNewBranches_andNoOpsWhenUpToDateOrAhead() {
        // Create: B has no such branch — integrate lands the remote head.
        byte[] h1 = put(a, BRANCH, "k1", "v1");
        DatabasePackSync.PackAndHead full = DatabasePackSync.buildPack(a, BRANCH, Set.of());
        assertArrayEquals(h1, DatabasePackSync.integrate(b, "feature", full.pack(), h1));
        assertArrayEquals(h1, b.getHeadHash("feature").orElseThrow());

        // Fast-forward: A advances; B integrates the delta.
        byte[] h2 = put(a, BRANCH, "k2", "v2");
        DatabasePackSync.PackAndHead delta =
                DatabasePackSync.buildPack(a, BRANCH, Set.of(HashUtils.toHex(h1)));
        assertArrayEquals(h2, DatabasePackSync.integrate(b, "feature", delta.pack(), h2));
        assertArrayEquals(h2, b.getHeadHash("feature").orElseThrow());
        assertEquals("v2", readValue(b, "k2", "feature"));

        // Up to date: integrating the same head is a no-op.
        assertArrayEquals(h2, DatabasePackSync.integrate(b, "feature", delta.pack(), h2));

        // Ahead: B commits past the remote head — integrate keeps B's head.
        byte[] h3 = put(b, "feature", "k3", "v3");
        assertArrayEquals(h3, DatabasePackSync.integrate(b, "feature", delta.pack(), h2));
        assertArrayEquals(h3, b.getHeadHash("feature").orElseThrow());
    }

    @Test
    void integrate_refusesADivergedBranch_localHeadUntouched() {
        // Common ancestor on both sides, then each commits its own way.
        byte[] h1 = put(a, BRANCH, "k1", "v1");
        DatabasePackSync.PackAndHead full = DatabasePackSync.buildPack(a, BRANCH, Set.of());
        assertTrue(
                b.receiveSyncPack(
                        full.pack().chunks(), BRANCH, h1, b.getHeadHash(BRANCH).orElse(null)));

        byte[] headA = put(a, BRANCH, "k2", "a-side");
        byte[] headB = put(b, BRANCH, "k2", "b-side");

        DatabasePackSync.PackAndHead delta =
                DatabasePackSync.buildPack(a, BRANCH, Set.of(HashUtils.toHex(h1)));
        IllegalStateException diverged =
                assertThrows(
                        IllegalStateException.class,
                        () -> DatabasePackSync.integrate(b, BRANCH, delta.pack(), headA));
        assertTrue(diverged.getMessage().contains("diverged"), diverged.getMessage());
        // The clobber this guards against: a bare receiveSyncPack would have
        // succeeded here (expected == B's local head) and silently dropped
        // B's commit. The local head must be untouched.
        assertArrayEquals(headB, b.getHeadHash(BRANCH).orElseThrow());
        assertEquals("b-side", readValue(b, "k2"));
    }

    private String readValue(Database db, String key, String branch) {
        return new String(
                db.getBranch(branch)
                        .get(keyTuple(key))
                        .orElseThrow()
                        .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                StandardCharsets.UTF_8);
    }

    @Test
    void interiorChunkDrop_bareSinkAccepts_hardenedApplyRejects() {
        // The differential that gives the S-9 torn-pack property its teeth
        // (an upstream hardening pass): Database.receiveSyncPack's own
        // guard is HEAD-COMMIT-DEEP only — a pack missing an interior tree
        // chunk still passes it, publishing a ref whose reads fail later.
        // DatabasePackSync.apply's verifyHeadState closes exactly that hole.
        put(a, BRANCH, "k1", "v1");
        byte[] headA = put(a, BRANCH, "k2", "v2");
        DatabasePackSync.PackAndHead full = DatabasePackSync.buildPack(a, BRANCH, Set.of());

        // Drop a chunk that is NOT the head commit blob (an interior/tree chunk).
        java.util.List<byte[]> torn = new java.util.ArrayList<>(full.pack().chunks());
        torn.removeIf(c -> java.util.Arrays.equals(HashUtils.hash(c), headA));
        assertTrue(torn.size() < full.pack().chunks().size(), "head blob located + kept out");
        java.util.List<byte[]> withHeadOnly = new java.util.ArrayList<>(torn);
        // Re-add the head commit blob, drop the FIRST non-commit chunk instead.
        for (byte[] c : full.pack().chunks()) {
            if (java.util.Arrays.equals(HashUtils.hash(c), headA)) {
                withHeadOnly.add(c);
            }
        }
        withHeadOnly.remove(0); // an interior chunk the head state needs

        // The bare sink ACCEPTS the torn pack (its guard reads only the head blob) —
        // the documented shallow contract this test pins as the near-miss.
        assertTrue(b.receiveSyncPack(withHeadOnly, "torn-bare", headA, null));

        // The hardened apply REJECTS the same pack, ref unmoved.
        IllegalStateException torn2 =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DatabasePackSync.apply(
                                        b,
                                        "torn-hardened",
                                        new SyncPack(withHeadOnly, java.util.List.of()),
                                        headA,
                                        null));
        assertTrue(torn2.getMessage().contains("torn sync pack"), torn2.getMessage());
        assertTrue(b.getHeadHash("torn-hardened").isEmpty(), "the hardened ref never moved");
    }
}
