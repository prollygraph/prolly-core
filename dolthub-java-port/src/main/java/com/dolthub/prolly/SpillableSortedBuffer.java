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
package com.dolthub.prolly;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeMap;
import org.apache.commons.collections4.bloomfilter.Hasher;
import org.apache.commons.collections4.bloomfilter.Shape;
import org.apache.commons.collections4.bloomfilter.SimpleBloomFilter;
import org.jspecify.annotations.Nullable;

/**
 *
 *
 * <h3>A sorted key→value buffer that spills to disk to bound heap — the no-OOM write-path staging.
 * </h3>
 *
 * <p>Drop-in replacement for the in-heap {@code TreeMap} that {@code MutableMap} uses to stage a
 * transaction's pending edits. Keeps a bounded in-heap tail (a {@link TreeMap} keyed on {@code K});
 * once it exceeds a byte threshold, the sorted tail is written to a temporary on-disk <i>run</i>
 * and the heap map is cleared, so a transaction of arbitrary size costs {@code O(threshold)} heap,
 * not {@code O(edits)} — with two deliberate exceptions held OUTSIDE the spill accounting: the
 * opt-in presence structures (see the five-argument constructor — the two-tier presence index at
 * 16–32 bytes per distinct staged key up to its byte budget, plus a ~1.2-byte-per-entry Bloom
 * filter per sealed run up to the same budget), because trading that bounded heap for per-run file
 * probes is their entire purpose; and each run's sparse index (a key + boxed offset every {@value
 * #INDEX_STRIDE} entries, roughly 0.1% of the on-disk run bytes), the map that makes a point probe
 * one block read instead of a scan. {@link #merged()} sorted-merges every run plus the tail into a
 * single ascending stream — exactly the sorted edit stream {@code TreeMutator.applyMutations}
 * requires — so spilling preserves the tree-build precondition for free.
 *
 * <p><b>Generic over the key</b> via a {@link KeyCodec}: the in-heap tail holds {@code K} directly
 * (so a caller whose {@code K} is alloc-free to compare — e.g. {@code Tuple} — pays no
 * per-comparison allocation on the normal, non-spilling path); only on the slow spill/merge path
 * are keys serialized to bytes and reconstructed. {@code K} reconstruction (and the byte copy)
 * therefore happen only while spilling.
 *
 * <p><b>Last-write-wins.</b> A key may appear in several runs and the tail (put → spill → put →
 * spill). Reads and the merge always return the <i>newest</i> value: the tail is newer than any
 * run, and a later run is newer than an earlier one. A {@code null} value is a <b>tombstone</b> (a
 * delete), distinct from a present zero-length value — both flow through unchanged for the consumer
 * to interpret.
 *
 * @param <K> the key type held in heap (e.g. {@code Tuple}); serialized via {@link KeyCodec}
 * @apiNote Not thread-safe — one buffer per transaction; the Sail's single-writer lock serializes
 *     them. Plan: {@code plans/prolly-bulk-load.md} D-8 (Phase 1.5). Reused by the Phase 2
 *     bulk-builder's external sort. {@link #close()} deletes the runs; a {@link Cleaner} backstop
 *     deletes them if the buffer is abandoned without {@code close} (e.g. a rolled-back
 *     transaction).
 * @implNote Each spilled run carries an in-memory <b>sparse index</b> (every {@value
 *     #INDEX_STRIDE}th key → file offset) + min/max, so a point {@link #get}/{@link #containsKey}
 *     reads at most one block per run rather than scanning it. Storage-agnostic: plain temp files,
 *     no {@code NodeStore}/RocksDB.
 */
public final class SpillableSortedBuffer<K> implements AutoCloseable {

    private static final int INDEX_STRIDE = 1024;
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final Cleaner CLEANER = Cleaner.create();

    /**
     * Process-global count of spill events across all buffers — pure telemetry (the source for the
     * {@code prolly.tx.spills} meter). A spill means a transaction exceeded its in-heap edit budget
     * and went to disk: the slow, bounded path. Static because buffers are per-transaction and
     * transient, so a per-instance counter can't aggregate process-wide.
     */
    private static final java.util.concurrent.atomic.LongAdder TOTAL_SPILLS =
            new java.util.concurrent.atomic.LongAdder();

    /** Total spill events since process start (telemetry; monotonic). */
    public static long totalSpills() {
        return TOTAL_SPILLS.sum();
    }

    /**
     * Process-global bytes of spill run files <b>currently resident on disk</b> across all buffers
     * — the source for the {@code prolly.tx.spill.disk.bytes} gauge and the spill-disk quota.
     * Unlike {@link #TOTAL_SPILLS} (a monotonic count), this <i>rises</i> on each spill write and
     * <i>falls</i> on cleanup, so it reflects the live temp-directory footprint. Static because
     * buffers are per-transaction and transient.
     */
    private static final java.util.concurrent.atomic.LongAdder SPILL_DISK_BYTES =
            new java.util.concurrent.atomic.LongAdder();

    /**
     * Bytes of spill run files currently resident on disk across all buffers (telemetry; also the
     * value a spill-disk quota bounds). Falls back to the baseline once every run is cleaned.
     */
    public static long currentSpillDiskBytes() {
        return SPILL_DISK_BYTES.sum();
    }

    /**
     * Serializes a heap key to bytes (cheap — should return the backing segment) and back (spill
     * path).
     */
    public interface KeyCodec<K> {
        MemorySegment toBytes(K key);

        K fromBytes(MemorySegment bytes);
    }

    /**
     * A buffered edit. {@code value == null} is a tombstone (delete); a zero-length value is
     * present. {@code value} is {@code @Nullable} <em>by design</em> — a delete genuinely carries
     * no value — so a consumer cannot forget the tombstone case: the merge / flush path must
     * distinguish a delete from a present value, and NullAway turns a missed distinction into a
     * compile error rather than a runtime surprise. (See the package-info null-safety notes.)
     */
    public record Entry<K>(K key, @Nullable MemorySegment value) {}

    /**
     * A run point-lookup result; {@code value} may be {@code null} (a tombstone). {@code null}
     * Lookup = absent.
     */
    private record Lookup(@Nullable MemorySegment value) {}

    private final Comparator<K> keyCmp;
    private final KeyCodec<K> codec;
    private final long spillThresholdBytes;
    private final Path tempDir;

    /**
     * Spill-disk quota (bytes); {@code 0} = unbounded (the default). Defaults from {@code
     * prolly.spill.max-disk-bytes}. The check is against the <b>process-global</b> resident bytes
     * ({@link #currentSpillDiskBytes()}), so every buffer (same default) enforces one global temp-
     * directory budget: a spill that would push the total past this fails the transaction with a
     * {@link SpillQuotaExceededException} <i>before</i> writing the run file (no orphan left
     * behind).
     */
    private long maxSpillDiskBytes = Long.getLong("prolly.spill.max-disk-bytes", 0L);

    /** Per-run filter false-positive rate: a false maybe costs one redundant file probe. */
    private static final double RUN_FILTER_FPP = 0.01;

    private final @Nullable LongPresenceSet presence; // opt-in absent-key index; null = off
    private final TreeMap<K, @Nullable MemorySegment> tail; // the in-heap, newest edits
    private long tailBytes;

    /**
     * Heap bytes the per-run filters hold so far, against {@link #runFilterBudgetBytes}: the same
     * heap-aware budget family as the presence index, and the same graceful policy past it — new
     * runs simply seal without a filter and take the full file probe.
     */
    private long runFilterBytes;

    private long runFilterBudgetBytes = LongPresenceSet.defaultBudgetBytes();
    private final List<Run> runs = new ArrayList<>(); // oldest first; index = recency rank
    private final List<RunFile> runFiles =
            new ArrayList<>(); // referenced by the Cleaner (NOT this buffer)
    private Cleaner.@Nullable Cleanable
            cleanable; // null until first spill (lazy — no cost otherwise)

    /**
     * A spilled run file plus its on-disk byte size. Carrying the size lets {@link #cleanup}
     * decrement {@link #SPILL_DISK_BYTES} <i>without</i> a reference to this buffer, so the {@link
     * Cleaner} backstop stays decoupled (a reference to {@code this} would pin the buffer and
     * defeat the Cleaner). {@code bytes} is set only after a successful write — it stays {@code 0}
     * if the write failed mid-way, so cleanup of a partial file (which was never counted)
     * decrements nothing.
     */
    private static final class RunFile {
        final Path path;
        volatile long bytes;

        RunFile(Path path) {
            this.path = path;
        }
    }

    public SpillableSortedBuffer(
            Comparator<K> keyComparator,
            KeyCodec<K> codec,
            long spillThresholdBytes,
            Path tempDir) {
        this(keyComparator, codec, spillThresholdBytes, tempDir, false);
    }

    /**
     * As {@link #SpillableSortedBuffer(Comparator, KeyCodec, long, Path)}, with an opt-in in-heap
     * <b>presence index</b>: a set of {@link LongPresenceSet#hashBytes} hashes of every staged
     * key's codec bytes, fed on {@link #put} and consulted before any spilled-run probe, so an
     * ABSENT-key {@link #get}/{@link #containsKey} answers from one array probe instead of a file
     * open plus up-to-a-stride of entry decodes <i>per run</i>. That per-run walk is the measured
     * quadratic wall of a bulk load's dictionary dedup (distinct keys grow with the transaction; so
     * does the run count).
     *
     * <p>Enabling it also gives every sealed run a per-run Bloom filter (built at spill time from
     * exactly that run's keys), so a PRESENT-key lookup probes ~one run's file instead of walking
     * all of them — the hit-side complement to the index's absent-side short-circuit.
     *
     * @apiNote <b>Contract:</b> enable only when {@code keyComparator} equality implies {@code
     *     codec.toBytes} byte-equality (canonical, fixed-width key encodings — the dictionary's
     *     single-column Int64 tuples are the intended user). The structures under-approximate
     *     <i>presence</i> never <i>absence</i>: a hash collision merely falls through to the
     *     ordinary probes, but a comparator-equal-yet-byte-different key pair would make "absent"
     *     WRONG, which is why this is a constructor opt-in and not a default. Memory, held for the
     *     buffer's lifetime and OUTSIDE the spill accounting (the trade that replaces keeping a
     *     whole dictionary in heap): the two-tier presence index at 16–32 bytes per distinct staged
     *     key up to a heap-aware byte budget ({@code max(64 MiB, maxHeap/8)}, {@code
     *     prolly.presence.max-bytes}), converting past it to budget-sized Bloom filters
     *     (probabilistic maybes, never a false absent — see {@link LongPresenceSet}); plus ~1.2
     *     bytes per spilled entry of per-run filters up to the same budget, past which new runs
     *     seal filterless. Reset by {@link #clear()} with everything else.
     */
    public SpillableSortedBuffer(
            Comparator<K> keyComparator,
            KeyCodec<K> codec,
            long spillThresholdBytes,
            Path tempDir,
            boolean presenceIndex) {
        this.keyCmp = keyComparator;
        this.codec = codec;
        this.spillThresholdBytes = spillThresholdBytes;
        this.tempDir = tempDir;
        this.tail = new TreeMap<>(keyComparator);
        this.presence = presenceIndex ? new LongPresenceSet() : null;
    }

    private static Runnable cleanup(List<RunFile> files) {
        return () -> {
            synchronized (files) {
                Iterator<RunFile> it = files.iterator();
                while (it.hasNext()) {
                    RunFile rf = it.next();
                    try {
                        Files.deleteIfExists(rf.path);
                    } catch (IOException deleteFailed) {
                        // The file is still on disk (permissions flipped, an
                        // external handle on Windows): keep it TRACKED and its
                        // bytes COUNTED, so the spill-disk quota gauge stays
                        // honest and the next cleanup/Cleaner firing retries.
                        // Decrementing here would under-count the gauge while
                        // the bytes sit resident — the quota's one blind spot.
                        continue;
                    }
                    if (rf.bytes != 0) {
                        SPILL_DISK_BYTES.add(-rf.bytes);
                        rf.bytes = 0; // idempotent: a later Cleaner firing decrements nothing
                    }
                    it.remove();
                }
            }
        };
    }

    /**
     * Stage {@code key → value}; {@code value == null} records a tombstone (delete). Last write
     * wins.
     */
    public void put(K key, @Nullable MemorySegment value) {
        MemorySegment keyBytes = codec.toBytes(key); // one call feeds accounting AND the index
        // The index registers BEFORE the tail insert, deliberately: add() can
        // throw (a doubling table copy mid-grow), and a key that reached the
        // tail without reaching the index would make later lookups answer an
        // authoritative wrong "absent" — the one direction the filter must
        // never err. This order's failure mode is the benign inverse: a hash
        // registered for a key that never landed is just a false "maybe".
        // Tombstones register too: a deleted key is CONTAINED (as a
        // tombstone), so the index must say "maybe" for it — delete is a put.
        if (presence != null) presence.add(LongPresenceSet.hashBytes(keyBytes));
        MemorySegment prev = tail.put(key, value);
        tailBytes += keyBytes.byteSize() + (value == null ? 0 : value.byteSize()) + 48;
        // An overwrite reclaims the WHOLE prior entry's accounting (key + overhead
        // + value), not just the value: the entry count didn't change. A prior
        // tombstone (put returns null, indistinguishable from a fresh insert)
        // stays conservatively over-counted — the error spills early, never late.
        if (prev != null) tailBytes -= keyBytes.byteSize() + prev.byteSize() + 48;
        if (tailBytes >= spillThresholdBytes && tail.size() > 1) spill();
    }

    /**
     * The {@link #getRaw} "no entry anywhere" sentinel — distinct from {@code null}, which is a
     * present tombstone. Package-private so {@code MutableMap} can do its present/tombstone/absent
     * three-way off ONE buffer walk.
     */
    static final Object ABSENT = new Object();

    /** True if the buffer holds any entry for {@code key} (an insert <i>or</i> a tombstone). */
    public boolean containsKey(K key) {
        return getRaw(key) != ABSENT;
    }

    /**
     * The newest value for {@code key}, or {@code null} if absent <i>or</i> tombstoned (mirror
     * {@code TreeMap}: check {@link #containsKey} to distinguish — or use {@link #getRaw} to learn
     * both in one walk).
     */
    public @Nullable MemorySegment get(K key) {
        Object r = getRaw(key);
        return r == ABSENT ? null : (MemorySegment) r;
    }

    /**
     * The single-walk three-way point lookup: the newest value for {@code key}, {@code null} for a
     * tombstone, or {@link #ABSENT} for no entry at all. {@link #containsKey} and {@link #get} are
     * views over this — callers needing both answers (the dedup hot path) call this ONCE instead of
     * paying two identical spilled-run walks, the measured 2× file-I/O tax on every dedupe hit.
     */
    @Nullable Object getRaw(K key) {
        // An empty buffer answers before any hashing: read-only transactions
        // (fresh forked read connections, diff/merge resolvers) never put, so
        // their every probe lands here at the cost of two size checks.
        if (tail.isEmpty() && runs.isEmpty()) return ABSENT;
        long h = 0;
        Hasher hasher = null;
        if (presence != null) {
            // Before even the tail: every staged key (tail or run) was put, and
            // every put registered — an index miss is an authoritative absent
            // under the constructor's canonical-keys contract.
            h = LongPresenceSet.hashBytes(codec.toBytes(key));
            if (!presence.mightContain(h)) return ABSENT;
        }
        MemorySegment v = tail.get(key);
        if (v != null || tail.containsKey(key)) return v; // second descent only on null/miss
        for (int i = runs.size() - 1; i >= 0; i--) {
            Run r = runs.get(i);
            if (r.filter != null) {
                // Per-run filter (built from exactly this run's keys at seal
                // time): a reject PROVES the key is not in this run — skip the
                // file probe. This is what turns a dedupe HIT from O(runs)
                // file opens into ~one, and mops up the presence tier's false
                // positives on the absent side.
                if (hasher == null) {
                    if (presence == null) h = LongPresenceSet.hashBytes(codec.toBytes(key));
                    hasher = LongPresenceSet.hasherFor(h);
                }
                if (!r.filter.contains(hasher)) continue;
            }
            Lookup lk = r.lookup(key);
            if (lk != null) return lk.value; // newest run that has the key wins
        }
        return ABSENT;
    }

    public boolean isEmpty() {
        return tail.isEmpty() && runs.isEmpty();
    }

    /** Heap bytes held by the in-memory tail (for tests / the spill trigger). */
    public long inHeapBytes() {
        return tailBytes;
    }

    /**
     * Test/tuning hook: set the spill-disk quota for this buffer (bytes; {@code 0} = unbounded).
     */
    void setMaxSpillDiskBytes(long maxBytes) {
        this.maxSpillDiskBytes = maxBytes;
    }

    /** Number of spilled runs (for tests). */
    public int spilledRunCount() {
        return runs.size();
    }

    // --- package-private test hooks: let the mutation suite observe internal state whose effect is
    //     otherwise invisible through the public API (the lazy Cleaner registration, the runFiles
    //     bookkeeping, and the sparse-index density). Test-only; not part of the buffer's contract.
    // ---

    /**
     * True once the GC-backstop {@link Cleaner} has been registered (i.e. after the first spill).
     */
    boolean cleanerRegisteredForTest() {
        return cleanable != null;
    }

    /**
     * Number of run-file paths the cleanup bookkeeping is tracking (distinct from {@link
     * #spilledRunCount}).
     */
    int runFileCountForTest() {
        synchronized (runFiles) {
            return runFiles.size();
        }
    }

    /** Sparse-index entry count of the oldest run, or {@code -1} if no run has spilled. */
    int firstRunIndexSizeForTest() {
        return runs.isEmpty() ? -1 : runs.get(0).idxKeys.size();
    }

    /**
     * Distinct keys the presence index holds, or {@code -1} when the index is off. After the index
     * converts to its Bloom tier the count FREEZES at the conversion point (see {@link
     * LongPresenceSet#size()}) — exact-count assertions are valid only while the exact tier holds.
     */
    int presenceSizeForTest() {
        return presence == null ? -1 : presence.size();
    }

    /** Number of sealed runs carrying a per-run filter (test observable). */
    int runFilterCountForTest() {
        int c = 0;
        for (Run r : runs) if (r.filter != null) c++;
        return c;
    }

    /** Test hook: shrink the per-run filter byte budget (0 = no new run filters). */
    void setRunFilterBudgetBytesForTest(long bytes) {
        this.runFilterBudgetBytes = bytes;
    }

    /**
     * A sorted, ascending, last-write-wins iterator over the tail + every run — the flush stream.
     *
     * <p><b>File-descriptor fan-out:</b> this opens one reader (one fd + a 64 KiB buffer) per run
     * <i>simultaneously</i> for the k-way merge, so a transaction that spilled R runs needs R
     * descriptors at flush/rebase time — thousands at hundreds-of-GB scale (R ≈ spilled bytes /
     * spill threshold). Modern JVMs raise the soft {@code RLIMIT_NOFILE} to the hard limit at
     * startup, but on hard-capped environments (containers, older distros, macOS) mind the limit or
     * use a batched commit cadence, which bounds R per batch.
     */
    public CloseableEntryIterator<K> merged() {
        PriorityQueue<Cursor> pq =
                new PriorityQueue<>(
                        (a, b) -> {
                            // Cursors enter pq only after advance()==true, which sets cur non-null.
                            int c =
                                    keyCmp.compare(
                                            Objects.requireNonNull(a.cur).key(),
                                            Objects.requireNonNull(b.cur).key());
                            return c != 0
                                    ? c
                                    : Integer.compare(
                                            b.rank, a.rank); // tie → newer (higher rank) first
                        });
        for (int i = 0; i < runs.size(); i++) {
            Cursor c = new Cursor(runs.get(i).reader(), i);
            if (c.advance()) pq.add(c);
        }
        Cursor t = new Cursor(tailCursor(), runs.size()); // tail is newest
        if (t.advance()) pq.add(t);
        return new MergeIterator(pq);
    }

    /**
     * Delete the spilled run files and reset to empty; the buffer remains usable (a flush calls
     * this). The {@link Cleaner} (registered on first spill) is the backstop for a buffer abandoned
     * without this — a rolled-back transaction.
     */
    public void clear() {
        cleanup(runFiles).run(); // deletes run files (failed deletions stay tracked for retry)
        runs.clear();
        tail.clear();
        tailBytes = 0;
        runFilterBytes = 0; // the per-run filters died with their runs
        // The buffer is documented reusable post-clear (a flush calls this);
        // stale hashes would not be WRONG for a filter, but they would poison
        // the reused transaction with false positives — reset with the rest.
        if (presence != null) presence.clear();
    }

    @Override
    public void close() {
        clear();
    }

    // ----- spill -----

    private void spill() {
        // Fail-closed spill-disk quota (D-2): a spill that would push the process-global resident
        // spill bytes past the quota aborts the transaction BEFORE the run file is written — no
        // orphan file, no silent temp-fill. tailBytes (the in-heap estimate of this run) is a
        // conservative over-estimate, so the quota trips slightly early rather than over-filling.
        if (maxSpillDiskBytes > 0 && SPILL_DISK_BYTES.sum() + tailBytes > maxSpillDiskBytes) {
            throw new SpillQuotaExceededException(
                    SPILL_DISK_BYTES.sum(), tailBytes, maxSpillDiskBytes);
        }
        try {
            TOTAL_SPILLS.increment();
            if (cleanable == null)
                cleanable = CLEANER.register(this, cleanup(runFiles)); // lazy: only spillers pay
            Path file = Files.createTempFile(tempDir, "spill-", ".run");
            RunFile runFile = new RunFile(file);
            synchronized (runFiles) {
                runFiles.add(runFile);
            }
            List<byte[]> idxKeys = new ArrayList<>();
            List<Long> idxOffsets = new ArrayList<>();
            // Seal-time filter build: the run's key set is exact and final right
            // now (n = tail.size()), so the filter is optimally shaped — the
            // LSM/SSTable pattern. Tied to the presence opt-in (both are the
            // same heap-for-file-probes trade) and to its byte budget.
            SimpleBloomFilter filter = null;
            if (presence != null) {
                Shape shape = Shape.fromNP(tail.size(), RUN_FILTER_FPP);
                long filterBytes = ((shape.getNumberOfBits() + 63L) / 64) * Long.BYTES;
                if (runFilterBytes + filterBytes <= runFilterBudgetBytes) {
                    filter = new SimpleBloomFilter(shape);
                    runFilterBytes += filterBytes;
                }
            }
            K min = null, max = null;
            long pos = 0;
            int n = 0;
            try (DataOutputStream out =
                    new DataOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(file), 1 << 16))) {
                for (var e : tail.entrySet()) {
                    byte[] k = codec.toBytes(e.getKey()).toArray(BYTE);
                    byte[] v = e.getValue() == null ? null : e.getValue().toArray(BYTE);
                    if (filter != null) {
                        filter.merge(
                                LongPresenceSet.hasherFor(
                                        LongPresenceSet.hashBytes(MemorySegment.ofArray(k))));
                    }
                    if (n % INDEX_STRIDE == 0) {
                        idxKeys.add(k);
                        idxOffsets.add(pos);
                    }
                    if (min == null) min = e.getKey();
                    max = e.getKey();
                    out.writeInt(k.length);
                    out.write(k);
                    out.writeInt(v == null ? -1 : v.length);
                    if (v != null) out.write(v);
                    pos += 4L + k.length + 4L + (v == null ? 0 : v.length);
                    n++;
                }
            }
            runFile.bytes = pos; // the run's on-disk size, known now the write has succeeded
            SPILL_DISK_BYTES.add(pos); // resident on disk until cleanup deletes it
            runs.add(new Run(file, idxKeys, idxOffsets, min, max, filter));
            tail.clear();
            tailBytes = 0;
        } catch (IOException ex) {
            throw new UncheckedIOException("spill failed", ex);
        }
    }

    // ----- a spilled run on disk + its sparse index -----

    private final class Run {
        final Path file;
        final List<byte[]> idxKeys; // every INDEX_STRIDE-th key (bytes)
        final List<Long> idxOffsets; // its byte offset in the file
        final @Nullable K min, max;

        /**
         * Per-run membership filter, built at seal time from exactly this run's keys (the run is
         * immutable from then on — the ideal filter regime), sized by {@code Shape.fromNP} at
         * {@value #RUN_FILTER_FPP} false-positive rate (~1.2 bytes/entry). A reject PROVES the key
         * is not in this run. {@code null} when the presence index is off or the run-filter byte
         * budget was exhausted — those runs take the ordinary file probe.
         */
        final @Nullable SimpleBloomFilter filter;

        Run(
                Path file,
                List<byte[]> idxKeys,
                List<Long> idxOffsets,
                @Nullable K min,
                @Nullable K max,
                @Nullable SimpleBloomFilter filter) {
            this.file = file;
            this.idxKeys = idxKeys;
            this.idxOffsets = idxOffsets;
            this.min = min;
            this.max = max;
            this.filter = filter;
        }

        /**
         * The entry for {@code key} in this run, or {@code null} if absent. Reads one block via the
         * index.
         */
        @Nullable Lookup lookup(K key) {
            if (min == null || keyCmp.compare(key, min) < 0 || keyCmp.compare(key, max) > 0)
                return null;
            int block = floorBlock(key);
            try (DataInputStream in = openAt(idxOffsets.get(block))) {
                int limit = (block + 1 < idxOffsets.size()) ? INDEX_STRIDE : Integer.MAX_VALUE;
                for (int i = 0; i < limit; i++) {
                    byte[] k = readBytes(in);
                    if (k == null) return null;
                    K rk = codec.fromBytes(MemorySegment.ofArray(k));
                    MemorySegment v = readValue(in);
                    int cmp = keyCmp.compare(rk, key);
                    if (cmp == 0) return new Lookup(v);
                    if (cmp > 0) return null; // passed it — sorted, so absent
                }
                return null;
            } catch (IOException ex) {
                throw new UncheckedIOException("run lookup failed", ex);
            }
        }

        private int floorBlock(K key) {
            int lo = 0, hi = idxKeys.size() - 1, ans = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (keyCmp.compare(codec.fromBytes(MemorySegment.ofArray(idxKeys.get(mid))), key)
                        <= 0) {
                    ans = mid;
                    lo = mid + 1;
                } else hi = mid - 1;
            }
            return ans;
        }

        DataInputStream reader() {
            try {
                return new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(file), 1 << 16));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private DataInputStream openAt(long offset) throws IOException {
            InputStream is = Files.newInputStream(file);
            is.skipNBytes(offset);
            return new DataInputStream(new BufferedInputStream(is, 1 << 16));
        }
    }

    // ----- serialization + k-way merge -----

    private static byte @Nullable [] readBytes(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException eof) {
            return null;
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    private static @Nullable MemorySegment readValue(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) return null; // tombstone
        byte[] b = new byte[len];
        in.readFully(b);
        return MemorySegment.ofArray(b);
    }

    private interface Source<K> {
        @Nullable Entry<K> next();
    } // null when exhausted

    private Source<K> tailCursor() {
        Iterator<java.util.Map.Entry<K, MemorySegment>> it = tail.entrySet().iterator();
        return () -> {
            if (!it.hasNext()) return null;
            var e = it.next();
            return new Entry<>(e.getKey(), e.getValue());
        };
    }

    private final class Cursor {
        final int rank;
        final @Nullable DataInputStream runReader; // non-null for a run
        final @Nullable Source<K> source; // non-null for the tail
        @Nullable Entry<K> cur;

        Cursor(DataInputStream runReader, int rank) {
            this.runReader = runReader;
            this.source = null;
            this.rank = rank;
        }

        Cursor(Source<K> source, int rank) {
            this.source = source;
            this.runReader = null;
            this.rank = rank;
        }

        boolean advance() {
            try {
                if (runReader != null) {
                    byte[] k = readBytes(runReader);
                    if (k == null) {
                        runReader.close();
                        cur = null;
                        return false;
                    }
                    cur =
                            new Entry<>(
                                    codec.fromBytes(MemorySegment.ofArray(k)),
                                    readValue(runReader));
                    return true;
                } else {
                    cur = Objects.requireNonNull(source).next();
                    return cur != null;
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /**
     * The {@link #merged()} stream. A {@link java.util.Iterator} that is also {@link
     * AutoCloseable}: a consumer that drains it fully needs no {@code close} (each run reader
     * auto-closes when its run is exhausted), but a consumer that stops <em>early</em> must {@code
     * close} it to release the run-file readers of the runs it didn't finish — otherwise those file
     * descriptors leak until garbage collection. (Pre-2026-06-03 {@code merged()} returned a bare
     * {@code Iterator}, so a partial consume leaked readers; surfaced by the {@code PartialMerge}
     * model action.)
     */
    public interface CloseableEntryIterator<K> extends Iterator<Entry<K>>, AutoCloseable {
        @Override
        void close(); // narrow: no checked exception
    }

    private final class MergeIterator implements CloseableEntryIterator<K> {
        private final PriorityQueue<Cursor> pq;

        MergeIterator(PriorityQueue<Cursor> pq) {
            this.pq = pq;
        }

        @Override
        public boolean hasNext() {
            return !pq.isEmpty();
        }

        /**
         * Close the run readers of every cursor not yet drained (drained cursors already closed
         * theirs).
         */
        @Override
        public void close() {
            for (Cursor c : pq) {
                if (c.runReader != null) {
                    try {
                        c.runReader.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            pq.clear();
        }

        @Override
        public Entry<K> next() {
            if (pq.isEmpty()) throw new NoSuchElementException();
            Cursor top = pq.poll();
            // A cursor is only ever added to pq after advance()==true, which sets cur non-null.
            Entry<K> result = Objects.requireNonNull(top.cur);
            if (top.advance()) pq.add(top);
            // drop older duplicates of this key (the newest already won the tie-break)
            while (!pq.isEmpty()
                    && keyCmp.compare(Objects.requireNonNull(pq.peek().cur).key(), result.key())
                            == 0) {
                Cursor dup = pq.poll();
                if (dup.advance()) pq.add(dup);
            }
            return result;
        }
    }
}
