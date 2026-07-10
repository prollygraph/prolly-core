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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import serial.ProllyTreeNode;

/**
 * A single prolly-tree node — a leaf (level 0, data tuples) or an internal node (child hashes +
 * subtree counts) — parsed as a thin view over its content-addressed bytes.
 *
 * <p>Nodes are the units of the content-addressed Merkle tree: a node's bytes hash to its identity,
 * and an internal node references its children by that hash. {@link #treeCount()} (the total
 * entries under it) is what makes the tree a counted B-tree (O(log n) rank/range estimates).
 *
 * @apiNote <b>Immutable.</b> Use {@link #isLeaf()}/{@link #level()} to discriminate, {@link
 *     #getKeySegment} + {@link #getSubtreeCount} to navigate, and {@link #segment()}/{@link
 *     #bytes()} for the raw form (what gets stored/hashed). <b>Gotcha:</b> {@link
 *     #getKey(int)}/{@link #getValue(int)} are stubs that return {@code null} — read fields via
 *     {@link #getKeySegment} instead.
 * @implNote A wrapper over a {@link java.lang.foreign.MemorySegment} (the serialized node); {@link
 *     #fromBytes} parses the header (offsets, counts, level, subtree counts) without copying the
 *     payload.
 *     <p><b>Collaborators:</b> {@link NodeStore} (stores/reads the bytes), the node serializer
 *     (writes them), {@code MemorySegment}. <b>Dependents:</b> {@link StaticMap}/{@code Cursor}
 *     (root + children), {@link TreeMutator} (builds them).
 */
public class Node {
    protected final MemorySegment msg;
    protected final int count;
    protected final int level;

    protected final int keyItemsVec;
    protected final int keyOffsVec;
    protected final int valItemsVec;
    protected final int valOffsVec;
    protected final int addressArrayVec;
    protected final int subtreeCountsVec;
    private final long treeCountValue;

    public Node(
            MemorySegment msg,
            int keyItems,
            int keyOffs,
            int valItems,
            int valOffs,
            int addrArr,
            int subCounts,
            long treeCnt,
            int count,
            int level) {
        this.msg = msg;
        this.keyItemsVec = keyItems;
        this.keyOffsVec = keyOffs;
        this.valItemsVec = valItems;
        this.valOffsVec = valOffs;
        this.addressArrayVec = addrArr;
        this.subtreeCountsVec = subCounts;
        this.treeCountValue = treeCnt;
        this.count = count;
        this.level = level;
    }

    protected Node(MemorySegment msg, int count, int level) {
        this(msg, 0, 0, 0, 0, 0, 0, count, count, level);
    }

    public byte @Nullable [] getKey(int i) {
        return null;
    }

    public byte @Nullable [] getValue(int i) {
        return null;
    }

    /**
     * The i-th key as a {@link MemorySegment}. Default copies via {@link #getKey} for subclasses
     * that only expose {@code byte[]}; the flatbuffer node overrides it to return a zero-copy slice
     * of its own backing segment — which matters on the scan hot path (one key read per row).
     */
    public MemorySegment getKeySegment(int i) {
        // The base copies via getKey for byte[]-only subclasses; such a subclass must provide a
        // non-null key (the flatbuffer node overrides this method outright for zero-copy).
        return MemorySegment.ofArray(Objects.requireNonNull(getKey(i)));
    }

    /**
     * The <b>cumulative</b> (prefix-sum) subtree count through child {@code i}: the total number of
     * leaf entries under children {@code 0..i} inclusive. At leaf level every entry counts 1, so
     * {@code getSubtreeCount(i) == i + 1} semantically (this base default returns the per-entry 1;
     * the flatbuffer node returns 1 per entry at level 0 — callers at leaf level treat entries as
     * unit-count either way).
     *
     * @apiNote <b>Prefix sums, not per-child counts.</b> The per-child count of child {@code i} is
     *     the delta {@code getSubtreeCount(i) - getSubtreeCount(i - 1)} (and {@code
     *     getSubtreeCount(0)} at {@code i == 0}) — exactly how {@link Cursor#currentSubtreeSize}
     *     recovers it. The consumers that rely on the cumulative semantics: {@code
     *     Cursor.currentSubtreeSize} (the O(log n) size machinery), {@code CardinalityEstimator}
     *     (ordinal arithmetic sums {@code getSubtreeCount(index - 1)} as "entries before index"),
     *     {@code TreeMutator}'s fast-forward accounting, and the tree-integrity audits. Any {@code
     *     Node} implementation MUST return prefix sums — a per-item-count implementation silently
     *     corrupts every one of those consumers (pinned by {@code SubtreeCountContractProperty}).
     */
    public long getSubtreeCount(int i) {
        return 1;
    }

    public long treeCount() {
        return treeCountValue;
    }

    public int count() {
        return count;
    }

    public int level() {
        return level;
    }

    public boolean isLeaf() {
        return level == 0;
    }

    public MemorySegment segment() {
        return msg;
    }

    public byte[] bytes() {
        return msg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
    }

    /**
     * Size of Dolt's {@code serial} message prefix — {@code serial.MessagePrefixSz} in the Go
     * reference. Dolt frames every prolly-node chunk as {@code [1-byte
     * NomsKind=SerialMessage][3-byte big-endian payload size][FlatBuffer]}, so the "TUPM" file
     * identifier lands at offset 8 rather than the bare-FlatBuffer offset 4. The port's own writer
     * emits bare FlatBuffers (no prefix); reads must accept both.
     */
    private static final int SERIAL_MESSAGE_PREFIX_SZ = 4;

    /**
     * Magic prefix of the port's own versioned node record. {@link #fromBytes} checks it first and
     * verifies the trailing version byte <em>before any flatbuffer field is read</em>, so a wrong /
     * foreign / future-incompatible node fails closed with {@link UnsupportedFormatException}
     * instead of being additive-misparsed by flatbuffer field tolerance (ADR-0072). Distinct from
     * {@code Commit}'s {@code 'PCMT'}, {@code RootMetaTree}'s {@code 'PRMT'}, and the inner
     * flatbuffer's {@code "TUPM"} file-identifier. Mirrors {@link Commit}'s {@code COMMIT_MAGIC} +
     * version header.
     */
    static final byte[] NODE_MAGIC = {'P', 'N', 'O', 'D'};

    /**
     * Size of the node header: {@link #NODE_MAGIC} + one {@code CORE_FORMAT_VERSION} byte. Public
     * so layout-annotating consumers (the playground's hex viewer) cite the engine's own constant
     * instead of duplicating format knowledge — the flatbuffer starts at exactly this offset in
     * every port-written node.
     */
    public static final int NODE_HEADER_SZ = NODE_MAGIC.length + 1;

    public static @Nullable Node fromBytes(@Nullable MemorySegment msg) {
        if (msg == null) return null;
        // 1. The port's own versioned node: [PNOD magic][1-byte version][TUPM flatbuffer]. Verified
        //    BEFORE any flatbuffer parse, so a wrong / future version fails closed
        //    (UnsupportedFormatException) instead of additive-misparsing (ADR-0072) — mirrors
        // Commit.
        //    The 5-byte header is NOT stripped from the Node's segment: the flatbuffer is parsed at
        //    offset NODE_HEADER_SZ but the Node keeps the whole blob, so hash(node.bytes()) stays
        //    equal to the node's content-address — the invariant the GC reachability walk relies on
        //    (ReachabilityWalkerTest / SmallTreeRootPersistsTest both compute the root hash that
        // way).
        if (startsWith(msg, NODE_MAGIC)) {
            verifyNodeVersion(msg);
            if (hasTupmIdentifierAt(msg, NODE_HEADER_SZ)) {
                return parseFlatbuffer(msg, NODE_HEADER_SZ);
            }
            throw new UnsupportedFormatException(
                    "prolly node: PNOD header present but the payload is not a TUPM flatbuffer");
        }
        // 2. Dolt serial message (cross-language import) — strip the 4-byte serial prefix so the
        // inner
        //    FlatBuffer's "TUPM" identifier (at offset 4 of the slice) is recognised. Dolt nodes
        // are
        //    serial-framed (a NomsKind byte + 3-byte size); the Dolt-import node keeps its
        // historical
        //    stripped segment (the content-address invariant is a port-node concern). The port's
        // own
        //    pre-ADR-0072 *bare* TUPM format is intentionally NOT accepted (pre-1.0
        // no-backwards-compat
        //    — an old store is re-ingested, never silently parsed); a bare TUPM falls through to
        // the
        //    fail-closed branch below, exactly like any other non-versioned blob.
        if (msg.byteSize() > 8 + SERIAL_MESSAGE_PREFIX_SZ) {
            MemorySegment inner = msg.asSlice(SERIAL_MESSAGE_PREFIX_SZ);
            if (hasTupmIdentifierAt(inner, 0)) {
                return parseFlatbuffer(inner, 0);
            }
        }
        // 3. No recognised format → fail closed (ADR-0072). Neither a PNOD-versioned node nor a
        //    Dolt-serial-framed TUPM. The silent TLV fallback (SimpleNodeSerializer) that used to
        //    live here let a non-versioned blob be misread as a TLV node; it was first demoted to
        //    test-only (ADR-0072), then deleted outright (plan subtree-count-contract D-3 — tests
        //    build nodes via the production FlatbufferNodeSerializer path).
        throw new UnsupportedFormatException(
                "prolly node: unrecognised format — no PNOD header and no Dolt-framed TUPM identifier");
    }

    /** True iff {@code msg} begins with the {@code prefix} bytes. */
    private static boolean startsWith(MemorySegment msg, byte[] prefix) {
        if (msg.byteSize() < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (msg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i) != prefix[i]) return false;
        }
        return true;
    }

    /**
     * True iff a {@code "TUPM"} flatbuffer file-identifier sits at {@code fbOffset} within {@code
     * msg} (the identifier is 4 bytes at flatbuffer-offset 4, so this needs {@code fbOffset + 8}
     * bytes).
     */
    private static boolean hasTupmIdentifierAt(MemorySegment msg, int fbOffset) {
        if (msg.byteSize() < (long) fbOffset + 8) return false;
        ByteBuffer bb = msg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        bb.position(fbOffset);
        return ProllyTreeNode.ProllyTreeNodeBufferHasIdentifier(bb);
    }

    /**
     * Verify the node header's version byte (the caller has already matched {@link #NODE_MAGIC}). A
     * blob too short to carry the version, or a version this engine does not write, fails closed
     * with {@link UnsupportedFormatException} before any field is read as data (ADR-0072).
     */
    private static void verifyNodeVersion(MemorySegment msg) {
        if (msg.byteSize() < NODE_HEADER_SZ) {
            throw new UnsupportedFormatException(
                    "prolly node: too short to carry the format header (magic + version)");
        }
        int version = msg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, NODE_MAGIC.length) & 0xFF;
        if (version != FormatVersion.CORE_FORMAT_VERSION) {
            throw new UnsupportedFormatException(
                    "unsupported prolly node format version "
                            + version
                            + " (this engine writes version "
                            + FormatVersion.CORE_FORMAT_VERSION
                            + ")");
        }
    }

    /**
     * Parses a TUPM-tagged Flatbuffer using the generated {@link ProllyTreeNode} accessors
     * (produced by {@code flatc --java -o src/main/java src/main/fbs/prolly.fbs}). Replaces an
     * earlier hand-rolled vtable parser; preserves identical semantics so on-disk format is
     * unchanged.
     */
    private static Node parseFlatbuffer(MemorySegment msg, int fbOffset) {
        ByteBuffer bb = msg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        // The flatbuffer root sits at fbOffset (0 for a bare/Dolt-stripped blob, NODE_HEADER_SZ for
        // a
        // PNOD-headered port node). bb spans all of msg, so every flatbuffer position the accessors
        // resolve — incl. keyItemsBase below — stays absolute within msg, and getKeySegment can
        // slice
        // msg directly. The Node keeps the whole msg (header included) as its segment (ADR-0072).
        bb.position(fbOffset);
        ProllyTreeNode pt = ProllyTreeNode.getRootAsProllyTreeNode(bb);

        int level = pt.treeLevel();
        long treeCount = pt.treeCount();
        // The offsets vector has count+1 entries (start of each item plus a final
        // sentinel = total bytes), so item count = length - 1.
        int count = Math.max(0, pt.keyOffsetsLength() - 1);
        // Absolute offset of the keyItems vector data within msg — captured
        // once so getKeySegment can slice msg directly instead of copying a
        // byte[] (+ a ByteBuffer view) on every key read.
        ByteBuffer keyItemsBuf = pt.keyItemsAsByteBuffer();
        final int keyItemsBase = (keyItemsBuf != null) ? keyItemsBuf.position() : 0;

        return new Node(msg, 0, 0, 0, 0, 0, 0, treeCount, count, level) {
            @Override
            public byte[] getKey(int i) {
                int start = pt.keyOffsets(i);
                int end = pt.keyOffsets(i + 1);
                byte[] data = new byte[end - start];
                ByteBuffer items = pt.keyItemsAsByteBuffer();
                items.position(items.position() + start);
                items.get(data);
                return data;
            }

            @Override
            public MemorySegment getKeySegment(int i) {
                // Zero-copy: the i-th key is keyItems[keyOffsets(i) ..
                // keyOffsets(i+1)], a contiguous run inside msg.
                int start = pt.keyOffsets(i);
                int end = pt.keyOffsets(i + 1);
                return msg.asSlice(keyItemsBase + start, end - start);
            }

            @Override
            public byte[] getValue(int i) {
                if (level == 0) {
                    int start = pt.valueOffsets(i);
                    int end = pt.valueOffsets(i + 1);
                    byte[] data = new byte[end - start];
                    ByteBuffer items = pt.valueItemsAsByteBuffer();
                    items.position(items.position() + start);
                    items.get(data);
                    return data;
                }
                byte[] hash = new byte[20];
                ByteBuffer addr = pt.addressArrayAsByteBuffer();
                addr.position(addr.position() + i * 20);
                addr.get(hash);
                return hash;
            }

            @Override
            public long getSubtreeCount(int i) {
                if (level == 0) return 1;
                // Returns the prefix sum of varints[0..i] per Varints.getUvarintAt.
                // Existing tree-integrity audits rely on this cumulative semantics.
                ByteBuffer cnts = pt.subtreeCountsAsByteBuffer();
                return Varints.getUvarintAt(cnts, i);
            }
        };
    }
}
