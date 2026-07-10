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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The terminal inspector decodes a real on-disk store with the real reader. */
class StoreInspectorTest {

    @Test
    void renders_the_tree_and_a_node_from_a_disk_store() throws Exception {
        Path dir = Files.createTempDirectory("inspect");
        TreeService writer = new TreeService("file", dir.toString());
        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= 5_000; i++) keys.add(i); // multi-level at real geometry
        String root = writer.replace(keys).rootHash();
        writer.shutdown();

        // a FRESH service over the same dir (the inspector's own path)
        TreeService svc = new TreeService("file", dir.toString());
        try {
            String tree = StoreInspector.renderTree(svc, "file");
            assertTrue(tree.contains("head:  ⋄" + root), tree);
            assertTrue(tree.contains("5000 keys"), tree);
            assertTrue(tree.contains("every reachable node verified ✓"), tree);
            assertTrue(tree.contains("└─ "), "renders the tree shape");

            String node = StoreInspector.renderNode(svc, root);
            assertTrue(node.contains("⋄" + root), node);
            assertTrue(node.contains("verified ✓"), node);
            assertTrue(node.contains("cumulative subtree counts"), node);

            assertNull(StoreInspector.renderNode(svc, "ab".repeat(20)), "unknown hash → null");
        } finally {
            svc.shutdown();
        }
        // a non-store path is REFUSED (and not created)
        assertEquals(2, StoreInspector.run(new String[] {dir.resolve("nope").toString()}));
        assertFalse(Files.exists(dir.resolve("nope")), "refusal must not create directories");
    }
}
