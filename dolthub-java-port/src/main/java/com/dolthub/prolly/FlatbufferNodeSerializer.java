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

import com.google.flatbuffers.FlatBufferBuilder;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>production node serializer</b> — turns one chunk's items into the on-disk node bytes:
 * {@code [NODE_MAGIC][CORE_FORMAT_VERSION]} header + a ProllyTreeNode flatbuffer (the only node
 * wire format; the test-only Simple/TLV reader was deleted 2026-07-01, so every node ever written
 * goes through this class).
 *
 * <p><b>Why flatbuffers:</b> the read path never deserializes a node into objects — {@link Node}
 * wraps the raw bytes and the generated {@code serial.ProllyTreeNode} accessors read fields
 * in-place, so a cursor walk touches only the bytes it needs. The schema layout is shared with
 * Dolt's, but the port maintains its <b>own</b> format — byte-for-byte Dolt parity is
 * optional/deferred (the chunker + tuple layers diverge from Dolt v2.0.3; {@code
 * cross-lang/BITCOMPAT_FINDINGS.md}), so {@code CrossLanguageFixtureTest} pins the port's own
 * format, not Go parity.
 *
 * @apiNote One instance serves all chunks of a single {@code TreeMutator.Chunker} (one level of one
 *     tree build); <b>not thread-safe</b> — each Chunker owns its own serializer. Fail-closed size
 *     contract (core-fail-closed-bounds D-1 / ADR-0069 Q1): a node's key or value byte sum over
 *     65535 throws {@link IllegalArgumentException} rather than silently truncating the {@code
 *     uint16} end-offset table (see {@link #toUint16OffsetOrThrow}) — only a lone item larger than
 *     64 KiB can reach the cap, because the splitter's {@code MAX_CHUNK_SIZE} keeps multi-item
 *     nodes far under it. Leaf nodes ({@code level == 0}) carry values; internal nodes carry child
 *     addresses + varint-encoded subtree counts (the prefix-sum contract documented on {@link
 *     Node#getSubtreeCount}).
 * @implNote <b>Collaborators:</b> {@code TreeMutator.Chunker} (the sole production constructor —
 *     one per level per build), {@link Node} + {@code serial.ProllyTreeNode} (the read side: what
 *     this writes, {@link Node#fromBytes} verifies + wraps), {@link Varints} (subtree-count
 *     encoding), {@link FormatVersion} + {@code Node.NODE_MAGIC} (the self-describing header,
 *     ADR-0072 — written here, verified before any field read). <b>Dependents:</b> every tree write
 *     ({@code TreeMutator.applyMutations}) and the format pins ({@code ChunkerDeterminismGateTest},
 *     {@code SubtreeCountContractProperty}). The {@link FlatBufferBuilder} is reused across calls
 *     via {@code clear()} — {@link #serialize} runs once per chunk, so a fresh builder + its
 *     growable buffer per call was per-chunk garbage.
 */
public class FlatbufferNodeSerializer {
    private final FlatBufferBuilder fbb = new FlatBufferBuilder(1024);

    // No-arg: the serializer's output is a fresh heap byte[] (the flatbuffer builder's own
    // buffer), so it never needed the BufferPool its constructor once took — the dead pool
    // field + parameter were removed 2026-07-02 (package-organization-and-javadoc-standard
    // Step 9).
    public FlatbufferNodeSerializer() {}

    /**
     * Narrow a node byte-size sum to {@code int}, or fail closed if it exceeds the 2 GiB limit (a
     * flatbuffer vector offset is 32-bit). Guards the silent {@code int}-overflow that would
     * otherwise serialize a &gt;2 GiB node to a wrong/negative-sized buffer with no exception.
     * core-fail-closed-bounds D-1.
     */
    static int toIntSizeOrThrow(long size, String what) {
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    what
                            + " total "
                            + size
                            + " exceeds the 2 GiB node limit — chunk large values before serializing");
        }
        return (int) size;
    }

    /**
     * Fail closed if a node's key (or value) byte sum exceeds the {@code uint16} offset-table limit
     * (65535) — otherwise the {@code (short) offset} cast in {@link #writeOffsetVector} silently
     * truncates and the value reads back corrupt (its low 16 bits: a 65536-byte value read back as
     * 0 bytes). Matches Dolt's {@code MaxVectorOffset} (65535) cap; the {@code MAX_CHUNK_SIZE}
     * splitter cap keeps multi-item nodes far under this, so only a <b>lone item larger than
     * 65535</b> reaches it (the splitter cannot split mid-item). ADR-0069 Q1. (Supporting larger
     * single values would mean widening the offset table to 32-bit — a deliberate format change,
     * not done here.)
     */
    static void toUint16OffsetOrThrow(int size, String what) {
        if (size > 0xFFFF) {
            throw new IllegalArgumentException(
                    what
                            + " total "
                            + size
                            + " exceeds the 64 KiB node offset-table limit (uint16) — chunk large"
                            + " values before serializing");
        }
    }

    public byte[] serialize(int level, List<TreeMutator.PendingItem> items) {
        // Accumulate as long, then fail closed BEFORE the narrowing cast (D-1) — a per-item byte
        // size is
        // bounded by the 65 KiB tuple cap, but the node-wide sum is not. Valid nodes are unchanged.
        long keyBytes = 0;
        long valBytes = 0;
        for (var item : items) {
            keyBytes += item.key().byteSize();
            valBytes += item.value().byteSize();
        }
        int keyDataSize = toIntSizeOrThrow(keyBytes, "node key bytes");
        int valDataSize = toIntSizeOrThrow(valBytes, "node value bytes");
        // The per-item end-offset table is uint16 (writeOffsetVector) — fail closed before a >65535
        // sum would silently truncate the offset and corrupt the value on read-back (ADR-0069 Q1).
        toUint16OffsetOrThrow(keyDataSize, "node key bytes");
        toUint16OffsetOrThrow(valDataSize, "node value bytes");

        fbb.clear();
        int keyItemsOffset = writeByteVector(items, true, keyDataSize);
        int keyOffsetsOffset = writeOffsetVector(items, true, keyDataSize);

        int valueItemsOffset = 0;
        int valueOffsetsOffset = 0;
        int addressArrayOffset = 0;
        int subtreeCountsOffset = 0;
        long totalTreeCount = 0;

        if (level == 0) {
            valueItemsOffset = writeByteVector(items, false, valDataSize);
            valueOffsetsOffset = writeOffsetVector(items, false, valDataSize);
            totalTreeCount = items.size();
        } else {
            addressArrayOffset = writeByteVector(items, false, valDataSize);
            List<Long> counts = new ArrayList<>(items.size());
            for (var item : items) {
                counts.add(item.subtreeCount());
                totalTreeCount += item.subtreeCount();
            }
            subtreeCountsOffset = fbb.createByteVector(Varints.encodeVarints(counts));
        }

        fbb.startTable(11);
        fbb.addOffset(0, keyItemsOffset, 0);
        fbb.addOffset(1, keyOffsetsOffset, 0);
        fbb.addByte(2, (byte) 1, 0);
        if (level == 0) {
            fbb.addOffset(3, valueItemsOffset, 0);
            fbb.addOffset(4, valueOffsetsOffset, 0);
            fbb.addByte(5, (byte) 1, 0);
        } else {
            fbb.addOffset(7, addressArrayOffset, 0);
            fbb.addOffset(8, subtreeCountsOffset, 0);
        }
        fbb.addLong(9, totalTreeCount, 0);
        fbb.addByte(10, (byte) level, 0);
        fbb.finish(fbb.endTable(), "TUPM");

        byte[] fb = fbb.sizedByteArray();
        // Prepend the self-describing node header [NODE_MAGIC][CORE_FORMAT_VERSION] (ADR-0072),
        // mirroring Commit.serialize. Node.fromBytes verifies it before any field is read, so a
        // foreign / future-incompatible node fails closed instead of additive-misparsing. The
        // header
        // is *outside* the flatbuffer (it needs no flatc regen) and changes every node's bytes/hash
        // —
        // a coordinated, one-time format break (pre-1.0 no-backwards-compat; back-up + restore).
        byte[] out = new byte[Node.NODE_HEADER_SZ + fb.length];
        System.arraycopy(Node.NODE_MAGIC, 0, out, 0, Node.NODE_MAGIC.length);
        out[Node.NODE_MAGIC.length] = (byte) FormatVersion.CORE_FORMAT_VERSION;
        System.arraycopy(fb, 0, out, Node.NODE_HEADER_SZ, fb.length);
        return out;
    }

    /**
     * Append every {@code items} key (or value) as one flat byte vector.
     *
     * <p><b>Bulk copy</b> (an upstream performance-bottleneck plan, D-1, 2026-06-10): assemble the
     * items' bytes into one heap {@code byte[]} with a bulk {@link MemorySegment#copy} per segment,
     * then hand the whole array to {@link FlatBufferBuilder#createByteVector(byte[])}. This is
     * byte-for-byte identical to the previous per-byte reverse loop — FlatBuffers vectors are built
     * back-to-front, so the old {@code for i desc { for j desc addByte }} laid the bytes in memory
     * low→high as {@code item0.byte0 … itemLast.byteLast}; the forward concatenation here + {@code
     * createByteVector} (which writes {@code data[0]} lowest) reproduce exactly that order (pinned
     * by {@code CrossLanguageFixtureTest} + the codec round-trip). The win: the old loop called
     * {@code seg.get(JAVA_BYTE, j)} once per byte, and each call ran the JDK's {@code
     * AbstractMemorySegmentImpl.isAlignedForElement} — a CPU flame attributed ~64% of a document
     * write's visible Java CPU to that per-byte path. One bulk copy per segment removes the
     * per-element alignment check entirely.
     */
    private int writeByteVector(
            List<TreeMutator.PendingItem> items, boolean useKey, int totalSize) {
        byte[] data = new byte[totalSize];
        int off = 0;
        for (TreeMutator.PendingItem item :
                items) { // forward order; createByteVector keeps data[0] lowest
            MemorySegment seg = useKey ? item.key() : item.value();
            int n = (int) seg.byteSize();
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0L, data, off, n);
            off += n;
        }
        return fbb.createByteVector(data);
    }

    /** Append the uint16 end-offset table for the keys (or values) vector. */
    private int writeOffsetVector(
            List<TreeMutator.PendingItem> items, boolean useKey, int totalSize) {
        fbb.startVector(2, items.size() + 1, 2);
        int offset = totalSize;
        fbb.addShort((short) offset);
        for (int i = items.size() - 1; i >= 0; i--) {
            MemorySegment seg = useKey ? items.get(i).key() : items.get(i).value();
            offset -= (int) seg.byteSize();
            fbb.addShort((short) offset);
        }
        return fbb.endVector();
    }
}
