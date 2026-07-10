/*
 * Copyright 2026 Earasoft
 * Copyright 2021 Dolthub, Inc.
 *
 * Derived from Dolt's design, adapted for Java by Earasoft.
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

import com.dolthub.prolly.*;
import com.earasoft.prolly.gc.GcReachabilityContributor;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.rocksdb.RocksIterator;

/**
 *
 *
 * <h3>Merkle-tree garbage collector</h3>
 *
 * <p>Reclaims disk space by deleting orphaned nodes from the <code>NodeStore</code>. <b>Important
 * for New Team Members:</b>
 *
 * <p>Garbage collection uses a mark-and-sweep approach. The mark phase starts at every branch
 * head's <i>commit hash</i> (not the data-root hash) and walks the commit graph transitively,
 * marking each commit and the entire data tree it points at. The sweep then deletes any 20-byte
 * hash key not in the reachable set.
 *
 * <p><b>Concurrency:</b> {@link #collect()} is stop-the-world relative to commits — it acquires a
 * per-{@link Database} garbage-collection lock for the full duration of mark + sweep. Writers must
 * hold {@link Database#gcLock()} for read while advancing the manifest. This prevents the race
 * where a commit lands between the mark and sweep phases and is then swept while the manifest still
 * references it. For high-write workloads, consider an incremental / generational
 * garbage-collection scheme.
 *
 * <p><b>Reachability contract (ADR-0074):</b> the mark phase walks the branch-head commit graph +
 * each commit's data tree, then unions every registered {@link GcReachabilityContributor}'s claimed
 * closure. Any 20-byte chunk on neither is swept. A co-tenant substrate that stores roots outside
 * the commit graph (the upstream RDF4J Sail's {@code RootMetaTree} and its provenance / event-sink
 * / prefix / term-stats / namespace index roots — which {@link Commit} does not carry) is safe
 * <b>only when its contributor is registered</b>: running the no-contributor collector on such a
 * shared store sweeps those live structures. The wiring rule is checkable now, not an open hazard —
 * both arms pinned by {@code GcRootReachabilityTest}; history in
 * docs/write-ups/gc-concurrent-write-flush-window.md, Phase 3.
 *
 * <h4>Collaborators (dependencies)</h4>
 *
 * <ul>
 *   <li>{@link Database} {@code db} — for {@link Database#gcLock()} (the write side of the
 *       GC↔writer lock), {@code listBranches()} + {@code getHeadHash()} (the mark roots), and the
 *       commit graph.
 *   <li>{@link RocksNodeStore} {@code store} — read commit/chunk bytes during mark; iterate every
 *       key and {@code delete} the unreachable ones during sweep.
 *   <li>{@link ReachabilityWalker} — walks a data-tree root, collecting every reachable chunk hash.
 *   <li>{@link Commit#deserialize} — to follow each commit's parents + its data root.
 * </ul>
 */
public class GarbageCollector {
    private final Database db;
    private final RocksNodeStore store;
    private final java.util.List<GcReachabilityContributor> contributors;

    /** Engine-only stores: no co-tenant substrates, no contributors. */
    public GarbageCollector(Database db, RocksNodeStore store) {
        this(db, store, java.util.List.of());
    }

    /**
     * A store shared with co-tenant substrates: every substrate holding roots outside the engine
     * commit graph MUST be represented in {@code contributors} (ADR-0074 — an absent contributor
     * means that substrate's live chunks are swept).
     */
    public GarbageCollector(
            Database db,
            RocksNodeStore store,
            java.util.List<GcReachabilityContributor> contributors) {
        this.db = db;
        this.store = store;
        this.contributors = java.util.List.copyOf(contributors);
    }

    /**
     * Test-only seam (Step 22 of the upstream test-strategy plan, invariant R-4): a hook invoked
     * between the <i>mark</i> and <i>sweep</i> phases of {@link #collectLocked()} while the
     * garbage-collection write lock is held, so a test can deterministically interleave a
     * concurrent commit and probe the {@code gcLock} write/read exclusion. Default no-op; never
     * assigned in production.
     */
    volatile Runnable betweenMarkAndSweep = () -> {};

    /**
     * Run a full stop-the-world mark-and-sweep. Acquires {@link Database#gcLock()}'s <b>write</b>
     * lock for the entire duration so no commit can write chunks while {@link #collectLocked} is
     * deciding what to delete (the exclusive side of the garbage-collection↔writer contract — see
     * the class warning for what this does <i>not</i> protect: out-of-band roots).
     */
    public GcResult collect() {
        // Take the database-wide garbage-collection write lock so no commit can advance the
        // manifest between the mark and sweep phases.
        db.gcLock().writeLock().lock();
        try {
            return collectLocked();
        } finally {
            db.gcLock().writeLock().unlock();
        }
    }

    /**
     * Collection WITHOUT an engine {@link Database} — for a store whose only roots are co-tenant
     * substrates' (the production RDF-face repos: no engine branches exist, and constructing a
     * throwaway {@code Database} would write engine manifest rows into a production store for
     * nothing). The mark set is exactly the contributors' union.
     *
     * <p><b>Contract: the CALLER guarantees writer exclusion</b> for the full duration — there is
     * no {@code gcLock} here because there are no engine writers to coordinate with; the
     * substrate's own writer gate (e.g. the RDF Sail's single-writer lock) is the boundary
     * (ADR-0074's quiesce consequence, narrowed to writer-exclusion).
     */
    public static GcResult collectExclusive(
            RocksNodeStore store, java.util.List<GcReachabilityContributor> contributors) {
        Set<String> reachable = new HashSet<>();
        for (GcReachabilityContributor contributor : contributors) {
            reachable.addAll(contributor.reachableHexes(store));
        }
        return sweep(store, reachable);
    }

    /**
     * <b>Mark</b> (walk every branch head → its commit graph → each commit's data tree via {@link
     * ReachabilityWalker}, collecting reachable chunk hashes) then <b>sweep</b> (iterate the store
     * and {@code delete} every 20-byte hash key not in the reachable set). The {@link
     * #betweenMarkAndSweep} test seam fires between the two phases. Runs only with the
     * garbage-collection write lock held (via {@link #collect}).
     */
    private GcResult collectLocked() {
        ReachabilityWalker walker = new ReachabilityWalker(store);
        Set<String> reachableCommits = new HashSet<>();
        Deque<byte[]> commitQueue = new ArrayDeque<>();

        for (String branch : db.listBranches()) {
            db.getHeadHash(branch).ifPresent(commitQueue::add);
        }

        while (!commitQueue.isEmpty()) {
            byte[] commitHash = commitQueue.poll();
            if (commitHash == null) continue;
            if (!reachableCommits.add(toHex(commitHash))) continue;

            Optional<MemorySegment> data = store.read(commitHash);
            if (data.isEmpty()) continue;
            Commit commit = Commit.deserialize(data.get().toArray(ValueLayout.JAVA_BYTE));

            if (commit.getRootValueHash() != null) {
                walker.walk(commit.getRootValueHash());
            }
            for (byte[] parent : commit.getParents()) {
                if (parent != null) commitQueue.add(parent);
            }
        }

        Set<String> reachable = new HashSet<>(walker.getReachableHashes());
        reachable.addAll(reachableCommits);
        // ADR-0074: co-tenant substrates' claimed closures (called under the gc write lock).
        for (GcReachabilityContributor contributor : contributors) {
            reachable.addAll(contributor.reachableHexes(store));
        }

        betweenMarkAndSweep.run(); // test-only seam (R-4 Step 22); no-op in production

        return sweep(store, reachable);
    }

    /** Delete every unmarked 20-byte key (non-20-byte keys — manifests, meta rows — never). */
    private static GcResult sweep(RocksNodeStore store, Set<String> reachable) {
        int swept = 0;
        try (RocksIterator it = store.db().newIterator()) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] key = it.key();
                if (key.length == 20 && !reachable.contains(toHex(key))) {
                    store.db().delete(key);
                    swept++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new GcResult(reachable.size(), swept);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
