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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step 4b of {@code plans/prolly-bulk-load.md} — correctness for {@link SpillableSortedBuffer}. The
 * strong test is an oracle: the same put/delete sequence is applied to the buffer (with a
 * <i>tiny</i> spill threshold, so it spills many times) and to a reference {@link TreeMap}; then
 * {@link SpillableSortedBuffer#merged()}, {@link SpillableSortedBuffer#get}, and {@link
 * SpillableSortedBuffer#containsKey} must all match the reference — i.e. spilling to disk changes
 * nothing the consumer sees (the property D-8 needs so a spilled flush equals an in-heap flush).
 */
class SpillableSortedBufferTest {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    /**
     * Unsigned-lexicographic order over key segments — a stand-in for the real tuple comparator.
     */
    private static final Comparator<MemorySegment> LEX =
            (a, b) -> {
                long n = Math.min(a.byteSize(), b.byteSize());
                for (long i = 0; i < n; i++) {
                    int x = Byte.toUnsignedInt(a.get(BYTE, i)),
                            y = Byte.toUnsignedInt(b.get(BYTE, i));
                    if (x != y) return Integer.compare(x, y);
                }
                return Long.compare(a.byteSize(), b.byteSize());
            };

    /** Identity codec — the test's keys are raw {@link MemorySegment}s. */
    private static final SpillableSortedBuffer.KeyCodec<MemorySegment> IDENTITY =
            new SpillableSortedBuffer.KeyCodec<>() {
                @Override
                public MemorySegment toBytes(MemorySegment key) {
                    return key;
                }

                @Override
                public MemorySegment fromBytes(MemorySegment bytes) {
                    return bytes;
                }
            };

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String str(MemorySegment s) {
        return s == null ? null : new String(s.toArray(BYTE), StandardCharsets.UTF_8);
    }

    @Test
    void spilledBufferMatchesReference_merge_get_contains(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(
                        LEX, IDENTITY, 200, dir); // tiny threshold → many spills
        TreeMap<String, String> ref = new TreeMap<>(); // value, or null = tombstone

        Random r = new Random(42);
        String[] keys = {
            "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india",
            "juliet"
        };
        for (int i = 0; i < 600; i++) {
            String k = keys[r.nextInt(keys.length)];
            if (r.nextInt(4) == 0) { // 1-in-4 delete (tombstone)
                buf.put(seg(k), null);
                ref.put(k, null);
            } else {
                String v = "v" + i;
                buf.put(seg(k), seg(v));
                ref.put(k, v);
            }
        }

        assertTrue(buf.spilledRunCount() > 0, "the tiny threshold must have forced spills");

        // (1) merged() == reference, ascending, last-write-wins, tombstones flow through as null
        List<String> got = new ArrayList<>();
        for (Iterator<SpillableSortedBuffer.Entry<MemorySegment>> it = buf.merged();
                it.hasNext(); ) {
            SpillableSortedBuffer.Entry<MemorySegment> e = it.next();
            got.add(str(e.key()) + "=" + str(e.value()));
        }
        List<String> expected =
                ref.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList();
        assertEquals(
                expected,
                got,
                "merged stream must equal the reference (order + last-write-wins + tombstones)");

        // (2) get() / containsKey() == reference for every key (newest value across tail + spilled
        // runs)
        for (String k : keys) {
            assertTrue(
                    buf.containsKey(seg(k)),
                    "every touched key is present (insert or tombstone): " + k);
            assertEquals(ref.get(k), str(buf.get(seg(k))), "newest value for " + k);
        }
        // (3) an untouched key is absent
        assertFalse(buf.containsKey(seg("zzz-absent")));
        buf.close();
    }

    @Test
    void inHeapFootprintStaysBounded(@TempDir Path dir) {
        long threshold = 4096;
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, threshold, dir);
        for (int i = 0; i < 50_000; i++) {
            buf.put(seg(String.format("key-%08d", i)), seg("value-" + i));
        }
        assertTrue(
                buf.spilledRunCount() > 5,
                "a long put stream past the threshold spills repeatedly");
        // The in-heap tail never holds more than ~threshold (+ one entry of slack) regardless of
        // total puts.
        assertTrue(
                buf.inHeapBytes() <= threshold + 256,
                "in-heap footprint bounded by the threshold, not the total ("
                        + buf.inHeapBytes()
                        + ")");
        // And the merged stream is still complete + sorted.
        long count = 0;
        String prev = null;
        for (Iterator<SpillableSortedBuffer.Entry<MemorySegment>> it = buf.merged();
                it.hasNext(); ) {
            String k = str(it.next().key());
            if (prev != null) assertTrue(prev.compareTo(k) < 0, "ascending + deduped");
            prev = k;
            count++;
        }
        assertEquals(50_000, count, "all distinct keys present exactly once");
        buf.close();
    }

    @Test
    void noSpillPathWhenUnderThreshold(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 1 << 20, dir); // huge threshold
        buf.put(seg("b"), seg("2"));
        buf.put(seg("a"), seg("1"));
        buf.put(seg("a"), seg("9")); // overwrite
        assertEquals(0, buf.spilledRunCount(), "stays in heap under a large threshold");
        List<String> got = new ArrayList<>();
        for (Iterator<SpillableSortedBuffer.Entry<MemorySegment>> it = buf.merged();
                it.hasNext(); ) {
            SpillableSortedBuffer.Entry<MemorySegment> e = it.next();
            got.add(str(e.key()) + "=" + str(e.value()));
        }
        assertEquals(List.of("a=9", "b=2"), got);
        buf.close();
    }

    @Test
    void closeDeletesTempRuns(@TempDir Path dir) throws IOException {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 200, dir);
        for (int i = 0; i < 2_000; i++) buf.put(seg(String.format("k-%06d", i)), seg("v"));
        assertTrue(buf.spilledRunCount() > 0);
        try (Stream<Path> s = Files.list(dir)) {
            assertTrue(
                    s.anyMatch(p -> p.getFileName().toString().endsWith(".run")),
                    "run files exist before close");
        }
        buf.close();
        try (Stream<Path> s = Files.list(dir)) {
            assertFalse(
                    s.anyMatch(p -> p.getFileName().toString().endsWith(".run")),
                    "run files deleted on close");
        }
    }

    // ---------- state accessors + accounting (mutation-testing gaps) ----------

    @Test
    void stateAccessorsAndOverwriteAccounting(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 1 << 20, dir); // no spill
        assertTrue(buf.isEmpty());
        assertEquals(0, buf.inHeapBytes());

        buf.put(seg("k"), MemorySegment.ofArray(new byte[256])); // a large value
        buf.put(seg("k2"), seg("y"));
        assertFalse(buf.isEmpty(), "populated buffer is not empty"); // kills isEmpty()->true
        long b1 = buf.inHeapBytes();
        assertTrue(b1 > 0, "in-heap estimate is positive when populated"); // kills inHeapBytes()->0

        buf.put(seg("k"), seg("x")); // overwrite 256B value with 1B
        long b2 = buf.inHeapBytes();
        assertTrue(
                b2 < b1,
                "overwriting a large value with a small one lowers the estimate"); // kills -= -> +=

        buf.clear();
        assertTrue(buf.isEmpty(), "empty after clear"); // kills clear()'s tail.clear() removal
        assertEquals(0, buf.inHeapBytes());
        buf.close();
    }

    // ---------- edge cases ----------

    /** Put filler keys (sorting after {@code z}) until at least one spill has happened. */
    private static void forceSpill(SpillableSortedBuffer<MemorySegment> buf) {
        for (int i = 0; buf.spilledRunCount() == 0 && i < 1000; i++) {
            buf.put(seg("~filler-" + String.format("%04d", i)), seg("0123456789ABCDEF"));
        }
        assertTrue(buf.spilledRunCount() > 0, "filler must have forced a spill");
    }

    @Test
    void emptyBuffer(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 200, dir);
        assertTrue(buf.isEmpty());
        assertFalse(buf.merged().hasNext());
        assertFalse(buf.containsKey(seg("x")));
        assertNull(buf.get(seg("x")));
        buf.close();
    }

    @Test
    void singleOversizedEntryStaysInHeap(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 10, dir);
        buf.put(seg("only"), seg("a-value-far-bigger-than-the-ten-byte-threshold"));
        assertEquals(
                0,
                buf.spilledRunCount(),
                "the size>1 guard keeps a lone entry in heap, never a 1-entry run");
        assertEquals("a-value-far-bigger-than-the-ten-byte-threshold", str(buf.get(seg("only"))));
        buf.close();
    }

    @Test
    void tombstoneInTailOverridesSpilledInsert(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 120, dir);
        buf.put(seg("k"), seg("v"));
        forceSpill(buf); // "k=v" now lives in a run
        buf.put(seg("k"), null); // newer tombstone in the tail
        assertTrue(buf.containsKey(seg("k")), "tombstoned key still 'contains'");
        assertNull(buf.get(seg("k")), "newest (tombstone) wins");
        buf.close();
    }

    @Test
    void reinsertInTailOverridesSpilledTombstone(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 120, dir);
        buf.put(seg("k"), null); // tombstone first
        forceSpill(buf); // tombstone now in a run
        buf.put(seg("k"), seg("v2")); // newer re-insert in the tail
        assertEquals(
                "v2", str(buf.get(seg("k"))), "newest (re-insert) wins over a spilled tombstone");
        buf.close();
    }

    @Test
    void keyFoundOnlyInOldestRun(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 200, dir);
        buf.put(seg("aaa-rare"), seg("found")); // sorts first; lands in the oldest run
        for (int i = 0; i < 300; i++)
            buf.put(seg("zzz-" + String.format("%04d", i)), seg("0123456789"));
        assertTrue(buf.spilledRunCount() >= 2, "multiple newer runs that lack the rare key");
        assertEquals(
                "found",
                str(buf.get(seg("aaa-rare"))),
                "get must search runs newest→oldest and find it");
        assertTrue(buf.containsKey(seg("aaa-rare")));
        buf.close();
    }

    @Test
    void emptyValueAndTombstoneStayDistinctThroughSpill(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 120, dir);
        buf.put(seg("present-empty"), MemorySegment.ofArray(new byte[0])); // present, zero-length
        buf.put(seg("tomb"), null); // tombstone
        forceSpill(buf);
        MemorySegment ev = buf.get(seg("present-empty"));
        assertTrue(buf.containsKey(seg("present-empty")));
        assertNotNull(ev, "a present zero-length value is NOT a tombstone");
        assertEquals(0, ev.byteSize());
        assertTrue(buf.containsKey(seg("tomb")));
        assertNull(buf.get(seg("tomb")), "tombstone reads as null");
        buf.close();
    }

    @Test
    void sparseIndexAcrossBlockBoundaries(@TempDir Path dir) {
        // ~5000 distinct keys with a ~256 KiB threshold → runs spanning several 1024-key
        // sparse-index blocks.
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 256 * 1024, dir);
        int n = 5000;
        for (int i = 0; i < n; i++)
            buf.put(seg("key-" + String.format("%06d", i)), seg("val-" + i));
        assertTrue(buf.spilledRunCount() > 0, "must have spilled at least one multi-block run");
        for (int i = 0; i < n; i++) { // every key resolves correctly via floorBlock + block scan
            assertEquals(
                    "val-" + i, str(buf.get(seg("key-" + String.format("%06d", i)))), "key " + i);
        }
        assertNull(buf.get(seg("key-999999")), "a key inside [min,max] but absent → null");
        assertNull(buf.get(seg("aaa-below-min")), "below min → null");
        buf.close();
    }

    @Test
    void prefixKeysOrderAndLookup(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 60, dir);
        for (String k : List.of("ab", "a", "aaa", "b", "aa")) buf.put(seg(k), seg("=" + k));
        forceSpill(buf);
        // LEX order: a < aa < aaa < ab < b  (a prefix sorts before its extension)
        List<String> order = new ArrayList<>();
        for (Iterator<SpillableSortedBuffer.Entry<MemorySegment>> it = buf.merged();
                it.hasNext(); ) {
            String k = str(it.next().key());
            if (!k.startsWith("~filler")) order.add(k);
        }
        assertEquals(List.of("a", "aa", "aaa", "ab", "b"), order);
        for (String k : List.of("ab", "a", "aaa", "b", "aa"))
            assertEquals("=" + k, str(buf.get(seg(k))));
        buf.close();
    }

    @Test
    void binaryKeysWithEmbeddedZeroBytes(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 60, dir);
        byte[][] keys = {{0, 1}, {1, 0}, {0, 0, 0}, {0}, {1}};
        for (int i = 0; i < keys.length; i++) buf.put(MemorySegment.ofArray(keys[i]), seg("v" + i));
        forceSpill(buf);
        for (int i = 0; i < keys.length; i++) {
            assertEquals(
                    "v" + i,
                    str(buf.get(MemorySegment.ofArray(keys[i]))),
                    "embedded-zero key " + i);
        }
        buf.close();
    }

    @Test
    void largeValueRoundTripsThroughSpill(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 200, dir);
        byte[] big = new byte[200_000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        buf.put(seg("big"), MemorySegment.ofArray(big));
        forceSpill(buf);
        MemorySegment got = buf.get(seg("big"));
        assertNotNull(got);
        assertArrayEquals(big, got.toArray(BYTE), "a 200 KB value survives serialization + spill");
        buf.close();
    }

    @Test
    void reuseAfterClear(@TempDir Path dir) throws IOException {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 200, dir);
        buf.put(seg("a"), seg("1"));
        forceSpill(buf);
        buf.clear();
        assertTrue(buf.isEmpty());
        assertEquals(0, buf.spilledRunCount());
        try (Stream<Path> s = Files.list(dir)) {
            assertFalse(
                    s.anyMatch(p -> p.getFileName().toString().endsWith(".run")),
                    "clear() deletes run files");
        }
        // reusable: a fresh round works
        buf.put(seg("b"), seg("2"));
        buf.put(seg("a"), seg("9"));
        List<String> got = new ArrayList<>();
        for (Iterator<SpillableSortedBuffer.Entry<MemorySegment>> it = buf.merged();
                it.hasNext(); ) {
            SpillableSortedBuffer.Entry<MemorySegment> e = it.next();
            got.add(str(e.key()) + "=" + str(e.value()));
        }
        assertEquals(List.of("a=9", "b=2"), got);
        buf.close();
    }

    @Test
    void partialMergeConsumptionThenCloseIsClean(@TempDir Path dir) throws IOException {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 200, dir);
        for (int i = 0; i < 500; i++)
            buf.put(seg("k-" + String.format("%04d", i)), seg("0123456789"));
        assertTrue(buf.spilledRunCount() > 0);
        Iterator<SpillableSortedBuffer.Entry<MemorySegment>> it = buf.merged();
        it.next(); // consume only the first entry, abandon the rest (open run readers)
        buf.close(); // must not throw + must delete the run files
        try (Stream<Path> s = Files.list(dir)) {
            assertFalse(
                    s.anyMatch(p -> p.getFileName().toString().endsWith(".run")),
                    "close after partial merge consumption still cleans up");
        }
    }

    // ----- merged() iterator close() — the CloseableEntryIterator contract (2026-06-03 fix) -----

    @Test
    void mergedIteratorCloseOnUnspilledBufferIsSafeAndExhausts(@TempDir Path dir) {
        // Huge threshold → tail-only, so merged()'s only cursor is the tail (runReader == null).
        // close() must
        // skip it without NPE (kills the `if (runReader != null)` negation), then report exhausted
        // (kills the
        // `pq.clear()` removal), and be idempotent.
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 1 << 20, dir);
        buf.put(seg("a"), seg("1"));
        buf.put(seg("b"), seg("2"));
        assertEquals(0, buf.spilledRunCount(), "huge threshold → tail-only, no run readers");
        SpillableSortedBuffer.CloseableEntryIterator<MemorySegment> it = buf.merged();
        assertTrue(it.hasNext());
        it.next(); // partial consume, "b" still queued
        it.close(); // tail cursor's runReader is null — must NOT NPE
        assertFalse(it.hasNext(), "a closed iterator is exhausted");
        it.close(); // idempotent: a second close is a no-op
        buf.close();
    }

    @Test
    void mergedIteratorCloseReleasesRunFileDescriptors(@TempDir Path dir) {
        // Behavioural proof of the fix: partial-consume-then-close, 300×, must NOT leak run-file
        // descriptors.
        // Needs a Unix OperatingSystemMXBean for getOpenFileDescriptorCount; skipped elsewhere.
        java.lang.management.OperatingSystemMXBean os =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                os instanceof com.sun.management.UnixOperatingSystemMXBean,
                "fd-count assertion needs a Unix OperatingSystemMXBean");
        com.sun.management.UnixOperatingSystemMXBean unix =
                (com.sun.management.UnixOperatingSystemMXBean) os;

        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir);
        for (int i = 0; i < 3000; i++) buf.put(seg("k-" + (i % 60)), seg("v" + i));
        int runs = buf.spilledRunCount();
        assertTrue(
                runs > 1,
                "need several spilled runs to exercise multi-reader close (got " + runs + ")");

        System.gc(); // stabilise the baseline (reclaim any prior transients)
        long base = unix.getOpenFileDescriptorCount();
        for (int i = 0; i < 300; i++) {
            try (SpillableSortedBuffer.CloseableEntryIterator<MemorySegment> it = buf.merged()) {
                if (it.hasNext())
                    it.next(); // partial → undrained run readers, released only by close()
            }
        }
        long leaked = unix.getOpenFileDescriptorCount() - base;
        assertTrue(
                leaked <= runs + 8L,
                "close() must release run-file fds; 300 partial merges leaked "
                        + leaked
                        + " fds (runs="
                        + runs
                        + ")");
        buf.close();
    }

    @Test
    void totalSpillsCounterRisesWhenABufferSpills(@TempDir Path dir) {
        // Covers the process-global spill metric (TOTAL_SPILLS / totalSpills()), previously
        // untested:
        // kills both "removed TOTAL_SPILLS.increment()" and "totalSpills() returns 0".
        long before = SpillableSortedBuffer.totalSpills();
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir);
        for (int i = 0; i < 500; i++) buf.put(seg("k-" + i), seg("0123456789"));
        assertTrue(buf.spilledRunCount() > 0, "the tiny threshold must have spilled");
        assertTrue(
                SpillableSortedBuffer.totalSpills() > before,
                "the process-global spill counter must rise when a buffer spills");
        buf.close();
    }

    @Test
    void spillDiskBytesTracksResidentRunsAndIsReleasedOnClose(@TempDir Path dir) {
        // Covers the resident spill-disk-bytes counter (SPILL_DISK_BYTES /
        // currentSpillDiskBytes()):
        // it RISES while runs are on disk and FALLS back to the baseline when close() deletes them.
        // The baseline absorbs any bytes other (closed-test) buffers left, so the equality is
        // robust
        // to test order under sequential execution.
        long before = SpillableSortedBuffer.currentSpillDiskBytes();
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir);
        for (int i = 0; i < 500; i++) buf.put(seg("k-" + i), seg("0123456789"));
        assertTrue(buf.spilledRunCount() > 0, "the tiny threshold must have spilled");
        assertTrue(
                SpillableSortedBuffer.currentSpillDiskBytes() > before,
                "resident spill-disk bytes rise while run files are on disk");
        buf.close();
        assertEquals(
                before,
                SpillableSortedBuffer.currentSpillDiskBytes(),
                "close() deletes the runs and releases the tracked bytes back to the baseline");
    }

    @Test
    void spillDiskQuotaFailsClosedAndLeavesNoOrphan(@TempDir Path dir) {
        // D-2 fail-closed guard: a spill that would push process-global resident bytes past the
        // quota aborts BEFORE writing the run file. Quota=1 byte trips on the first spill
        // (tailBytes
        // alone is >= the 64-byte threshold), independent of any bytes other tests left resident.
        long before = SpillableSortedBuffer.currentSpillDiskBytes();
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir);
        buf.setMaxSpillDiskBytes(1);
        SpillQuotaExceededException ex =
                assertThrows(
                        SpillQuotaExceededException.class,
                        () -> {
                            for (int i = 0; i < 500; i++) buf.put(seg("k-" + i), seg("0123456789"));
                        },
                        "a spill over the quota must abort the transaction");
        assertTrue(
                ex.getMessage().contains("quota exceeded"),
                "the typed error names the quota breach: " + ex.getMessage());
        assertEquals(
                0,
                buf.spilledRunCount(),
                "the pre-write check leaves no run on disk (the quota tripped before the file write)");
        assertEquals(
                before,
                SpillableSortedBuffer.currentSpillDiskBytes(),
                "no orphan bytes are charged when a spill is refused");
        buf.close();
    }

    @Test
    void spillUnderQuotaSucceeds(@TempDir Path dir) {
        // The quota check must not false-positive: with a generous (non-zero) quota, the same
        // workload that trips quota=1 above spills freely. This exercises the maxSpillDiskBytes > 0
        // branch on its under-budget path (the negation of the fail-closed case above).
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir);
        buf.setMaxSpillDiskBytes(64L * 1024 * 1024); // 64 MiB — far above this tiny workload
        for (int i = 0; i < 500; i++) buf.put(seg("k-" + i), seg("0123456789"));
        assertTrue(
                buf.spilledRunCount() > 0,
                "an under-quota workload spills normally (the check does not block it)");
        buf.close();
    }

    // ----- the last documented survivors, killed via package-private state hooks (2026-06-03, "do
    // all") -----

    @Test
    void cleanerIsRegisteredLazilyOnFirstSpill(
            @TempDir Path dir) { // kills negate of `if (cleanable == null)`
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 200, dir);
        assertFalse(
                buf.cleanerRegisteredForTest(),
                "no GC-backstop Cleaner before any spill (lazy registration)");
        forceSpill(buf);
        assertTrue(buf.cleanerRegisteredForTest(), "the Cleaner is registered on the first spill");
        buf.close();
    }

    @Test
    void clearEmptiesTheRunFileBookkeeping(@TempDir Path dir) { // kills removal of `files.clear()`
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 200, dir);
        forceSpill(buf);
        assertTrue(buf.runFileCountForTest() > 0, "spilling tracks run-file paths");
        buf.clear();
        assertEquals(
                0,
                buf.runFileCountForTest(),
                "clear() empties the run-file list (the cleanup's files.clear())");
        buf.close();
    }

    @Test
    void sparseIndexSamplesMoreThanOnceForALargeRun(
            @TempDir Path dir) { // kills `n % STRIDE` -> `n * STRIDE`
        // Per-entry tailBytes = key + value + 48 (see put()): 6 + 1 + 48 = 55. A 70 000-byte
        // threshold first
        // spills at ~1273 distinct entries → one run with >1024 entries → the sparse index samples
        // at n=0 AND
        // n=1024 (size 2). The modulus→multiply mutant makes `n * STRIDE == 0` true only at n=0
        // (size 1).
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 70_000, dir);
        assertEquals(-1, buf.firstRunIndexSizeForTest(), "no spilled run yet → -1");
        for (int i = 0; i < 1400; i++) buf.put(seg("k" + String.format("%05d", i)), seg("v"));
        assertTrue(buf.spilledRunCount() >= 1, "the big tail must have spilled into a run");
        assertTrue(
                buf.firstRunIndexSizeForTest() >= 2,
                "a >1024-entry run samples the sparse index >=2x (n=0 and n=1024); the mutant collapses it to 1 (got "
                        + buf.firstRunIndexSizeForTest()
                        + ")");
        buf.close();
    }

    @Test
    void spillTriggersExactlyWhenTailBytesEqualsThreshold(
            @TempDir Path dir) { // kills `>=` -> `>` boundary
        // Per-entry tailBytes = key(2) + value(1) + 48 = 51. Five distinct entries → tailBytes ==
        // 255 exactly.
        // With `>=` the 5th put spills (255 >= 255); the `>` boundary mutant would NOT (255 > 255
        // is false).
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 5 * 51, dir);
        for (int i = 0; i < 5; i++) buf.put(seg("a" + i), seg("x"));
        assertEquals(
                1,
                buf.spilledRunCount(),
                "tailBytes hits the threshold exactly at the 5th entry → `>=` spills; the `>` boundary mutant would not");
        buf.close();
    }

    // ── the opt-in presence index ───────────────────────────────────────────

    /**
     * Oracle equivalence: the SAME operation sequence, index off vs on, must give identical lookup
     * answers and an identical merged() stream — the index is a pure accelerator.
     */
    @Test
    void presenceIndexIsObservationallyEquivalentToTheProbePath(@TempDir Path dir)
            throws IOException {
        SpillableSortedBuffer<MemorySegment> plain =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir.resolve("p"));
        SpillableSortedBuffer<MemorySegment> indexed =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir.resolve("i"), true);
        Files.createDirectories(dir.resolve("p"));
        Files.createDirectories(dir.resolve("i"));
        java.util.Random rnd = new java.util.Random(11);
        for (int i = 0; i < 400; i++) {
            String k = "k" + rnd.nextInt(60);
            if (rnd.nextInt(4) == 0) {
                plain.put(seg(k), null);
                indexed.put(seg(k), null);
            } else {
                String v = "v" + rnd.nextInt(1000);
                plain.put(seg(k), seg(v));
                indexed.put(seg(k), seg(v));
            }
        }
        assertTrue(indexed.spilledRunCount() >= 3, "the indexed buffer must be in spill regime");
        for (int i = 0; i < 120; i++) { // present, tombstoned, and absent keys alike
            String k = "k" + i;
            assertEquals(plain.containsKey(seg(k)), indexed.containsKey(seg(k)), k);
            assertEquals(str(plain.get(seg(k))), str(indexed.get(seg(k))), k);
        }
        try (SpillableSortedBuffer.CloseableEntryIterator<MemorySegment> a = plain.merged();
                SpillableSortedBuffer.CloseableEntryIterator<MemorySegment> b = indexed.merged()) {
            while (a.hasNext() || b.hasNext()) {
                assertEquals(a.hasNext(), b.hasNext(), "stream lengths must match");
                SpillableSortedBuffer.Entry<MemorySegment> ea = a.next(), eb = b.next();
                assertEquals(str(ea.key()), str(eb.key()));
                assertEquals(str(ea.value()), str(eb.value()));
            }
        }
        plain.close();
        indexed.close();
    }

    @Test
    void tombstonesStayContainedWithThePresenceIndex(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir, true);
        buf.put(seg("gone"), seg("v"));
        buf.put(seg("gone"), null); // tombstone — a put, so the index knows it
        forceSpill(buf);
        assertTrue(buf.containsKey(seg("gone")), "tombstoned is contained");
        assertEquals(null, buf.get(seg("gone")), "…but its value is the tombstone null");
        assertEquals(false, buf.containsKey(seg("never")), "absent short-circuits correctly");
        buf.close();
    }

    @Test
    void clearResetsThePresenceIndexForReuse(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir, true);
        forceSpill(buf);
        assertTrue(buf.presenceSizeForTest() > 0);
        buf.clear();
        assertEquals(0, buf.presenceSizeForTest(), "clear resets the index with everything else");
        buf.put(seg("again"), seg("v")); // reusable, index live again
        assertTrue(buf.containsKey(seg("again")));
        assertEquals(1, buf.presenceSizeForTest());
        buf.close();
    }

    @Test
    void thePresenceIndexRegistersEveryDistinctPut(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> off =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 1 << 20, dir);
        assertEquals(-1, off.presenceSizeForTest(), "off means off");
        off.close();

        SpillableSortedBuffer<MemorySegment> on =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 1 << 20, dir, true);
        for (int i = 0; i < 100; i++) on.put(seg("k" + i), seg("v"));
        for (int i = 0; i < 100; i++) on.put(seg("k" + i), seg("w")); // overwrites: same keys
        assertEquals(100, on.presenceSizeForTest(), "overwrites add no new distinct keys");
        on.close();
    }

    // ── per-run filters (the hit-side complement to the presence index) ─────

    /**
     * Every sealed run of a presence-enabled buffer carries a filter, and lookups stay correct
     * through the filter-skip path (a present key's newest value found even when newer runs'
     * filters reject it). With the filter byte budget at zero, runs seal filterless and the same
     * lookups take the plain walk — the graceful-degradation path.
     */
    @Test
    void perRunFiltersAcceleratePresentKeysAndDegradeGracefully(@TempDir Path dir)
            throws IOException {
        SpillableSortedBuffer<MemorySegment> filtered =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir.resolve("f"), true);
        SpillableSortedBuffer<MemorySegment> unfiltered =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir.resolve("u"), true);
        Files.createDirectories(dir.resolve("f"));
        Files.createDirectories(dir.resolve("u"));
        unfiltered.setRunFilterBudgetBytesForTest(0);
        for (int i = 0; i < 200; i++) {
            String k = "k" + (i % 50), v = "v" + i; // overwrites spread keys across many runs
            filtered.put(seg(k), seg(v));
            unfiltered.put(seg(k), seg(v));
        }
        assertTrue(filtered.spilledRunCount() >= 3, "must be in spill regime");
        assertEquals(
                filtered.spilledRunCount(),
                filtered.runFilterCountForTest(),
                "every sealed run carries a filter within budget");
        assertEquals(0, unfiltered.runFilterCountForTest(), "budget zero seals runs filterless");
        for (int i = 0; i < 60; i++) { // present keys and absent keys alike, both buffers agree
            String k = "k" + i;
            assertEquals(str(unfiltered.get(seg(k))), str(filtered.get(seg(k))), k);
            assertEquals(unfiltered.containsKey(seg(k)), filtered.containsKey(seg(k)), k);
        }
        filtered.clear();
        assertEquals(0, filtered.runFilterCountForTest(), "clear drops the filters with the runs");
        filtered.close();
        unfiltered.close();
    }

    /**
     * THE COST PIN for the hit-side point-lookup path (hardening round 1, promoted from the manual
     * {@code PresenceScaleProbe} measurement: hit walks fell ~1.8 ms → 151 µs when per-run filters
     * landed — this asserts the same claim as deterministic FILE-PROBE COUNTS, so a regression
     * gates the build instead of waiting for a human to re-run the probe).
     *
     * <p>Data engineered so the min/max screen CANNOT prune (the middle-range era-1 keys are
     * bracketed by every later run, like real hash-shaped dictionary keys): era 1 stages the
     * looked-up keys; eras 2..5 stage disjoint keys on BOTH sides of them. Unfiltered, a hit on an
     * era-1 key must file-probe every newer run before reaching its run; with per-run filters,
     * newer runs REJECT without opening. The pin: filtered probes stay ~1 per lookup (plus a
     * generous false-positive allowance), and the filtered:unfiltered ratio is at least 2×.
     */
    @Test
    void perRunFiltersBoundHitPathFileProbes(@TempDir Path dir) throws IOException {
        SpillableSortedBuffer<MemorySegment> filtered =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir.resolve("f"), true);
        SpillableSortedBuffer<MemorySegment> unfiltered =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir.resolve("u"), true);
        unfiltered.setRunFilterBudgetBytesForTest(0);
        Files.createDirectories(dir.resolve("f"));
        Files.createDirectories(dir.resolve("u"));
        // Era 1: the keys we will look up — the MIDDLE of the key space.
        for (int i = 0; i < 25; i++) {
            String k = String.format("k-5%02d", i); // k-500..k-524
            filtered.put(seg(k), seg("era1"));
            unfiltered.put(seg(k), seg("era1"));
        }
        // Eras 2..5: disjoint keys bracketing era 1 on both sides, so every
        // later run's [min,max] contains the era-1 keys and the screen is dead.
        for (int era = 2; era <= 5; era++) {
            for (int i = 0; i < 13; i++) {
                String lo = String.format("k-1%02d-e%d", i, era);
                String hi = String.format("k-9%02d-e%d", i, era);
                filtered.put(seg(lo), seg("x"));
                filtered.put(seg(hi), seg("x"));
                unfiltered.put(seg(lo), seg("x"));
                unfiltered.put(seg(hi), seg("x"));
            }
        }
        forceSpill(filtered); // both tails sealed: every key now lives in a run
        forceSpill(unfiltered);
        assertTrue(filtered.spilledRunCount() >= 4, "need several runs for the walk to matter");
        int lookups = 25;
        long f0 = SpillableSortedBuffer.totalRunFileProbes();
        for (int i = 0; i < lookups; i++) {
            assertEquals("era1", str(filtered.get(seg(String.format("k-5%02d", i)))));
        }
        long filteredProbes = SpillableSortedBuffer.totalRunFileProbes() - f0;
        long u0 = SpillableSortedBuffer.totalRunFileProbes();
        for (int i = 0; i < lookups; i++) {
            assertEquals("era1", str(unfiltered.get(seg(String.format("k-5%02d", i)))));
        }
        long unfilteredProbes = SpillableSortedBuffer.totalRunFileProbes() - u0;
        assertTrue(
                filteredProbes <= lookups * 2L,
                "with per-run filters a hit is ~one file probe (got "
                        + filteredProbes
                        + " for "
                        + lookups
                        + " lookups; 2x bound allows filter false positives)");
        assertTrue(
                unfilteredProbes >= filteredProbes * 2,
                "the filters must actually be doing the skipping: unfiltered="
                        + unfilteredProbes
                        + " vs filtered="
                        + filteredProbes);
        filtered.close();
        unfiltered.close();
    }

    /** The single-walk three-way: newest value, tombstone null, or the ABSENT sentinel. */
    @Test
    void getRawDistinguishesPresentTombstoneAbsentInOneWalk(@TempDir Path dir) {
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, dir, true);
        assertSame(
                SpillableSortedBuffer.ABSENT,
                buf.getRaw(seg("x")),
                "an empty buffer answers absent before any hashing");
        buf.put(seg("alive"), seg("v1"));
        buf.put(seg("dead"), seg("v"));
        buf.put(seg("dead"), null);
        forceSpill(buf);
        buf.put(seg("alive"), seg("v2")); // newest value lives in the tail, older in a run
        assertEquals("v2", str((MemorySegment) buf.getRaw(seg("alive"))));
        assertEquals(null, buf.getRaw(seg("dead")), "a tombstone is a present null");
        assertSame(SpillableSortedBuffer.ABSENT, buf.getRaw(seg("never")));
        buf.close();
    }

    /**
     * A run file whose deletion fails must stay TRACKED and COUNTED: dropping it would under-count
     * the spill-disk quota gauge while the bytes sit resident (the fail-closed quota's one blind
     * spot), and forgetting the path forfeits the retry. The next cleanup retries and succeeds.
     */
    @Test
    void failedRunFileDeletionKeepsQuotaAndTrackingForRetry(@TempDir Path dir) throws IOException {
        Path runDir = Files.createDirectories(dir.resolve("locked"));
        SpillableSortedBuffer<MemorySegment> buf =
                new SpillableSortedBuffer<>(LEX, IDENTITY, 64, runDir);
        forceSpill(buf);
        assertTrue(buf.runFileCountForTest() > 0);
        long before = SpillableSortedBuffer.currentSpillDiskBytes();
        java.util.Set<java.nio.file.attribute.PosixFilePermission> rw =
                Files.getPosixFilePermissions(runDir);
        Files.setPosixFilePermissions(
                runDir,
                java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        try {
            buf.clear();
            assertTrue(
                    buf.runFileCountForTest() > 0,
                    "failed deletion keeps the run file tracked for retry");
            assertEquals(
                    before,
                    SpillableSortedBuffer.currentSpillDiskBytes(),
                    "the quota gauge still counts the undeleted bytes");
        } finally {
            Files.setPosixFilePermissions(runDir, rw);
        }
        buf.clear(); // the retry: deletion now succeeds
        assertEquals(0, buf.runFileCountForTest());
        assertTrue(SpillableSortedBuffer.currentSpillDiskBytes() <= before);
        buf.close();
    }
}
