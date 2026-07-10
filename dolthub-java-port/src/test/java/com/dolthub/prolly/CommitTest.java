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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link Commit} serialization. The commit record is on the durable side of the
 * boundary — any drift in the byte format (string encoding, parent ordering, field layout) makes
 * existing commits unreadable.
 */
class CommitTest {

    private static byte[] hash(int seed) {
        byte[] out = new byte[20];
        out[0] = (byte) seed;
        return out;
    }

    @Test
    void roundtrip_minimal_commit() {
        Commit original = new Commit(hash(1), List.of(), "alice", "initial", 1700000000L);
        byte[] bytes = original.serialize();
        Commit roundtripped = Commit.deserialize(bytes);
        assertArrayEquals(original.getRootValueHash(), roundtripped.getRootValueHash());
        assertEquals(original.getAuthor(), roundtripped.getAuthor());
        assertEquals(original.getMessage(), roundtripped.getMessage());
        assertEquals(original.getTimestamp(), roundtripped.getTimestamp());
        assertEquals(0, roundtripped.getParents().size());
    }

    @Test
    void roundtrip_with_one_parent() {
        Commit c = new Commit(hash(1), List.of(hash(2)), "alice", "msg", 100L);
        Commit back = Commit.deserialize(c.serialize());
        assertEquals(1, back.getParents().size());
        assertArrayEquals(hash(2), back.getParents().get(0));
    }

    @Test
    void roundtrip_with_two_parents_preserves_order() {
        // Merge commit: parents[0] = target side, parents[1] = source side. Order matters.
        Commit c = new Commit(hash(0), List.of(hash(10), hash(20)), "bot", "merge", 200L);
        Commit back = Commit.deserialize(c.serialize());
        assertEquals(2, back.getParents().size());
        assertArrayEquals(hash(10), back.getParents().get(0));
        assertArrayEquals(hash(20), back.getParents().get(1));
    }

    @Test
    void roundtrip_with_many_parents() {
        List<byte[]> parents = new ArrayList<>();
        for (int i = 0; i < 100; i++) parents.add(hash(i));
        Commit c = new Commit(hash(0), parents, "alice", "many parents", 1L);
        Commit back = Commit.deserialize(c.serialize());
        assertEquals(100, back.getParents().size());
        for (int i = 0; i < 100; i++) {
            assertArrayEquals(hash(i), back.getParents().get(i));
        }
    }

    @Test
    void utf8_author_roundtrip() {
        // Mixed scripts; bytes.length != string.length.
        Commit c = new Commit(hash(1), List.of(), "アリス 🚀", "ok", 1L);
        Commit back = Commit.deserialize(c.serialize());
        assertEquals("アリス 🚀", back.getAuthor());
    }

    @Test
    void utf8_message_roundtrip() {
        String msg = "🐉 emoji + Unicode: café—naïve resumé";
        Commit c = new Commit(hash(1), List.of(), "a", msg, 1L);
        assertEquals(msg, Commit.deserialize(c.serialize()).getMessage());
    }

    @Test
    void empty_message_and_author_roundtrip() {
        Commit c = new Commit(hash(1), List.of(), "", "", 0L);
        Commit back = Commit.deserialize(c.serialize());
        assertEquals("", back.getAuthor());
        assertEquals("", back.getMessage());
    }

    @Test
    void boundary_timestamps_roundtrip() {
        long[] cases = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 1700000000_000L};
        for (long ts : cases) {
            Commit c = new Commit(hash(1), List.of(), "a", "m", ts);
            assertEquals(
                    ts,
                    Commit.deserialize(c.serialize()).getTimestamp(),
                    "timestamp " + ts + " did not round-trip");
        }
    }

    @Test
    void serialize_is_deterministic() {
        Commit a = new Commit(hash(1), List.of(hash(2)), "alice", "msg", 100L);
        Commit b = new Commit(hash(1), List.of(hash(2)), "alice", "msg", 100L);
        assertArrayEquals(
                a.serialize(),
                b.serialize(),
                "two identical Commit values must serialize to identical bytes");
    }

    @Test
    void distinct_commits_produce_distinct_bytes() {
        Commit a = new Commit(hash(1), List.of(), "alice", "msg", 100L);
        Commit b = new Commit(hash(1), List.of(), "alice", "msg2", 100L); // message differs
        assertFalse(Arrays.equals(a.serialize(), b.serialize()));
    }

    @Test
    void long_message_roundtrip() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) sb.append('x');
        Commit c = new Commit(hash(1), List.of(), "a", sb.toString(), 1L);
        assertEquals(10_000, Commit.deserialize(c.serialize()).getMessage().length());
    }

    @Test
    void wire_format_size_matches_documented_layout() {
        // Layout: 4 (magic) + 1 (version) + 20 (rootHash) + 4 (parentCount) + N*20 (parents)
        //       + 8 (ts) + 4 (authorLen) + authorBytes + 4 (msgLen) + msgBytes
        Commit c = new Commit(hash(1), List.of(hash(2), hash(3)), "x", "y", 1L);
        byte[] bytes = c.serialize();
        int expected = 4 + 1 + 20 + 4 + (2 * 20) + 8 + 4 + 1 + 4 + 1;
        assertEquals(expected, bytes.length);
    }

    @Test
    void deserialize_round_trip_preserves_immutable_parent_list() {
        Commit c = new Commit(hash(1), List.of(hash(2)), "a", "m", 1L);
        Commit back = Commit.deserialize(c.serialize());
        // Parents list mutability isn't specified; just verify the contents survived.
        assertNotNull(back.getParents());
        assertArrayEquals(hash(2), back.getParents().get(0));
    }

    // ---- format magic + version (core-format-versioning Step 2) ----

    @Test
    void wrong_version_fails_closed() {
        byte[] valid = new Commit(hash(1), List.of(), "a", "m", 0L).serialize();
        valid[4] = (byte) 99; // the version byte, right after the 4-byte magic
        UnsupportedFormatException ex =
                assertThrows(UnsupportedFormatException.class, () -> Commit.deserialize(valid));
        assertTrue(ex.getMessage().contains("version 99"), ex.getMessage());
    }

    @Test
    void wrong_or_missing_magic_fails_closed() {
        // An old/foreign blob's first bytes are not the commit magic → fail closed, not a misparse.
        byte[] valid = new Commit(hash(1), List.of(), "a", "m", 0L).serialize();
        valid[0] = (byte) 'X'; // corrupt the magic
        UnsupportedFormatException ex =
                assertThrows(UnsupportedFormatException.class, () -> Commit.deserialize(valid));
        assertTrue(ex.getMessage().contains("magic"), ex.getMessage());
    }

    @Test
    void length_bound_still_fires_behind_the_magic() {
        // Defense in depth: the magic/version check does NOT replace the attacker-length bound. A
        // blob with a VALID header but a corrupt author-length must still be rejected by the length
        // guard (IllegalArgumentException), not slip through.
        byte[] valid = new Commit(hash(1), List.of(), "a", "m", 0L).serialize();
        // author-length is at offset magic(4)+version(1)+root(20)+pCount(4)+ts(8) = 37; set it to
        // -1.
        valid[37] = valid[38] = valid[39] = valid[40] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> Commit.deserialize(valid));
    }
}
