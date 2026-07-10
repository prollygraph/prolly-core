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
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server side of the pack-protocol wire: four RPCs over a {@link RepoResolver}, delegating all
 * pack semantics to {@link DatabasePackSync} (the transport adds framing, limits, and status
 * mapping — nothing else).
 *
 * @apiNote Error surface: unknown repo → {@code NOT_FOUND}; a pack over {@link PackLimits} → {@code
 *     RESOURCE_EXHAUSTED} (checked as frames accumulate, before parse); a corrupt pack (codec
 *     parse/content-address failure) → {@code INVALID_ARGUMENT}; a torn pack (head closure
 *     unreadable after staging — the ref never moves) → {@code FAILED_PRECONDITION}. A lost ref
 *     compare-and-set race is NOT an error: {@code updated=false} + the winner's value.
 * @implNote Each RPC holds its {@link RepoResolver.Lease} for exactly the request duration — {@code
 *     ReceivePack} acquires at the header frame and releases on completion/error, so a
 *     registry-backed host cannot evict-and-close the store mid-request.
 */
public final class PackSyncService extends PackSyncGrpc.PackSyncImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(PackSyncService.class);

    private final RepoResolver resolver;
    private final PackLimits limits;

    public PackSyncService(RepoResolver resolver, PackLimits limits) {
        this.resolver = resolver;
        this.limits = limits;
    }

    @Override
    public void advertiseRefs(
            AdvertiseRefsRequest request, StreamObserver<AdvertiseRefsResponse> out) {
        try (RepoResolver.Lease lease = resolve(request.getRepoId())) {
            Database db = lease.db();
            AdvertiseRefsResponse.Builder b = AdvertiseRefsResponse.newBuilder();
            for (String branch : db.listBranches()) {
                db.getHeadHash(branch).ifPresent(h -> b.putRefs(branch, ByteString.copyFrom(h)));
            }
            out.onNext(b.build());
            out.onCompleted();
        } catch (NoSuchElementException unknownRepo) {
            out.onError(notFound(request.getRepoId()));
        }
    }

    @Override
    public void fetchPack(FetchPackRequest request, StreamObserver<FetchPackFrame> out) {
        try (RepoResolver.Lease lease = resolve(request.getRepoId())) {
            Database db = lease.db();
            Set<String> haves = new HashSet<>();
            for (ByteString have : request.getHaveList()) {
                haves.add(HashUtils.toHex(have.toByteArray()));
            }
            DatabasePackSync.PackAndHead built =
                    DatabasePackSync.buildPack(db, request.getBranch(), haves);
            FetchPackHeader.Builder header = FetchPackHeader.newBuilder();
            built.head().ifPresent(h -> header.setHead(ByteString.copyFrom(h)));
            out.onNext(FetchPackFrame.newBuilder().setHeader(header).build());
            byte[] bytes = SyncPackCodec.serialize(built.pack());
            for (ByteString frame : PackFraming.slice(bytes)) {
                out.onNext(FetchPackFrame.newBuilder().setData(frame).build());
            }
            out.onCompleted();
        } catch (NoSuchElementException unknownRepo) {
            out.onError(notFound(request.getRepoId()));
        }
    }

    @Override
    public StreamObserver<ReceivePackFrame> receivePack(StreamObserver<ReceivePackResponse> out) {
        return new StreamObserver<>() {
            private RepoResolver.@Nullable Lease lease;
            private @Nullable ReceivePackHeader header;
            private final PackFraming.Accumulator acc = new PackFraming.Accumulator(limits);

            @Override
            public void onNext(ReceivePackFrame frame) {
                try {
                    if (frame.hasHeader()) {
                        if (header != null) {
                            throw Status.INVALID_ARGUMENT
                                    .withDescription("duplicate header frame")
                                    .asRuntimeException();
                        }
                        header = frame.getHeader();
                        lease = resolve(header.getRepoId());
                    } else {
                        if (header == null) {
                            throw Status.INVALID_ARGUMENT
                                    .withDescription("first frame must be the header")
                                    .asRuntimeException();
                        }
                        acc.append(frame.getData());
                    }
                } catch (NoSuchElementException unknownRepo) {
                    fail(notFound(header == null ? "?" : header.getRepoId()));
                } catch (PackFraming.PackTooLargeException tooLarge) {
                    fail(
                            Status.RESOURCE_EXHAUSTED
                                    .withDescription(tooLarge.getMessage())
                                    .asRuntimeException());
                } catch (RuntimeException e) {
                    fail(e);
                }
            }

            @Override
            public void onCompleted() {
                RepoResolver.Lease l = lease;
                ReceivePackHeader h = header;
                if (l == null || h == null) {
                    fail(
                            Status.INVALID_ARGUMENT
                                    .withDescription("stream completed before the header frame")
                                    .asRuntimeException());
                    return;
                }
                try {
                    SyncPack pack = parseWithLimits(acc.toByteArray(), limits);
                    byte[] newHead = h.getNewHead().toByteArray();
                    byte[] expectedOld =
                            h.getExpectedOld().isEmpty() ? null : h.getExpectedOld().toByteArray();
                    boolean updated =
                            DatabasePackSync.apply(
                                    l.db(), h.getBranch(), pack, newHead, expectedOld);
                    ByteString current =
                            l.db().getHeadHash(h.getBranch())
                                    .map(ByteString::copyFrom)
                                    .orElse(ByteString.EMPTY);
                    out.onNext(
                            ReceivePackResponse.newBuilder()
                                    .setUpdated(updated)
                                    .setCurrent(current)
                                    .build());
                    out.onCompleted();
                } catch (IllegalStateException torn) {
                    // the torn-pack hardening: the ref never moved
                    out.onError(
                            Status.FAILED_PRECONDITION
                                    .withDescription(torn.getMessage())
                                    .asRuntimeException());
                } catch (RuntimeException corrupt) {
                    out.onError(statusOf(corrupt));
                } finally {
                    l.close();
                    lease = null;
                }
            }

            @Override
            public void onError(Throwable t) {
                LOG.debug("receivePack client error: {}", t.toString());
                release();
            }

            private void fail(Throwable t) {
                release();
                out.onError(t instanceof io.grpc.StatusRuntimeException ? t : statusOf(t));
            }

            private void release() {
                RepoResolver.Lease l = lease;
                if (l != null) {
                    l.close();
                    lease = null;
                }
            }
        };
    }

    @Override
    public void compareAndSetRef(
            CompareAndSetRefRequest request, StreamObserver<CompareAndSetRefResponse> out) {
        try (RepoResolver.Lease lease = resolve(request.getRepoId())) {
            Database db = lease.db();
            byte[] newValue = request.getNewValue().toByteArray();
            byte[] expectedOld =
                    request.getExpectedOld().isEmpty()
                            ? null
                            : request.getExpectedOld().toByteArray();
            // An empty pack through the protocol's apply: verifies the target commit's closure
            // exists on THIS host (a ref may never point at bytes the host does not hold), then
            // compare-and-sets under the store's write lock.
            boolean updated =
                    DatabasePackSync.apply(
                            db,
                            request.getBranch(),
                            new SyncPack(List.of(), List.of()),
                            newValue,
                            expectedOld);
            ByteString current =
                    db.getHeadHash(request.getBranch())
                            .map(ByteString::copyFrom)
                            .orElse(ByteString.EMPTY);
            out.onNext(
                    CompareAndSetRefResponse.newBuilder()
                            .setUpdated(updated)
                            .setCurrent(current)
                            .build());
            out.onCompleted();
        } catch (NoSuchElementException unknownRepo) {
            out.onError(notFound(request.getRepoId()));
        } catch (IllegalStateException unknownCommit) {
            out.onError(
                    Status.FAILED_PRECONDITION
                            .withDescription(unknownCommit.getMessage())
                            .asRuntimeException());
        }
    }

    /** Parse under the chunk cap (bytes were capped during accumulation). */
    static SyncPack parseWithLimits(byte[] bytes, PackLimits limits) {
        SyncPack pack;
        try {
            pack = SyncPackCodec.parse(bytes);
        } catch (RuntimeException corrupt) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("corrupt pack: " + corrupt.getMessage())
                    .asRuntimeException();
        }
        if (pack.chunks().size() > limits.maxChunks()) {
            throw Status.RESOURCE_EXHAUSTED
                    .withDescription(
                            "pack carries "
                                    + pack.chunks().size()
                                    + " chunks (limit "
                                    + limits.maxChunks()
                                    + ")")
                    .asRuntimeException();
        }
        return pack;
    }

    private RepoResolver.Lease resolve(String repoId) {
        return resolver.resolve(repoId);
    }

    private static io.grpc.StatusRuntimeException notFound(String repoId) {
        return Status.NOT_FOUND
                .withDescription("unknown repo: '" + repoId + "'")
                .asRuntimeException();
    }

    private static io.grpc.StatusRuntimeException statusOf(Throwable t) {
        if (t instanceof io.grpc.StatusRuntimeException sre) {
            return sre;
        }
        return Status.INVALID_ARGUMENT
                .withDescription(String.valueOf(t.getMessage()))
                .asRuntimeException();
    }
}
