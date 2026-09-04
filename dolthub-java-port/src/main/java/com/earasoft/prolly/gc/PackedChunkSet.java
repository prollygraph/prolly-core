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
package com.earasoft.prolly.gc;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * The production {@link ChunkSet}: one flat {@code byte[]} of open-addressed slots, keys stored
 * inline.
 *
 * @apiNote NOT thread-safe — see {@link ConcurrentChunkSet}. Size it with {@link
 *     #PackedChunkSet(int)} when the count is known; a mark walk that grows from the default
 *     rehashes about twenty times, and each rehash copies the whole table.
 * @implNote <b>The slot index is the key's own leading bytes.</b> Every key here is a SHA-512/20
 *     digest, so its high bits are already uniformly distributed — running them through a mixing
 *     function would cost work to achieve a property they already have. Probing is linear, which
 *     suits a uniform key distribution and keeps the scan inside one cache line for the common
 *     case.
 *     <p><b>Footprint:</b> {@code capacity * 20} bytes for the table plus {@code capacity / 8} for
 *     the occupancy bitmap, where capacity is the power of two above {@code size / 0.6}. For the
 *     4.08M-chunk store this measured 8,388,608 slots — 160 MiB plus a 1 MiB bitmap, against ~477
 *     MB for the {@code HashSet<String>} of hex it replaces, and with no per-visit allocation at
 *     all.
 *     <p><b>Why an occupancy bitmap rather than an all-zero sentinel key:</b> a 20-byte zero hash
 *     is astronomically improbable but not impossible, and the failure it would cause — one chunk
 *     silently treated as absent, therefore swept — is unrecoverable. One bit per slot buys the
 *     impossibility outright.
 *     <p><b>Collaborators:</b> implements {@link ChunkSet}; filled by {@code DataTreeReachability}
 *     and {@code ReachabilityWalker}; read by {@code GarbageCollector.sweep}.
 */
public final class PackedChunkSet implements ChunkSet {

    private static final double LOAD_FACTOR = 0.6;

    private byte[] table;
    private long[] occupied;
    private int capacity;
    private int size;
    private int growAt;

    public PackedChunkSet() {
        this(1 << 12);
    }

    /**
     * @param expected a hint; the table is sized so this many keys fit without a rehash
     */
    public PackedChunkSet(int expected) {
        allocate(capacityFor(expected));
    }

    private static int capacityFor(int expected) {
        int want = Math.max(1, (int) (expected / LOAD_FACTOR) - 1);
        return Math.max(16, Integer.highestOneBit(want) << 1);
    }

    private void allocate(int cap) {
        this.capacity = cap;
        this.table = new byte[cap * HASH_LEN];
        this.occupied = new long[(cap + 63) >>> 6];
        this.growAt = (int) (cap * LOAD_FACTOR);
    }

    /** The slot a key starts probing from — its own leading four bytes, masked to the table. */
    private static int slotOf(byte[] key, int off, int cap) {
        int h =
                ((key[off] & 0xFF) << 24)
                        | ((key[off + 1] & 0xFF) << 16)
                        | ((key[off + 2] & 0xFF) << 8)
                        | (key[off + 3] & 0xFF);
        return h & (cap - 1);
    }

    private boolean isOccupied(int slot) {
        return (occupied[slot >>> 6] & (1L << (slot & 63))) != 0;
    }

    private void markOccupied(int slot) {
        occupied[slot >>> 6] |= 1L << (slot & 63);
    }

    private boolean keyEquals(int slot, byte[] key, int off) {
        int base = slot * HASH_LEN;
        for (int i = 0; i < HASH_LEN; i++) {
            if (table[base + i] != key[off + i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean add(byte[] key) {
        return add(key, 0);
    }

    /**
     * Adds the {@link #HASH_LEN} bytes at {@code key[off]}, avoiding a defensive copy.
     *
     * @throws IllegalArgumentException if fewer than {@link #HASH_LEN} bytes are available. This is
     *     a precondition, not a lookup: a short key reaching {@link #contains} is correctly absent
     *     (a 19-byte value cannot be a member of a set of 20-byte keys, and the sweep filters on
     *     {@code key.length == 20} besides), but a short key reaching {@code add} would corrupt the
     *     table, so it fails loudly here rather than as an array index deep inside {@code
     *     System.arraycopy}.
     */
    public boolean add(byte[] key, int off) {
        if (key == null || off < 0 || key.length - off < HASH_LEN) {
            throw new IllegalArgumentException(
                    "a chunk hash is "
                            + HASH_LEN
                            + " bytes; got "
                            + (key == null
                                    ? "null"
                                    : (key.length - off) + " available at offset " + off));
        }
        if (size >= growAt) {
            rehash();
        }
        int slot = slotOf(key, off, capacity);
        while (isOccupied(slot)) {
            if (keyEquals(slot, key, off)) {
                return false;
            }
            slot = (slot + 1) & (capacity - 1);
        }
        System.arraycopy(key, off, table, slot * HASH_LEN, HASH_LEN);
        markOccupied(slot);
        size++;
        return true;
    }

    @Override
    public boolean contains(byte[] key) {
        if (key == null || key.length < HASH_LEN) {
            return false;
        }
        int slot = slotOf(key, 0, capacity);
        while (isOccupied(slot)) {
            if (keyEquals(slot, key, 0)) {
                return true;
            }
            slot = (slot + 1) & (capacity - 1);
        }
        return false;
    }

    private void rehash() {
        byte[] oldTable = table;
        long[] oldOccupied = occupied;
        int oldCapacity = capacity;
        allocate(oldCapacity << 1);
        size = 0;
        for (int slot = 0; slot < oldCapacity; slot++) {
            if ((oldOccupied[slot >>> 6] & (1L << (slot & 63))) != 0) {
                add(oldTable, slot * HASH_LEN);
            }
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void forEach(Consumer<byte[]> sink) {
        for (int slot = 0; slot < capacity; slot++) {
            if (isOccupied(slot)) {
                sink.accept(Arrays.copyOfRange(table, slot * HASH_LEN, slot * HASH_LEN + HASH_LEN));
            }
        }
    }

    /**
     * Adds every hash in {@code other}.
     *
     * @implNote Specialised for a packed source so a bulk union costs no allocation: the default
     *     {@link ChunkSet#addAll} goes through {@link #forEach}, which hands out a fresh array per
     *     element — four million of them when unioning a mark set, which is exactly the churn this
     *     class exists to remove.
     */
    @Override
    public void addAll(ChunkSet other) {
        if (other instanceof PackedChunkSet p) {
            for (int slot = 0; slot < p.capacity; slot++) {
                if (p.isOccupied(slot)) {
                    add(p.table, slot * HASH_LEN);
                }
            }
            return;
        }
        ChunkSet.super.addAll(other);
    }

    /** Heap held by the table and its occupancy bitmap, for budgeting a collection. */
    public long footprintBytes() {
        return (long) table.length + occupied.length * 8L;
    }

    @Override
    public String toString() {
        return "PackedChunkSet(size=" + size + ", capacity=" + capacity + ")";
    }
}
