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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A permanent, content-addressed snapshot in the database history — one node in the commit graph,
 * pointing at a data-tree root and its parent commits.
 *
 * <p>A commit is the versioning layer's unit of history. It pins the root hash of the data tree as
 * it stood at commit time, plus the hashes of its parent commits (one for an ordinary commit, two
 * for a merge, zero for the first). Because the commit serializes its parent hashes into its own
 * bytes, its hash transitively covers the entire history behind it — changing any ancestor would
 * change this commit's hash. That is what makes the commit graph tamper-evident and lets two stores
 * compare histories by hash alone.
 *
 * @apiNote A commit is immutable once built. {@link #getRootValueHash()} is the data-tree root it
 *     snapshots (the {@code EMPTY_ROOT_SENTINEL} stands in for an empty tree, so the field always
 *     round-trips — see the empty-tree-commit fix); the parents list ({@code getParents()}) walks
 *     history backwards. {@link #serialize()} and {@link #deserialize(byte[])} are the on-disk
 *     form; the deserialize path is a trust boundary for untrusted bytes (length fields are
 *     bounds-checked — see the untrusted-byte-boundary work). Author, timestamp, and message are
 *     carried for human-facing history, and they too feed the content hash.
 * @implNote <b>Collaborators:</b> a {@link NodeStore} persists the serialized commit under its hash
 *     — written <b>directly</b> ({@code store.write(commit.serialize())}); no prolly-tree machinery
 *     (chunker / {@code TreeMutator} / node framing) sits in between, so a commit is a "chunk" only
 *     in the any-content-addressed-blob sense the {@link NodeStore} doc defines. {@link HashUtils}
 *     derives that hash; the {@code EMPTY_ROOT_SENTINEL} encodes "no data root yet".
 *     <b>Dependents:</b> in {@code prolly-storage}, {@code Database} writes commits and advances
 *     branch refs to them, {@code GarbageCollector}'s mark phase deserializes each commit to follow
 *     its parents and data root, and {@link ReachabilityWalker} treats a commit's data root as a
 *     walk start.
 */
public class Commit {
    /**
     * On-disk sentinel for a commit whose data tree is empty (no root) — e.g. a commit that deleted
     * the last row. The {@code Commit} format stores a fixed 20-byte root, so {@link #serialize()}
     * writes these zero bytes and {@link #deserialize(byte[])} maps them back to a {@code null}
     * {@code rootValueHash}. A real SHA-512/20 chunk hash is never all-zero, so the sentinel never
     * collides with a genuine root.
     */
    private static final byte[] EMPTY_ROOT_SENTINEL = new byte[20];

    /**
     * Magic prefix of the versioned commit record. {@link #deserialize} checks it (and the version
     * byte that follows) <b>before reading any field</b>, so a wrong/old-format blob — whose first
     * bytes are not this magic — fails closed with {@link UnsupportedFormatException} instead of
     * reading arbitrary bytes as a root hash ({@code core-format-versioning.md} Step 2).
     */
    private static final byte[] COMMIT_MAGIC = {'P', 'C', 'M', 'T'};

    private final byte @Nullable [] rootValueHash;
    private final List<byte[]> parents;
    private final String author;
    private final String message;
    private final long timestamp;

    public Commit(
            byte @Nullable [] rootValueHash,
            List<byte[]> parents,
            String author,
            String message,
            long timestamp) {
        this.rootValueHash = rootValueHash;
        this.parents = parents;
        this.author = author;
        this.message = message;
        this.timestamp = timestamp;
    }

    public byte @Nullable [] getRootValueHash() {
        return rootValueHash;
    }

    public List<byte[]> getParents() {
        return parents;
    }

    public String getAuthor() {
        return author;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public byte[] serialize() {
        // Encode strings explicitly as UTF-8 and use the byte-array length, NOT
        // String.length() — the latter counts UTF-16 code units and diverges
        // from the on-disk byte count for any non-ASCII character.
        byte[] authorBytes = author.getBytes(StandardCharsets.UTF_8);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        int size =
                COMMIT_MAGIC.length
                        + 1 // version byte
                        + 20
                        + 4
                        + (parents.size() * 20)
                        + 8
                        + 4
                        + authorBytes.length
                        + 4
                        + messageBytes.length;
        ByteBuffer bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(COMMIT_MAGIC);
        bb.put((byte) FormatVersion.CORE_FORMAT_VERSION);
        // A commit with an empty data tree has a null root; persist it as the
        // 20-byte zero sentinel so the fixed-width layout is preserved.
        bb.put(rootValueHash != null ? rootValueHash : EMPTY_ROOT_SENTINEL);
        bb.putInt(parents.size());
        for (byte[] p : parents) bb.put(p);
        bb.putLong(timestamp);
        bb.putInt(authorBytes.length);
        bb.put(authorBytes);
        bb.putInt(messageBytes.length);
        bb.put(messageBytes);
        return bb.array();
    }

    public static Commit deserialize(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        // Verify the magic + version BEFORE any field read, so a wrong/old-format blob fails closed
        // (UnsupportedFormatException) rather than reading arbitrary bytes as a root hash
        // (core-format-versioning Step 2).
        verifyCommitFormat(bb);
        byte[] rootHash = new byte[20];
        bb.get(rootHash);
        // The all-zero sentinel denotes an empty-tree commit (see serialize()).
        if (java.util.Arrays.equals(rootHash, EMPTY_ROOT_SENTINEL)) rootHash = null;
        // Bound every attacker-controlled length against the bytes actually
        // remaining BEFORE allocating. Commit chunks arrive over the sync path
        // from an untrusted peer (and from the on-disk commit log), so a forged
        // count/length field would otherwise turn `new byte[len]` into a
        // NegativeArraySizeException or, worse, a multi-GB allocation / OOM DoS.
        // A malformed commit must be rejected with a controlled exception.
        int pCount = bb.getInt();
        if (pCount < 0 || (long) pCount * 20 > bb.remaining()) {
            throw new IllegalArgumentException(
                    "malformed commit: parent count " + pCount + " exceeds remaining bytes");
        }
        List<byte[]> parents = new ArrayList<>(pCount);
        for (int i = 0; i < pCount; i++) {
            byte[] p = new byte[20];
            bb.get(p);
            parents.add(p);
        }
        long ts = bb.getLong();
        byte[] authorBytes = readLengthPrefixed(bb, "author");
        byte[] msgBytes = readLengthPrefixed(bb, "message");
        return new Commit(
                rootHash,
                parents,
                new String(authorBytes, StandardCharsets.UTF_8),
                new String(msgBytes, StandardCharsets.UTF_8),
                ts);
    }

    /**
     * Verify the commit's magic + version header. A blob too short to carry the header, with the
     * wrong magic (an old/foreign blob), or with an unsupported version fails closed with {@link
     * UnsupportedFormatException} — before {@link #deserialize} reads a single field as data.
     */
    private static void verifyCommitFormat(ByteBuffer bb) {
        if (bb.remaining() < COMMIT_MAGIC.length + 1) {
            throw new UnsupportedFormatException(
                    "malformed commit: too short to carry the format header (magic + version)");
        }
        byte[] magic = new byte[COMMIT_MAGIC.length];
        bb.get(magic);
        if (!java.util.Arrays.equals(magic, COMMIT_MAGIC)) {
            throw new UnsupportedFormatException(
                    "unsupported commit format: bad magic — not a versioned prolly commit (an"
                            + " old/foreign blob); back up + restore");
        }
        int version = bb.get() & 0xFF;
        if (version != FormatVersion.CORE_FORMAT_VERSION) {
            throw new UnsupportedFormatException(
                    "unsupported commit format version "
                            + version
                            + " (this engine reads/writes version "
                            + FormatVersion.CORE_FORMAT_VERSION
                            + "); back up + restore");
        }
    }

    /**
     * Read a 4-byte-length-prefixed field, rejecting any length the buffer cannot actually satisfy
     * (negative, or larger than the bytes left).
     */
    private static byte[] readLengthPrefixed(ByteBuffer bb, String field) {
        int len = bb.getInt();
        if (len < 0 || len > bb.remaining()) {
            throw new IllegalArgumentException(
                    "malformed commit: "
                            + field
                            + " length "
                            + len
                            + " exceeds remaining bytes ("
                            + bb.remaining()
                            + ")");
        }
        byte[] out = new byte[len];
        bb.get(out);
        return out;
    }
}
