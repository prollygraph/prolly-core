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

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SQLite-grade coverage for {@link MutableMap}. This is the edit buffer used by every transaction —
 * bugs in read-your-writes, delete-shadowing, or flush semantics corrupt every commit path.
 */
class MutableMapTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static String str(MemorySegment s) {
        return new String(s.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }

    private static Node baseTree(HeapBufferPool pool, InMemoryNodeStore store, String... keys) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        for (String k : keys) {
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, k), MemorySegment.ofArray(("base-" + k).getBytes())));
        }
        return m.applyMutations(null, edits.iterator());
    }

    // ---- buffered semantics ----

    @Test
    void put_then_get_returns_buffered_value() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            m.put(key(pool, "x"), MemorySegment.ofArray("new".getBytes()));
            assertEquals(
                    "new",
                    str(m.get(key(pool, "x")).orElseThrow()),
                    "read-your-writes: buffered value must be visible immediately");
        }
    }

    @Test
    void buffered_put_shadows_base_value() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            m.put(key(pool, "a"), MemorySegment.ofArray("override".getBytes()));
            assertEquals(
                    "override",
                    str(m.get(key(pool, "a")).orElseThrow()),
                    "buffered value must take precedence over base tree");
        }
    }

    @Test
    void delete_then_get_returns_empty() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            m.delete(key(pool, "a"));
            assertFalse(
                    m.get(key(pool, "a")).isPresent(),
                    "deleted key must read as empty even though base still has it");
        }
    }

    @Test
    void get_falls_through_to_base_when_unedited() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a", "b"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            assertEquals("base-a", str(m.get(key(pool, "a")).orElseThrow()));
            assertEquals("base-b", str(m.get(key(pool, "b")).orElseThrow()));
        }
    }

    @Test
    void get_missing_key_returns_empty() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            assertFalse(m.get(key(pool, "zzz")).isPresent());
        }
    }

    // ---- flush() ----

    @Test
    void flush_with_no_edits_returns_base() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            assertSame(
                    base,
                    m.flush(),
                    "flush with no buffered edits must return the same base instance");
        }
    }

    @Test
    void flush_produces_new_tree_with_buffered_edits() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            m.put(key(pool, "z"), MemorySegment.ofArray("z-val".getBytes()));
            StaticMap result = m.flush();
            assertNotSame(base, result);
            assertEquals("z-val", str(result.get(key(pool, "z")).orElseThrow()));
            assertEquals("base-a", str(result.get(key(pool, "a")).orElseThrow()));
        }
    }

    @Test
    void flush_clears_edit_buffer() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            m.put(key(pool, "x"), MemorySegment.ofArray("first".getBytes()));
            m.flush();
            // After flush, base() is still the original base — edits are gone.
            // A fresh flush should be a no-op returning the original base.
            assertSame(base, m.flush(), "post-flush buffer must be empty");
        }
    }

    @Test
    void flush_persists_deletes() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base =
                    new StaticMap(store, baseTree(pool, store, "a", "b", "c"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            m.delete(key(pool, "b"));
            StaticMap result = m.flush();
            assertTrue(result.get(key(pool, "a")).isPresent());
            assertFalse(
                    result.get(key(pool, "b")).isPresent(),
                    "delete must be persisted into the new tree");
            assertTrue(result.get(key(pool, "c")).isPresent());
        }
    }

    // ---- copyEditsTo() ----

    @Test
    void copyEditsTo_replicates_put_and_delete() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap src = new MutableMap(base, store, STRING_DESC, pool);
            src.put(key(pool, "x"), MemorySegment.ofArray("new".getBytes()));
            src.delete(key(pool, "a"));

            MutableMap dst = new MutableMap(base, store, STRING_DESC, pool);
            src.copyEditsTo(dst);

            assertEquals("new", str(dst.get(key(pool, "x")).orElseThrow()));
            assertFalse(
                    dst.get(key(pool, "a")).isPresent(), "deletes must transfer via copyEditsTo");
        }
    }

    @Test
    void put_then_delete_same_key_resolves_to_delete() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            m.put(key(pool, "x"), MemorySegment.ofArray("first".getBytes()));
            m.delete(key(pool, "x"));
            assertFalse(
                    m.get(key(pool, "x")).isPresent(),
                    "last write wins — delete after put → absent");
        }
    }

    @Test
    void delete_then_put_same_key_resolves_to_put() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            m.delete(key(pool, "a"));
            m.put(key(pool, "a"), MemorySegment.ofArray("resurrected".getBytes()));
            assertEquals("resurrected", str(m.get(key(pool, "a")).orElseThrow()));
        }
    }

    @Test
    void base_accessor_returns_underlying_static_map() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            assertSame(base, m.base());
        }
    }

    @Test
    void empty_base_supports_inserts() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, null, STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            m.put(key(pool, "a"), MemorySegment.ofArray("first".getBytes()));
            StaticMap result = m.flush();
            assertEquals("first", str(result.get(key(pool, "a")).orElseThrow()));
        }
    }

    // ---- custom-comparator constructor overload ----

    @Test
    void comparator_overload_with_null_matches_default_constructor() {
        // The 5-arg constructor with a null comparator must behave exactly
        // like the 4-arg one — fall back to descriptor.compare.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, null, STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool, null);
            m.put(key(pool, "b"), MemorySegment.ofArray("B".getBytes()));
            m.put(key(pool, "a"), MemorySegment.ofArray("A".getBytes()));
            StaticMap result = m.flush();
            assertEquals("A", str(result.get(key(pool, "a")).orElseThrow()));
            assertEquals("B", str(result.get(key(pool, "b")).orElseThrow()));
        }
    }

    @Test
    void custom_comparator_is_honoured_for_buffer_ordering() {
        // A caller-supplied comparator (here order-equivalent to the
        // descriptor) must drive the edit buffer: scrambled inserts, a
        // shadowing put, and flush all still resolve correctly.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, null, STRING_DESC);
            java.util.Comparator<Tuple> custom = (x, y) -> STRING_DESC.compare(x, y);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool, custom);
            for (String s : new String[] {"d", "a", "c", "b"}) {
                m.put(key(pool, s), MemorySegment.ofArray(s.toUpperCase().getBytes()));
            }
            m.put(key(pool, "b"), MemorySegment.ofArray("BB".getBytes())); // shadow
            StaticMap result = m.flush();
            assertEquals("A", str(result.get(key(pool, "a")).orElseThrow()));
            assertEquals("BB", str(result.get(key(pool, "b")).orElseThrow()));
            assertEquals("C", str(result.get(key(pool, "c")).orElseThrow()));
            assertEquals("D", str(result.get(key(pool, "d")).orElseThrow()));
        }
    }

    @Test
    void custom_comparator_buffer_supports_read_your_writes() {
        // get() also routes through the custom-comparator-backed TreeMap.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            java.util.Comparator<Tuple> custom = (x, y) -> STRING_DESC.compare(x, y);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool, custom);
            m.put(key(pool, "z"), MemorySegment.ofArray("new".getBytes()));
            assertEquals("new", str(m.get(key(pool, "z")).orElseThrow()));
            m.delete(key(pool, "z"));
            assertFalse(m.get(key(pool, "z")).isPresent());
        }
    }

    // ---- failure-path resource cleanup (core-error-taxonomy-and-failpaths Step 1) ----

    /**
     * A {@link NodeStore} that serves reads from a delegate (so the base tree is readable) but
     * throws on every write — forces {@link MutableMap#flush} to fail on its first chunk write,
     * <i>after</i> the merge iterator has eagerly opened its spilled run-file readers. That is the
     * exact window the descriptor-leak fix protects.
     */
    private static final class WriteThrowingNodeStore implements NodeStore {
        private final NodeStore delegate;

        WriteThrowingNodeStore(NodeStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<MemorySegment> read(byte[] hash) {
            return delegate.read(hash);
        }

        @Override
        public byte[] write(MemorySegment data) {
            throw new RuntimeException("simulated chunk-write failure");
        }

        @Override
        public byte[] write(byte[] data) {
            throw new RuntimeException("simulated chunk-write failure");
        }
    }

    @Test
    void flushThrowingMidwayDoesNotLeakRunFileDescriptors(@TempDir Path dir) {
        // A flush() that throws partway (the store fails a chunk write) must close the merge
        // iterator's spilled run-file readers — no file-descriptor leak on the failure path.
        // Without
        // the try-with-resources fix each failed flush strands its run readers; 40 of them leak
        // dozens of descriptors. (Unix-only: needs getOpenFileDescriptorCount.)
        java.lang.management.OperatingSystemMXBean os =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                os instanceof com.sun.management.UnixOperatingSystemMXBean,
                "fd-count assertion needs a Unix OperatingSystemMXBean");
        com.sun.management.UnixOperatingSystemMXBean unix =
                (com.sun.management.UnixOperatingSystemMXBean) os;

        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore real = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(real, baseTree(pool, real, "a"), STRING_DESC);
            NodeStore throwing = new WriteThrowingNodeStore(real);

            // Sanity: the tiny-threshold workload actually spills multiple runs (else there would
            // be
            // no open run readers to leak — a vacuous test). Read spilledRunCount() before flush.
            MutableMap probe = new MutableMap(base, throwing, STRING_DESC, pool, null, 64L, dir);
            fillSpillable(pool, probe);
            assertTrue(
                    probe.spilledRunCount() > 1,
                    "tiny threshold must spill multiple runs (got "
                            + probe.spilledRunCount()
                            + ")");
            assertThrows(
                    RuntimeException.class,
                    probe::flush,
                    "the write-failing store must fail the flush");

            System.gc(); // stabilise the baseline
            long beforeFds = unix.getOpenFileDescriptorCount();
            int iterations = 40;
            for (int i = 0; i < iterations; i++) {
                MutableMap m = new MutableMap(base, throwing, STRING_DESC, pool, null, 64L, dir);
                fillSpillable(pool, m);
                assertThrows(RuntimeException.class, m::flush);
            }
            long leaked = unix.getOpenFileDescriptorCount() - beforeFds;
            assertTrue(
                    leaked <= 16L,
                    "flush() must close run-file readers on the throw path; "
                            + iterations
                            + " failed flushes leaked "
                            + leaked
                            + " fds");
        }
    }

    /** Stage enough distinct edits to force the buffer (tiny threshold) to spill several runs. */
    private static void fillSpillable(HeapBufferPool pool, MutableMap m) {
        for (int i = 0; i < 800; i++) {
            m.put(
                    key(pool, "k-" + String.format("%05d", i)),
                    MemorySegment.ofArray(("v-" + i).getBytes()));
        }
    }

    // ---- post-failure recovery contract + fail-fast arg guards (Step 3) ----

    @Test
    void flushFailureLeavesBaseRecoverableViaFreshBuffer() {
        // D-3 recovery contract: a failed flush() leaves `base` unchanged, so building a FRESH
        // MutableMap on the same base re-applies the edits and commits. The spent buffer is
        // discarded, never reused.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore real = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(real, baseTree(pool, real, "a"), STRING_DESC);
            MemorySegment x = key(pool, "x");

            // First attempt: a write-failing store fails the flush partway.
            MutableMap failed =
                    new MutableMap(base, new WriteThrowingNodeStore(real), STRING_DESC, pool);
            failed.put(x, MemorySegment.ofArray("v1".getBytes()));
            assertThrows(
                    RuntimeException.class,
                    failed::flush,
                    "the write-failing store fails the flush");

            // Recovery: same (unchanged) base + a working store + a fresh buffer → success.
            MutableMap fresh = new MutableMap(base, real, STRING_DESC, pool);
            fresh.put(x, MemorySegment.ofArray("v1".getBytes()));
            StaticMap result = fresh.flush();
            assertEquals(
                    "v1",
                    str(result.get(x).orElseThrow()),
                    "a fresh buffer on the same base recovers after a failed flush");
            assertEquals(
                    "base-a",
                    str(result.get(key(pool, "a")).orElseThrow()),
                    "the base content survives into the recovered commit");
            assertEquals(
                    "base-a",
                    str(base.get(key(pool, "a")).orElseThrow()),
                    "the base snapshot itself is unchanged by the failed flush");
        }
    }

    @Test
    void nullKeyFailsFastWithClearMessage() {
        // Goal #4: a null key fails fast as IllegalArgumentException (bad input, per the taxonomy),
        // not a deep NullPointerException inside the tuple comparator. A null value is NOT guarded
        // —
        // it is the defined tombstone/delete path.
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap base = new StaticMap(store, baseTree(pool, store, "a"), STRING_DESC);
            MutableMap m = new MutableMap(base, store, STRING_DESC, pool);
            MemorySegment val = MemorySegment.ofArray("v".getBytes());
            assertThrows(IllegalArgumentException.class, () -> m.put(null, val), "put(null, …)");
            assertThrows(IllegalArgumentException.class, () -> m.get(null), "get(null)");
            assertThrows(IllegalArgumentException.class, () -> m.delete(null), "delete(null)");
        }
    }
}
