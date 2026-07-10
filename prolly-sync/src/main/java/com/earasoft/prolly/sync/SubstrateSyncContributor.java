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

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A substrate's sync half, plugged into the shared {@code /sync} surface (D-1 of {@code the
 * upstream document-store sync plan.md}: one sync operation covers every substrate — no separate
 * {@code /sync-json/} path for operators to forget). The RDF substrate is NOT a contributor — it
 * keeps its operative {@code RepoSync}/{@code CommitLog} pipeline; contributors are the {@code
 * Database}-level substrates (today: {@code "json"}) whose packs are pure content-addressed chunk
 * sets with the commits riding as ordinary chunks (ADR-0073, finding #8).
 *
 * @apiNote Substrate routing lives on the <b>endpoint surface</b> ({@code ?substrate=...}), not in
 *     the pack bytes — a {@code Database}-substrate pack carries no codec commit entries to tag
 *     (plan Step 2's resolution), so the D-2 per-commit substrate tag has nothing to attach to. An
 *     unknown {@code ?substrate=} value rejects with a clear error at the endpoint, preserving
 *     D-2's extensibility property (a future {@code bom} substrate adds a contributor, not a wire
 *     change). {@code storageKey} is the platform repo identity ({@code "default"}, {@code
 *     "{repo}"}, or {@code "{org}/{repo}"}).
 * @implNote Implementations resolve their own per-repo store (the JSON face's registry lives in an
 *     upstream consumer module; the implementation bridges it from the upstream composition root —
 *     keeping consumers decoupled, the boundary the upstream extraction plan preserves).
 *     <b>Collaborators:</b> {@link DatabasePackSync} (the pack build/apply primitives); {@link
 *     SyncPack} (the payload). <b>Dependents:</b> the inbound {@code SyncController} (apply) +
 *     outbound {@code SyncControlController} (build) in an upstream server module.
 */
public interface SubstrateSyncContributor {

    /** The substrate this contributes, e.g. {@code "json"} — the {@code ?substrate=} token. */
    String substrate();

    /** Whether the substrate's store actually exists for {@code storageKey} on this server. */
    boolean available(String storageKey);

    /** The substrate's current head for {@code branch}, or empty if the branch doesn't exist. */
    Optional<byte[]> head(String storageKey, String branch) throws IOException;

    /** All of the substrate's branches for {@code storageKey}: branch name → head commit hash. */
    java.util.Map<String, byte[]> refs(String storageKey) throws IOException;

    /**
     * Build the outbound pack: everything reachable from {@code branch}'s head minus what {@code
     * haveCommitHexes} already covers (Merkle-pruned).
     */
    DatabasePackSync.PackAndHead buildPack(
            String storageKey, String branch, Set<String> haveCommitHexes) throws IOException;

    /**
     * Apply an inbound pack and compare-and-set the substrate's branch ref to {@code newHead}.
     *
     * @return false when the compare-and-set lost (the ref moved concurrently) — chunks stay,
     *     harmless; the ref never clobbers
     * @throws IllegalStateException on a torn pack ({@code newHead} unreadable after the writes)
     */
    boolean apply(
            String storageKey,
            String branch,
            SyncPack pack,
            byte[] newHead,
            byte @Nullable [] expectedOldHead)
            throws IOException;

    /**
     * Pull-side integration: apply a fetched pack and advance the local branch to {@code
     * remoteHead} — <b>fast-forward only</b> (see {@link DatabasePackSync#integrate}).
     *
     * @return the local head after integration (unchanged when already at or ahead of the remote)
     * @throws IllegalStateException when the branches diverged (conflict resolution is plan Step 9)
     *     or on a lost compare-and-set race
     */
    byte[] integrate(String storageKey, String branch, SyncPack pack, byte[] remoteHead)
            throws IOException;

    /**
     * The substrate-level conflict report for a diverged pull (plan Step 9, D-3): stage the pack's
     * chunks (no ref moves), find the merge base, and surface every conflicting unit the
     * substrate's own 3-way merge cannot auto-resolve. An empty list means everything auto-resolves
     * — the sync is blocked only on landing the merge (plan Step 10), not on human decisions.
     *
     * @throws IllegalStateException when the local and remote histories are unrelated (no merge
     *     base)
     */
    java.util.List<SubstrateConflict> detectConflicts(
            String storageKey, String branch, SyncPack pack, byte[] remoteHead) throws IOException;

    /**
     * Complete a blocked pull (plan Step 10): integrate {@code remoteHead} into the local branch,
     * applying the substrate's own 3-way merge with {@code decisions} answering every conflict
     * {@link #detectConflicts} reported. The result is local merge commit(s) carrying the remote
     * head as a parent — so the next pull no-ops and the next push fast-forwards the remote.
     *
     * @return the local head after the merge landed
     * @throws IllegalStateException when a conflict has no matching decision (nothing lands — the
     *     sync stays blocked), or when the histories are unrelated
     */
    byte[] resolve(
            String storageKey,
            String branch,
            SyncPack pack,
            byte[] remoteHead,
            java.util.List<Resolution> decisions)
            throws IOException;

    /**
     * One human decision for a conflicting unit: keep {@code ours} or take {@code theirs}. The
     * coordinates match a {@link SubstrateConflict} row ({@code collection}/{@code docId}/{@code
     * pointer} for the JSON substrate). A decision that matches no live conflict is ignored — the
     * report is recomputed at resolve time (blocked syncs are stateless) and a conflict may have
     * auto-resolved in the meantime.
     */
    record Resolution(String collection, String docId, String pointer, String choose) {}

    /**
     * One conflicting unit in a blocked sync — the coordinator's unified list carries these across
     * substrates (D-3: substrate + identifier columns; resolution stays substrate-specific). The
     * value fields are <b>display renderings</b> (substrate-owned formatting, e.g. canonical JSON
     * leaf text), not wire bytes; {@code null} = absent on that side.
     */
    record SubstrateConflict(
            String substrate,
            @Nullable String collection,
            @Nullable String docId,
            @Nullable String pointer,
            @Nullable String base,
            @Nullable String ours,
            @Nullable String theirs,
            @Nullable String detail) {}
}
