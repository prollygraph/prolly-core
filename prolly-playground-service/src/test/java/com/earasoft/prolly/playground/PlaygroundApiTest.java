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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The playground backend against the REAL engine: history-independence measured on real roots, a
 * write's spine measured as the store's actual mint set, and node CAS verified by re-hash.
 */
class PlaygroundApiTest {

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new PlaygroundController(new TreeService())).build();
    }

    private JsonNode putKeys(MockMvc mvc, List<Long> keys) throws Exception {
        var res =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .put("/api/tree")
                                        .contentType("application/json")
                                        .content(
                                                om.writeValueAsString(
                                                        java.util.Map.of("keys", keys))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return om.readTree(res);
    }

    @Test
    void history_independence_on_the_real_engine() throws Exception {
        MockMvc mvc = mvc();
        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= 200; i++) keys.add(i * 10);
        String root1 = putKeys(mvc, keys).path("rootHash").asText();
        assertTrue(root1.matches("[0-9a-f]{40}"), root1);

        Collections.shuffle(keys, new Random(42));
        String root2 = putKeys(mvc, keys).path("rootHash").asText();
        assertEquals(root1, root2, "same key set, any insertion order, same root bytes");
    }

    @Test
    void an_insert_writes_the_spine_not_the_tree() throws Exception {
        MockMvc mvc = mvc();
        List<Long> keys = new ArrayList<>();
        // REAL chunk geometry: 512 B - 16 KiB per node, ~11 B per int64 entry --
        // hundreds of entries per leaf. 300 keys fit in ~3 nodes (measured on this
        // test's first run); a multi-level, many-node tree needs thousands.
        for (long i = 1; i <= 20_000; i++) keys.add(i * 10);
        JsonNode built = putKeys(mvc, keys);
        int total = built.path("storedNodes").asInt();
        int height = built.path("height").asInt();
        assertTrue(total > 10, "expected a multi-node tree, got " + total);
        assertTrue(height >= 1);

        var res =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/api/tree/keys")
                                        .contentType("application/json")
                                        .content("{\"keys\":[15555]}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode after = om.readTree(res);
        int written = after.path("written").size();
        assertTrue(
                written >= 1 && written <= height + 3,
                "one insert should mint ~a spine (height " + height + "), wrote " + written);
        assertTrue(written < total / 2, "the write set must be a small minority of " + total);
        assertEquals(20_001, after.path("treeCount").asLong());
        // every mutate reports the ENGINE's own duration (client adds the round-trip half)
        assertTrue(after.path("engineMicros").isIntegralNumber(), after.toString());
        assertTrue(after.path("engineMicros").asLong() >= 0);

        var found =
                om.readTree(
                        mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.get("/api/tree/find/15555"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertTrue(found.path("found").asBoolean());
        assertTrue(found.path("engineMicros").isIntegralNumber(), found.toString());
    }

    private JsonNode bench(MockMvc mvc, String kind, int ops) throws Exception {
        var res =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/api/bench")
                                        .contentType("application/json")
                                        .content("{\"kind\":\"" + kind + "\",\"ops\":" + ops + "}"))
                        .andReturn()
                        .getResponse();
        return om.readTree(res.getContentAsString());
    }

    @Test
    void bench_read_measures_the_descent() throws Exception {
        MockMvc mvc = mvc();
        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= 5_000; i++) keys.add(i * 10);
        int height = putKeys(mvc, keys).path("height").asInt();

        JsonNode r = bench(mvc, "read", 2_000);
        assertEquals(2_000, r.path("ops").asInt(), r.toString());
        assertTrue(r.path("warmupOps").asInt() > 0, "warm-up ran and is reported");
        long p50 = r.path("p50Nanos").asLong();
        long p90 = r.path("p90Nanos").asLong();
        long p95 = r.path("p95Nanos").asLong();
        long p99 = r.path("p99Nanos").asLong();
        long max = r.path("maxNanos").asLong();
        assertTrue(p50 > 0, "a measured latency, never a floored zero");
        assertTrue(
                p50 <= p90 && p90 <= p95 && p95 <= p99 && p99 <= max,
                p50 + "/" + p90 + "/" + p95 + "/" + p99 + "/" + max);
        assertTrue(r.path("opsPerSec").asLong() > 0);
        double nodesPerOp = r.path("nodesPerOp").asDouble();
        assertTrue(
                nodesPerOp >= 1 && nodesPerOp <= height + 1.01,
                "descent should read ~height nodes: " + nodesPerOp + " vs height " + height);
        assertEquals(5_000, r.path("treeCount").asLong());
    }

    @Test
    void bench_write_restores_the_root_byte_identically() throws Exception {
        MockMvc mvc = mvc();
        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= 3_000; i++) keys.add(i * 10);
        String rootBefore = putKeys(mvc, keys).path("rootHash").asText();

        JsonNode r = bench(mvc, "write", 40);
        assertEquals(40, r.path("ops").asInt(), r.toString());
        assertTrue(r.path("p50Nanos").asLong() > 0);
        assertTrue(r.path("nodesPerOp").asDouble() >= 1, "each insert writes at least a root");
        // D-3: insert + delete of the same keys re-derives the identical root bytes
        assertTrue(r.path("rootRestored").asBoolean(), r.toString());
        assertEquals(rootBefore, r.path("rootAfter").asText());
        assertTrue(r.path("storedNodesDelta").asInt() > 0, "the bench spines are honest garbage");
        var tree =
                om.readTree(
                        mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.get("/api/tree"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertEquals(3_000, tree.path("treeCount").asLong(), "the user's tree is untouched");
        assertEquals(rootBefore, tree.path("rootHash").asText());
    }

    @Test
    void bench_compare_runs_three_byte_equal_arms() throws Exception {
        MockMvc mvc = mvc();
        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= 3_000; i++) keys.add(i * 10);
        String liveRoot = putKeys(mvc, keys).path("rootHash").asText();

        var res =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/api/bench/compare")
                                        .contentType("application/json")
                                        .content("{\"readOps\":400,\"writeOps\":20}"))
                        .andReturn()
                        .getResponse();
        assertEquals(200, res.getStatus(), res.getContentAsString());
        JsonNode r = om.readTree(res.getContentAsString());
        assertEquals(liveRoot, r.path("rootHash").asText());
        assertEquals("memory", r.path("liveKind").asText());
        JsonNode arms = r.path("arms");
        assertEquals(3, arms.size());
        List<String> kinds = new ArrayList<>();
        for (JsonNode arm : arms) {
            kinds.add(arm.path("kind").asText());
            // content addressing as the equality proof: the copied arm re-derives the SAME root
            assertTrue(arm.path("sameRoot").asBoolean(), arm.toString());
            assertEquals(liveRoot, arm.path("read").path("rootBefore").asText());
            assertEquals(400, arm.path("read").path("ops").asInt());
            assertTrue(arm.path("read").path("p50Nanos").asLong() > 0);
            assertEquals(20, arm.path("write").path("ops").asInt());
            assertTrue(arm.path("write").path("rootRestored").asBoolean(), arm.toString());
            assertTrue(arm.path("seedMillis").asLong() >= 0);
        }
        assertEquals(List.of("memory", "file", "rocks"), kinds);
        // and the LIVE tree came through the whole comparison untouched
        var tree =
                om.readTree(
                        mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.get("/api/tree"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertEquals(liveRoot, tree.path("rootHash").asText());
        assertEquals(3_000, tree.path("treeCount").asLong());

        // empty tree: the comparison refuses loudly
        var refusal =
                mvc().perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/api/bench/compare")
                                        .contentType("application/json")
                                        .content("{}"))
                        .andReturn()
                        .getResponse();
        assertEquals(409, refusal.getStatus());
    }

    @Test
    void bench_read_on_empty_tree_refuses_and_ops_clamp() throws Exception {
        MockMvc mvc = mvc();
        var refusal =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/api/bench")
                                        .contentType("application/json")
                                        .content("{\"kind\":\"read\",\"ops\":100}"))
                        .andReturn()
                        .getResponse();
        assertEquals(409, refusal.getStatus(), "an empty tree is a clear refusal, not a NaN");

        putKeys(mvc, List.of(10L, 20L, 30L));
        assertEquals(1, bench(mvc, "read", 0).path("ops").asInt(), "floor clamp");
        assertEquals(50_000, bench(mvc, "read", 999_999).path("ops").asInt(), "cap clamp");
    }

    @Test
    void node_cas_every_stored_node_verifies_and_the_root_decodes() throws Exception {
        MockMvc mvc = mvc();
        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= 5_000; i++) keys.add(i); // internal root at real geometry
        JsonNode built = putKeys(mvc, keys);
        String root = built.path("rootHash").asText();

        var rootView =
                om.readTree(
                        mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.get("/api/nodes/" + root))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertTrue(rootView.path("verified").asBoolean(), "root bytes must re-hash to its name");
        assertEquals(5_000, rootView.path("treeCount").asLong());
        assertFalse(rootView.path("leaf").asBoolean());
        // counted B-tree: child prefix sums are strictly increasing, ending at treeCount
        long prev = 0;
        JsonNode children = rootView.path("children");
        assertTrue(children.size() >= 2);
        for (JsonNode c : children) {
            long p = c.path("subtreeCountPrefix").asLong();
            assertTrue(p > prev, "prefix sums must be cumulative");
            prev = p;
        }
        assertEquals(5_000, prev);

        // decoded keys are the INPUT longs — the parity encode/decode round-trip
        // (this assertion is what the garbage-key bug slipped past: nothing checked
        // the decoded values, only their presence)
        JsonNode rootKeys = rootView.path("keys");
        assertTrue(rootKeys.size() >= 2);
        long prevKey = Long.MIN_VALUE;
        for (JsonNode k : rootKeys) {
            long v = k.asLong();
            assertTrue(v >= 1 && v <= 5_000, "decoded key out of input range: " + v);
            assertTrue(v > prevKey, "keys must ascend numerically");
            prevKey = v;
        }

        // every node in the store verifies (names are checksums, live)
        var nodes =
                om.readTree(
                        mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.get("/api/nodes"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        for (JsonNode n : nodes) {
            var v =
                    om.readTree(
                            mvc.perform(
                                            org.springframework.test.web.servlet.request
                                                    .MockMvcRequestBuilders.get(
                                                    "/api/nodes/" + n.path("hash").asText()))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertTrue(v.path("verified").asBoolean(), n.path("hash").asText());
        }

        // unknown and malformed names resolve to 404, never 500
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/nodes/" + "ab".repeat(20)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/nodes/not-a-hash"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
    }

    @Test
    void tree_nodes_returns_the_whole_live_tree_root_first() throws Exception {
        MockMvc mvc = mvc();
        // empty tree -> empty list, not an error
        putKeys(mvc, List.of());
        var empty =
                om.readTree(
                        mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.get("/api/tree/nodes"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertEquals(0, empty.size());

        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= 5_000; i++) keys.add(i); // internal root at real geometry
        JsonNode built = putKeys(mvc, keys);
        String root = built.path("rootHash").asText();

        var nodes =
                om.readTree(
                        mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.get("/api/tree/nodes"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertTrue(nodes.size() >= 3, "multi-node tree expected, got " + nodes.size());
        assertEquals(root, nodes.path(0).path("hash").asText(), "root comes first");

        // closed under children: every child hash of every internal node is in the response;
        // every view verifies; breadth-first order never ascends in level
        java.util.Set<String> present = new java.util.HashSet<>();
        for (JsonNode n : nodes) present.add(n.path("hash").asText());
        int prevLevel = Integer.MAX_VALUE;
        long leafKeyCount = 0;
        java.util.Set<Long> leafKeys = new java.util.HashSet<>();
        for (JsonNode n : nodes) {
            assertTrue(n.path("verified").asBoolean(), n.path("hash").asText());
            int level = n.path("level").asInt();
            assertTrue(level <= prevLevel, "breadth-first order must not ascend levels");
            prevLevel = level;
            if (n.path("leaf").asBoolean()) {
                for (JsonNode k : n.path("keys")) {
                    leafKeyCount++;
                    leafKeys.add(k.asLong());
                }
            } else {
                for (JsonNode c : n.path("children")) {
                    assertTrue(
                            present.contains(c.path("hash").asText()),
                            "child must be in the response: " + c.path("hash").asText());
                }
            }
        }
        // the leaves' key union IS the inserted key set (each key on exactly one leaf)
        assertEquals(5_000, leafKeyCount, "no key duplicated across leaves");
        assertEquals(5_000, leafKeys.size());
        assertTrue(leafKeys.contains(1L) && leafKeys.contains(5_000L));
    }

    private JsonNode get(MockMvc mvc, String path) throws Exception {
        return om.readTree(
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get(path))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    @Test
    void read_endpoints_run_the_engine_descent_and_report_it() throws Exception {
        MockMvc mvc = mvc();
        // empty tree first: reads answer honestly with an empty path
        putKeys(mvc, List.of());
        assertFalse(get(mvc, "/api/tree/find/1").path("found").asBoolean());
        assertEquals(0, get(mvc, "/api/tree/find/1").path("readPath").size());

        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= 5_000; i++) keys.add(i);
        JsonNode built = putKeys(mvc, keys);
        String root = built.path("rootHash").asText();
        int height =
                built.path("height").asInt(); // root level; a point descent reads height+1 nodes
        assertTrue(height >= 1);

        // find, present: the descent is exactly one node per level, root first
        JsonNode hit = get(mvc, "/api/tree/find/2500");
        assertTrue(hit.path("found").asBoolean());
        JsonNode path = hit.path("readPath");
        assertEquals(height + 1, path.size(), "point descent = one node per level");
        assertEquals(root, path.path(0).asText(), "root first");
        for (JsonNode h : path) {
            assertTrue(
                    get(mvc, "/api/nodes/" + h.asText()).path("verified").asBoolean(),
                    "every descent node is a real stored node: " + h.asText());
        }
        // find, absent: same-shaped descent, found=false
        JsonNode miss = get(mvc, "/api/tree/find/99999");
        assertFalse(miss.path("found").asBoolean());
        assertEquals(height + 1, miss.path("readPath").size());

        // rank: 0-based ordinal seek via the subtree-count prefix sums
        assertEquals(1, get(mvc, "/api/tree/rank/0").path("key").asLong());
        assertEquals(5_000, get(mvc, "/api/tree/rank/4999").path("key").asLong());
        assertEquals(2_500, get(mvc, "/api/tree/rank/2499").path("key").asLong());
        assertTrue(get(mvc, "/api/tree/rank/5000").path("key").isNull(), "out of range → null");
        assertEquals(height + 1, get(mvc, "/api/tree/rank/2499").path("readPath").size());

        // scan: one descent + leaf hops, stop past 'to'; limit truncates loudly
        JsonNode scan = get(mvc, "/api/tree/scan?from=100&to=110");
        assertEquals(11, scan.path("keys").size());
        assertEquals(100, scan.path("keys").path(0).asLong());
        assertEquals(110, scan.path("keys").path(10).asLong());
        assertFalse(scan.path("truncated").asBoolean());
        // the tree's LAST key must be scannable — the atNodeEnd()-guard bug dropped
        // exactly each node's final entry (found by e2e, pinned here)
        JsonNode tail = get(mvc, "/api/tree/scan?from=4990&to=5000");
        assertEquals(11, tail.path("keys").size());
        assertEquals(5_000, tail.path("keys").path(10).asLong(), "the max key is reachable");

        JsonNode capped = get(mvc, "/api/tree/scan?from=1&to=5000&limit=50");
        assertEquals(50, capped.path("keys").size());
        assertTrue(capped.path("truncated").asBoolean());
        // a scan reads more than a point descent (it walks leaves) but nowhere near the tree
        int scanned = scan.path("readPath").size();
        assertTrue(scanned >= height + 1 && scanned < built.path("storedNodes").asInt() / 2);
    }

    @Test
    void a_superseded_root_still_walks_time_travel_from_retention_alone() throws Exception {
        MockMvc mvc = mvc();
        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= 5_000; i++) keys.add(i);
        String root1 = putKeys(mvc, keys).path("rootHash").asText();

        // a later write supersedes root1 — but the store never deletes
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/tree/keys")
                        .contentType("application/json")
                        .content("{\"keys\":[99999]}"));
        String root2 = get(mvc, "/api/tree").path("rootHash").asText();
        assertNotEquals(root1, root2);

        JsonNode old = get(mvc, "/api/tree/nodes?root=" + root1);
        assertTrue(old.size() >= 3, "the superseded tree still walks");
        assertEquals(root1, old.path(0).path("hash").asText(), "requested root first");
        long total = 0;
        for (JsonNode n : old) if (n.path("leaf").asBoolean()) total += n.path("keys").size();
        assertEquals(5_000, total, "the OLD tree's exact content, after the write");

        // an unknown root is 404, not an empty success
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/tree/nodes?root=" + "ab".repeat(20)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
    }

    @Test
    void node_bytes_are_the_name_the_hex_rehashes_to_the_hash() throws Exception {
        MockMvc mvc = mvc();
        String root = putKeys(mvc, List.of(10L, 20L, 30L)).path("rootHash").asText();
        JsonNode b = get(mvc, "/api/nodes/" + root + "/bytes");
        String hex = b.path("hex").asText();
        assertEquals(b.path("byteSize").asInt() * 2, hex.length());
        byte[] bytes = java.util.HexFormat.of().parseHex(hex);
        assertEquals(
                root,
                com.dolthub.prolly.HashUtils.toHex(com.dolthub.prolly.HashUtils.hash(bytes)),
                "SHA-512/20 of exactly these bytes IS the name");
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/nodes/not-a-hash/bytes"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
    }

    @Test
    void node_layout_regions_tile_exactly_and_decode_truthfully() throws Exception {
        MockMvc mvc = mvc();
        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= 5_000; i++) keys.add(i); // multi-level: leaves AND internals
        putKeys(mvc, keys);

        JsonNode nodes = get(mvc, "/api/tree/nodes");
        assertTrue(nodes.size() >= 3);
        for (JsonNode nv : nodes) {
            String hash = nv.path("hash").asText();
            JsonNode layout = get(mvc, "/api/nodes/" + hash + "/layout");
            int byteSize = layout.path("byteSize").asInt();
            assertEquals(nv.path("byteSize").asInt(), byteSize, hash);

            // THE tiling contract: sorted, gapless, overlap-free, ending at byteSize
            int pos = 0;
            List<Long> decodedKeys = new ArrayList<>();
            int addressRegions = 0;
            for (JsonNode r : layout.path("regions")) {
                assertEquals(pos, r.path("start").asInt(), hash + ": gap/overlap at " + pos);
                assertTrue(r.path("end").asInt() > r.path("start").asInt(), hash);
                assertTrue(
                        java.util.Set.of(
                                        "envelope",
                                        "key",
                                        "value",
                                        "address",
                                        "counts",
                                        "scaffolding")
                                .contains(r.path("role").asText()),
                        r.path("role").asText());
                if (r.path("role").asText().equals("key")) {
                    // decoded reads "int64 <value> (parity-encoded)"
                    decodedKeys.add(Long.parseLong(r.path("decoded").asText().split(" ")[1]));
                }
                if (r.path("role").asText().equals("address")) addressRegions++;
                pos = r.path("end").asInt();
            }
            assertEquals(byteSize, pos, hash + ": regions must tile to the end");

            // the envelope leads and spells the magic
            JsonNode first = layout.path("regions").path(0);
            assertEquals("envelope", first.path("role").asText());
            assertEquals(0, first.path("start").asInt());
            assertTrue(first.path("decoded").asText().contains("magic"), hash);

            // every key region decodes to exactly the node view's key list, in order
            List<Long> viewKeys = new ArrayList<>();
            for (JsonNode k : nv.path("keys")) viewKeys.add(k.asLong());
            assertEquals(viewKeys, decodedKeys, hash + ": key regions must decode truthfully");

            // internals carry one address region per child
            if (!nv.path("leaf").asBoolean()) {
                assertEquals(nv.path("children").size(), addressRegions, hash);
            }
        }

        // unknown + malformed names are 404, never 500
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/nodes/" + "ab".repeat(20) + "/layout"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
    }

    @Test
    void reset_erases_the_store_and_the_tree() throws Exception {
        MockMvc mvc = mvc();
        putKeys(mvc, List.of(10L, 20L, 30L));
        assertTrue(get(mvc, "/api/tree").path("storedNodes").asInt() > 0);
        var after =
                om.readTree(
                        mvc.perform(
                                        org.springframework.test.web.servlet.request
                                                .MockMvcRequestBuilders.post("/api/reset"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertTrue(after.path("rootHash").isNull());
        assertEquals(0, after.path("treeCount").asLong());
        assertEquals(0, after.path("storedNodes").asInt(), "the ONE way the store forgets");
        assertEquals(0, get(mvc, "/api/tree/nodes").size());
        assertEquals(0, get(mvc, "/api/nodes").size());
        // and the engine works normally after (fresh world)
        assertEquals(2, putKeys(mvc, List.of(1L, 2L)).path("treeCount").asLong());
    }

    @Test
    void disk_engines_survive_a_restart_file_and_rocks() throws Exception {
        for (String kind : List.of("file", "rocks")) {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("playground-" + kind);
            TreeService first = new TreeService(kind, dir.toString());
            MockMvc mvc1 = MockMvcBuilders.standaloneSetup(new PlaygroundController(first)).build();
            List<Long> keys = new ArrayList<>();
            for (long i = 1; i <= 5_000; i++) keys.add(i); // multi-node at real geometry
            String root = putKeys(mvc1, keys).path("rootHash").asText();
            first.shutdown(); // the process "exits"

            TreeService second = new TreeService(kind, dir.toString());
            MockMvc mvc2 =
                    MockMvcBuilders.standaloneSetup(new PlaygroundController(second)).build();
            JsonNode tree = get(mvc2, "/api/tree");
            assertEquals(root, tree.path("rootHash").asText(), kind + ": the head survives");
            assertEquals(5_000, tree.path("treeCount").asLong(), kind);
            // the whole tree walks from disk bytes and every node re-verifies (CAS from disk)
            JsonNode nodes = get(mvc2, "/api/tree/nodes");
            assertTrue(nodes.size() >= 3, kind);
            for (JsonNode n : nodes) assertTrue(n.path("verified").asBoolean(), kind);
            if (kind.equals("file")) {
                // the file store enumerates: a reopened store's chunks appear in /nodes
                assertTrue(get(mvc2, "/api/nodes").size() >= nodes.size(), "seeded after reopen");
            }
            // reads run on the reopened store
            assertTrue(get(mvc2, "/api/tree/find/2500").path("found").asBoolean(), kind);
            // and it stays writable — the spine lands on disk
            var res =
                    mvc2.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post("/api/tree/keys")
                                            .contentType("application/json")
                                            .content("{\"keys\":[99999]}"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertEquals(5_001, om.readTree(res).path("treeCount").asLong(), kind);
            // reset wipes the DISK too: a third open finds nothing
            mvc2.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                            "/api/reset"));
            second.shutdown();
            TreeService third = new TreeService(kind, dir.toString());
            MockMvc mvc3 = MockMvcBuilders.standaloneSetup(new PlaygroundController(third)).build();
            assertTrue(
                    get(mvc3, "/api/tree").path("rootHash").isNull(), kind + ": reset is durable");
            third.shutdown();
        }
    }

    @Test
    void delete_to_empty_returns_the_null_root() throws Exception {
        MockMvc mvc = mvc();
        putKeys(mvc, List.of(10L, 20L));
        var res =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .delete("/api/tree/keys")
                                        .contentType("application/json")
                                        .content("{\"keys\":[10,20]}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode after = om.readTree(res);
        assertTrue(after.path("rootHash").isNull());
        assertEquals(0, after.path("treeCount").asLong());
        assertEquals(-1, after.path("height").asInt());
    }
}
