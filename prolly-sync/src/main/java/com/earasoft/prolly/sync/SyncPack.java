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

import java.util.List;

/**
 * One sync transfer unit — the two halves a fetch or push moves together:
 *
 * <ul>
 *   <li>{@code chunks} — the raw, content-addressed node bytes (the data; each is re-hashed to its
 *       address on receipt, so no claimed hash is carried);
 *   <li>{@code commits} — the {@link SyncCommitEntry} closure (the history; the DAG edges,
 *       timestamps and messages, which are not content-addressed — see {@code the upstream
 *       distributed-sync plan} Phase 1). The upstream store-owned {@code CommitLog.Entry} maps to
 *       it at the pack-build seam (extract-prolly-sync-module D-1); a {@code Database}-substrate
 *       pack carries commits as ordinary chunks and leaves this list empty.
 * </ul>
 */
public record SyncPack(List<byte[]> chunks, List<SyncCommitEntry> commits) {

    public SyncPack {
        chunks = List.copyOf(chunks);
        commits = List.copyOf(commits);
    }

    /** True iff the pack carries neither chunks nor commits — nothing to transfer. */
    public boolean isEmpty() {
        return chunks.isEmpty() && commits.isEmpty();
    }
}
