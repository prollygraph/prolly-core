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
import java.time.Instant;
import java.util.List;

/**
 * One commit-history entry as the <b>sync wire</b> owns it (the upstream extraction plan D-1) — the
 * structural fields a pack's history half carries, decoupled from the upstream store-owned {@code
 * CommitLog.Entry} so the pack protocol depends on no upstream type. Field-for-field identical to
 * the upstream log entry (timestamp, commit id, meta-tree hash, parents, message, author); the
 * "adapter" is a one-line map at the pack-build seam and direct field consumption on the receive
 * side — the wire bytes are unchanged.
 */
public record SyncCommitEntry(
        Instant timestamp,
        byte[] id,
        byte[] metaTreeHash,
        List<byte[]> parents,
        String message,
        String author) {

    public SyncCommitEntry {
        parents = List.copyOf(parents);
    }

    /** The commit id as hex — the DAG identity the closure and dedup key on. */
    public String hashHex() {
        return HashUtils.toHex(id);
    }

    /** The referenced tree hash as hex. */
    public String treeHashHex() {
        return HashUtils.toHex(metaTreeHash);
    }

    /** The parent ids as hex, in order. */
    public List<String> parentsHex() {
        return parents.stream().map(HashUtils::toHex).toList();
    }
}
