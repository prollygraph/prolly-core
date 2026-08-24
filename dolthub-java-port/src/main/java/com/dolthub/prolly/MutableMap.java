/*
 * Copyright 2026 Earasoft
 * Copyright 2021 Dolthub, Inc.
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
package com.dolthub.prolly;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A buffered, mutable overlay on a base {@link StaticMap}: stage puts/deletes, then {@link #flush}
 * to a new immutable tree.
 *
 * <p>This is the write-side staging area. A transaction makes its edits here, reads see them over
 * the base (read-your-writes), and {@code flush()} merges them into a new committed tree.
 *
 * @apiNote <b>Not thread-safe</b> — one writer per buffer (the Sail serializes writers). {@link
 *     #get} returns buffered edits layered over the base; {@link #copyEditsTo} rebases the pending
 *     edits onto a newer base for optimistic-concurrency retry.
 *     <p><b>Garbage-collection caveat:</b> {@link #flush} <i>writes chunks to the store</i>. Under
 *     a concurrent garbage collector those writes must happen under the garbage-collection read
 *     lock — use {@code Database.commit(branch, MutableMap, …)} (which does), not {@code
 *     commit(branch, mm.flush(), …)}. See docs/write-ups/gc-concurrent-write-flush-window.md.
 * @implNote Edits live in a sorted, disk-spilling {@link SpillableSortedBuffer} keyed on {@code
 *     Tuple}; {@link #flush} feeds that buffer's merged sorted stream + {@code base.root()} to
 *     {@link TreeMutator#applyMutations} to build the new root. The buffer keeps a bounded in-heap
 *     tail and spills sorted runs to disk past {@code prolly.tx.spill.bytes} (default 64 MiB), so a
 *     transaction of arbitrary size costs {@code O(threshold)} heap, not {@code O(edits)} — "slow
 *     is better than blow up" ({@code plans/prolly-bulk-load.md} D-8). Normal transactions stay
 *     under the threshold and never touch disk; only a giant un-batched transaction spills.
 *     Spilling is invisible to the result: a spilled flush builds the byte-identical root an
 *     all-in-heap flush would (history-independence — pinned by the differential oracle test).
 *     <p><b>Collaborators:</b> {@link StaticMap} (base + flush result), {@link TreeMutator} (the
 *     build), {@link NodeStore}/{@link TupleDescriptor}/{@link BufferPool}. <b>Dependents:</b>
 *     {@code Database.commit} and the RDF4J Sail write path.
 *     <p><b>Wart:</b> each {@code put} allocates a {@code Tuple} wrapper + a pooled key segment;
 *     that per-insert churn is the write-path optimization plan's target ({@code
 *     the upstream write-path zero-copy plan).
 */
public class MutableMap {
    private final StaticMap base;
    private final NodeStore store;

    /** The flush's boundary function — the seam (D-1); default is the production rolling hash. */
    private BoundarySplitter.Factory splitterFactory = BoundarySplitter.ROLLING_HASH;

    private final TupleDescriptor descriptor;
    private final BufferPool pool;
    // Keyed by Tuple, not the raw MemorySegment: the sort comparator runs
    // O(log n) times per put, so wrapping the segment in a Tuple once at
    // insertion — rather than allocating two throwaway Tuples on every
    // comparison — keeps the ingest hot path's allocation flat. The in-heap
    // tail of the spillable buffer is exactly that Tuple-keyed sorted map.
    private final SpillableSortedBuffer<Tuple> edits;

    /**
     * Tuple ⇄ bytes for the spill: both directions are cheap (wrap, no copy); the byte copy +
     * reconstruction only happen on the slow spill/merge path.
     */
    private static final SpillableSortedBuffer.KeyCodec<Tuple> TUPLE_CODEC =
            new SpillableSortedBuffer.KeyCodec<>() {
                @Override
                public MemorySegment toBytes(Tuple t) {
                    return t.segment();
                }

                @Override
                public Tuple fromBytes(MemorySegment s) {
                    return new Tuple(s);
                }
            };

    /**
     * Heap budget per edit buffer before it spills to disk. Explicit override via the system
     * property {@code prolly.tx.spill.bytes}; otherwise an <b>adaptive default scaled to the max
     * heap</b>: {@code min(64 MiB, maxHeap/128)}. The fixed 64 MiB suited large heaps but a
     * measurement ({@code plans/prolly-bulk-load.md} Step 4d) found it never engages on a small
     * heap — a 500k single transaction on a 2 GiB heap left each buffer at ~49 MiB (< 64), so it
     * didn't spill and still OOM'd. Scaling to the heap makes the no-OOM net engage before that
     * wall on small heaps (2 GiB → 16 MiB, measured to land ~1.6 GiB peak with comfortable margin;
     * {@code /64} → 32 MiB held too much staging and peaked at 99% of a 2 GiB heap) while staying
     * at 64 MiB for heaps ≥ 8 GiB, where normal transactions never spill. Per-buffer; ~5–7 buffers
     * are live per transaction (the four quad indexes + dictionary + namespaces + stats). <b>Not a
     * hard guarantee:</b> the spill bounds the staging *accumulation*, not the commit-time
     * tree-build peak (a threshold-independent floor the per-commit parallelism cap, Phase 1.5 Step
     * 4c-2, addresses); for very tight heaps tune {@code prolly.tx.spill.bytes}.
     */
    private static final long SPILL_BYTES = resolveSpillBytes();

    private static long resolveSpillBytes() {
        Long override = Long.getLong("prolly.tx.spill.bytes");
        if (override != null) return override;
        return Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 128);
    }

    private static final Path SPILL_DIR = resolveSpillDir();

    private static Path resolveSpillDir() {
        // prolly.spill.temp-dir lets an operator point spill run files at a larger / faster disk
        // than
        // java.io.tmpdir (often a small tmpfs); paired with the prolly.spill.max-disk-bytes quota.
        String configured = System.getProperty("prolly.spill.temp-dir");
        return Path.of(
                configured != null && !configured.isBlank()
                        ? configured
                        : System.getProperty("java.io.tmpdir"));
    }

    public MutableMap(
            StaticMap base, NodeStore store, TupleDescriptor descriptor, BufferPool pool) {
        this(base, store, descriptor, pool, null);
    }

    /**
     * As {@link #MutableMap(StaticMap, NodeStore, TupleDescriptor, BufferPool)}, but lets the
     * caller supply a key {@code comparator} for the edit buffer.
     *
     * <p>When {@code comparator} is {@code null} the buffer sorts by {@code descriptor.compare} —
     * the generic path. A caller whose keys all share one fixed layout can pass a specialised
     * comparator that avoids the per-field {@code MemorySegment} slicing {@code
     * TupleDescriptor.compare} does. <b>It MUST induce the exact same ordering as {@code
     * descriptor.compare}</b> for every key that will be inserted — the {@link #flush} path
     * rebuilds the tree with {@code descriptor}, so a divergent order would corrupt the result.
     */
    public MutableMap(
            StaticMap base,
            NodeStore store,
            TupleDescriptor descriptor,
            BufferPool pool,
            @Nullable Comparator<Tuple> comparator) {
        this(base, store, descriptor, pool, comparator, SPILL_BYTES, SPILL_DIR);
    }

    /**
     * Seam constructor (the upstream SPOC boundary-function-adoption plan, D-1): inject the
     * boundary function this map's flush uses to chunk the rebuilt tree. Every other constructor
     * keeps the production default.
     */
    public MutableMap(
            StaticMap base,
            NodeStore store,
            TupleDescriptor descriptor,
            BufferPool pool,
            @Nullable Comparator<Tuple> comparator,
            BoundarySplitter.Factory splitterFactory) {
        this(base, store, descriptor, pool, comparator, SPILL_BYTES, SPILL_DIR);
        this.splitterFactory = splitterFactory;
    }

    /**
     * Public constructor with an explicit per-instance spill threshold (temp dir defaults to {@code
     * java.io.tmpdir}). Lets one buffer be tuned independently of the global {@code
     * prolly.tx.spill.bytes} — e.g. a bulk load keeps the <b>dictionary</b> buffer in-heap (its
     * per-term {@code get} for dedup is {@code O(runs)} when spilled, the build-once encode wall)
     * while the insert-only index buffers spill at the normal threshold (plans/prolly-bulk-load.md
     * Phase 2 / an upstream bulk-load decision, D-3).
     */
    public MutableMap(
            StaticMap base,
            NodeStore store,
            TupleDescriptor descriptor,
            BufferPool pool,
            @Nullable Comparator<Tuple> comparator,
            long spillThresholdBytes) {
        this(base, store, descriptor, pool, comparator, spillThresholdBytes, SPILL_DIR, false);
    }

    /**
     * As {@link #MutableMap(StaticMap, NodeStore, TupleDescriptor, BufferPool, Comparator)}, with
     * the edit buffer's opt-in <b>presence index</b> at the default spill threshold (see {@link
     * SpillableSortedBuffer#SpillableSortedBuffer(Comparator, KeyCodec, long, Path, boolean)} for
     * the contract — enable only for canonical key encodings where comparator equality implies
     * byte equality). The dictionary is the intended caller: its per-term dedup {@code get} is the
     * measured quadratic wall once the buffer spills, and the index answers absent first
     * encounters from heap instead of walking every run file.
     */
    public MutableMap(
            StaticMap base,
            NodeStore store,
            TupleDescriptor descriptor,
            BufferPool pool,
            @Nullable Comparator<Tuple> comparator,
            boolean presenceIndex) {
        this(base, store, descriptor, pool, comparator, SPILL_BYTES, SPILL_DIR, presenceIndex);
    }

    /** As above, with an explicit per-instance spill threshold (the dictionary's tuning pair). */
    public MutableMap(
            StaticMap base,
            NodeStore store,
            TupleDescriptor descriptor,
            BufferPool pool,
            @Nullable Comparator<Tuple> comparator,
            long spillThresholdBytes,
            boolean presenceIndex) {
        this(base, store, descriptor, pool, comparator, spillThresholdBytes, SPILL_DIR,
                presenceIndex);
    }

    /**
     * Full constructor exposing the spill threshold + temp directory. The public constructors
     * default these to {@code prolly.tx.spill.bytes} (64 MiB) and {@code java.io.tmpdir}; the
     * differential oracle test uses a tiny threshold to force spilling and assert the spilled flush
     * builds the same root as an in-heap one.
     */
    MutableMap(
            StaticMap base,
            NodeStore store,
            TupleDescriptor descriptor,
            BufferPool pool,
            @Nullable Comparator<Tuple> comparator,
            long spillThresholdBytes,
            Path tempDir) {
        this(base, store, descriptor, pool, comparator, spillThresholdBytes, tempDir, false);
    }

    /** As above, carrying the edit buffer's presence-index opt-in. */
    MutableMap(
            StaticMap base,
            NodeStore store,
            TupleDescriptor descriptor,
            BufferPool pool,
            @Nullable Comparator<Tuple> comparator,
            long spillThresholdBytes,
            Path tempDir,
            boolean presenceIndex) {
        this.base = base;
        this.store = store;
        this.descriptor = descriptor;
        this.pool = pool;
        // Lambda, not descriptor::compare — a bound method reference would
        // NPE here if descriptor is null, but some callers legitimately
        // construct an empty MutableMap with a null descriptor. The lambda
        // defers the dereference to the first actual comparison. Keys are
        // Tuples already, so the comparator allocates nothing.
        Comparator<Tuple> cmp =
                comparator != null ? comparator : (a, b) -> descriptor.compare(a, b);
        this.edits =
                new SpillableSortedBuffer<>(
                        cmp, TUPLE_CODEC, spillThresholdBytes, tempDir, presenceIndex);
    }

    public void put(MemorySegment key, MemorySegment value) {
        // Fail fast on a null key (the deep-failure input: new Tuple(null) NPEs in the comparator
        // far from here). A null value is NOT rejected — it is the defined tombstone/delete path.
        if (key == null) throw new IllegalArgumentException("key must not be null");
        edits.put(new Tuple(key), value);
    }

    public void delete(MemorySegment key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        edits.put(new Tuple(key), null);
    }

    public Optional<MemorySegment> get(MemorySegment key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        Tuple tk = new Tuple(key);
        if (edits.containsKey(tk)) {
            return Optional.ofNullable(edits.get(tk));
        }
        return base.get(key);
    }

    /** Copies all pending edits from this map to another mutable map. */
    public void copyEditsTo(MutableMap other) {
        // try-with-resources so the merge iterator's open run-file readers are closed even if
        // other.put/delete throws partway — the same descriptor leak the flush path had. This is
        // the
        // rebase seam (Database rebases pending edits onto a moved branch head), so a throw here is
        // a
        // real failure path, not hypothetical.
        try (SpillableSortedBuffer.CloseableEntryIterator<Tuple> it = edits.merged()) {
            while (it.hasNext()) {
                SpillableSortedBuffer.Entry<Tuple> e = it.next();
                if (e.value() == null) {
                    other.delete(e.key().segment());
                } else {
                    other.put(e.key().segment(), e.value());
                }
            }
        }
    }

    public StaticMap base() {
        return base;
    }

    /**
     * Test/diagnostic: spilled run count of the edit buffer ({@code 0} ⇒ the transaction stayed in
     * heap). Read it before {@link #flush}, which clears the buffer.
     */
    int spilledRunCount() {
        return edits.spilledRunCount();
    }

    /**
     * Materializes the buffered edits into a new immutable {@link StaticMap}, leaving this buffer's
     * base unchanged.
     *
     * @return a new {@code StaticMap} = base ⊕ buffered edits (last-write-wins; deletes remove
     *     keys)
     * @apiNote <b>Writes the new tree's chunks to the store</b> as it builds (via {@link
     *     TreeMutator}). Under a concurrent garbage collector, call this <i>inside</i> the
     *     garbage-collection read lock — i.e. via {@code Database.commit(branch, MutableMap, …)},
     *     not {@code commit(branch, mm.flush(), …)} — else a sweep can delete the fresh chunks (the
     *     in-repo docs/write-ups/gc-concurrent-write-flush-window.md).
     *     <p><b>Failure contract:</b> if this throws, <b>this buffer is spent — do not reuse it</b>
     *     (its merge iterator was consumed; some edits may already be drained). The {@code base}
     *     snapshot is <i>unchanged</i>, so the recovery is to build a fresh {@link MutableMap} on
     *     the same base and re-apply the edits. Any chunks written before the failure are
     *     content-addressed orphans: harmless (nothing references them) and reclaimed by the next
     *     garbage collection — no manual cleanup, no corruption of {@code base}.
     * @implNote Feeds the sorted {@code edits} + {@code base.root()} to {@link
     *     TreeMutator#applyMutations}.
     * @throws ProllyIoException if the store fails to persist a chunk (transient — retry on a fresh
     *     buffer over the same base)
     * @throws ProllyCorruptionException if reading the {@code base} tree finds a content-hash
     *     mismatch (corruption — the base is bad; restore it, do not retry)
     */
    public StaticMap flush() {
        if (edits.isEmpty()) return base;

        TreeMutator mutator = new TreeMutator(store, descriptor, pool, splitterFactory);
        // try-with-resources: if applyMutations throws partway (e.g. the store fails a chunk
        // write),
        // the merge iterator's open run-file readers are closed — no file-descriptor leak on the
        // failure path. On success the cursors are already drained-and-closed, so close() is a
        // no-op
        // and edits.clear() has already deleted the run files.
        try (SpillableSortedBuffer.CloseableEntryIterator<Tuple> src = edits.merged()) {
            Iterator<TreeMutator.Mutation> mutationIter =
                    new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return src.hasNext();
                        }

                        @Override
                        public TreeMutator.Mutation next() {
                            SpillableSortedBuffer.Entry<Tuple> e = src.next();
                            return new TreeMutator.Mutation(e.key().segment(), e.value());
                        }
                    };

            Node newRoot = mutator.applyMutations(base.root(), mutationIter);
            edits.clear(); // deletes any spilled run files
            return new StaticMap(store, newRoot, descriptor);
        }
    }
}
