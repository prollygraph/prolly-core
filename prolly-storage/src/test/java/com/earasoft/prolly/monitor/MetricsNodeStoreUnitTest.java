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
package com.earasoft.prolly.monitor;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.InMemoryNodeStore;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/**
 * JUnit unit-level coverage for {@link MetricsNodeStore}. Counters are what production dashboards
 * and SLO alerts watch — undercounting a read or losing byte sums silently hides regressions in
 * higher layers.
 */
class MetricsNodeStoreUnitTest {

    @Test
    void initial_counters_are_zero() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        assertEquals(0, m.getReadCount());
        assertEquals(0, m.getWriteCount());
        assertEquals(0, m.getReadBytes());
        assertEquals(0, m.getWriteBytes());
    }

    // ---- write ----

    @Test
    void write_increments_count_and_bytes() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        m.write("hello".getBytes());
        assertEquals(1, m.getWriteCount());
        assertEquals(5, m.getWriteBytes(), "5-byte payload → +5 bytes");
    }

    @Test
    void multiple_writes_accumulate() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        m.write("aa".getBytes());
        m.write("bbb".getBytes());
        m.write("cccc".getBytes());
        assertEquals(3, m.getWriteCount());
        assertEquals(2 + 3 + 4, m.getWriteBytes());
    }

    @Test
    void write_memory_segment_overload_tracks_byteSize() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        m.write(MemorySegment.ofArray(new byte[100]));
        assertEquals(1, m.getWriteCount());
        assertEquals(100, m.getWriteBytes());
    }

    @Test
    void write_empty_array_still_counts_one() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        m.write(new byte[0]);
        assertEquals(1, m.getWriteCount());
        assertEquals(0, m.getWriteBytes());
    }

    // ---- read ----

    @Test
    void read_increments_count() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        byte[] hash = m.write("read me".getBytes());
        assertEquals(1, m.getWriteCount());
        m.read(hash);
        assertEquals(1, m.getReadCount());
    }

    @Test
    void read_hit_adds_to_bytes() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        byte[] hash = m.write("12345".getBytes());
        m.read(hash);
        assertEquals(5, m.getReadBytes(), "successful read must add the payload size to readBytes");
    }

    @Test
    void read_miss_increments_count_but_not_bytes() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        byte[] phantom = new byte[20];
        phantom[0] = 0x42;
        assertFalse(m.read(phantom).isPresent());
        assertEquals(1, m.getReadCount(), "miss still counts as an attempt");
        assertEquals(0, m.getReadBytes(), "miss must NOT inflate readBytes");
    }

    @Test
    void multiple_reads_accumulate_bytes() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        byte[] h1 = m.write("aa".getBytes()); // +2 written
        byte[] h2 = m.write("bbb".getBytes()); // +3 written
        long bytesBefore = m.getReadBytes();
        m.read(h1); // +2 read
        m.read(h2); // +3 read
        assertEquals(bytesBefore + 2 + 3, m.getReadBytes());
        assertEquals(2, m.getReadCount());
    }

    // ---- unwrap ----

    @Test
    void unwrap_returns_inner_store() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        MetricsNodeStore m = new MetricsNodeStore(inner);
        assertSame(inner, m.unwrap());
    }

    // ---- read/write independence ----

    @Test
    void read_counters_independent_of_write_counters() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        m.write("x".getBytes());
        m.write("y".getBytes());
        assertEquals(0, m.getReadCount(), "writes must not bump read counters");
    }

    @Test
    void write_counters_independent_of_read_counters() {
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        byte[] phantom = new byte[20];
        m.read(phantom);
        m.read(phantom);
        assertEquals(0, m.getWriteCount(), "reads must not bump write counters");
    }

    // ---- delegation ----

    @Test
    void delegates_writes_to_inner() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        MetricsNodeStore m = new MetricsNodeStore(inner);
        byte[] hash = m.write("via metrics".getBytes());
        assertTrue(inner.read(hash).isPresent(), "writes must reach the inner store");
    }

    @Test
    void large_chunk_byte_count_accurate() {
        // No truncation or overflow in the counter — 1 MiB chunk.
        MetricsNodeStore m = new MetricsNodeStore(new InMemoryNodeStore());
        byte[] big = new byte[1024 * 1024];
        m.write(big);
        assertEquals(1024 * 1024, m.getWriteBytes());
    }
}
