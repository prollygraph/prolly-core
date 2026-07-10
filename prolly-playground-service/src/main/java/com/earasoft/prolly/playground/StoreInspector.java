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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Terminal inspector for an on-disk store — {@code java -jar … --inspect <store-dir> [head|hash]}.
 * Decodes with the engine's own {@code Node} reader (via {@link TreeService}), so it can never
 * disagree with the format: the deliberate alternative to a second parser in another language,
 * which would silently rot while the pre-1.0 format evolves freely.
 *
 * <p>Auto-detects the store kind ({@code chunks/} → file store, {@code db/} → RocksDB) and REFUSES
 * a path that is neither — the service constructor would otherwise create directories at a typo'd
 * path. Note: inspecting a RocksDB store requires nothing else holding the database (RocksDB is
 * single-process); the file store reads fine beside a running service.
 */
final class StoreInspector {

    private StoreInspector() {}

    static int run(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: --inspect <store-dir> [head|<40-hex-hash>]");
            return 2;
        }
        Path dir = Path.of(args[0]);
        final String kind;
        if (Files.isDirectory(dir.resolve("chunks"))) {
            kind = "file";
        } else if (Files.isDirectory(dir.resolve("db"))) {
            kind = "rocks";
        } else {
            System.err.println(dir + " is not a store directory (no chunks/ or db/ inside)");
            return 2;
        }
        TreeService svc = new TreeService(kind, dir.toString());
        try {
            String target = args.length == 2 ? args[1] : "head";
            String out = target.equals("head") ? renderTree(svc, kind) : renderNode(svc, target);
            if (out == null) {
                return 1;
            }
            System.out.println(out);
            return 0;
        } finally {
            svc.shutdown();
        }
    }

    /** The whole live tree from {@code head}, one line per node, verification aggregated. */
    static String renderTree(TreeService svc, String kind) {
        Dto.TreeSummary s = svc.summary(Set.of());
        StringBuilder sb = new StringBuilder();
        sb.append("store: ").append(kind).append(" · ").append(s.storedNodes()).append(" chunks");
        if (s.rootHash() == null) {
            return sb.append("\nhead: (empty tree — no root)").toString();
        }
        List<Dto.NodeView> nodes = svc.treeNodes();
        Map<String, Dto.NodeView> byHash = new HashMap<>();
        long unverified = nodes.stream().filter(n -> !n.verified()).count();
        for (Dto.NodeView n : nodes) {
            byHash.put(n.hash(), n);
        }
        sb.append(" (").append(nodes.size()).append(" reachable from head)\n");
        sb.append("head:  ⋄").append(s.rootHash()).append("\n");
        sb.append("tree:  ")
                .append(s.treeCount())
                .append(" keys · height ")
                .append(s.height())
                .append(" · ")
                .append(
                        unverified == 0
                                ? "every reachable node verified ✓ (bytes re-hash to their names)"
                                : unverified + " NODES FAIL VERIFICATION ✗")
                .append("\n\n");
        renderSubtree(byHash, s.rootHash(), "", true, 0, sb);
        return sb.toString();
    }

    private static void renderSubtree(
            Map<String, Dto.NodeView> byHash,
            String hash,
            String indent,
            boolean last,
            int depth,
            StringBuilder sb) {
        Dto.NodeView n = byHash.get(hash);
        sb.append(indent);
        if (depth > 0) {
            sb.append(last ? "└─ " : "├─ ");
        }
        if (n == null) {
            sb.append("⋄").append(hash, 0, 10).append("  (MISSING FROM STORE ✗)\n");
            return;
        }
        sb.append("⋄").append(n.hash(), 0, 10).append(n.leaf() ? "  leaf" : "  L" + n.level());
        if (!n.keys().isEmpty()) {
            sb.append("  [")
                    .append(n.keys().get(0))
                    .append("‥")
                    .append(n.keys().get(n.keys().size() - 1))
                    .append("]");
        }
        sb.append("  ×").append(n.treeCount()).append(n.verified() ? "  ✓" : "  ✗ HASH MISMATCH");
        sb.append("\n");
        if (!n.leaf()) {
            String childIndent = depth == 0 ? "" : indent + (last ? "   " : "│  ");
            List<Dto.ChildRef> kids = n.children();
            for (int i = 0; i < kids.size(); i++) {
                renderSubtree(
                        byHash,
                        kids.get(i).hash(),
                        childIndent,
                        i == kids.size() - 1,
                        depth + 1,
                        sb);
            }
        }
    }

    /** One node, fully decoded; keys capped for terminal sanity. */
    static String renderNode(TreeService svc, String hex) {
        Optional<Dto.NodeView> ov = svc.node(hex);
        if (ov.isEmpty()) {
            System.err.println("no chunk named " + hex + " in this store");
            return null;
        }
        Dto.NodeView n = ov.get();
        StringBuilder sb = new StringBuilder();
        sb.append("⋄").append(n.hash()).append("\n");
        sb.append(n.leaf() ? "leaf" : "internal L" + n.level())
                .append(" · ")
                .append(n.count())
                .append(" entries · subtree ")
                .append(n.treeCount())
                .append(" · ")
                .append(n.byteSize())
                .append(" B · ")
                .append(n.verified() ? "verified ✓ (bytes re-hash to the name)" : "HASH MISMATCH ✗")
                .append("\n");
        List<Long> keys = n.keys();
        sb.append("keys (").append(keys.size()).append("): ");
        int cap = Math.min(keys.size(), 20);
        for (int i = 0; i < cap; i++) {
            sb.append(i == 0 ? "" : ", ").append(keys.get(i));
        }
        if (keys.size() > cap) {
            sb.append(" … ").append(keys.get(keys.size() - 1));
        }
        sb.append("\n");
        if (!n.children().isEmpty()) {
            sb.append("children (cumulative subtree counts — the counted B-tree):\n");
            for (Dto.ChildRef c : n.children()) {
                sb.append("  ⋄")
                        .append(c.hash(), 0, 10)
                        .append("  ≤")
                        .append(c.subtreeCountPrefix())
                        .append("\n");
            }
        }
        return sb.toString();
    }
}
