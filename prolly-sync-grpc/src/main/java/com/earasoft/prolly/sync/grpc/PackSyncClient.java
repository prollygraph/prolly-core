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

import com.dolthub.prolly.HashUtils;
import com.earasoft.prolly.Database;
import com.earasoft.prolly.sync.DatabasePackSync;
import com.earasoft.prolly.sync.SyncPack;
import com.earasoft.prolly.sync.SyncPackCodec;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The client side of the pack-protocol wire: raw RPC wrappers ({@link #refs}, {@link #fetch},
 * {@link #send}, {@link #casRef}) plus the two-call choreography ({@link #push}, {@link #pull})
 * that moves a branch between a local {@link Database} and a remote host.
 *
 * @apiNote The client enforces the same {@link PackLimits} on what it RECEIVES — a hostile server
 *     is untrusted input too. Auth rides on the channel the caller builds (TLS, call credentials,
 *     interceptors); this class takes the channel as given and owns only its shutdown when
 *     constructed via {@link #PackSyncClient(ManagedChannel, String, PackLimits)}.
 * @implNote Push = refs → {@code buildPack(local, branch, remoteRefs)} → streamed ReceivePack
 *     (which applies + compare-and-sets on the host in one call). Pull = streamed FetchPack →
 *     {@code DatabasePackSync.integrate} (fast-forward or create; a DIVERGED branch throws {@link
 *     IllegalStateException} exactly like the in-process protocol — merge resolution is the
 *     caller's, {@code DatabasePackSync.mergeBase} in hand).
 */
public final class PackSyncClient implements AutoCloseable {

    private final ManagedChannel channel;
    private final PackSyncGrpc.PackSyncBlockingStub blocking;
    private final PackSyncGrpc.PackSyncStub async;
    private final String repoId;
    private final PackLimits limits;

    /**
     * @param channel the caller-built channel (credentials/TLS are the caller's) — owned: closed on
     *     {@link #close()}
     * @param repoId the remote repo id; the empty string for single-repo hosts
     */
    public PackSyncClient(ManagedChannel channel, String repoId, PackLimits limits) {
        this.channel = channel;
        this.blocking = PackSyncGrpc.newBlockingStub(channel);
        this.async = PackSyncGrpc.newStub(channel);
        this.repoId = repoId;
        this.limits = limits;
    }

    /** The remote's branches: name → 20-byte commit hash. */
    public Map<String, byte[]> refs() {
        AdvertiseRefsResponse resp =
                blocking.advertiseRefs(AdvertiseRefsRequest.newBuilder().setRepoId(repoId).build());
        Map<String, byte[]> refs = new HashMap<>();
        resp.getRefsMap().forEach((branch, hash) -> refs.put(branch, hash.toByteArray()));
        return refs;
    }

    /**
     * The streamed FetchPack reassembled: the remote head + the pack advancing past {@code haves}.
     */
    public FetchResult fetch(String branch, Set<byte[]> haves) {
        FetchPackRequest.Builder req =
                FetchPackRequest.newBuilder().setRepoId(repoId).setBranch(branch);
        for (byte[] have : haves) {
            req.addHave(ByteString.copyFrom(have));
        }
        Iterator<FetchPackFrame> frames = blocking.fetchPack(req.build());
        if (!frames.hasNext()) {
            throw new IllegalStateException("empty FetchPack stream: missing header frame");
        }
        FetchPackFrame first = frames.next();
        if (!first.hasHeader()) {
            throw new IllegalStateException("first FetchPack frame is not the header");
        }
        Optional<byte[]> head =
                first.getHeader().getHead().isEmpty()
                        ? Optional.empty()
                        : Optional.of(first.getHeader().getHead().toByteArray());
        PackFraming.Accumulator acc = new PackFraming.Accumulator(limits);
        while (frames.hasNext()) {
            acc.append(frames.next().getData());
        }
        SyncPack pack = PackSyncService.parseWithLimits(acc.toByteArray(), limits);
        return new FetchResult(head, pack);
    }

    /** Stream a pack + ref move to the remote (the server's full apply). */
    public RefUpdate send(
            String branch, SyncPack pack, byte[] newHead, byte @Nullable [] expectedOld) {
        AtomicReference<ReceivePackResponse> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ReceivePackFrame> req =
                async.receivePack(
                        new StreamObserver<>() {
                            @Override
                            public void onNext(ReceivePackResponse r) {
                                result.set(r);
                            }

                            @Override
                            public void onError(Throwable t) {
                                error.set(t);
                                done.countDown();
                            }

                            @Override
                            public void onCompleted() {
                                done.countDown();
                            }
                        });
        try {
            ReceivePackHeader.Builder header =
                    ReceivePackHeader.newBuilder()
                            .setRepoId(repoId)
                            .setBranch(branch)
                            .setNewHead(ByteString.copyFrom(newHead));
            if (expectedOld != null) {
                header.setExpectedOld(ByteString.copyFrom(expectedOld));
            }
            req.onNext(ReceivePackFrame.newBuilder().setHeader(header).build());
            for (ByteString frame : PackFraming.slice(SyncPackCodec.serialize(pack))) {
                req.onNext(ReceivePackFrame.newBuilder().setData(frame).build());
            }
            req.onCompleted();
        } catch (RuntimeException e) {
            req.onError(e);
            throw e;
        }
        awaitUninterruptibly(done);
        if (error.get() != null) {
            throw asRuntime(error.get());
        }
        ReceivePackResponse r = result.get();
        return new RefUpdate(r.getUpdated(), r.getCurrent().toByteArray());
    }

    /** Compare-and-set a remote ref (the target commit must already exist on the host). */
    public RefUpdate casRef(String branch, byte @Nullable [] expectedOld, byte[] newValue) {
        CompareAndSetRefRequest.Builder req =
                CompareAndSetRefRequest.newBuilder()
                        .setRepoId(repoId)
                        .setBranch(branch)
                        .setNewValue(ByteString.copyFrom(newValue));
        if (expectedOld != null) {
            req.setExpectedOld(ByteString.copyFrom(expectedOld));
        }
        CompareAndSetRefResponse resp = blocking.compareAndSetRef(req.build());
        return new RefUpdate(resp.getUpdated(), resp.getCurrent().toByteArray());
    }

    /**
     * Push {@code branch} from {@code local} to the remote: build the pack against everything the
     * remote advertises, stream it, and move the remote ref in the same call.
     *
     * @return {@code upToDate} when the remote already has the local head; otherwise the ref
     *     update's outcome ({@code updated=false} = lost race — rebuild against fresh refs + retry)
     */
    public PushResult push(Database local, String branch) {
        byte[] localHead =
                local.getHeadHash(branch)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "local branch '" + branch + "' does not exist"));
        Map<String, byte[]> remoteRefs = refs();
        byte[] remoteHead = remoteRefs.get(branch);
        if (remoteHead != null && java.util.Arrays.equals(remoteHead, localHead)) {
            return new PushResult(true, false, localHead);
        }
        Set<String> haves = new HashSet<>();
        for (byte[] h : remoteRefs.values()) {
            haves.add(HashUtils.toHex(h));
        }
        DatabasePackSync.PackAndHead built = DatabasePackSync.buildPack(local, branch, haves);
        RefUpdate update = send(branch, built.pack(), localHead, remoteHead);
        return new PushResult(false, update.updated(), update.current());
    }

    /**
     * Pull {@code branch} from the remote into {@code local}: fetch the pack past the local head,
     * then fast-forward/create via {@code DatabasePackSync.integrate}.
     *
     * @return the local head after integration; empty when the remote branch does not exist
     * @throws IllegalStateException when the branches have DIVERGED (same as the in-process
     *     protocol — resolve with {@code DatabasePackSync.mergeBase} + a merge, then push)
     */
    public Optional<byte[]> pull(Database local, String branch) {
        Set<byte[]> haves = new HashSet<>();
        local.getHeadHash(branch).ifPresent(haves::add);
        FetchResult fetched = fetch(branch, haves);
        if (fetched.head().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                DatabasePackSync.integrate(local, branch, fetched.pack(), fetched.head().get()));
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(10, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static RuntimeException asRuntime(Throwable t) {
        return t instanceof RuntimeException re ? re : new RuntimeException(t);
    }

    /** A reassembled fetch: the remote head (empty = no such branch) + the pack. */
    public record FetchResult(Optional<byte[]> head, SyncPack pack) {}

    /** A ref move's outcome; {@code current} is the branch value after the operation. */
    public record RefUpdate(boolean updated, byte[] current) {}

    /**
     * A push's outcome. {@code upToDate} = nothing to send; otherwise {@code updated} is the ref
     * move's result and {@code current} the remote value after it (the winner's on a lost race).
     */
    public record PushResult(boolean upToDate, boolean updated, byte[] current) {}
}
