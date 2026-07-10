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
package com.earasoft.prolly.sync;

import com.dolthub.prolly.HashUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Binary wire codec for {@link SyncPack} — the format in {@code docs/distributed_sync_protocol.md}
 * §3:
 *
 * <pre>
 *   [u32 BE  MAGIC = 0x53595020 "SYP "]   ← format identifier
 *   [u8      protocolVersion = 1]          ← bumped on any wire change; reader rejects unknown
 *   [u32 BE  chunkCount]
 *   chunkCount × ( [20-byte SHA-512/20 hash] [u32 BE dataLength] [dataLength bytes] )
 *   [u32 BE  commitSectionLength]
 *   [commitSectionLength bytes]      ← the commit-section text (below)
 * </pre>
 *
 * <p><b>Commit section</b> — one line per {@link SyncCommitEntry}, UTF-8, joined by {@code \n}
 * (byte-identical to the pre-extraction {@code CommitLogSync} text; the format moved here with the
 * pack ownership, extract-prolly-sync-module D-1):
 *
 * <pre>
 *   &lt;epochMillis&gt; &lt;hexCommitId&gt; &lt;hexMetaTreeHash&gt; &lt;parentCount&gt; &lt;hexParentId&gt;… &lt;base64Message|-&gt; &lt;base64Author|-&gt;
 * </pre>
 *
 * <p>{@link #parse} verifies each chunk — its data must hash to the carried hash — and rejects the
 * whole pack on a mismatch. Content-addressing is a backstop (a tampered chunk would land at a
 * different address than the tree references), but the explicit check fails fast with a clear
 * error.
 *
 * <p><b>Format versioning</b> (the upstream sync work): the magic + version header fails closed on
 * a wrong-format or future-version stream instead of misparsing arbitrary bytes as a chunk count.
 * The version is the forward-compat enabler — a later wire change (e.g. a per-substrate tag, once
 * JSON packs are real) bumps it, and a v1 reader rejects a v2 stream with a clear message rather
 * than corrupting.
 */
public final class SyncPackCodec {

    private SyncPackCodec() {}

    /**
     * Wire magic — ASCII {@code "SYP "} — identifies a SyncPack stream + fails closed on garbage.
     */
    static final int MAGIC = 0x53595020;

    /**
     * Wire protocol version; bumped on any format change, rejected by a reader that doesn't speak
     * it.
     */
    static final byte PROTOCOL_VERSION = 1;

    /** Serialize {@code pack} to the wire form. */
    public static byte[] serialize(SyncPack pack) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeInt(MAGIC);
            out.writeByte(PROTOCOL_VERSION);
            List<byte[]> chunks = pack.chunks();
            out.writeInt(chunks.size());
            for (byte[] data : chunks) {
                out.write(HashUtils.hash(data)); // 20-byte content hash
                out.writeInt(data.length);
                out.write(data);
            }
            byte[] commitSection = serializeCommitSection(pack.commits());
            out.writeInt(commitSection.length);
            out.write(commitSection);
        } catch (IOException e) {
            // A ByteArrayOutputStream never throws — this is unreachable.
            throw new UncheckedIOException("SyncPackCodec.serialize failed", e);
        }
        return buf.toByteArray();
    }

    /**
     * Parse a wire pack produced by {@link #serialize}.
     *
     * @throws IllegalArgumentException if the bytes are truncated, malformed, or a chunk fails its
     *     integrity check
     */
    public static SyncPack parse(byte[] wire) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IllegalArgumentException(
                        "not a SyncPack: bad magic 0x"
                                + Integer.toHexString(magic)
                                + " (expected 0x"
                                + Integer.toHexString(MAGIC)
                                + ")");
            }
            int version = in.readUnsignedByte();
            if (version != PROTOCOL_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported SyncPack protocol version "
                                + version
                                + " (this build speaks version "
                                + PROTOCOL_VERSION
                                + ")");
            }
            int chunkCount = in.readInt();
            if (chunkCount < 0) {
                throw new IllegalArgumentException("negative chunk count: " + chunkCount);
            }
            // Bound the untrusted count against the available bytes BEFORE
            // allocating. Each chunk occupies at least 24 wire bytes (20-byte
            // hash + 4-byte length, with zero-length data), so a chunkCount
            // larger than wire.length/24 cannot be honest. Without this guard
            // a garbage count drives a heap-exhausting allocation — a 49-byte
            // body whose first 4 bytes decode to ~1.2e9 OOM'd the parse via
            // new ArrayList<>(chunkCount) (resource-exhaustion DoS, 2026-05-28
            // bug-hunt). An OutOfMemoryError is an Error, not the
            // IllegalArgumentException the caller catches, so it surfaced as a
            // raw 500 / JVM-destabilizing OOM rather than a clean 400.
            if (chunkCount > wire.length / 24) {
                throw new IllegalArgumentException(
                        "chunk count "
                                + chunkCount
                                + " exceeds what "
                                + wire.length
                                + " wire bytes can hold");
            }
            List<byte[]> chunks = new ArrayList<>(chunkCount);
            for (int i = 0; i < chunkCount; i++) {
                byte[] claimedHash = new byte[20];
                in.readFully(claimedHash);
                int length = in.readInt();
                if (length < 0) {
                    throw new IllegalArgumentException("negative chunk length: " + length);
                }
                // A single chunk's data cannot exceed the whole wire — reject
                // an oversized length before allocating (same DoS class as the
                // chunkCount guard above).
                if (length > wire.length) {
                    throw new IllegalArgumentException(
                            "chunk length " + length + " exceeds wire size " + wire.length);
                }
                byte[] data = new byte[length];
                in.readFully(data);
                byte[] actualHash = HashUtils.hash(data);
                if (!Arrays.equals(actualHash, claimedHash)) {
                    throw new IllegalArgumentException(
                            "SyncPack chunk "
                                    + i
                                    + " failed its integrity check: data hashes to "
                                    + HashUtils.toHex(actualHash)
                                    + ", pack claimed "
                                    + HashUtils.toHex(claimedHash));
                }
                chunks.add(data);
            }
            int commitSectionLength = in.readInt();
            if (commitSectionLength < 0) {
                throw new IllegalArgumentException(
                        "negative commit-section length: " + commitSectionLength);
            }
            // The commit section is part of the wire — a length larger than
            // the whole wire cannot be honest (same DoS class as above).
            if (commitSectionLength > wire.length) {
                throw new IllegalArgumentException(
                        "commit-section length "
                                + commitSectionLength
                                + " exceeds wire size "
                                + wire.length);
            }
            byte[] commitSection = new byte[commitSectionLength];
            in.readFully(commitSection);
            return new SyncPack(chunks, parseCommitSection(commitSection));
        } catch (EOFException eof) {
            throw new IllegalArgumentException("truncated SyncPack", eof);
        } catch (IOException e) {
            // ByteArrayInputStream only throws EOFException (handled above).
            throw new UncheckedIOException("SyncPackCodec.parse failed", e);
        }
    }

    // ---- The commit-section text (see class doc) ----

    private static final String EMPTY_TOKEN = "-";

    /** Base64 token, or {@code -} for an empty string (never a valid base64 string). */
    private static String encodeOrDash(String s) {
        return s.isEmpty()
                ? EMPTY_TOKEN
                : Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Inverse of {@link #encodeOrDash}: {@code -} → empty, else base64-decode. */
    private static String decodeOrEmpty(String token) {
        return token.equals(EMPTY_TOKEN)
                ? ""
                : new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
    }

    private static byte[] serializeCommitSection(List<SyncCommitEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (SyncCommitEntry e : entries) {
            sb.append(e.timestamp().toEpochMilli())
                    .append(' ')
                    .append(e.hashHex())
                    .append(' ')
                    .append(e.treeHashHex())
                    .append(' ')
                    .append(e.parents().size());
            for (byte[] p : e.parents()) {
                sb.append(' ').append(HashUtils.toHex(p));
            }
            sb.append(' ').append(encodeOrDash(e.message()));
            sb.append(' ').append(encodeOrDash(e.author()));
            sb.append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<SyncCommitEntry> parseCommitSection(byte[] wire) {
        List<SyncCommitEntry> out = new ArrayList<>();
        for (String line : new String(wire, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] t = line.trim().split("\\s+");
            if (t.length < 6) {
                throw new IllegalArgumentException("malformed commit-section line: " + line);
            }
            long epochMillis;
            int parentCount;
            try {
                epochMillis = Long.parseLong(t[0]);
                parentCount = Integer.parseInt(t[3]);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("malformed commit-section line: " + line, nfe);
            }
            if (parentCount < 0 || t.length != 4 + parentCount + 2) {
                throw new IllegalArgumentException(
                        "commit-section line: parent count "
                                + parentCount
                                + " disagrees with token count, in: "
                                + line);
            }
            byte[] id = HashUtils.fromHex(t[1]);
            byte[] metaTreeHash = HashUtils.fromHex(t[2]);
            List<byte[]> parents = new ArrayList<>(parentCount);
            for (int i = 0; i < parentCount; i++) {
                parents.add(HashUtils.fromHex(t[4 + i]));
            }
            String message = decodeOrEmpty(t[4 + parentCount]);
            String author = decodeOrEmpty(t[4 + parentCount + 1]);
            out.add(
                    new SyncCommitEntry(
                            Instant.ofEpochMilli(epochMillis),
                            id,
                            metaTreeHash,
                            parents,
                            message,
                            author));
        }
        return out;
    }
}
