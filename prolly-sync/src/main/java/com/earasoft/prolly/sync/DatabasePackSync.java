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

import com.dolthub.prolly.Commit;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.Database;
import com.earasoft.prolly.gc.ChunkSet;
import com.earasoft.prolly.gc.PackedChunkSet;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The <b>{@code Database}-level sync source/sink</b> — the substrate-agnostic sibling of {@code
 * RepoSync} (the upstream document-store sync plan Steps 3–4, D-9). Where {@code RepoSync} is
 * upstream-store-coupled (its refs, {@code CommitLog}, {@code RootMetaTree}), this operates on any
 * {@link Database} — the JSON document store's substrate, and anything else Database-backed — using
 * only core types, which is why it lives in the sync package rather than a JSON module
 * (re-verifying finding #8: an upstream consumer module carries no dependency on this module, and
 * none is needed).
 *
 * @apiNote <b>The hash model is the resume's correctness invariant (finding #8b):</b> the commit
 *     closure walks the {@code Database} DAG keyed on <b>core {@link Commit}</b> hashes — the
 *     content address of {@code Commit.serialize()} bytes — never {@code CommitLog}'s RDF-style
 *     entry hashing, whose values differ (a mix-up misaligns want/have and silently ships a wrong
 *     pack). Commits ride the pack as ordinary chunks (ADR-0073: a commit is a content-addressed
 *     blob), so the receiver's head-commit hash is byte-for-byte the sender's — pinned by the
 *     round-trip HEAD-hash-equality test the deferral note demanded before any controller wiring.
 * @implNote <b>Finding #8c (two closure traps, both pinned by test):</b> the commit walk must
 *     follow ALL parents (breadth-first over {@link Commit#getParents()}) — {@code
 *     CommitWalk.firstParent}, the history projection's walker, would silently drop a merge
 *     commit's second-parent lineage from the pack. And chunk reachability enters via {@link
 *     DataTreeReachability#fromRoot}: an upstream face's {@code ChunkReachability.from} expects a
 *     {@code RootMetaTree}, which a plain {@code Database} commit's {@code rootValueHash} is not.
 *     <b>Collaborators:</b> {@link DataTreeReachability} (the shared tree DFS), {@link SyncPack} +
 *     {@link SyncPackCodec} (the wire — commits travel in the chunk list, {@code commits()} stays
 *     empty at this layer), {@code Database.receiveSyncPack} (the locked write→flush→CAS sink
 *     primitive).
 */
public final class DatabasePackSync {

    private DatabasePackSync() {}

    /**
     * Build the pack for advancing a remote that already holds {@code haveCommitHexes}: every
     * commit reachable from {@code branch}'s head but not from the haves (all-parents closure), as
     * chunk bytes — each new commit's blob plus its tree chunks, Merkle-pruned by everything
     * reachable from the have commits this store holds.
     *
     * @return the pack, and the head it advances to — empty pack + empty head for an empty branch
     */
    public static PackAndHead buildPack(Database src, String branch, Set<String> haveCommitHexes) {
        Optional<byte[]> head = src.getHeadHash(branch);
        if (head.isEmpty()) {
            return new PackAndHead(new SyncPack(List.of(), List.of()), Optional.empty());
        }
        NodeStore store = src.store();

        // 1. Commit closure — all-parents breadth-first search, core-Commit hashes (#8b, #8c).
        List<byte[]> newCommits = new ArrayList<>();
        Set<String> seen = new HashSet<>(haveCommitHexes);
        Deque<byte[]> frontier = new ArrayDeque<>();
        frontier.add(head.get());
        while (!frontier.isEmpty()) {
            byte[] h = frontier.poll();
            if (!seen.add(HashUtils.toHex(h))) {
                continue;
            }
            Commit c = readCommit(store, h);
            newCommits.add(h);
            frontier.addAll(c.getParents());
        }
        Collections.reverse(newCommits); // oldest-first — deterministic, parents before children

        // 2. Merkle prune: chunks reachable from the have commits we actually hold contribute
        //    nothing new (a have we do not hold simply cannot prune).
        ChunkSet covered = new PackedChunkSet();
        for (String haveHex : haveCommitHexes) {
            byte[] haveHash = HashUtils.fromHex(haveHex);
            store.read(haveHash)
                    .map(seg -> Commit.deserialize(seg.toArray(ValueLayout.JAVA_BYTE)))
                    .ifPresent(
                            c -> {
                                if (c.getRootValueHash() != null) {
                                    covered.addAll(
                                            DataTreeReachability.fromRoot(
                                                    store, c.getRootValueHash(), ChunkSet.EMPTY));
                                }
                            });
        }

        // 3. Chunks: each new commit's blob + its tree chunks, deduped across commits.
        LinkedHashMap<String, byte[]> chunks = new LinkedHashMap<>();
        for (byte[] h : newCommits) {
            chunks.put(HashUtils.toHex(h), readBytes(store, h));
            Commit c = readCommit(store, h);
            if (c.getRootValueHash() != null) {
                // fromRoot returns only what `covered` did not already prune, so the fresh set
                // IS the increment — union it back rather than re-deriving the increment from
                // chunks.keySet(). The keys that drops are the commit blobs put above, and a tree
                // walk cannot reach a commit hash, so excluding them was inert.
                ChunkSet fresh =
                        DataTreeReachability.fromRoot(store, c.getRootValueHash(), covered);
                fresh.forEach(
                        chunkHash -> {
                            String hex = HashUtils.toHex(chunkHash);
                            if (!chunks.containsKey(hex)) {
                                chunks.put(hex, readBytes(store, chunkHash));
                            }
                        });
                covered.addAll(fresh);
            }
        }
        return new PackAndHead(new SyncPack(new ArrayList<>(chunks.values()), List.of()), head);
    }

    /**
     * Apply a pack and advance the branch: stage the chunks, verify the head state is fully
     * readable (the Step-21 torn-pack hardening — see {@link #verifyHeadState}), then delegate to
     * the {@code Database}'s locked write→flush→compare-and-set sink primitive.
     *
     * @throws IllegalStateException on a torn pack — the ref never moves; the staged chunks are
     *     content-addressed and harmless, and a later complete pack heals the store idempotently
     */
    public static boolean apply(
            Database dst, String branch, SyncPack pack, byte[] newHead, byte[] expectedOldHead) {
        dst.receiveChunks(pack.chunks());
        verifyHeadState(dst, newHead, expectedOldHead);
        return dst.receiveSyncPack(pack.chunks(), branch, newHead, expectedOldHead);
    }

    /**
     * Post-stage, pre-compare-and-set verification (an upstream hardening pass, the torn-pack
     * hardening): before any ref may move, the <b>commit chain</b> from {@code newHead} down to
     * {@code expectedOldHead} (or to roots, for a create) must be readable, and the <b>head
     * commit's tree closure</b> must be complete in the receiver's store. The previous guard
     * checked only that the head commit BLOB was readable — a pack missing an interior tree chunk
     * or a mid-chain commit blob was silently accepted, publishing a ref whose reads fail later
     * (found by the S-9 torn-pack property, which drops one arbitrary chunk).
     *
     * @implNote Deliberately verified: the head state (what the ref promises reads today) + the
     *     history spine (what closure walks need). Deliberately NOT verified: ancestor commits'
     *     full tree closures — chunks only reachable from a replaced historical state; verifying
     *     them would walk the whole store per receive for something no head read needs. Historical
     *     reads of a mid-chain state remain the pack contract, not a receive-time guarantee.
     */
    static void verifyHeadState(Database dst, byte[] newHead, byte @Nullable [] expectedOldHead) {
        NodeStore store = dst.store();
        String stopHex = expectedOldHead == null ? null : HashUtils.toHex(expectedOldHead);
        Set<String> seen = new HashSet<>();
        Deque<byte[]> frontier = new ArrayDeque<>();
        frontier.add(newHead);
        while (!frontier.isEmpty()) {
            byte[] h = frontier.poll();
            String hex = HashUtils.toHex(h);
            if (hex.equals(stopHex) || !seen.add(hex)) {
                continue;
            }
            Commit c;
            try {
                c = readCommit(store, h);
            } catch (IllegalStateException missing) {
                throw new IllegalStateException(
                        "torn sync pack: commit " + hex + " is unreadable after staging", missing);
            }
            frontier.addAll(c.getParents());
        }
        Commit head = readCommit(store, newHead);
        if (head.getRootValueHash() != null) {
            try {
                DataTreeReachability.fromRoot(store, head.getRootValueHash(), ChunkSet.EMPTY);
            } catch (IllegalStateException missing) {
                throw new IllegalStateException(
                        "torn sync pack: the head state's tree closure is incomplete — "
                                + missing.getMessage(),
                        missing);
            }
        }
    }

    /**
     * <b>Pull-side</b> integration ({@code the upstream sync work} Step 7): apply a fetched pack
     * and advance the local branch to {@code remoteHead} — but <b>fast-forward only</b>. Unlike
     * {@link #apply} (the push sink, where the sender's compare-and-set lease is the guard), a
     * pull's expected-old-head is the local head itself, so a bare compare-and-set would silently
     * <b>clobber</b> local commits the remote never saw. This walks the remote lineage first —
     * through the pack's own chunks plus the local store — and refuses a divergent branch.
     *
     * @return the local head after integration: {@code remoteHead} on a fast-forward or create; the
     *     unchanged local head when the local branch is already at or ahead of the remote
     * @throws IllegalStateException when the branches have diverged (a real merge — per-doc
     *     conflict resolution lands with plan Step 9) or on a lost compare-and-set race
     */
    public static byte[] integrate(Database dst, String branch, SyncPack pack, byte[] remoteHead) {
        Optional<byte[]> local = dst.getHeadHash(branch);
        if (local.isEmpty()) {
            // New branch locally — a create. Stage + verify before the ref may move
            // (the same torn-pack hardening as apply()).
            dst.receiveChunks(pack.chunks());
            verifyHeadState(dst, remoteHead, null);
            if (!dst.receiveSyncPack(pack.chunks(), branch, remoteHead, null)) {
                throw new IllegalStateException(
                        "pull rejected: branch '" + branch + "' appeared concurrently — retry");
            }
            return remoteHead;
        }
        byte[] localHead = local.get();
        if (java.util.Arrays.equals(localHead, remoteHead)) {
            return localHead; // already up to date
        }
        // Index the pack's chunks by hash so the remote lineage is walkable
        // BEFORE anything lands in the local store.
        LinkedHashMap<String, byte[]> packByHash = new LinkedHashMap<>();
        for (byte[] chunk : pack.chunks()) {
            packByHash.put(HashUtils.toHex(HashUtils.hash(chunk)), chunk);
        }
        if (reaches(packByHash, dst.store(), remoteHead, localHead)) {
            // Fast-forward: the local head is an ancestor of the remote head.
            dst.receiveChunks(pack.chunks());
            verifyHeadState(dst, remoteHead, localHead);
            if (!dst.receiveSyncPack(pack.chunks(), branch, remoteHead, localHead)) {
                throw new IllegalStateException(
                        "pull rejected: branch '" + branch + "' moved concurrently — retry");
            }
            return remoteHead;
        }
        if (reaches(packByHash, dst.store(), localHead, remoteHead)) {
            return localHead; // local is ahead of the remote — nothing to integrate
        }
        throw new IllegalStateException(
                "pull rejected: branch '"
                        + branch
                        + "' has diverged from the remote — per-substrate conflict resolution"
                        + " lands with the upstream sync work; push from the other side or"
                        + " resolve manually for now");
    }

    /**
     * Whether {@code target} is reachable from {@code from} over the commit parent graph, reading
     * each commit from the pack's chunk index first, then the local store (a pack is pruned, so
     * older lineage lives only locally). An unreadable parent is a dead end, not an error.
     */
    private static boolean reaches(
            LinkedHashMap<String, byte[]> packByHash, NodeStore store, byte[] from, byte[] target) {
        String targetHex = HashUtils.toHex(target);
        Set<String> seen = new HashSet<>();
        Deque<byte[]> frontier = new ArrayDeque<>();
        frontier.add(from);
        while (!frontier.isEmpty()) {
            byte[] h = frontier.poll();
            String hex = HashUtils.toHex(h);
            if (hex.equals(targetHex)) {
                return true;
            }
            if (!seen.add(hex)) {
                continue;
            }
            byte[] bytes = packByHash.get(hex);
            if (bytes == null) {
                Optional<java.lang.foreign.MemorySegment> read = store.read(h);
                if (read.isEmpty()) {
                    continue; // lineage not held here — a dead end for this walk
                }
                bytes = read.get().toArray(ValueLayout.JAVA_BYTE);
            }
            frontier.addAll(Commit.deserialize(bytes).getParents());
        }
        return false;
    }

    /**
     * A merge base for {@code a} and {@code b} over the store's commit parent graph — the first
     * ancestor of {@code b} (breadth-first, all parents) that is also an ancestor of {@code a}.
     * Empty when the histories are unrelated.
     *
     * @apiNote On a criss-cross history this returns <b>one</b> of the multiple candidate bases
     *     (breadth-first order — a nearest one), the same documented degradation as {@code
     *     Database}'s own single-base selection; the conflict detector built on it may then
     *     over-report a conflict, never under-report.
     */
    public static Optional<byte[]> mergeBase(NodeStore store, byte[] a, byte[] b) {
        Set<String> ancestorsOfA = new HashSet<>();
        Deque<byte[]> frontier = new ArrayDeque<>();
        frontier.add(a);
        while (!frontier.isEmpty()) {
            byte[] h = frontier.poll();
            if (ancestorsOfA.add(HashUtils.toHex(h))) {
                store.read(h)
                        .map(seg -> Commit.deserialize(seg.toArray(ValueLayout.JAVA_BYTE)))
                        .ifPresent(c -> frontier.addAll(c.getParents()));
            }
        }
        Set<String> seen = new HashSet<>();
        frontier.add(b);
        while (!frontier.isEmpty()) {
            byte[] h = frontier.poll();
            if (ancestorsOfA.contains(HashUtils.toHex(h))) {
                return Optional.of(h);
            }
            if (seen.add(HashUtils.toHex(h))) {
                store.read(h)
                        .map(seg -> Commit.deserialize(seg.toArray(ValueLayout.JAVA_BYTE)))
                        .ifPresent(c -> frontier.addAll(c.getParents()));
            }
        }
        return Optional.empty();
    }

    /** A built pack plus the head commit it advances the receiver to. */
    public record PackAndHead(SyncPack pack, Optional<byte[]> head) {}

    private static Commit readCommit(NodeStore store, byte[] hash) {
        return Commit.deserialize(readBytes(store, hash));
    }

    private static byte[] readBytes(NodeStore store, byte[] hash) {
        return store.read(hash)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "chunk missing from store: " + HashUtils.toHex(hash)))
                .toArray(ValueLayout.JAVA_BYTE);
    }
}
