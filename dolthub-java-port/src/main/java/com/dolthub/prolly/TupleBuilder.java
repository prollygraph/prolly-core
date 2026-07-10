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
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Assembles a {@link Tuple}: stage fields by index ({@link #putField}/{@link #putInt64}), then
 * {@link #build()} copies them into one contiguous pool-borrowed segment in the Dolt tuple layout.
 *
 * <p><b>Why a builder with an explicit {@link #close()}:</b> {@code putInt64} borrows 8-byte
 * scratch blocks from the {@link BufferPool}; on an arena-backed pool those must go back
 * (ADR-0062), and the only safe moment is after the <em>last</em> build — which only the caller
 * knows. Hence {@code AutoCloseable} + the use-after-free guard ({@code putX}/{@code build} after
 * {@code close()} throws rather than copying from released memory).
 *
 * @apiNote Single-threaded, single-use by convention: stage → {@code build()} (repeatable — the
 *     build-modify-rebuild pattern is supported) → {@code close()} once, ideally via
 *     try-with-resources. {@code build()} rejects tuples over 65535 bytes (the {@code uint16}
 *     offset space — store a hash and blob out larger values). A null field encodes as an empty
 *     range (NULL-encoded per {@link Tuple}).
 * @implNote <b>Collaborators:</b> {@link BufferPool} (scratch + the built tuple's backing), {@link
 *     TypeCodec} (binary-parity int64 encoding when a {@link TupleDescriptor} demands it), {@link
 *     Tuple} (the output layout). <b>Dependents:</b> the upstream key/value builders and the RDF
 *     index writers. The {@code borrowedScratch} bookkeeping (track the original block, never the
 *     8-byte slice) is load-bearing — see the field doc and {@link #close()}.
 */
public class TupleBuilder implements AutoCloseable {
    /**
     * Cached little-endian layouts. Hoisted to {@code static final} (was created inline per write
     * via {@code .withOrder(...)}): a fresh, non-constant layout forced {@code MemorySegment.set}
     * to allocate a {@code VarHandle} + boxing on every offset/field write — a top descent
     * allocator after the {@link Tuple} fix (the upstream triejoin-performance plan, Phase 3).
     * Constants let the JIT intrinsify with zero allocation.
     */
    private static final ValueLayout.OfShort LE_U16 =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final ValueLayout.OfLong LE_I64 =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final List<MemorySegment> fields = new ArrayList<>();

    /**
     * The ORIGINAL pool-borrowed blocks {@link #putInt64} allocated for int64 field scratch —
     * tracked so {@link #close()} can recycle them (ADR-0062 D-2/D-3/D-4). The list holds the
     * borrowed <em>block</em>, NOT the {@code asSlice(0, 8)} view stored in {@link #fields}: {@code
     * release} buckets by the segment's {@code byteSize()}, so releasing the 8-byte slice would
     * miss the (1024-byte min) bucket and silently leak — D-4. {@code build()} copies each field's
     * bytes into the tuple's own segment, so these blocks are dead scratch after the last build and
     * safe to recycle on {@code close()}.
     */
    private final List<MemorySegment> borrowedScratch = new ArrayList<>();

    private final BufferPool pool;
    private final @Nullable TupleDescriptor descriptor;
    private boolean closed = false;

    public TupleBuilder(BufferPool pool) {
        this(pool, null);
    }

    public TupleBuilder(BufferPool pool, @Nullable TupleDescriptor descriptor) {
        this.pool = pool;
        this.descriptor = descriptor;
    }

    public void putField(int index, byte @Nullable [] value) {
        putField(index, value == null ? null : MemorySegment.ofArray(value));
    }

    public void putField(int index, @Nullable MemorySegment value) {
        ensureOpen();
        while (fields.size() <= index) {
            fields.add(null);
        }
        fields.set(index, value);
    }

    public void putInt64(int index, long value) {
        ensureOpen();
        // Track the ORIGINAL borrowed block so close() can recycle it (D-4); the slice is the view
        // we fill.
        MemorySegment block = pool.borrow(8);
        borrowedScratch.add(block);
        MemorySegment seg = block.asSlice(0, 8);
        if (descriptor != null && descriptor.isBinaryParity()) {
            TypeCodec.encodeInt64(value, seg);
        } else {
            seg.set(LE_I64, 0, value);
        }
        putField(index, seg);
    }

    public Tuple build() {
        ensureOpen();
        int count = fields.size();
        int dataSize = 0;
        for (var f : fields) {
            if (f != null) dataSize += (int) f.byteSize();
        }

        int offsetTableSize = count * 2;
        int footerSize = 2;
        int totalSize = dataSize + offsetTableSize + footerSize;

        // CRITICAL: Prolly Tuples use uint16 for offsets.
        if (totalSize > 65535) {
            throw new IllegalArgumentException(
                    "Tuple too large: "
                            + totalSize
                            + " bytes. Max allowed is 65535. For larger objects, use a blob storage layer and store the hash.");
        }

        MemorySegment segment = pool.borrow(totalSize);

        long currentPos = 0;
        for (int i = 0; i < count; i++) {
            MemorySegment field = fields.get(i);
            if (field != null) {
                MemorySegment.copy(field, 0, segment, currentPos, field.byteSize());
                currentPos += field.byteSize();
            }

            long offsetPos = (long) totalSize - footerSize - (long) (count - i) * 2;
            // Write uint16 offset
            segment.set(LE_U16, offsetPos, (short) currentPos);
        }

        segment.set(LE_U16, (long) totalSize - 2, (short) count);

        return new Tuple(segment.asSlice(0, totalSize));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "TupleBuilder used after close() — its int64 scratch is back in the pool");
        }
    }

    /**
     * Recycle this builder's pool-borrowed int64 scratch back to the pool and retire the builder
     * (ADR-0062 D-2/D-3/D-4 — the off-heap {@code DirectBufferPool} win; a no-op on {@code
     * HeapBufferPool}). Releases ONLY the blocks {@link #putInt64} borrowed — never an external
     * {@link #putField(int, byte[])} segment (heap-wrapped, not pool-owned) and never a built
     * {@link Tuple}'s backing ({@link #build()} copies field bytes into the tuple's own segment, so
     * the int64 scratch is dead after the last build). Idempotent.
     *
     * @apiNote use-after-free-critical contract: call after the LAST {@code build()} on a
     *     single-use builder (e.g. try-with-resources); do NOT call {@code build()} / {@code putX}
     *     after {@code close()} — {@link #fields} still holds {@code asSlice} views into the
     *     now-freed scratch, so a subsequent build would copy from released memory (the guard
     *     throws to make that misuse loud).
     * @implNote {@code build()} itself never recycles, so the build→modify-field→rebuild reuse
     *     pattern stays correct (the {@code TableTest} regression a first cut of
     *     recycling-in-{@code build()} caused). The recycle site is here, on the explicit close,
     *     not in {@code build()}.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (MemorySegment block : borrowedScratch) {
            pool.release(block);
        }
        borrowedScratch.clear();
    }
}
