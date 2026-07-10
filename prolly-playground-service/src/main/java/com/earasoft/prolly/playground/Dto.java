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
package com.earasoft.prolly.playground;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire shapes for the playground API. */
public final class Dto {
    private Dto() {}

    /**
     * @param rootHash null for the empty tree
     * @param height root level; -1 for the empty tree
     * @param written the node hashes THIS operation minted — the measured spine
     */
    public record TreeSummary(
            @Nullable String rootHash,
            long treeCount,
            int height,
            int storedNodes,
            List<String> written,
            @Nullable Long engineMicros) {}

    /**
     * @param verified the bytes re-hash to the requested name — node CAS, checked live
     */
    public record NodeView(
            String hash,
            int level,
            boolean leaf,
            int count,
            long treeCount,
            int byteSize,
            boolean verified,
            List<Long> keys,
            List<ChildRef> children) {}

    /**
     * @param subtreeCountPrefix cumulative entries through this child (the counted B-tree)
     */
    public record ChildRef(String hash, long subtreeCountPrefix) {}

    public record NodeRef(String hash, int level, int count) {}

    public record KeysRequest(List<Long> keys) {}

    /** {@code POST /api/bench} body; null fields take the server defaults. */
    public record BenchRequest(@Nullable String kind, @Nullable Integer ops) {}

    /**
     * One benchmark run's summary, measured server-side (D-1 of playground-benchmark-section).
     * Latency fields are NANOS — warm point reads are sub-microsecond and a micros field would
     * floor them to an invented 0. {@code rootRestored}/{@code storedNodesDelta} are write-bench
     * only (the cleanup's history-independence assertion + the honest copy-on-write garbage).
     */
    public record BenchResult(
            String kind,
            int ops,
            int warmupOps,
            long engineTotalNanos,
            long opsPerSec,
            long meanNanos,
            long p50Nanos,
            long p90Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos,
            double nodesPerOp,
            long treeCount,
            int height,
            @Nullable String rootBefore,
            @Nullable String rootAfter,
            @Nullable Boolean rootRestored,
            @Nullable Integer storedNodesDelta) {}

    /** {@code POST /api/bench/compare} body; null fields take the server defaults. */
    public record CompareRequest(@Nullable Integer readOps, @Nullable Integer writeOps) {}

    /**
     * One store kind's turn in the comparison: a fresh ephemeral store seeded by copying the live
     * tree's reachable chunks ({@code seedMillis}; {@code sameRoot} asserts the copy re-derived the
     * identical root — content addressing as the equality proof), then the standard read + write
     * bench results.
     */
    public record CompareArm(
            String kind, long seedMillis, boolean sameRoot, BenchResult read, BenchResult write) {}

    /** The three-arm store comparison; {@code liveKind} names the store this service runs on. */
    public record CompareResult(
            String rootHash, long treeCount, int height, String liveKind, List<CompareArm> arms) {}

    /**
     * A point lookup's outcome + the descent that produced it. {@code readPath} is the node hashes
     * the store actually served (root first) — measured by the store, never re-derived.
     */
    public record FindResult(boolean found, long key, List<String> readPath, long engineMicros) {}

    /** An ordinal seek's outcome; {@code key} is null when {@code n} is out of range. */
    public record RankResult(
            long n, @Nullable Long key, List<String> readPath, long engineMicros) {}

    /** A stored node's raw bytes as hex — SHA-512/20 of exactly these bytes IS the name. */
    public record BytesView(String hash, int byteSize, String hex) {}

    /**
     * One byte range of a stored node, attributed by the engine's own parse. {@code [start,end)};
     * {@code role} is a closed set: {@code envelope | key | value | address | counts |
     * scaffolding}. Regions tile the byte array exactly — unattributed bytes are honest {@code
     * scaffolding}, never hidden.
     */
    public record LayoutRegion(
            int start, int end, String role, String label, @Nullable String decoded) {}

    /** A stored node's full byte-layout annotation (the hex viewer's server-computed truth). */
    public record NodeLayout(String hash, int byteSize, List<LayoutRegion> regions) {}

    /** A range scan's keys (up to {@code limit}; {@code truncated} says so) + the descent. */
    public record ScanResult(
            long from,
            long to,
            List<Long> keys,
            boolean truncated,
            List<String> readPath,
            long engineMicros) {}
}
