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
package com.earasoft.prolly.sync.grpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.Database;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import com.earasoft.prolly.sync.DatabasePackSync;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transport's acceptance contract, over a REAL socket (netty on an ephemeral port, plaintext —
 * not in-process transport): push and pull move a branch between two {@code Database}s with the
 * head reproduced byte-for-byte; a lost ref race is {@code updated=false}, not an error; a diverged
 * pull throws exactly like the in-process protocol; an oversized pack dies at the boundary with
 * {@code RESOURCE_EXHAUSTED}; an unknown repo is {@code NOT_FOUND}; and the pluggable-auth seam
 * works (the static-token interceptor worked example).
 */
class PackSyncEndToEndTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final String BRANCH = "main";

    private DirectBufferPool pool;
    private RocksNodeStore rocksLocal;
    private RocksNodeStore rocksRemote;
    private Database local;
    private Database remote;
    private PackSyncServer server;
    private PackSyncClient client;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        pool = new DirectBufferPool();
        rocksLocal = new RocksNodeStore(dir.resolve("local").toString());
        rocksRemote = new RocksNodeStore(dir.resolve("remote").toString());
        local = new Database(rocksLocal, "local", DESC, pool);
        remote = new Database(rocksRemote, "remote", DESC, pool);
        local.createBranch(BRANCH, "EMPTY");
        remote.createBranch(BRANCH, "EMPTY");
        server =
                PackSyncServer.start(
                        0, RepoResolver.singleRepo(remote), PackLimits.defaults(), List.of());
        client = new PackSyncClient(plaintextChannel(server.port()), "", PackLimits.defaults());
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (server != null) server.close();
        if (rocksLocal != null) rocksLocal.close();
        if (rocksRemote != null) rocksRemote.close();
        if (pool != null) pool.close();
    }

    private static ManagedChannel plaintextChannel(int port) {
        return ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    }

    private byte[] put(Database db, String key, String value) {
        byte[] parent = db.getHeadHash(BRANCH).orElse(null);
        StaticMap base =
                parent == null ? new StaticMap(db.store(), null, DESC) : db.getBranch(BRANCH);
        MutableMap mm = new MutableMap(base, db.store(), DESC, pool);
        mm.put(keyTuple(key), MemorySegment.ofArray(value.getBytes(StandardCharsets.UTF_8)));
        assertTrue(db.commit(BRANCH, mm, parent, "t", "put " + key));
        return db.getHeadHash(BRANCH).orElseThrow();
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

    // ---- push / pull round trips ---------------------------------------

    @Test
    void push_movesTheBranchAndReproducesTheHeadByteForByte() {
        put(local, "k1", "v1");
        byte[] localHead = put(local, "k2", "v2");

        PackSyncClient.PushResult result = client.push(local, BRANCH);

        assertFalse(result.upToDate());
        assertTrue(result.updated());
        assertArrayEquals(localHead, result.current());
        assertArrayEquals(localHead, remote.getHeadHash(BRANCH).orElseThrow());
        assertEquals("v2", readValue(remote, "k2"));

        // A second push is a clean up-to-date no-op, not a resend.
        assertTrue(client.push(local, BRANCH).upToDate());
    }

    @Test
    void pull_fastForwardsTheLocalBranch() {
        put(remote, "r1", "v1");
        byte[] remoteHead = put(remote, "r2", "v2");

        Optional<byte[]> newLocal = client.pull(local, BRANCH);

        assertArrayEquals(remoteHead, newLocal.orElseThrow());
        assertArrayEquals(remoteHead, local.getHeadHash(BRANCH).orElseThrow());
        assertEquals("v1", readValue(local, "r1"));
    }

    @Test
    void pull_ofAMissingRemoteBranchIsEmptyNotAnError() {
        assertTrue(client.pull(local, "no-such-branch").isEmpty());
    }

    @Test
    void incrementalPush_sendsOnlyTheDelta() {
        put(local, "k1", "v1");
        client.push(local, BRANCH);
        byte[] second = put(local, "k2", "v2");

        PackSyncClient.PushResult result = client.push(local, BRANCH);

        assertTrue(result.updated());
        assertArrayEquals(second, remote.getHeadHash(BRANCH).orElseThrow());
        assertEquals("v2", readValue(remote, "k2"));
    }

    // ---- divergence + races ---------------------------------------------

    @Test
    void divergedPull_throwsLikeTheInProcessProtocol_andMergeBaseIsAvailable() {
        byte[] shared = put(local, "base", "v");
        client.push(local, BRANCH);
        put(local, "mine", "local-side");
        put(remote, "theirs", "remote-side");

        IllegalStateException diverged =
                assertThrows(IllegalStateException.class, () -> client.pull(local, BRANCH));
        assertTrue(diverged.getMessage().toLowerCase(java.util.Locale.ROOT).contains("diverge"));

        // The resolution primitive the caller reaches for: fetch, then merge-base locally.
        PackSyncClient.FetchResult fetched = client.fetch(BRANCH, java.util.Set.of());
        local.receiveChunks(fetched.pack().chunks());
        Optional<byte[]> base =
                DatabasePackSync.mergeBase(
                        local.store(),
                        local.getHeadHash(BRANCH).orElseThrow(),
                        fetched.head().orElseThrow());
        assertArrayEquals(shared, base.orElseThrow());
    }

    @Test
    void lostCasRace_isUpdatedFalseWithTheWinnersValue() {
        put(local, "k1", "v1");
        client.push(local, BRANCH);
        // The remote moves on (the "winner").
        byte[] winner = put(remote, "w", "winner");
        // A stale push: expectedOld is the pre-winner head the client last saw.
        byte[] mine = put(local, "mine", "loser");
        DatabasePackSync.PackAndHead pack =
                DatabasePackSync.buildPack(local, BRANCH, java.util.Set.of());

        PackSyncClient.RefUpdate update =
                client.send(BRANCH, pack.pack(), mine, local.getHeadHash(BRANCH).orElseThrow());

        assertFalse(update.updated());
        assertArrayEquals(winner, remote.getHeadHash(BRANCH).orElseThrow());
    }

    @Test
    void casRef_onACommitTheHostDoesNotHold_isFailedPrecondition() {
        byte[] never = new byte[20]; // no such commit on the host
        StatusRuntimeException e =
                assertThrows(
                        StatusRuntimeException.class, () -> client.casRef(BRANCH, null, never));
        assertEquals(Status.Code.FAILED_PRECONDITION, e.getStatus().getCode());
    }

    // ---- the untrusted boundary ------------------------------------------

    @Test
    void oversizedPack_isResourceExhaustedAtTheBoundary(@TempDir Path dir) throws Exception {
        // A server with a 64 KiB byte cap; the client pushes a bigger pack.
        try (var tinyServer =
                PackSyncServer.start(
                        0,
                        RepoResolver.singleRepo(remote),
                        new PackLimits(1_000_000, 64 * 1024),
                        List.of())) {
            try (var tinyClient =
                    new PackSyncClient(
                            plaintextChannel(tinyServer.port()), "", PackLimits.defaults())) {
                for (int i = 0; i < 200; i++) {
                    put(local, "key-" + i, "x".repeat(1024));
                }
                StatusRuntimeException e =
                        assertThrows(
                                StatusRuntimeException.class, () -> tinyClient.push(local, BRANCH));
                assertEquals(Status.Code.RESOURCE_EXHAUSTED, e.getStatus().getCode());
                // The ref never moved.
                assertTrue(remote.getHeadHash(BRANCH).isEmpty());
            }
        }
    }

    @Test
    void unknownRepo_isNotFound(@TempDir Path dir) throws Exception {
        RepoResolver onlyDefault =
                repoId -> {
                    if (!repoId.isEmpty()) {
                        throw new NoSuchElementException(repoId);
                    }
                    return RepoResolver.singleRepo(remote).resolve(repoId);
                };
        try (var multiServer =
                PackSyncServer.start(0, onlyDefault, PackLimits.defaults(), List.of())) {
            try (var wrongRepo =
                    new PackSyncClient(
                            plaintextChannel(multiServer.port()),
                            "no-such-repo",
                            PackLimits.defaults())) {
                StatusRuntimeException e =
                        assertThrows(StatusRuntimeException.class, wrongRepo::refs);
                assertEquals(Status.Code.NOT_FOUND, e.getStatus().getCode());
            }
        }
    }

    // ---- the auth seam (worked example, not shipped policy) ---------------

    @Test
    void authInterceptor_gatesTheService() throws Exception {
        Metadata.Key<String> tokenKey =
                Metadata.Key.of("x-sync-token", Metadata.ASCII_STRING_MARSHALLER);
        ServerInterceptor requireToken =
                new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call,
                            Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        if (!"open-sesame".equals(headers.get(tokenKey))) {
                            call.close(
                                    Status.UNAUTHENTICATED.withDescription("token"),
                                    new Metadata());
                            return new ServerCall.Listener<>() {};
                        }
                        return next.startCall(call, headers);
                    }
                };
        try (var authed =
                PackSyncServer.start(
                        0,
                        RepoResolver.singleRepo(remote),
                        PackLimits.defaults(),
                        List.of(requireToken))) {
            // Without the token: rejected.
            try (var anonymous =
                    new PackSyncClient(
                            plaintextChannel(authed.port()), "", PackLimits.defaults())) {
                StatusRuntimeException e =
                        assertThrows(StatusRuntimeException.class, anonymous::refs);
                assertEquals(Status.Code.UNAUTHENTICATED, e.getStatus().getCode());
            }
            // With it: the full push round-trip works through the interceptor.
            Metadata token = new Metadata();
            token.put(tokenKey, "open-sesame");
            ManagedChannel channel =
                    ManagedChannelBuilder.forAddress("localhost", authed.port())
                            .usePlaintext()
                            .intercept(
                                    io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(token))
                            .build();
            try (var tokenClient = new PackSyncClient(channel, "", PackLimits.defaults())) {
                byte[] head = put(local, "k", "v");
                assertTrue(tokenClient.push(local, BRANCH).updated());
                assertArrayEquals(head, remote.getHeadHash(BRANCH).orElseThrow());
            }
        }
    }
}
