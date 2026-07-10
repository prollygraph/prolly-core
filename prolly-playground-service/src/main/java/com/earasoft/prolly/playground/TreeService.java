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
package com.earasoft.prolly.playground;

import com.dolthub.prolly.Cursor;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.dolthub.prolly.TypeCodec;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The real engine behind the playground: one in-memory content-addressed store, one current tree of
 * int64 keys. Every mutation runs the actual {@code MutableMap} → {@code TreeMutator} write path
 * and reports which nodes it truly minted; every node view is parsed from the stored bytes by the
 * actual {@code Node} reader and re-hashed against its name.
 *
 * <p>Keys use the binary-parity int64 descriptor — the engine's own lesson: plain little-endian
 * int64 does NOT byte-sort numerically, parity mode lex-flips so it does.
 */
@Service
public class TreeService {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);

    private final String storeKind; // memory | file | rocks
    private final java.nio.file.@Nullable Path storeDir;
    private RecordingNodeStore store; // swapped only by reset(); every access is synchronized
    private @Nullable Node root;
    private @Nullable String rootHash;

    /** The ephemeral default — tests and the no-config boot. */
    public TreeService() {
        this("memory", "");
    }

    /**
     * {@code playground.store}: {@code memory} (default, ephemeral), {@code file} (a {@code
     * FileNodeStore} — one file per chunk under {@code <store-dir>/chunks}, the content-addressed
     * store visible to {@code ls}), or {@code rocks} (the production {@code RocksNodeStore} under
     * {@code <store-dir>/db}). Disk modes persist the root pointer as a plain {@code
     * <store-dir>/head} file (loose-refs style) and survive restarts.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TreeService(
            @org.springframework.beans.factory.annotation.Value("${playground.store:memory}")
                    String storeKind,
            @org.springframework.beans.factory.annotation.Value("${playground.store-dir:}")
                    String storeDir) {
        this.storeKind = storeKind.toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("memory", "file", "rocks").contains(this.storeKind)) {
            throw new IllegalArgumentException(
                    "playground.store must be memory|file|rocks, got: " + storeKind);
        }
        boolean disk = !this.storeKind.equals("memory");
        if (disk && storeDir.isBlank()) {
            throw new IllegalArgumentException(
                    "playground.store=" + this.storeKind + " needs playground.store-dir");
        }
        this.storeDir = disk ? java.nio.file.Path.of(storeDir) : null;
        this.store = openStore();
        bootFromHead();
    }

    private RecordingNodeStore openStore() {
        if (storeDir != null) {
            try {
                java.nio.file.Files.createDirectories(storeDir); // reset wipes it whole
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("creating the store dir failed", e);
            }
        }
        switch (storeKind) {
            case "file" -> {
                var f = new com.earasoft.prolly.storage.FileNodeStore(storeDir.resolve("chunks"));
                RecordingNodeStore r = new RecordingNodeStore(f);
                r.seed(f.hashes()); // a reopened store's chunks show in /nodes
                return r;
            }
            case "rocks" -> {
                try {
                    // No public enumeration on RocksNodeStore: after a reopen, /nodes lists only
                    // this process's writes; the tree walk (/tree/nodes) is always complete.
                    return new RecordingNodeStore(
                            new com.earasoft.prolly.storage.RocksNodeStore(
                                    storeDir.resolve("db").toString()));
                } catch (org.rocksdb.RocksDBException e) {
                    throw new IllegalStateException("opening the RocksDB store failed", e);
                }
            }
            default -> {
                return new RecordingNodeStore();
            }
        }
    }

    /** Disk modes resume where they left off: {@code head} names the root, the store has it. */
    private void bootFromHead() {
        if (storeDir == null) return;
        java.nio.file.Path head = storeDir.resolve("head");
        try {
            if (!java.nio.file.Files.exists(head)) return;
            String hex = java.nio.file.Files.readString(head).trim();
            if (hex.isEmpty()) return;
            MemorySegment seg =
                    store.read(HashUtils.fromHex(hex))
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "head points at a chunk the store lacks: "
                                                            + hex));
            root = Node.fromBytes(seg);
            rootHash = hex;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("reading head failed", e);
        }
    }

    /** Publish the root pointer atomically (write-then-move) — the manifest, minimally. */
    private void persistHead() {
        if (storeDir == null) return;
        java.nio.file.Path head = storeDir.resolve("head");
        try {
            if (rootHash == null) {
                java.nio.file.Files.deleteIfExists(head);
                return;
            }
            java.nio.file.Path tmp = storeDir.resolve("head.tmp");
            java.nio.file.Files.writeString(tmp, rootHash);
            java.nio.file.Files.move(
                    tmp,
                    head,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("persisting head failed", e);
        }
    }

    @jakarta.annotation.PreDestroy
    public synchronized void shutdown() {
        store.close();
    }

    private MemorySegment key(HeapBufferPool pool, long k) {
        // The descriptor MUST reach the builder: with it, putInt64 parity-encodes
        // (big-endian + sign-flip) so byte order == numeric order. Without it the
        // bytes are little-endian and keys >= 256 byte-sort wrongly — caught live
        // when the node view decoded garbage keys (KeyDecodeDebugTest's probe).
        TupleBuilder tb = new TupleBuilder(pool, DESC);
        tb.putInt64(0, k);
        return tb.build().segment();
    }

    private static MemorySegment value(long k) {
        byte[] b = new byte[8];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(k);
        return MemorySegment.ofArray(b);
    }

    /** Replace the world: build a fresh tree holding exactly {@code keys}. */
    public synchronized Dto.TreeSummary replace(List<Long> keys) {
        store.drainWrites();
        root = null;
        rootHash = null;
        return mutate(keys, List.of());
    }

    /** Insert (or overwrite) {@code puts}, delete {@code deletes}, one flush. */
    public synchronized Dto.TreeSummary mutate(List<Long> puts, List<Long> deletes) {
        store.drainWrites();
        long t0 = System.nanoTime(); // ENGINE time only — JSON/HTTP is the client's half
        try (HeapBufferPool pool = new HeapBufferPool()) {
            StaticMap base = new StaticMap(store, root, DESC);
            MutableMap mm = new MutableMap(base, store, DESC, pool);
            for (long k : puts) mm.put(key(pool, k), value(k));
            for (long k : deletes) mm.delete(key(pool, k));
            StaticMap flushed = mm.flush();
            root = flushed.root();
            rootHash = root == null ? null : HashUtils.toHex(store.write(root.segment()));
        }
        persistHead(); // the durable publish is part of the write's cost
        long micros = (System.nanoTime() - t0) / 1_000;
        Set<String> written = store.drainWrites();
        return summary(written, micros);
    }

    public synchronized Dto.TreeSummary summary(Set<String> written) {
        return summary(written, null); // a plain GET measures nothing
    }

    private Dto.TreeSummary summary(Set<String> written, @Nullable Long engineMicros) {
        int total = store.allHashes().size();
        return new Dto.TreeSummary(
                rootHash,
                root == null ? 0 : root.treeCount(),
                root == null ? -1 : root.level(),
                total,
                List.copyOf(written),
                engineMicros);
    }

    /** Parse a stored node and re-verify its name against its bytes — live node CAS. */
    public synchronized Optional<Dto.NodeView> node(String hex) {
        final byte[] hash;
        try {
            hash = HashUtils.fromHex(hex);
        } catch (RuntimeException malformed) {
            return Optional.empty(); // a name that can't exist resolves to nothing, not a 500
        }
        return store.read(hash).map(seg -> view(hex, seg));
    }

    private Dto.NodeView view(String hex, MemorySegment seg) {
        Node n = Node.fromBytes(seg);
        byte[] bytes = seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        boolean verified = HashUtils.toHex(HashUtils.hash(bytes)).equals(hex);
        List<Long> keys = new ArrayList<>();
        List<Dto.ChildRef> children = new ArrayList<>();
        for (int i = 0; i < n.count(); i++) {
            keys.add(TypeCodec.decodeInt64(new Tuple(n.getKeySegment(i)).getFieldSegment(0)));
            if (!n.isLeaf()) {
                children.add(
                        new Dto.ChildRef(HashUtils.toHex(n.getValue(i)), n.getSubtreeCount(i)));
            }
        }
        return new Dto.NodeView(
                hex,
                n.level(),
                n.isLeaf(),
                n.count(),
                n.treeCount(),
                bytes.length,
                verified,
                keys,
                children);
    }

    /**
     * The byte-layout annotation for a stored node — every range attributed by the engine's own
     * parse (the flatc-generated {@code serial.ProllyTreeNode} accessors give exact vector
     * positions; {@code key_offsets}/{@code value_offsets} subdivide per entry). Regions are
     * returned sorted and TILING: unattributed bytes become {@code scaffolding} regions (vtables,
     * offsets, inline scalars) — honest residue, never a gap. No client ever re-parses the format.
     */
    public synchronized Optional<Dto.NodeLayout> nodeLayout(String hex) {
        final byte[] hash;
        try {
            hash = HashUtils.fromHex(hex);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
        Optional<MemorySegment> seg = store.read(hash);
        if (seg.isEmpty()) return Optional.empty();
        byte[] all = seg.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        final Node n;
        try {
            n = Node.fromBytes(seg.get());
        } catch (RuntimeException notANode) {
            return Optional.empty(); // a non-node blob resolves to nothing, not a 500
        }
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(all);
        bb.position(Node.NODE_HEADER_SZ); // [PNOD magic][1-byte version][TUPM flatbuffer]
        serial.ProllyTreeNode fb = serial.ProllyTreeNode.getRootAsProllyTreeNode(bb);

        List<Dto.LayoutRegion> attributed = new ArrayList<>();
        attributed.add(
                new Dto.LayoutRegion(
                        0,
                        Node.NODE_HEADER_SZ,
                        "envelope",
                        "envelope",
                        "magic '"
                                + new String(
                                        all,
                                        0,
                                        Node.NODE_HEADER_SZ - 1,
                                        java.nio.charset.StandardCharsets.US_ASCII)
                                + "' + format version "
                                + all[Node.NODE_HEADER_SZ - 1]
                                + " — fromBytes verifies BOTH before any flatbuffer field is read"
                                + " (fail-closed, never additive-misparse)"));

        // key_items, subdivided per key by the key_offsets u16 array (count+1 entries)
        java.nio.ByteBuffer ki = fb.keyItemsAsByteBuffer();
        if (ki != null) {
            int base = ki.position();
            for (int i = 0; i < n.count(); i++) {
                int s = base + fb.keyOffsets(i);
                int e = base + fb.keyOffsets(i + 1);
                long key = TypeCodec.decodeInt64(new Tuple(n.getKeySegment(i)).getFieldSegment(0));
                attributed.add(
                        new Dto.LayoutRegion(
                                s,
                                e,
                                "key",
                                "key[" + i + "]",
                                "int64 " + key + " (parity-encoded)"));
            }
        }
        java.nio.ByteBuffer ko = fb.keyOffsetsAsByteBuffer();
        if (ko != null) {
            attributed.add(
                    new Dto.LayoutRegion(
                            ko.position(),
                            ko.limit(),
                            "scaffolding",
                            "key_offsets (u16 × " + fb.keyOffsetsLength() + ")",
                            "prefix offsets delimiting each key item"));
        }
        // value_items: leaf tuples, subdivided by value_offsets
        java.nio.ByteBuffer vi = fb.valueItemsAsByteBuffer();
        if (vi != null && n.isLeaf()) {
            int base = vi.position();
            for (int i = 0; i < n.count(); i++) {
                int s = base + fb.valueOffsets(i);
                int e = base + fb.valueOffsets(i + 1);
                byte[] v = n.getValue(i);
                String decoded =
                        v != null && v.length == 8
                                ? "int64 "
                                        + java.nio.ByteBuffer.wrap(v)
                                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                                .getLong()
                                        + " (little-endian)"
                                : (v == null ? null : v.length + " B");
                attributed.add(new Dto.LayoutRegion(s, e, "value", "value[" + i + "]", decoded));
            }
        }
        java.nio.ByteBuffer vo = fb.valueOffsetsAsByteBuffer();
        if (vo != null) {
            attributed.add(
                    new Dto.LayoutRegion(
                            vo.position(),
                            vo.limit(),
                            "scaffolding",
                            "value_offsets (u16 × " + fb.valueOffsetsLength() + ")",
                            "prefix offsets delimiting each value item"));
        }
        // address_array: child hashes on internals (20 B each)
        java.nio.ByteBuffer aa = fb.addressArrayAsByteBuffer();
        if (aa != null && !n.isLeaf()) {
            int base = aa.position();
            for (int i = 0; i < n.count(); i++) {
                byte[] child = n.getValue(i);
                attributed.add(
                        new Dto.LayoutRegion(
                                base + i * 20,
                                base + (i + 1) * 20,
                                "address",
                                "child[" + i + "]",
                                child == null
                                        ? null
                                        : "⋄"
                                                + HashUtils.toHex(child).substring(0, 10)
                                                + "… (a content address — the child's name)"));
            }
        }
        // subtree_counts: the counted-B-tree varints (internals)
        java.nio.ByteBuffer sc = fb.subtreeCountsAsByteBuffer();
        if (sc != null && sc.limit() > sc.position()) {
            StringBuilder cum = new StringBuilder("cumulative: ");
            for (int i = 0; i < n.count(); i++) {
                cum.append(i == 0 ? "" : ", ").append(n.getSubtreeCount(i));
            }
            attributed.add(
                    new Dto.LayoutRegion(
                            sc.position(),
                            sc.limit(),
                            "counts",
                            "subtree_counts (varint × " + n.count() + ")",
                            cum.toString()));
        }

        // sort + fill: unattributed bytes are honest scaffolding, and overlaps are a bug
        attributed.sort(java.util.Comparator.comparingInt(Dto.LayoutRegion::start));
        List<Dto.LayoutRegion> out = new ArrayList<>();
        int pos = 0;
        for (Dto.LayoutRegion r : attributed) {
            if (r.start() < pos) {
                throw new IllegalStateException(
                        "overlapping layout regions at " + r.start() + " (" + r.label() + ")");
            }
            if (r.start() > pos) {
                out.add(scaffolding(pos, r.start()));
            }
            out.add(r);
            pos = r.end();
        }
        if (pos < all.length) {
            out.add(scaffolding(pos, all.length));
        }
        return Optional.of(new Dto.NodeLayout(hex, all.length, out));
    }

    private static Dto.LayoutRegion scaffolding(int start, int end) {
        return new Dto.LayoutRegion(
                start,
                end,
                "scaffolding",
                "flatbuffer scaffolding",
                "root offset / vtable / vector headers / inline scalars — structure, not payload");
    }

    /** A stored node's exact bytes, hex-encoded — the preimage whose hash is its name. */
    public synchronized Optional<Dto.BytesView> nodeBytes(String hex) {
        final byte[] hash;
        try {
            hash = HashUtils.fromHex(hex);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
        return store.read(hash)
                .map(seg -> seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))
                .map(b -> new Dto.BytesView(hex, b.length, java.util.HexFormat.of().formatHex(b)));
    }

    /** Operator reset: erase the store AND the tree — the one way this store forgets. */
    public synchronized Dto.TreeSummary reset() {
        store.close();
        if (storeDir != null) deleteRecursively(storeDir);
        store = openStore();
        root = null;
        rootHash = null;
        return summary(Set.of());
    }

    private static void deleteRecursively(java.nio.file.Path dir) {
        if (!java.nio.file.Files.exists(dir)) return;
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    java.nio.file.Files.delete(p);
                                } catch (java.io.IOException e) {
                                    throw new java.io.UncheckedIOException(
                                            "reset wipe failed: " + p, e);
                                }
                            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("reset wipe failed", e);
        }
    }

    /**
     * The recorded descent of the operation just run: the in-memory root, then every store read.
     */
    private List<String> drainReadPath() {
        List<String> path = new ArrayList<>();
        path.add(rootHash); // the root Node is held in memory — the descent's origin
        path.addAll(store.drainReads());
        return path;
    }

    /** Point lookup via the engine's own {@code StaticMap#get}; the read path is measured. */
    public synchronized Dto.FindResult find(long k) {
        if (root == null) return new Dto.FindResult(false, k, List.of(), 0);
        store.drainReads();
        long t0 = System.nanoTime();
        boolean found;
        try (HeapBufferPool pool = new HeapBufferPool()) {
            found = new StaticMap(store, root, DESC).get(key(pool, k)).isPresent();
        }
        long micros = (System.nanoTime() - t0) / 1_000;
        return new Dto.FindResult(found, k, drainReadPath(), micros);
    }

    /**
     * Ordinal seek — the counted-B-tree descent: at each internal node, find the child whose
     * cumulative subtree count first exceeds the target and step into it, never reading the
     * children skipped past. ({@code getSubtreeCount(i)} is the PREFIX SUM through child i.)
     */
    public synchronized Dto.RankResult rank(long n) {
        if (root == null || n < 0 || n >= root.treeCount())
            return new Dto.RankResult(n, null, List.of(), 0);
        store.drainReads();
        long t0 = System.nanoTime();
        Node cur = root;
        long pos = n; // position within cur's subtree
        while (!cur.isLeaf()) {
            int i = 0;
            while (pos >= cur.getSubtreeCount(i)) i++;
            pos -= i == 0 ? 0 : cur.getSubtreeCount(i - 1);
            cur = Node.fromBytes(store.read(cur.getValue(i)).orElseThrow());
        }
        long key =
                TypeCodec.decodeInt64(new Tuple(cur.getKeySegment((int) pos)).getFieldSegment(0));
        long micros = (System.nanoTime() - t0) / 1_000;
        return new Dto.RankResult(n, key, drainReadPath(), micros);
    }

    /**
     * Range scan via the engine's {@code Cursor}: one descent, then leaf hops, stop past {@code
     * to}.
     */
    public synchronized Dto.ScanResult scan(long from, long to, int limit) {
        if (root == null) return new Dto.ScanResult(from, to, List.of(), false, List.of(), 0);
        store.drainReads();
        long t0 = System.nanoTime();
        List<Long> keys = new ArrayList<>();
        boolean truncated = false;
        try (HeapBufferPool pool = new HeapBufferPool()) {
            // NOT atNodeEnd() as a loop guard: it means AT the last entry (still
            // valid), not past it — guarding on it drops every node's final key
            // (caught live by the spawned-jar e2e: a full scan returned 32 of 33,
            // missing exactly the max key). advance() terminates correctly.
            Cursor cur = Cursor.atRawKey(store, root, key(pool, from));
            while (cur.isValid()) {
                long k = TypeCodec.decodeInt64(new Tuple(cur.currentKey()).getFieldSegment(0));
                if (k > to) break;
                if (keys.size() >= limit) {
                    truncated = true;
                    break;
                }
                keys.add(k);
                if (!cur.advance()) break;
            }
        }
        long micros = (System.nanoTime() - t0) / 1_000;
        return new Dto.ScanResult(from, to, keys, truncated, drainReadPath(), micros);
    }

    /* ------------- benchmark: server-side measured loops (plan playground-benchmark-section) --- */

    /**
     * A measured benchmark loop the engine runs on itself. The loop lives HERE, not in the browser,
     * deliberately (D-1): a client-driven loop of N fetches would measure HTTP + JSON + the
     * browser, not the engine — the instrument must be cheaper than the subject. Per-op {@code
     * System.nanoTime} brackets the engine work alone; percentiles are computed from the per-op
     * array. This is a teaching instrument (single process, warm page cache, no fork isolation) —
     * the repo's JMH suites own rigorous numbers.
     *
     * @param kind {@code read} (point lookups over pre-sampled existing keys) or {@code write}
     *     (single-key insert + flush + head persist per op — the full write path)
     * @param opsRaw requested op count; clamped to [1, 50k] reads / [1, 2k] writes (the service is
     *     synchronized, so a run blocks other ops — the cap keeps the worst case in seconds)
     * @throws IllegalStateException read bench on an empty tree (nothing to look up)
     */
    public synchronized Dto.BenchResult bench(String kind, int opsRaw) {
        boolean isRead = "read".equals(kind);
        if (!isRead && !"write".equals(kind)) {
            throw new IllegalArgumentException("kind must be read|write, got: " + kind);
        }
        int ops = Math.max(1, Math.min(opsRaw, isRead ? 50_000 : 2_000));
        return isRead ? benchRead(ops) : benchWrite(ops);
    }

    private Dto.BenchResult benchRead(int ops) {
        if (root == null || root.treeCount() == 0) {
            throw new IllegalStateException(
                    "read bench needs keys to look up — the tree is empty; insert first");
        }
        long treeCount = root.treeCount();
        int height = root.level();
        Random rnd = new Random();
        // D-2: <=1000 distinct keys pre-sampled by UNTIMED ordinal descents, then the timed
        // loop draws uniformly with replacement from the sample
        int sampleSize = (int) Math.min(1_000, treeCount);
        java.util.LinkedHashSet<Long> ordinals = new java.util.LinkedHashSet<>();
        if (sampleSize == treeCount) {
            for (long i = 0; i < treeCount; i++) ordinals.add(i);
        } else {
            while (ordinals.size() < sampleSize)
                ordinals.add((long) (rnd.nextDouble() * treeCount));
        }
        long[] sampleKeys = new long[sampleSize];
        int si = 0;
        for (long n : ordinals) sampleKeys[si++] = keyAtOrdinal(n);

        int warmup = Math.min(200, Math.max(1, ops / 10));
        long[] lat = new long[ops];
        long totalNanos = 0;
        long nodesRead = 0;
        try (HeapBufferPool pool = new HeapBufferPool()) {
            StaticMap map = new StaticMap(store, root, DESC);
            // keys pre-encoded ONCE: the timed op is the engine's get alone
            MemorySegment[] segs = new MemorySegment[sampleSize];
            for (int i = 0; i < sampleSize; i++) segs[i] = key(pool, sampleKeys[i]);
            store.drainReads();
            for (int i = 0; i < warmup; i++)
                map.get(segs[rnd.nextInt(sampleSize)]); // uncounted (D-4)
            store.drainReads();
            for (int i = 0; i < ops; i++) {
                MemorySegment s = segs[rnd.nextInt(sampleSize)];
                long t0 = System.nanoTime();
                map.get(s);
                long d = System.nanoTime() - t0;
                lat[i] = d;
                totalNanos += d;
                // drained BETWEEN ops (outside the timed bracket); the per-read recording
                // inside get() is the acknowledged, run-invariant instrument tax (D-4)
                nodesRead += store.drainReads().size();
            }
        }
        return summarize(
                "read",
                ops,
                warmup,
                lat,
                totalNanos,
                (double) nodesRead / ops,
                treeCount,
                height,
                rootHash,
                rootHash,
                null,
                null);
    }

    private Dto.BenchResult benchWrite(int ops) {
        String rootBefore = rootHash;
        long treeCountBefore = root == null ? 0 : root.treeCount();
        int height = root == null ? -1 : root.level();
        int storedBefore = store.allHashes().size();
        int warmup = 20;
        Random rnd = new Random();
        // D-2/D-3: bench keys come from a high disjoint range AND are verified absent
        // (untimed) — deleting a key that pre-existed would LOSE user data, and only an
        // exact restore makes the root-restoration assertion meaningful
        java.util.LinkedHashSet<Long> benchKeys = new java.util.LinkedHashSet<>();
        try (HeapBufferPool pool = new HeapBufferPool()) {
            StaticMap current = new StaticMap(store, root, DESC);
            while (benchKeys.size() < ops + warmup) {
                long k = 1_000_000_000_000_000L + (long) (rnd.nextDouble() * 8.0e15);
                if (benchKeys.contains(k)) continue;
                if (root != null && current.get(key(pool, k)).isPresent()) continue;
                benchKeys.add(k);
            }
        }
        long[] all = benchKeys.stream().mapToLong(Long::longValue).toArray();
        long[] lat = new long[ops];
        long totalNanos = 0;
        long nodesWritten = 0;
        store.drainWrites();
        for (int i = 0; i < warmup; i++) insertOne(all[i]); // JIT warm-up, uncounted (D-4)
        store.drainWrites();
        for (int i = 0; i < ops; i++) {
            long k = all[warmup + i];
            long t0 = System.nanoTime();
            insertOne(k);
            long d = System.nanoTime() - t0;
            lat[i] = d;
            totalNanos += d;
            nodesWritten += store.drainWrites().size(); // between ops, outside the bracket
        }
        // UNTIMED cleanup: delete every bench key (warm-up included). The tree is
        // history-independent, so the restored key set re-derives the identical root
        // bytes — asserted, not assumed (D-3). The bench spines stay in the store as
        // unreachable copy-on-write garbage; storedNodesDelta reports them honestly.
        deleteAll(new ArrayList<>(benchKeys));
        String rootAfter = rootHash;
        boolean restored = java.util.Objects.equals(rootBefore, rootAfter);
        int storedDelta = store.allHashes().size() - storedBefore;
        return summarize(
                "write",
                ops,
                warmup,
                lat,
                totalNanos,
                (double) nodesWritten / ops,
                treeCountBefore,
                height,
                rootBefore,
                rootAfter,
                restored,
                storedDelta);
    }

    /**
     * The three-store comparison: fresh ephemeral {@code memory} / {@code file} / {@code rocks}
     * stores, each seeded by COPYING the current tree's reachable chunk set (content addressing
     * makes replication a byte copy — the same move the sync pack protocol makes), then the
     * existing read + write bench loops run on each arm. The identical per-arm root hash PROVES the
     * arms hold byte-equal trees; only the storage layer differs. Arms run sequentially in this one
     * JVM (order effects possible; each arm's warm-up ops are uncounted as usual).
     *
     * @throws IllegalStateException empty tree (nothing to copy or look up), or a tree past the
     *     2M-key cap (the file arm's seeding — one file per chunk — would take minutes)
     */
    public synchronized Dto.CompareResult compareStores(int readOps, int writeOps) {
        if (root == null || root.treeCount() == 0) {
            throw new IllegalStateException(
                    "compare needs a tree to copy and read — the tree is empty; insert first");
        }
        if (root.treeCount() > 2_000_000) {
            throw new IllegalStateException(
                    "compare caps at 2,000,000 keys — seeding the file arm (one file per chunk)"
                            + " would take minutes on a tree this size");
        }
        List<Dto.CompareArm> arms = new ArrayList<>();
        for (String kind : List.of("memory", "file", "rocks")) {
            java.nio.file.Path dir = null;
            TreeService arm = null;
            try {
                if (!kind.equals("memory")) {
                    dir = java.nio.file.Files.createTempDirectory("playground-compare-" + kind);
                }
                arm = new TreeService(kind, dir == null ? "" : dir.toString());
                long t0 = System.nanoTime();
                arm.adoptTree(store, java.util.Objects.requireNonNull(rootHash));
                long seedMillis = (System.nanoTime() - t0) / 1_000_000;
                boolean sameRoot = java.util.Objects.equals(arm.rootHash, rootHash);
                Dto.BenchResult read = arm.bench("read", readOps);
                Dto.BenchResult write = arm.bench("write", writeOps);
                arms.add(new Dto.CompareArm(kind, seedMillis, sameRoot, read, write));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("compare arm " + kind + " failed", e);
            } finally {
                if (arm != null) arm.shutdown();
                if (dir != null) {
                    try {
                        deleteRecursively(dir);
                    } catch (RuntimeException scratchCleanup) {
                        // best-effort: never mask a compare result over temp-dir cleanup
                        // (deleteRecursively throws because reset's wipe MUST be loud)
                    }
                }
            }
        }
        return new Dto.CompareResult(rootHash, root.treeCount(), root.level(), storeKind, arms);
    }

    /** Adopt another store's tree: copy every reachable chunk, then point at the same root. */
    private synchronized void adoptTree(RecordingNodeStore src, String rootHex) {
        MemorySegment rootSeg = src.read(HashUtils.fromHex(rootHex)).orElseThrow();
        copyReachable(src, rootSeg, new java.util.HashSet<>());
        root = Node.fromBytes(store.read(HashUtils.fromHex(rootHex)).orElseThrow());
        rootHash = rootHex;
        persistHead();
        src.drainReads(); // the walk's reads are seeding, not a measured descent
    }

    private void copyReachable(
            RecordingNodeStore src, MemorySegment seg, java.util.Set<String> seen) {
        Node n = Node.fromBytes(seg);
        String hex = HashUtils.toHex(store.write(seg)); // content-addressed: write IS the copy
        if (!seen.add(hex) || n.isLeaf()) return;
        for (int i = 0; i < n.count(); i++) {
            copyReachable(src, src.read(n.getValue(i)).orElseThrow(), seen);
        }
    }

    /** The single-key write path, exactly the shape {@link #mutate} runs per HTTP op. */
    private void insertOne(long k) {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            MutableMap mm = new MutableMap(new StaticMap(store, root, DESC), store, DESC, pool);
            mm.put(key(pool, k), value(k));
            StaticMap flushed = mm.flush();
            root = flushed.root();
            rootHash = root == null ? null : HashUtils.toHex(store.write(root.segment()));
            persistHead();
        }
    }

    /** One batched delete + flush — the write bench's untimed cleanup. */
    private void deleteAll(List<Long> ks) {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            MutableMap mm = new MutableMap(new StaticMap(store, root, DESC), store, DESC, pool);
            for (long k : ks) mm.delete(key(pool, k));
            StaticMap flushed = mm.flush();
            root = flushed.root();
            rootHash = root == null ? null : HashUtils.toHex(store.write(root.segment()));
            persistHead();
        }
    }

    /** The counted-B-tree descent of {@link #rank}, without the Dto/recording ceremony. */
    private long keyAtOrdinal(long n) {
        Node cur = java.util.Objects.requireNonNull(root);
        long pos = n;
        while (!cur.isLeaf()) {
            int i = 0;
            while (pos >= cur.getSubtreeCount(i)) i++;
            pos -= i == 0 ? 0 : cur.getSubtreeCount(i - 1);
            cur = Node.fromBytes(store.read(cur.getValue(i)).orElseThrow());
        }
        return TypeCodec.decodeInt64(new Tuple(cur.getKeySegment((int) pos)).getFieldSegment(0));
    }

    /**
     * Latency fields are NANOS, not micros: warm point reads are sub-microsecond, and micro
     * granularity would floor them to 0 — an invented zero. {@code opsPerSec} derives from the
     * summed per-op time (engine-only throughput; the harness between ops is excluded).
     */
    private static Dto.BenchResult summarize(
            String kind,
            int ops,
            int warmup,
            long[] latNanos,
            long totalNanos,
            double nodesPerOp,
            long treeCount,
            int height,
            @Nullable String rootBefore,
            @Nullable String rootAfter,
            @Nullable Boolean rootRestored,
            @Nullable Integer storedNodesDelta) {
        long[] sorted = latNanos.clone();
        java.util.Arrays.sort(sorted);
        long opsPerSec = totalNanos == 0 ? 0 : Math.round(ops / (totalNanos / 1e9));
        return new Dto.BenchResult(
                kind,
                ops,
                warmup,
                totalNanos,
                opsPerSec,
                totalNanos / ops,
                pct(sorted, 0.50),
                pct(sorted, 0.90),
                pct(sorted, 0.95),
                pct(sorted, 0.99),
                sorted[sorted.length - 1],
                Math.round(nodesPerOp * 100) / 100.0,
                treeCount,
                height,
                rootBefore,
                rootAfter,
                rootRestored,
                storedNodesDelta);
    }

    private static long pct(long[] sortedNanos, double p) {
        int idx = (int) Math.min(sortedNanos.length - 1, Math.ceil(p * sortedNanos.length) - 1);
        return sortedNanos[Math.max(0, idx)];
    }

    /**
     * Every node reachable from the current root, breadth-first (root first). One response holds
     * the whole live tree — enough for a client to render its structure without N+1 node fetches.
     * Shared subtrees appear once (content addressing dedupes by hash). Empty when the tree is.
     */
    public synchronized List<Dto.NodeView> treeNodes() {
        return treeNodesFrom(null).orElseThrow(); // the current root is always resolvable
    }

    /**
     * The tree under any STORED root — the current one when {@code rootHex} is null, or any
     * superseded root still retained by the store (nothing is ever deleted): time travel from
     * content addressing alone. Empty result for an unknown root.
     */
    public synchronized Optional<List<Dto.NodeView>> treeNodesFrom(@Nullable String rootHex) {
        String start = rootHex == null ? rootHash : rootHex;
        List<Dto.NodeView> out = new ArrayList<>();
        if (start == null) return Optional.of(out); // empty current tree
        if (node(start).isEmpty()) return rootHex == null ? Optional.of(out) : Optional.empty();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        Set<String> seen = new java.util.HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            node(queue.poll())
                    .ifPresent(
                            v -> {
                                out.add(v);
                                for (Dto.ChildRef c : v.children()) {
                                    if (seen.add(c.hash())) queue.add(c.hash());
                                }
                            });
        }
        return Optional.of(out);
    }

    /** All stored hashes in first-write order, each parsed for its level. */
    public synchronized List<Dto.NodeRef> nodes() {
        List<Dto.NodeRef> out = new ArrayList<>();
        for (String hex : store.allHashes()) {
            store.read(HashUtils.fromHex(hex))
                    .ifPresent(
                            seg -> {
                                try {
                                    Node n = Node.fromBytes(seg);
                                    out.add(new Dto.NodeRef(hex, n.level(), n.count()));
                                } catch (RuntimeException notANode) {
                                    // commit/other blobs would land here; the playground stores
                                    // only nodes
                                }
                            });
        }
        return out;
    }
}
