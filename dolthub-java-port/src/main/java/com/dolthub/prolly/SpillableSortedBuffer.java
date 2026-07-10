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
 * not {@code O(edits)}. {@link #merged()} sorted-merges every run plus the tail into a single
 * ascending stream — exactly the sorted edit stream {@code TreeMutator.applyMutations} requires —
 * so spilling preserves the tree-build precondition for free.
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

    private final TreeMap<K, @Nullable MemorySegment> tail; // the in-heap, newest edits
    private long tailBytes;
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
        this.keyCmp = keyComparator;
        this.codec = codec;
        this.spillThresholdBytes = spillThresholdBytes;
        this.tempDir = tempDir;
        this.tail = new TreeMap<>(keyComparator);
    }

    private static Runnable cleanup(List<RunFile> files) {
        return () -> {
            synchronized (files) {
                for (RunFile rf : files) {
                    try {
                        Files.deleteIfExists(rf.path);
                    } catch (IOException ignored) {
                    }
                    if (rf.bytes != 0) {
                        SPILL_DISK_BYTES.add(-rf.bytes);
                        rf.bytes = 0; // idempotent: clear() then a later Cleaner firing decrements
                        // nothing
                    }
                }
                files.clear();
            }
        };
    }

    /**
     * Stage {@code key → value}; {@code value == null} records a tombstone (delete). Last write
     * wins.
     */
    public void put(K key, @Nullable MemorySegment value) {
        MemorySegment prev = tail.put(key, value);
        tailBytes += codec.toBytes(key).byteSize() + (value == null ? 0 : value.byteSize()) + 48;
        if (prev != null) tailBytes -= prev.byteSize();
        if (tailBytes >= spillThresholdBytes && tail.size() > 1) spill();
    }

    /** True if the buffer holds any entry for {@code key} (an insert <i>or</i> a tombstone). */
    public boolean containsKey(K key) {
        if (tail.containsKey(key)) return true;
        for (int i = runs.size() - 1; i >= 0; i--) {
            if (runs.get(i).lookup(key) != null)
                return true; // present-as-tombstone still "contains"
        }
        return false;
    }

    /**
     * The newest value for {@code key}, or {@code null} if absent <i>or</i> tombstoned (mirror
     * {@code TreeMap}: check {@link #containsKey} to distinguish).
     */
    public @Nullable MemorySegment get(K key) {
        if (tail.containsKey(key)) return tail.get(key);
        for (int i = runs.size() - 1; i >= 0; i--) {
            Lookup lk = runs.get(i).lookup(key);
            if (lk != null) return lk.value; // newest run that has the key wins
        }
        return null;
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
     * A sorted, ascending, last-write-wins iterator over the tail + every run — the flush stream.
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
        cleanup(runFiles).run(); // deletes run files + empties runFiles
        runs.clear();
        tail.clear();
        tailBytes = 0;
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
            K min = null, max = null;
            long pos = 0;
            int n = 0;
            try (DataOutputStream out =
                    new DataOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(file), 1 << 16))) {
                for (var e : tail.entrySet()) {
                    byte[] k = codec.toBytes(e.getKey()).toArray(BYTE);
                    byte[] v = e.getValue() == null ? null : e.getValue().toArray(BYTE);
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
            runs.add(new Run(file, idxKeys, idxOffsets, min, max));
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

        Run(
                Path file,
                List<byte[]> idxKeys,
                List<Long> idxOffsets,
                @Nullable K min,
                @Nullable K max) {
            this.file = file;
            this.idxKeys = idxKeys;
            this.idxOffsets = idxOffsets;
            this.min = min;
            this.max = max;
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
