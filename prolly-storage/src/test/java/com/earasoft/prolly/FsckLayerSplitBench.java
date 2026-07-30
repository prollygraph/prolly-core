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
package com.earasoft.prolly;

import com.dolthub.prolly.HashUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

/**
 * Which layer does a whole-store integrity check (an "fsck") actually spend its time in?
 *
 * <p>The question this answers is NOT "how fast is fsck" but "what fraction of it is hashing" —
 * because that fraction is the hard ceiling on any accelerator, GPU included. If reading every
 * chunk out of the store dominates, then no amount of hashing throughput can help, and the whole
 * idea is dead before a kernel is written. Amdahl, applied before the work rather than after.
 *
 * <p>Three passes over the same store, in this order, each timed separately:
 *
 * <ol>
 *   <li><b>read-only</b> — iterate every chunk, touch its bytes, hash nothing. The floor.
 *   <li><b>read+hash</b> — the real fsck: re-hash each chunk and compare to its key.
 *   <li><b>hash-only</b> — hash the already-resident bytes again, no store access. The arithmetic.
 * </ol>
 *
 * <p>The read pass runs first deliberately, so the operating-system page cache is warm for the
 * read+hash pass: that biases the measurement AGAINST the conclusion this bench is likely to reach
 * (a warm cache makes reads look cheap, which flatters the accelerator case). If hashing still
 * fails to dominate under conditions favourable to it, the negative result is solid.
 *
 * <h4>Result (2026-07-29, three soak-produced stores, 1,940 chunks / 9.1 MiB each)</h4>
 *
 * <p><b>fsck is read-bound, not hash-bound — an accelerator is not worth building.</b>
 *
 * <pre>
 *   read-only    43.9 ms   207 MiB/s
 *   read+hash    68.0 ms   133 MiB/s
 *   hash-only    22.1 ms   410 MiB/s     &lt;- one core, already faster than the store can feed it
 *
 *   hashing = 15-33% of fsck across the three stores
 *   Amdahl ceiling: 1.18-1.48x even with INFINITELY fast hashing; ~1.16-1.42x for a 10x device
 * </pre>
 *
 * <p>Two consequences. First, hashing on a single core (410 MiB/s) already outruns the store's read
 * throughput (63-207 MiB/s), so neither a device nor extra CPU threads can help — the lever for a
 * faster fsck is the READ path (parallel iteration across files, readahead, or verifying chunks
 * that are already being read for another reason). Second, the measurement was deliberately biased
 * in favour of the accelerator — the read pass runs first, leaving the page cache warm so reads
 * look as cheap as possible — and the conclusion held anyway.
 *
 * <p><b>Implementation note for a real fsck:</b> the default column family also holds {@code
 * prolly_format_version} and {@code prolly_hash_algorithm}, which are NOT content-addressed. A
 * naive whole-family check reports them as two corrupt chunks in every store; skip non-hash-length
 * keys or it raises a false alarm on a healthy store.
 *
 * <p>Run: {@code java -cp <test-classpath> com.earasoft.prolly.FsckLayerSplitBench <store-dir>}
 */
public final class FsckLayerSplitBench {

    private FsckLayerSplitBench() {}

    static {
        RocksDB.loadLibrary();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: FsckLayerSplitBench <rocksdb-store-dir>");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        if (!Files.isDirectory(dir)) {
            System.err.println("not a directory: " + dir);
            System.exit(2);
        }

        try (org.rocksdb.Options opts = new org.rocksdb.Options().setCreateIfMissing(false);
                RocksDB db = RocksDB.openReadOnly(opts, dir.toString())) {
            ColumnFamilyHandle cf = db.getDefaultColumnFamily();

            // ---- pass 1: read only (also collects the bytes for pass 3) --------------
            List<byte[]> keys = new ArrayList<>();
            List<byte[]> vals = new ArrayList<>();
            long bytes = 0;
            long t0 = System.nanoTime();
            try (RocksIterator it = db.newIterator(cf)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    byte[] k = it.key();
                    byte[] v = it.value();
                    bytes += v.length;
                    keys.add(k);
                    vals.add(v);
                }
            }
            long readNs = System.nanoTime() - t0;
            int n = keys.size();
            if (n == 0) {
                System.out.println("[fsck-split] store has no chunks — nothing to measure");
                return;
            }

            // ---- pass 2: the real fsck (read + hash + compare) -----------------------
            long t1 = System.nanoTime();
            long mismatches = 0;
            try (RocksIterator it = db.newIterator(cf)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    byte[] k = it.key();
                    byte[] actual = HashUtils.hash(it.value());
                    if (!java.util.Arrays.equals(k, actual)) mismatches++;
                }
            }
            long fsckNs = System.nanoTime() - t1;

            // ---- pass 3: hashing alone, bytes already in memory ---------------------
            long t2 = System.nanoTime();
            long sink = 0;
            for (byte[] v : vals) sink += HashUtils.hash(v)[0];
            long hashNs = System.nanoTime() - t2;

            double mb = bytes / (1024.0 * 1024.0);
            double readMs = readNs / 1e6, fsckMs = fsckNs / 1e6, hashMs = hashNs / 1e6;
            double hashFrac = hashNs / (double) fsckNs;

            System.out.printf(
                    "[fsck-split] chunks=%,d bytes=%.1f MiB avg-chunk=%.0f B%n",
                    n, mb, bytes / (double) n);
            System.out.printf(
                    "[fsck-split] read-only   %8.1f ms  (%.0f MiB/s)%n",
                    readMs, mb / (readMs / 1000));
            System.out.printf(
                    "[fsck-split] read+hash   %8.1f ms  (%.0f MiB/s)  mismatches=%d%n",
                    fsckMs, mb / (fsckMs / 1000), mismatches);
            System.out.printf(
                    "[fsck-split] hash-only   %8.1f ms  (%.0f MiB/s)%n",
                    hashMs, mb / (hashMs / 1000));
            System.out.printf("[fsck-split] hashing is %.0f%% of fsck time%n", 100 * hashFrac);

            // Amdahl: the best any accelerator can do, even at infinite hashing speed.
            System.out.printf(
                    "[fsck-split] CEILING: infinite-speed hashing gives %.2fx overall; "
                            + "a realistic 10x device gives %.2fx%n",
                    1.0 / (1.0 - hashFrac), 1.0 / ((1.0 - hashFrac) + hashFrac / 10.0));
            System.out.println("[fsck-split] sink=" + sink);
        }
    }
}
