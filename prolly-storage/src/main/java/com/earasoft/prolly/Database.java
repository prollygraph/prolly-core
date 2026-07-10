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
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Versioned store over a content-addressed chunk store: the commit graph + branch refs.
 *
 * <p>The prolly tree ({@link com.dolthub.prolly.StaticMap}) is a single immutable,
 * content-addressed map with no notion of history, branches, or "the current value". {@code
 * Database} adds commits, branches, merge, cherryPick/revert, blame/bisect, and garbage collection
 * over it — git's object database + refs, in miniature, for the layers above.
 *
 * @apiNote Commits are <b>optimistic</b>: pass the expected parent hash; a commit that lost the
 *     race returns {@code false} and must rebuild on the new head. Pure reads take <b>no lock</b> —
 *     a connection pins an immutable root and is unaffected by concurrent writers (snapshot
 *     isolation for free). Under a concurrent {@link GarbageCollector}, prefer {@link
 *     #commit(String, com.dolthub.prolly.MutableMap, byte[], String, String)} over {@code
 *     commit(branch, mm.flush(), …)} — it flushes the chunks under the garbage-collection read
 *     lock, closing a data-loss window.
 * @implNote <b>Two conflicts, two mechanisms.</b> <i>writer vs. writer</i> → an optimistic
 *     compare-and-set on the manifest ref ({@link Manifest#updateRef}; linearizability proven by
 *     {@code DatabaseCommitOccTest}). <i>writer vs. GC</i> → the {@link #gcLock} {@code
 *     ReentrantReadWriteLock} (commits hold the read lock across the whole tree-build + commit;
 *     {@link GarbageCollector} holds the write lock across mark+sweep).
 *     <p><b>Collaborators:</b> {@link NodeStore} {@code store} (chunks, commit objects, roots — and
 *     what garbage collection sweeps); {@link Manifest}/{@link RocksManifest} (the branch→commit
 *     ref table + the compare-and-set); {@link com.dolthub.prolly.Commit} (the commit-graph node);
 *     {@link MergeEngine} / {@link DiffEngine} / {@link com.dolthub.prolly.TreeMutator} (build the
 *     new tree for merge / cherryPick / revert — why those hold the read lock); {@link
 *     com.dolthub.prolly.StaticMap} / {@link com.dolthub.prolly.MutableMap} (committed vs.
 *     in-progress roots); {@link GarbageCollector} (the only other {@code gcLock} holder).
 *     <p><b>Dependents:</b> the commit-graph tests, tools, and sync layer — but <i>not</i> the
 *     upstream RDF4J Sail, which advances its own roots via a separate path.
 *     <p><b>Deep dives (upstream docs):</b> the read-write-locks and concurrency-model explainers +
 *     docs/write-ups/gc-concurrent-write-flush-window.md (the gcLock + the flush-before-lock bug;
 *     the reader-side snapshot model).
 * @see GarbageCollector
 * @see MergeEngine
 */
public class Database implements AutoCloseable {
    private final NodeStore store;
    private final Manifest manifest;
    private final TupleDescriptor descriptor;
    private final DirectBufferPool pool;
    private final String repoId;

    /**
     * Garbage-collection vs. commit coordination — readers (commits) take the read lock; garbage
     * collection takes the write lock.
     */
    private final ReentrantReadWriteLock gcLock = new ReentrantReadWriteLock();

    /**
     * Returns the GC coordination lock so {@link GarbageCollector} can stop commits during
     * mark+sweep.
     */
    public ReentrantReadWriteLock gcLock() {
        return gcLock;
    }

    private static final String HEAD_PREFIX = "heads/";
    private static final String TAG_PREFIX = "tags/";

    /**
     * Convenience constructor: derives a {@link RocksManifest} from the wrapped RocksDB store. The
     * store may be wrapped in a chain of {@code NodeStore} decorators ({@link
     * com.earasoft.prolly.monitor.MetricsNodeStore}, {@link IntegrityVerifyingNodeStore}, etc.) —
     * we unwrap until we find the RocksDB-backed leaf so the manifest can co-locate with the
     * chunks.
     */
    public Database(
            NodeStore store, String repoId, TupleDescriptor descriptor, DirectBufferPool pool) {
        this(store, deriveManifest(store), repoId, descriptor, pool);
    }

    /**
     * Full constructor: lets callers provide their own {@link Manifest} — useful for testing,
     * alternative storage backends, or when the {@code NodeStore} chain hides the RocksDB instance
     * behind a decorator that {@link #deriveManifest(NodeStore)} cannot unwrap.
     */
    public Database(
            NodeStore store,
            Manifest manifest,
            String repoId,
            TupleDescriptor descriptor,
            DirectBufferPool pool) {
        this.store = store;
        this.manifest = manifest;
        this.repoId = repoId;
        this.descriptor = descriptor;
        this.pool = pool;
    }

    /** Flushes the underlying RocksDB store if available; no-op for other backends. */
    private void flushDurable() {
        NodeStore base = store;
        while (true) {
            if (base instanceof com.earasoft.prolly.monitor.MetricsNodeStore ms) {
                base = ms.unwrap();
                continue;
            }
            if (base instanceof IntegrityVerifyingNodeStore ivs) {
                base = ivs.unwrap();
                continue;
            }
            break;
        }
        if (base instanceof RocksNodeStore rs) rs.flushDurable();
    }

    private static Manifest deriveManifest(NodeStore store) {
        NodeStore baseStore = store;
        // Walk through known decorators to find the RocksDB-backed leaf.
        while (true) {
            if (baseStore instanceof com.earasoft.prolly.monitor.MetricsNodeStore ms) {
                baseStore = ms.unwrap();
                continue;
            }
            if (baseStore instanceof IntegrityVerifyingNodeStore ivs) {
                baseStore = ivs.unwrap();
                continue;
            }
            break;
        }
        if (baseStore instanceof RocksNodeStore rs) {
            return new RocksManifest(rs.db());
        }
        throw new UnsupportedOperationException(
                "Database needs an injected Manifest when the NodeStore is not RocksDB-backed");
    }

    public NodeStore store() {
        return store;
    }

    public DirectBufferPool pool() {
        return pool;
    }

    public TupleDescriptor descriptor() {
        return descriptor;
    }

    public Optional<byte[]> getHeadHash(String branchName) {
        return manifest.getRef(repoId, HEAD_PREFIX + branchName);
    }

    public @Nullable Commit getHead(String branchName) {
        return getHeadHash(branchName)
                .flatMap(h -> store.read(h))
                .map(
                        seg ->
                                Commit.deserialize(
                                        seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)))
                .orElse(null);
    }

    public StaticMap getBranch(String name) {
        Commit head = getHead(name);
        // head == null: branch never committed. rootValueHash == null: the
        // head commit has an empty data tree (e.g. every row was deleted).
        // Both cases are an empty StaticMap — and guarding the null root here
        // avoids a store.read(null).
        if (head == null || head.getRootValueHash() == null) {
            return new StaticMap(store, null, descriptor);
        }
        Optional<java.lang.foreign.MemorySegment> data = store.read(head.getRootValueHash());
        if (data.isEmpty()) return new StaticMap(store, null, descriptor);
        Node root = Node.fromBytes(data.get());
        return new StaticMap(store, root, descriptor);
    }

    /**
     * Root {@link Node} of a commit's data tree, or {@code null} when the commit has an empty tree
     * (no root). Safe for empty-tree commits — it never calls {@code store.read(null)}.
     */
    private @Nullable Node rootNode(@Nullable Commit c) {
        if (c == null) return null;
        byte[] h = c.getRootValueHash();
        return h == null ? null : store.read(h).map(Node::fromBytes).orElse(null);
    }

    /**
     * Commit an <b>already-flushed</b> tree root to {@code branch} — optimistic: succeeds (returns
     * {@code true}) only if the branch head still equals {@code expectedParentHash}, else returns
     * {@code false} (the caller lost the race and must rebuild on the new head). Delegates to
     * {@link #commitInternal}.
     *
     * <p><b>Garbage-collection caveat (why a second overload exists):</b> {@code next}'s chunks
     * were written by {@code flush()} <i>before</i> this call — outside the {@link #gcLock}. If a
     * concurrent {@link GarbageCollector} may run, prefer {@link #commit(String, MutableMap,
     * byte[], String, String)} (which flushes under the lock), or hold the read lock yourself
     * around the flush. This {@code StaticMap} form is kept for the optimistic-concurrency tests,
     * which deliberately pre-flush competing maps.
     */
    public boolean commit(
            String branch,
            StaticMap next,
            byte @Nullable [] expectedParentHash,
            String author,
            String message) {
        requireCommitArgs(branch, next);
        List<byte[]> parents = expectedParentHash == null ? List.of() : List.of(expectedParentHash);
        return commitInternal(branch, next, parents, author, message);
    }

    /**
     * Multi-parent variant of {@link #commit(String, StaticMap, byte[], String, String)}
     * (already-flushed form; same garbage-collection caveat — prefer the {@link MutableMap}
     * overload under a concurrent garbage collector).
     */
    public boolean commitMerge(
            String branch, StaticMap next, List<byte[]> parents, String author, String message) {
        requireCommitArgs(branch, next);
        return commitInternal(branch, next, parents, author, message);
    }

    /**
     * garbage-collection-safe commit: flushes {@code mutations} to durable chunks <b>and</b>
     * advances the manifest under one hold of the garbage-collection read lock, so a concurrent
     * {@link GarbageCollector} cannot sweep the freshly-written chunks between flush and commit.
     *
     * <p><b>Prefer this over {@code commit(branch, mm.flush(), …)}.</b> Passing an already-flushed
     * {@link StaticMap} writes its chunks <i>outside</i> this lock — a concurrent garbage collector
     * mark→sweep can then delete a multi-level tree's interior/leaf chunks, leaving the committed
     * root pointing at a missing child (silent data loss). See the upstream flush-window bug
     * write-up.
     */
    public boolean commit(
            String branch,
            MutableMap mutations,
            byte @Nullable [] expectedParentHash,
            String author,
            String message) {
        requireCommitArgs(branch, mutations);
        List<byte[]> parents = expectedParentHash == null ? List.of() : List.of(expectedParentHash);
        return commitInternalFlushing(branch, mutations, parents, author, message);
    }

    /**
     * garbage-collection-safe merge commit — see {@link #commit(String, MutableMap, byte[], String,
     * String)}.
     */
    public boolean commitMerge(
            String branch,
            MutableMap mutations,
            List<byte[]> parents,
            String author,
            String message) {
        requireCommitArgs(branch, mutations);
        return commitInternalFlushing(branch, mutations, parents, author, message);
    }

    /**
     * Fail-fast guard for the public commit entry points: a null {@code branch} or null {@code
     * tree} would otherwise surface as a deep {@link NullPointerException} inside the commit body
     * (ref lookup / tree walk) far from the call site. {@code expectedParentHash}/{@code parents}
     * may legitimately be null/empty (no parent — the first commit), and {@code author}/{@code
     * message} are metadata, so they are not guarded here. Bad input is an {@link
     * IllegalArgumentException} per the engine error taxonomy (core-error-taxonomy-and-failpaths
     * D-1).
     */
    private static void requireCommitArgs(String branch, Object tree) {
        if (branch == null) throw new IllegalArgumentException("branch must not be null");
        if (tree == null) throw new IllegalArgumentException("commit tree must not be null");
    }

    private boolean commitInternalFlushing(
            String branch,
            MutableMap mutations,
            List<byte[]> parents,
            String author,
            String message) {
        // Take the garbage-collection read lock BEFORE flush so the chunk writes (flush) and the
        // manifest
        // update happen in one read-locked span, mutually exclusive with the garbage collector's
        // write lock.
        // commitInternal re-acquires the read lock reentrantly (same thread) — safe.
        gcLock.readLock().lock();
        try {
            StaticMap next = mutations.flush();
            return commitInternal(branch, next, parents, author, message);
        } finally {
            gcLock.readLock().unlock();
        }
    }

    /**
     * The shared commit body. Under the {@link #gcLock} read lock: (1) re-check the branch head
     * equals the expected parent — the optimistic-concurrency precondition; (2) {@code store.write}
     * the tree root + the serialized {@link com.dolthub.prolly.Commit}; (3) {@code flushDurable()}
     * so chunks are durable <i>before</i> the ref moves (no manifest entry ever points at
     * volatile-only chunks); (4) {@link Manifest#updateRef} — the compare-and-set on {@code
     * expectedParent} that serializes writer-vs-writer. The read lock is reentrant, so callers
     * already holding it (the {@link MutableMap} overloads, {@link #merge}/{@link
     * #cherryPick}/{@link #revert}) re-enter without deadlock.
     */
    private boolean commitInternal(
            String branch, StaticMap next, List<byte[]> parents, String author, String message) {
        // Hold the garbage-collection read lock from the first node write through the manifest
        // update so a
        // concurrent garbage collector cannot mark-and-sweep between us writing chunks and updating
        // the manifest.
        gcLock.readLock().lock();
        try {
            Optional<byte[]> currentHeadHash = getHeadHash(branch);
            byte[] mainParent = parents.isEmpty() ? null : parents.get(0);

            if (mainParent == null) {
                if (currentHeadHash.isPresent()) return false;
            } else {
                if (currentHeadHash.isEmpty()
                        || !Arrays.equals(currentHeadHash.get(), mainParent)) {
                    return false;
                }
            }

            byte[] rootValueHash = next.root() != null ? store.write(next.root().segment()) : null;
            Commit newCommit =
                    new Commit(
                            rootValueHash, parents, author, message, Instant.now().toEpochMilli());
            byte[] newCommitHash = store.write(newCommit.serialize());

            // Durably persist all chunk + commit writes BEFORE advancing the manifest.
            // Otherwise a power failure can leave the manifest pointing at a commit hash
            // whose underlying chunks survive only in volatile RocksDB memory.
            flushDurable();

            return manifest.updateRef(repoId, HEAD_PREFIX + branch, newCommitHash, mainParent);
        } finally {
            gcLock.readLock().unlock();
        }
    }

    /**
     * Three-way merge of {@code theirs} into {@code ours}: find the merge base via {@link
     * #findLCA}, build the merged tree with {@link MergeEngine}, and — only if conflict-free —
     * {@link #commitMerge} it onto {@code ours} with both heads as parents (a conflicted merge is
     * side-effect-free; inspect {@link MergeEngine.MergeResult#conflicts()}). The whole
     * build+commit is held under the {@link #gcLock} read lock because {@code MergeEngine} writes
     * the merged tree's chunks to {@code store} — the same flush-before-lock window as a data
     * commit.
     */
    public MergeEngine.MergeResult merge(
            String ours, String theirs, String author, String message) {
        byte[] ourCommitHash = getHeadHash(ours).orElse(null);
        byte[] theirCommitHash = getHeadHash(theirs).orElse(null);

        Commit ourHead = getHead(ours);
        Commit theirHead = getHead(theirs);

        Node ourNode = (ourHead != null) ? rootNode(ourHead) : null;
        Node theirNode = (theirHead != null) ? rootNode(theirHead) : null;

        // Hold the garbage-collection read lock across the merge-base build, the merge-tree build,
        // AND the commit: both the `--recursive` virtual base and the final merge write chunks to
        // the
        // store, so the same flush-before-lock window as the data commit applies
        // (docs/write-ups/gc-concurrent-write-flush-window.md). commitMerge re-locks reentrantly.
        gcLock.readLock().lock();
        try {
            // Merge-base (ADR-0070): a single base for linear/diamond; a `--recursive` VIRTUAL base
            // for a criss-cross (two minimal common ancestors). When the two minimal ancestors
            // disagree on a key, the virtual-base merge conflicts on it — that key is "contested":
            // if
            // our and their sides also disagree on it, the outer merge must SURFACE a conflict
            // rather
            // than silently auto-resolve by which base happened to be picked (the silent divergence
            // Step 1 measured).
            Node ancestorNode = null;
            List<MergeEngine.Conflict> contested = List.of();
            if (ourHead != null && theirHead != null) {
                List<byte[]> minimal = minimalCommonAncestors(ourCommitHash, theirCommitHash);
                if (minimal.size() == 2) {
                    byte[] a1 = minimal.get(0);
                    byte[] a2 = minimal.get(1);
                    MergeEngine baseEngine = new MergeEngine(store, descriptor, pool);
                    MergeEngine.MergeResult vb =
                            baseEngine.merge(
                                    mergeBaseNode(a1, a2),
                                    rootNode(loadCommit(a1)),
                                    rootNode(loadCommit(a2)));
                    ancestorNode = vb.root();
                    contested = contestedConflicts(vb.conflicts(), ourNode, theirNode);
                } else if (!minimal.isEmpty()) {
                    // Single base (linear/diamond). A criss-cross with >=3 minimal ancestors falls
                    // back to single-base latest-timestamp (the full N-way fold is a follow-on).
                    ancestorNode = rootNode(loadCommit(latestTimestampCommit(minimal)));
                }
            }

            MergeEngine engine = new MergeEngine(store, descriptor, pool);
            MergeEngine.MergeResult result =
                    withContested(engine.merge(ancestorNode, ourNode, theirNode), contested);

            if (result.conflicts().isEmpty()) {
                commitMerge(
                        ours,
                        new StaticMap(store, result.root(), descriptor),
                        List.of(ourCommitHash, theirCommitHash),
                        author,
                        message);
            }
            return result;
        } finally {
            gcLock.readLock().unlock();
        }
    }

    /**
     * Returns the data-root hash of the latest-timestamp minimal common ancestor of two commits —
     * the single-base selector (git's {@code resolve} tiebreak), pinned by {@code
     * LcaCorrectnessProperty}.
     *
     * <p><b>For a criss-cross this single pick is NOT the merge base {@link #merge} uses.</b> Merge
     * builds a {@code --recursive} VIRTUAL base from the full {@link #minimalCommonAncestors} set
     * ({@link #mergeBaseNode}, ADR-0070) — resolving the silent divergence that single-base {@code
     * resolve} allowed (a manually-resolved disagreeing criss-cross silently auto-resolving by
     * which base was picked). {@code findLCA} remains for the single-base selection + its tests.
     */
    private byte @Nullable [] findLCA(byte[] hashA, byte[] hashB) {
        List<byte[]> minimal = minimalCommonAncestors(hashA, hashB);
        if (minimal.isEmpty()) return null;
        Commit best = loadCommit(latestTimestampCommit(minimal));
        return best != null ? best.getRootValueHash() : null;
    }

    /**
     * The minimal common ancestors of two commits (their commit hashes): common ancestors that are
     * not themselves a strict ancestor of another common ancestor. A single element for
     * linear/diamond history; two (or more) for a criss-cross. The selection logic from the
     * original {@code findLCA}, minus the latest-timestamp tiebreak — so {@link #merge} can build a
     * {@code --recursive} virtual base from the whole set (ADR-0070).
     */
    private List<byte[]> minimalCommonAncestors(byte @Nullable [] hashA, byte @Nullable [] hashB) {
        if (hashA == null || hashB == null) return List.of();

        Map<String, byte[]> ancA = collectAncestors(hashA);
        Map<String, byte[]> ancB = collectAncestors(hashB);

        Set<String> commonHexes = new HashSet<>(ancA.keySet());
        commonHexes.retainAll(ancB.keySet());
        if (commonHexes.isEmpty()) return List.of();

        // Drop any common commit that is a strict ancestor of another common commit.
        Set<String> shadowed = new HashSet<>();
        for (String c : commonHexes) {
            Commit cc = loadCommit(ancA.get(c));
            if (cc == null) continue;
            for (byte[] p : cc.getParents()) {
                String ph = toHex(p);
                if (commonHexes.contains(ph)) shadowed.add(ph);
            }
        }
        // Iterate the shadow closure: a parent of a shadowed common is also shadowed.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String s : new ArrayList<>(shadowed)) {
                Commit cc = loadCommit(ancA.get(s));
                if (cc == null) continue;
                for (byte[] p : cc.getParents()) {
                    String ph = toHex(p);
                    if (commonHexes.contains(ph) && shadowed.add(ph)) changed = true;
                }
            }
        }
        Set<String> minimalHexes = new HashSet<>(commonHexes);
        minimalHexes.removeAll(shadowed);
        if (minimalHexes.isEmpty()) {
            // All common ancestors shadowed (only on a cyclic shadow relation); fall back to any.
            minimalHexes = commonHexes;
        }

        List<byte[]> out = new ArrayList<>(minimalHexes.size());
        for (String hex : minimalHexes) out.add(ancA.get(hex));
        return out;
    }

    /**
     * The latest-timestamp commit among a set of commit hashes (git's {@code resolve} tiebreak).
     */
    private byte @Nullable [] latestTimestampCommit(List<byte[]> commits) {
        byte[] best = null;
        long bestTs = Long.MIN_VALUE;
        for (byte[] h : commits) {
            Commit c = loadCommit(h);
            if (c != null && c.getTimestamp() >= bestTs) {
                bestTs = c.getTimestamp();
                best = h;
            }
        }
        return best;
    }

    /**
     * The {@code --recursive} virtual merge base of two commits as a tree {@link Node} (ADR-0070):
     * the single base for ≤1 minimal ancestor; for exactly two, the three-way merge of the two
     * ancestors against THEIR (recursively computed) base. Recursion descends to strict ancestors,
     * so it terminates at a single common ancestor / the root. A criss-cross with ≥3 minimal
     * ancestors falls back to the latest-timestamp single base (the full N-way fold is a
     * follow-on). Nested virtual-base conflicts are dropped here — the top-level {@link #merge}
     * captures the contested keys it needs; for the common single-fork criss-cross the nested base
     * is a single ancestor, so none arise.
     */
    private @Nullable Node mergeBaseNode(byte[] commitA, byte[] commitB) {
        List<byte[]> minimal = minimalCommonAncestors(commitA, commitB);
        if (minimal.isEmpty()) return null;
        if (minimal.size() != 2) return rootNode(loadCommit(latestTimestampCommit(minimal)));
        byte[] a1 = minimal.get(0);
        byte[] a2 = minimal.get(1);
        MergeEngine engine = new MergeEngine(store, descriptor, pool);
        return engine.merge(
                        mergeBaseNode(a1, a2), rootNode(loadCommit(a1)), rootNode(loadCommit(a2)))
                .root();
    }

    /**
     * The genuinely-contested keys among the virtual-base conflicts: a key on which the two minimal
     * ancestors disagreed AND on which our/their sides also disagree. These must surface as
     * conflicts in the outer merge (matching git {@code --recursive}: a contested base means "both
     * sides changed it", so differing sides conflict while agreeing sides take their shared value).
     */
    private List<MergeEngine.Conflict> contestedConflicts(
            List<MergeEngine.Conflict> virtualBaseConflicts,
            @Nullable Node ourNode,
            @Nullable Node theirNode) {
        if (virtualBaseConflicts.isEmpty()) return List.of();
        StaticMap ourMap = new StaticMap(store, ourNode, descriptor);
        StaticMap theirMap = new StaticMap(store, theirNode, descriptor);
        List<MergeEngine.Conflict> out = new ArrayList<>();
        for (MergeEngine.Conflict c : virtualBaseConflicts) {
            Optional<java.lang.foreign.MemorySegment> ourV = ourMap.get(c.key());
            Optional<java.lang.foreign.MemorySegment> theirV = theirMap.get(c.key());
            if (!valuesEqual(ourV, theirV)) {
                out.add(
                        new MergeEngine.Conflict(
                                c.key(), c.baseVal(), ourV.orElse(null), theirV.orElse(null)));
            }
        }
        return out;
    }

    /** Folds contested-base conflicts into a merge result (dedup by key); the root is unchanged. */
    private MergeEngine.MergeResult withContested(
            MergeEngine.MergeResult result, List<MergeEngine.Conflict> contested) {
        if (contested.isEmpty()) return result;
        List<MergeEngine.Conflict> all = new ArrayList<>(result.conflicts());
        Set<String> seen = new HashSet<>();
        for (MergeEngine.Conflict c : all) {
            seen.add(toHex(c.key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)));
        }
        for (MergeEngine.Conflict c : contested) {
            if (seen.add(toHex(c.key().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)))) {
                all.add(c);
            }
        }
        return new MergeEngine.MergeResult(result.root(), all);
    }

    private static boolean valuesEqual(
            Optional<java.lang.foreign.MemorySegment> a,
            Optional<java.lang.foreign.MemorySegment> b) {
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() == b.isEmpty();
        return Arrays.equals(
                a.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                b.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }

    private Map<String, byte[]> collectAncestors(byte[] startHash) {
        Map<String, byte[]> out = new HashMap<>();
        Deque<byte[]> queue = new ArrayDeque<>();
        queue.add(startHash);
        while (!queue.isEmpty()) {
            byte[] h = queue.poll();
            if (h == null) continue;
            String hex = toHex(h);
            if (out.putIfAbsent(hex, h) != null) continue;
            Commit c = loadCommit(h);
            if (c != null) queue.addAll(c.getParents());
        }
        return out;
    }

    private @Nullable Commit loadCommit(byte @Nullable [] hash) {
        if (hash == null) return null;
        return store.read(hash)
                .map(
                        seg ->
                                Commit.deserialize(
                                        seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)))
                .orElse(null);
    }

    public void cherryPick(String branch, byte[] commitHash, String author) {
        Commit commit =
                Objects.requireNonNull(
                        loadCommit(commitHash),
                        () -> "cherryPick: no such commit " + toHex(commitHash));
        byte[] parentHash = commit.getParents().get(0);
        Commit parent = loadCommit(parentHash);

        DiffEngine diffEngine = new DiffEngine(store, descriptor);

        // garbage-collection read lock across diff+build+commit — applyMutations writes the patched
        // tree's chunks (docs/write-ups/gc-concurrent-write-flush-window.md). commit re-locks
        // reentrantly.
        // The (parent→commit) diff STREAMS straight into applyMutations: heap is O(tree height),
        // not
        // O(change-set), so cherry-picking a huge commit cannot OOM (was a materialised patch List
        // —
        // see plans/oom-hardening.md). Diffing under the read lock also keeps GC off the read
        // nodes.
        gcLock.readLock().lock();
        try {
            StaticMap current = getBranch(branch);
            TreeMutator mutator = new TreeMutator(store, descriptor, pool);
            Iterator<DiffEngine.DiffEntry> diffIt =
                    diffEngine.diffIterator(rootNode(parent), rootNode(commit));
            Iterator<TreeMutator.Mutation> patch =
                    new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return diffIt.hasNext();
                        }

                        @Override
                        public TreeMutator.Mutation next() {
                            DiffEngine.DiffEntry e = diffIt.next();
                            return new TreeMutator.Mutation(e.key(), e.valueB());
                        }
                    };
            Node newRoot = mutator.applyMutations(current.root(), patch);
            commit(
                    branch,
                    new StaticMap(store, newRoot, descriptor),
                    getHeadHash(branch).get(),
                    author,
                    "Cherry-picked " + toHex(commitHash));
        } finally {
            gcLock.readLock().unlock();
        }
    }

    public void revert(String branch, byte[] commitHash, String author) {
        Commit commit =
                Objects.requireNonNull(
                        loadCommit(commitHash),
                        () -> "revert: no such commit " + toHex(commitHash));
        byte[] parentHash = commit.getParents().get(0);
        Commit parent = loadCommit(parentHash);

        DiffEngine diffEngine = new DiffEngine(store, descriptor);

        // garbage-collection read lock across diff+build+commit — applyMutations writes the
        // inverse-patched tree's chunks (docs/write-ups/gc-concurrent-write-flush-window.md).
        // commit re-locks
        // reentrantly. The (parent→commit) diff STREAMS into applyMutations, mapped to its INVERSE
        // (each key → valueA, i.e. restore the pre-commit value); heap is O(tree height), not
        // O(change-set), so reverting a huge commit cannot OOM. See plans/oom-hardening.md.
        gcLock.readLock().lock();
        try {
            StaticMap current = getBranch(branch);
            TreeMutator mutator = new TreeMutator(store, descriptor, pool);
            Iterator<DiffEngine.DiffEntry> diffIt =
                    diffEngine.diffIterator(rootNode(parent), rootNode(commit));
            Iterator<TreeMutator.Mutation> inversePatch =
                    new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return diffIt.hasNext();
                        }

                        @Override
                        public TreeMutator.Mutation next() {
                            DiffEngine.DiffEntry e = diffIt.next();
                            return new TreeMutator.Mutation(e.key(), e.valueA());
                        }
                    };
            Node newRoot = mutator.applyMutations(current.root(), inversePatch);
            commit(
                    branch,
                    new StaticMap(store, newRoot, descriptor),
                    getHeadHash(branch).get(),
                    author,
                    "Reverted " + toHex(commitHash));
        } finally {
            gcLock.readLock().unlock();
        }
    }

    /**
     * The <b>sync-sink primitive</b> (the upstream document-sync plan, Steps 3–4): atomically
     * receive a pack of content-addressed chunks — tree nodes AND commit objects, which are chunks
     * too (ADR-0073) — and advance {@code branch} to {@code newHead} by compare-and-set. Mirrors
     * the commit path's ordering under the same garbage-collection read lock: every chunk is
     * written and made durable BEFORE the ref moves, so a concurrent collector cannot sweep the
     * pack between write and publish (the flush-outside-the-lock lesson), and a power cut never
     * leaves the ref pointing at volatile-only chunks.
     *
     * @param chunks the pack's chunk bytes (content-addressed — re-writes are idempotent)
     * @param branch the branch to advance
     * @param newHead the commit hash the branch should point at; MUST be readable after {@code
     *     chunks} land (fail-fast against a torn/incomplete pack)
     * @param expectedOldHead the compare-and-set guard — the head the caller believes the branch is
     *     at ({@code null}: the branch is new/empty)
     * @return true if the ref advanced; false on a lost compare-and-set race
     * @throws IllegalStateException if {@code newHead} is not readable after the chunk writes
     */
    public boolean receiveSyncPack(
            List<byte[]> chunks, String branch, byte[] newHead, byte @Nullable [] expectedOldHead) {
        gcLock.readLock().lock();
        try {
            receiveChunks(chunks);
            if (store.read(newHead).isEmpty()) {
                throw new IllegalStateException(
                        "torn sync pack: new head commit "
                                + toHex(newHead)
                                + " is not present after applying "
                                + chunks.size()
                                + " chunks");
            }
            return manifest.updateRef(repoId, HEAD_PREFIX + branch, newHead, expectedOldHead);
        } finally {
            gcLock.readLock().unlock();
        }
    }

    /**
     * <b>Stage</b> a pack's chunks without moving any ref (the upstream document-sync plan, Step
     * 9): the chunk writes + durable flush of {@link #receiveSyncPack}, minus the compare-and-set —
     * so a pull's conflict detector can read the remote lineage's state (via commit-hash refs)
     * before deciding whether anything may advance. The staged chunks are content-addressed and
     * unreachable until a ref later claims them: garbage-collectable, never wrong.
     */
    public void receiveChunks(List<byte[]> chunks) {
        gcLock.readLock().lock();
        try {
            for (byte[] chunk : chunks) {
                store.write(chunk);
            }
            flushDurable();
        } finally {
            gcLock.readLock().unlock();
        }
    }

    public void createBranch(String name, String fromRef) {
        Optional<byte[]> hash =
                manifest.getRef(repoId, fromRef.contains("/") ? fromRef : HEAD_PREFIX + fromRef);
        if (hash.isPresent()) {
            manifest.updateRef(repoId, HEAD_PREFIX + name, hash.get(), null);
        } else if (fromRef.equals("EMPTY")) {
            manifest.updateRef(repoId, HEAD_PREFIX + name, null, null);
        }
    }

    public List<String> listBranches() {
        return manifest.listRefs(repoId).stream()
                .filter(r -> r.startsWith(HEAD_PREFIX))
                .map(r -> r.substring(HEAD_PREFIX.length()))
                .collect(Collectors.toList());
    }

    public MutableMap rebase(MutableMap pending, StaticMap newBase) {
        MutableMap rebased = new MutableMap(newBase, store, descriptor, pool);
        pending.copyEditsTo(rebased);
        return rebased;
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Override
    public void close() {
        if (store instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                throw new RuntimeException("Failed to close NodeStore", e);
            }
        }
    }
}
