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
package com.dolthub.prolly;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the empty-tree commit NPE (fixed 2026-05-16).
 *
 * <p>A commit whose data tree is empty — e.g. one that deleted the last row — has a {@code null}
 * {@code rootValueHash}. {@code Commit.serialize()} used to NPE on {@code bb.put(null)}; it now
 * writes the 20-byte zero sentinel, which {@code deserialize()} maps back to {@code null}.
 */
class CommitEmptyRootTest {

    @Test
    void serialize_does_not_npe_on_a_null_root() {
        Commit empty = new Commit(null, List.of(), "author", "deleted everything", 12345L);
        assertDoesNotThrow(
                empty::serialize, "an empty-tree commit (null root) must serialize, not NPE");
    }

    @Test
    void serialize_then_deserialize_round_trips_a_null_root() {
        Commit empty = new Commit(null, List.of(), "author", "deleted everything", 12345L);
        Commit back = Commit.deserialize(empty.serialize());
        assertNull(
                back.getRootValueHash(),
                "the empty-tree commit round-trips with its null root intact");
        assertEquals("author", back.getAuthor());
        assertEquals("deleted everything", back.getMessage());
        assertEquals(12345L, back.getTimestamp());
        assertTrue(back.getParents().isEmpty());
    }

    @Test
    void a_genuine_non_zero_root_hash_round_trips_unchanged() {
        byte[] root = new byte[20];
        for (int i = 0; i < root.length; i++) {
            root[i] = (byte) (i + 1);
        }
        Commit c = new Commit(root, List.of(), "a", "m", 1L);
        Commit back = Commit.deserialize(c.serialize());
        assertArrayEquals(
                root,
                back.getRootValueHash(),
                "a real root hash is unaffected by the empty-root sentinel handling");
    }
}
