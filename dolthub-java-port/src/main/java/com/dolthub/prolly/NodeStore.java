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
import java.util.Optional;

/**
 * The content-addressed <b>blob store</b> everything sits on: write bytes and get back their
 * content hash; read by that hash. A flat {@code hash → bytes} map — nothing more.
 *
 * <p><b>Terminology (load-bearing):</b> a "chunk" is <i>any</i> content-addressed blob stored here,
 * not specifically the chunker's output. Prolly-tree nodes are the primary client (hence the
 * historical, Dolt-ported name {@code NodeStore}); <b>commit objects are the second</b>, written
 * <i>directly</i> as {@code write(commit.serialize())} with no tree machinery — no chunker, no
 * {@code TreeMutator}, no node framing — in between (ADR-0073; {@code Database} + {@code
 * CommitStore}). <b>Content-addressed</b> means the hash of a blob's bytes <i>is</i> its key —
 * which makes stored blobs immutable, automatically deduplicated, and self-verifying (a blob that
 * does not hash to its key is corrupt).
 *
 * @apiNote {@code write(bytes)} returns the content hash and is idempotent — the same bytes always
 *     yield the same hash, so re-writing an existing node is a no-op. {@code read(hash)} returns
 *     the bytes or empty when absent. Implementations must honor content-addressing and may
 *     re-verify integrity on read.
 * @implNote <b>Collaborators / implementations:</b> {@code RocksNodeStore} (RocksDB-backed — the
 *     production store when a store directory is configured), {@link InMemoryNodeStore} (the
 *     no-store-dir ephemeral dev default + test store), {@code FileNodeStore} (prolly-storage,
 *     git-loose-objects layout), and decorators that add metrics, integrity re-hashing, or a {@link
 *     NodeCache}. The production-vs-alternative pairing is registered in the upstream
 *     production-primitive parity registry; the backend-agnostic contract is pinned across
 *     implementations by {@code NodeStoreContractTest}. <b>Dependents:</b> {@link StaticMap}/{@link
 *     TreeMutator} (read/write nodes), {@code GarbageCollector} (iterate every key and delete the
 *     unreachable ones), and upstream commit-object stores (commit chunks live in the same store —
 *     the upstream ADR-0073 decision).
 */
public interface NodeStore {
    /**
     * Reads the bytes of the node stored under {@code hash}.
     *
     * @param hash the content hash returned by a prior {@link #write}
     * @return the node's bytes, or empty if no node is stored under {@code hash}
     * @throws IllegalArgumentException if {@code hash} is null (fail fast — a null hash is a caller
     *     bug, not a missing node; the production + in-memory stores both enforce this)
     */
    Optional<MemorySegment> read(byte[] hash);

    /**
     * Stores {@code data} and returns its content hash.
     *
     * @param data the node bytes to persist
     * @return the content hash (the key for a later {@link #read}); idempotent — identical bytes
     *     always hash the same, so re-writing an already-stored node is a no-op
     */
    byte[] write(MemorySegment data);

    /** Convenience overload of {@link #write(MemorySegment)} for bytes already on the heap. */
    byte[] write(byte[] data);

    /**
     * Begin a write batch on the <em>current thread</em>. Between this call and {@link
     * #endWriteBatch()}, {@link #write} may buffer chunks instead of persisting each one
     * individually — letting a backing store (e.g. RocksDB) commit a whole tree build as one batch.
     *
     * <p>Contract: a balanced begin/end pair wraps a single-threaded run of writes — {@link
     * TreeMutator#applyMutations} uses one pair per tree build. It is non-reentrant. Buffered
     * chunks are <em>not</em> guaranteed visible to {@link #read} until {@link #endWriteBatch()};
     * callers must not read a chunk they wrote inside the same batch (the tree builders never do —
     * the cursor only walks the prior, already-persisted tree).
     *
     * <p>Default: no-op — the store writes through on every {@link #write}.
     */
    default void beginWriteBatch() {}

    /**
     * End the current thread's write batch, persisting any buffered writes with the store's normal
     * durability. Safe to call when no batch is active. Default: no-op.
     */
    default void endWriteBatch() {}
}
