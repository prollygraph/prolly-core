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
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The playground's window onto the real engine. CORS is wide open by design — the web playground is
 * served from {@code file://} (a null origin) and this backend holds no data worth protecting (a
 * single in-memory toy tree).
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class PlaygroundController {

    private final TreeService trees;

    public PlaygroundController(TreeService trees) {
        this.trees = trees;
    }

    /** Current tree summary (no write set — nothing was written by asking). */
    @GetMapping("/tree")
    public Dto.TreeSummary tree() {
        return trees.summary(Set.of());
    }

    /** Replace the world with a fresh tree of exactly these keys. */
    @PutMapping("/tree")
    public Dto.TreeSummary replace(@RequestBody Dto.KeysRequest req) {
        return trees.replace(req.keys() == null ? List.of() : req.keys());
    }

    /** Insert keys into the current tree; the response's written[] is the real spine. */
    @PostMapping("/tree/keys")
    public Dto.TreeSummary insert(@RequestBody Dto.KeysRequest req) {
        return trees.mutate(req.keys() == null ? List.of() : req.keys(), List.of());
    }

    /** Delete keys from the current tree. */
    @DeleteMapping("/tree/keys")
    public Dto.TreeSummary delete(@RequestBody Dto.KeysRequest req) {
        return trees.mutate(List.of(), req.keys() == null ? List.of() : req.keys());
    }

    /** Operator reset: erase the store and the tree (the CAS never forgets otherwise). */
    @PostMapping("/reset")
    public Dto.TreeSummary reset() {
        return trees.reset();
    }

    /**
     * A measured benchmark loop, run SERVER-side so the numbers are engine time, not HTTP + JSON
     * (D-1 of plan playground-benchmark-section). 409 for a read bench on an empty tree.
     */
    @PostMapping("/bench")
    public ResponseEntity<?> bench(@RequestBody Dto.BenchRequest req) {
        String kind = req.kind() == null ? "read" : req.kind();
        int ops = req.ops() != null ? req.ops() : ("write".equals(kind) ? 500 : 5_000);
        try {
            return ResponseEntity.ok(trees.bench(kind, ops));
        } catch (IllegalStateException emptyTree) {
            return ResponseEntity.status(409)
                    .body(java.util.Map.of("error", String.valueOf(emptyTree.getMessage())));
        } catch (IllegalArgumentException badKind) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", String.valueOf(badKind.getMessage())));
        }
    }

    /**
     * The three-store comparison — fresh memory/file/rocks arms seeded with a byte copy of the live
     * tree, benched sequentially. 409 for an empty tree or one past the seeding cap.
     */
    @PostMapping("/bench/compare")
    public ResponseEntity<?> benchCompare(@RequestBody Dto.CompareRequest req) {
        int readOps = req.readOps() != null ? req.readOps() : 5_000;
        int writeOps = req.writeOps() != null ? req.writeOps() : 200;
        try {
            return ResponseEntity.ok(trees.compareStores(readOps, writeOps));
        } catch (IllegalStateException refusal) {
            return ResponseEntity.status(409)
                    .body(java.util.Map.of("error", String.valueOf(refusal.getMessage())));
        }
    }

    /** Point lookup run by the actual engine; the response carries the measured descent. */
    @GetMapping("/tree/find/{key}")
    public Dto.FindResult find(@PathVariable long key) {
        return trees.find(key);
    }

    /** Ordinal seek (0-based) — the counted-B-tree descent, measured. */
    @GetMapping("/tree/rank/{n}")
    public Dto.RankResult rank(@PathVariable long n) {
        return trees.rank(n);
    }

    /** Range scan: one descent + leaf hops, stop past {@code to}; {@code limit} caps the result. */
    @GetMapping("/tree/scan")
    public Dto.ScanResult scan(
            @RequestParam long from,
            @RequestParam long to,
            @RequestParam(defaultValue = "500") int limit) {
        return trees.scan(from, to, limit);
    }

    /**
     * The tree's nodes, breadth-first (root first) — the current tree, or with {@code ?root=} any
     * superseded root the store still retains (it retains all of them). 404 for an unknown root.
     */
    @GetMapping("/tree/nodes")
    public ResponseEntity<List<Dto.NodeView>> treeNodes(
            @RequestParam(required = false) String root) {
        return trees.treeNodesFrom(root)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The node content-addressed store, first-write order. */
    @GetMapping("/nodes")
    public List<Dto.NodeRef> nodes() {
        return trees.nodes();
    }

    /** A node's byte layout, attributed by the engine's own parse — regions tile exactly. */
    @GetMapping("/nodes/{hash}/layout")
    public ResponseEntity<Dto.NodeLayout> nodeLayout(@PathVariable String hash) {
        return trees.nodeLayout(hash)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** A node's raw stored bytes (hex) — the preimage whose SHA-512/20 is the name. */
    @GetMapping("/nodes/{hash}/bytes")
    public ResponseEntity<Dto.BytesView> nodeBytes(@PathVariable String hash) {
        return trees.nodeBytes(hash)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** One node, parsed from its stored bytes and re-hashed against its name. */
    @GetMapping("/nodes/{hash}")
    public ResponseEntity<Dto.NodeView> node(@PathVariable String hash) {
        return trees.node(hash)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
