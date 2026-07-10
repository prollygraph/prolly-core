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
package com.earasoft.prolly.sync.grpc;

import com.earasoft.prolly.Database;
import java.util.NoSuchElementException;

/**
 * Resolves a wire {@code repo_id} to an open {@link Database} for the duration of one request.
 *
 * @apiNote LEASE-SHAPED on purpose: a registry-backed host (e.g. an LRU warm set with eviction)
 *     must be able to pin the store open while a request runs — eviction closing a store under an
 *     in-flight pack build is the use-after-close failure the lease prevents. The transport
 *     acquires a lease per RPC and closes it when the RPC completes; a resolver whose stores are
 *     never closed can return a no-op lease. Throw {@link NoSuchElementException} for an unknown id
 *     — the transport maps it to {@code NOT_FOUND}.
 * @implNote The single-repo host case is {@link #singleRepo}: every id (including the empty string)
 *     resolves to the one store, no pinning needed.
 */
@FunctionalInterface
public interface RepoResolver {

    /**
     * @param repoId the wire repo id; single-repo hosts receive the empty string
     * @throws NoSuchElementException when no such repo exists (mapped to {@code NOT_FOUND})
     */
    Lease resolve(String repoId);

    /** An open store, held for at most one request. */
    interface Lease extends AutoCloseable {
        Database db();

        /** Releases the pin, if any. Must not throw. */
        @Override
        void close();
    }

    /**
     * A resolver for the one-store host: every repo id resolves to {@code db}, nothing is ever
     * pinned.
     */
    static RepoResolver singleRepo(Database db) {
        Lease lease =
                new Lease() {
                    @Override
                    public Database db() {
                        return db;
                    }

                    @Override
                    public void close() {}
                };
        return repoId -> lease;
    }
}
