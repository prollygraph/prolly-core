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
package com.earasoft.prolly.storage;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.NodeStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A {@link NodeStore} whose backing store <b>is the filesystem</b>: each chunk is one immutable
 * file named by its content hash, git-loose-objects style.
 *
 * <p>A chunk with hash {@code h} lives at {@code <root>/<hex[0:2]>/<hex[2:40]>} (a 2-character
 * fan-out, so no single directory holds every object). Because the path <em>is</em> the SHA-512/20
 * of the bytes, the store is content-addressed and deduplicating by construction: writing the same
 * bytes twice produces the same path and one file, and a file, once at its final path, is never
 * modified.
 *
 * @apiNote The third {@code NodeStore} backend beside {@link RocksNodeStore} (the production
 *     packer) and {@code InMemoryNodeStore} (the reference). Pick it for a small store you want to
 *     inspect by hand or back up with {@code tar}/{@code rsync}, and where a zero-dependency chunk
 *     store is worth more than RocksDB's packing. It is <b>not</b> the production default (see the
 *     {@code prolly-storage/plans/filesystem-node-store.md} promotion gate) and is not space- or
 *     inode-efficient at millions of chunks — that regime wants packfiles, a separate plan.
 * @implNote <b>Writes are atomic and idempotent with no locks.</b> {@link #write} computes the
 *     hash, returns immediately if the path already exists (dedup, no I/O), else writes a temp file
 *     in the <em>same</em> fan-out directory and {@link Files#move moves} it onto the final path
 *     with {@link StandardCopyOption#ATOMIC_MOVE}. Because the filename is the content hash, two
 *     writers (same JVM or cross-process) racing on the same chunk produce byte-identical files, so
 *     the atomic rename is safe either way — concurrency-safety and crash-atomicity fall out of
 *     POSIX {@code rename} for free, with no write-ahead log. A crash mid-write leaves the temp
 *     (garbage, never read) and no final file; a retry reproduces the chunk. The same-directory
 *     temp keeps the rename same-filesystem, which {@code ATOMIC_MOVE} requires.
 *     <p><b>Durability</b> is a {@link Durability} mode (default {@link Durability#BATCH}). {@code
 *     EACH} and a batch-less {@code BATCH} write fsync the data <em>before</em> publishing the
 *     name; {@code BATCH} inside a {@link #beginWriteBatch}/{@link #endWriteBatch} span defers each
 *     chunk's fsync to {@code endWriteBatch} (one flush amortized over a commit's chunks); {@code
 *     NONE} never fsyncs. The batch is per-thread (a {@link ThreadLocal}) because the Sail builds
 *     its trees concurrently — mirrors {@code RocksNodeStore}'s per-thread {@code WriteBatch}. Only
 *     the file's <em>data</em> is fsync'd, not its parent directory entry: Java has no portable
 *     directory-fsync, and the content-addressed design makes a chunk whose data survived but whose
 *     entry did not simply <em>absent</em> after a crash (re-writable), never corrupt.
 *     <p><b>Collaborators:</b> {@link HashUtils} (content hash + hex), {@link NodeStore} (the
 *     contract), the engine's {@code Node}/{@code Database} as clients. For garbage collection the
 *     store exposes a store-specific sweep surface — {@link #hashes()} (enumerate every stored
 *     chunk) + {@link #delete(byte[])} (unlink one, pruning its empty fan-out dir) — the filesystem
 *     analogue of {@code RocksNodeStore} iterating + deleting its column family. {@code delete} is
 *     <b>not</b> on the {@link NodeStore} interface: content-addressed writes are immutable, so
 *     only a collector holding the reachability set may remove chunks.
 */
public final class FileNodeStore implements NodeStore, AutoCloseable {

    /** When a written chunk's bytes are fsync'd to the storage device. */
    public enum Durability {
        /** Never fsync — fastest; a crash may lose recent chunks (they stay absent, never torn). */
        NONE,
        /**
         * fsync a batch's chunks at {@link FileNodeStore#endWriteBatch}; a write outside any batch
         * fsyncs immediately. The default — one flush amortized over a commit's many chunks.
         */
        BATCH,
        /** fsync every write before it returns — safest, slowest. */
        EACH
    }

    private final Path root;
    private final Durability durability;

    // The chunk paths written between begin/endWriteBatch on THIS thread whose fsync is deferred to
    // endWriteBatch under BATCH. Per-thread because the Sail builds its trees concurrently, each on
    // its own thread (mirrors RocksNodeStore's ThreadLocal WriteBatch). Null when no batch is
    // active.
    private final ThreadLocal<@Nullable List<Path>> pendingBatch = new ThreadLocal<>();

    /** Opens (creating if absent) a filesystem-backed node store with the default {@code BATCH}. */
    public FileNodeStore(Path root) {
        this(root, Durability.BATCH);
    }

    /**
     * Opens (creating if absent) a filesystem-backed node store rooted at {@code root}.
     *
     * @param root the directory that will hold the {@code <hex[0:2]>/<hex[2:40]>} chunk files
     * @param durability when written chunks are fsync'd (see {@link Durability})
     * @throws UncheckedIOException if the root directory cannot be created
     */
    public FileNodeStore(Path root, Durability durability) {
        this.root = Objects.requireNonNull(root, "root must not be null");
        this.durability = Objects.requireNonNull(durability, "durability must not be null");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create node store root: " + root, e);
        }
    }

    /** Visible for tests — the fsync mode this store was opened with. */
    Durability durability() {
        return durability;
    }

    private Path pathFor(byte[] hash) {
        String hex = HashUtils.toHex(hash);
        return root.resolve(hex.substring(0, 2)).resolve(hex.substring(2));
    }

    @Override
    public Optional<MemorySegment> read(byte[] hash) {
        if (hash == null) {
            throw new IllegalArgumentException("hash must not be null");
        }
        Path path = pathFor(hash);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MemorySegment.ofArray(Files.readAllBytes(path)));
        } catch (IOException e) {
            throw new UncheckedIOException("read failed: " + path, e);
        }
    }

    @Override
    public byte[] write(MemorySegment segment) {
        return write(segment.toArray(ValueLayout.JAVA_BYTE));
    }

    @Override
    public byte[] write(byte[] data) {
        byte[] hash = HashUtils.hash(data);
        Path path = pathFor(hash);
        if (Files.exists(path)) {
            return hash; // content-addressed dedup — identical bytes already stored.
        }
        // pathFor always yields <root>/<xx>/<yyyy…>, so the parent (the fan-out dir) is never null.
        Path dir = Objects.requireNonNull(path.getParent());
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".tmp-", null);
            try {
                Files.write(tmp, data);
                List<Path> batch = pendingBatch.get();
                if (durability == Durability.EACH
                        || (durability == Durability.BATCH && batch == null)) {
                    // Immediate durability: flush the data before publishing the name.
                    forceFile(tmp);
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    // NONE, or BATCH inside a batch: publish now; fsync is skipped (NONE) or
                    // deferred (BATCH).
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
                    if (durability == Durability.BATCH && batch != null) {
                        batch.add(path);
                    }
                }
            } catch (FileAlreadyExistsException raced) {
                // A concurrent writer landed the identical chunk first — drop our temp and accept
                // it.
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                Files.deleteIfExists(tmp); // don't leak the temp on any write/move failure.
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("write failed: " + path, e);
        }
        return hash;
    }

    @Override
    public void beginWriteBatch() {
        pendingBatch.set(new ArrayList<>());
    }

    @Override
    public void endWriteBatch() {
        List<Path> batch = pendingBatch.get();
        pendingBatch.remove();
        if (batch == null || durability != Durability.BATCH) {
            return;
        }
        try {
            for (Path chunk : batch) {
                forceFile(chunk); // flush each deferred chunk's data now.
            }
        } catch (IOException e) {
            throw new UncheckedIOException("endWriteBatch fsync failed", e);
        }
    }

    /**
     * fsync one file's data to the storage device (parent-directory entry not flushed — see class).
     */
    private static void forceFile(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }

    /**
     * Every chunk hash currently stored — the enumeration a garbage collector marks against before
     * sweeping (the filesystem analogue of iterating a RocksDB column family).
     *
     * <p>Walks the {@code <hex[0:2]>/<hex[2:40]>} fan-out and reconstructs each 20-byte hash from
     * its path, <b>skipping</b> in-flight {@code .tmp-*} temps and any file whose name is not a
     * well-formed 40-hex chunk (so a stray file never poisons the sweep). Materialized into a list
     * rather than returned as a lazy stream: the filesystem backend targets small stores, and an
     * eager list closes the directory walk immediately — no leaked file descriptor.
     *
     * @return the content hash of every stored chunk, in filesystem-walk order (unspecified)
     * @throws UncheckedIOException if the store cannot be walked
     */
    public List<byte[]> hashes() {
        List<byte[]> out = new ArrayList<>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .forEach(
                            file -> {
                                byte @Nullable [] hash = hashOfChunkPath(file);
                                if (hash != null) {
                                    out.add(hash);
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException("enumerate failed: " + root, e);
        }
        return out;
    }

    /**
     * Reconstructs the content hash from a chunk file's {@code <xx>/<yyyy…>} path, or {@code null}
     * if {@code file} is not a well-formed chunk (a {@code .tmp-*} temp, or any name that is not
     * the exact 2 + 38 lowercase-hex fan-out shape).
     */
    private byte @Nullable [] hashOfChunkPath(Path file) {
        Path dir = file.getParent();
        if (dir == null) {
            return null;
        }
        String name = file.getFileName().toString();
        if (name.startsWith(".tmp-")) {
            return null; // an in-flight (or crash-orphaned) temp, never a chunk.
        }
        String hex = dir.getFileName().toString() + name;
        if (hex.length() != 40 || !isLowerHex(hex)) {
            return null; // not a chunk file — ignore it rather than mis-decode.
        }
        return HashUtils.fromHex(hex);
    }

    private static boolean isLowerHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Unlinks the chunk file for {@code hash} — a garbage collector's sweep of an unreachable chunk
     * — then <b>lazily prunes</b> its now-possibly-empty fan-out directory so a swept-clean store
     * leaves no empty {@code <xx>/} shells behind.
     *
     * <p>Idempotent: returns whether a file was actually removed ({@code false} if the chunk was
     * already absent). Deleting a chunk that is still <em>reachable</em> is a garbage-collector
     * correctness bug, not this method's concern — it only unlinks what it is told to. The prune
     * tolerates a concurrent writer repopulating the fan-out dir between the emptiness check and
     * the directory unlink (the {@link DirectoryNotEmptyException} is swallowed — the dir is simply
     * kept).
     *
     * @param hash the content hash of the chunk to remove
     * @return {@code true} if a chunk file was removed, {@code false} if none existed
     * @throws IllegalArgumentException if {@code hash} is null
     * @throws UncheckedIOException if the unlink fails
     */
    public boolean delete(byte[] hash) {
        if (hash == null) {
            throw new IllegalArgumentException("hash must not be null");
        }
        Path path = pathFor(hash);
        try {
            boolean removed = Files.deleteIfExists(path);
            // pathFor always yields <root>/<xx>/<yyyy…>, so the fan-out parent is never null.
            Path dir = Objects.requireNonNull(path.getParent());
            if (removed) {
                pruneIfEmpty(dir);
            }
            return removed;
        } catch (IOException e) {
            throw new UncheckedIOException("delete failed: " + path, e);
        }
    }

    /** Removes {@code dir} iff it currently holds no entries (lazy fan-out prune after a sweep). */
    private static void pruneIfEmpty(Path dir) throws IOException {
        boolean empty;
        try (Stream<Path> entries = Files.list(dir)) {
            empty = entries.findAny().isEmpty();
        }
        if (empty) {
            try {
                Files.deleteIfExists(dir);
            } catch (DirectoryNotEmptyException raced) {
                // A concurrent writer landed a chunk (or temp) between the check and the unlink —
                // leave the fan-out dir in place; it is correct either way.
            }
        }
    }

    @Override
    public void close() {
        // The files are the durable state; there is nothing to release.
    }
}
