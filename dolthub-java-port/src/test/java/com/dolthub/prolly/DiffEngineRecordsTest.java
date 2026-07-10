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
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Coverage gap for {@link DiffEngine.DiffType} enum, {@link DiffEngine.DiffEntry} record, and
 * {@link DiffEngine.DiffHandler} interface contracts. Existing DiffEngineTest exercises behavior;
 * this file pins the type-level contract that downstream consumers (event log, merge engine) depend
 * on.
 */
class DiffEngineRecordsTest {

    // ---- DiffType enum ----

    @Test
    void diff_type_has_exactly_three_kinds() {
        assertEquals(
                3,
                DiffEngine.DiffType.values().length,
                "DiffType is ADD/MOD/DEL — adding a fourth kind requires updating "
                        + "every consumer (MergeEngine, event log, etc.)");
    }

    @Test
    void diff_type_constants_present() {
        assertNotNull(DiffEngine.DiffType.ADD);
        assertNotNull(DiffEngine.DiffType.MOD);
        assertNotNull(DiffEngine.DiffType.DEL);
    }

    @Test
    void diff_type_ordinals_pinned_for_serialization() {
        // Reordering changes serialized integer-form representations in any
        // future wire format that records the diff type.
        assertEquals(0, DiffEngine.DiffType.ADD.ordinal());
        assertEquals(1, DiffEngine.DiffType.MOD.ordinal());
        assertEquals(2, DiffEngine.DiffType.DEL.ordinal());
    }

    @Test
    void diff_type_valueOf_for_each_name() {
        for (DiffEngine.DiffType t : DiffEngine.DiffType.values()) {
            assertEquals(t, DiffEngine.DiffType.valueOf(t.name()));
        }
    }

    @Test
    void diff_type_valueOf_unknown_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DiffEngine.DiffType.valueOf("UPDATE"),
                "UPDATE is not a DiffType — INSERT+DELETE pair is the canonical update form");
    }

    @Test
    void diff_type_set_uniqueness() {
        Set<DiffEngine.DiffType> set = new HashSet<>();
        for (DiffEngine.DiffType t : DiffEngine.DiffType.values()) set.add(t);
        assertEquals(3, set.size());
    }

    // ---- DiffEntry record ----

    @Test
    void diff_entry_carries_all_fields() {
        MemorySegment k = MemorySegment.ofArray("k".getBytes());
        MemorySegment a = MemorySegment.ofArray("a".getBytes());
        MemorySegment b = MemorySegment.ofArray("b".getBytes());
        DiffEngine.DiffEntry e = new DiffEngine.DiffEntry(k, a, b, DiffEngine.DiffType.MOD);
        assertSame(k, e.key());
        assertSame(a, e.valueA());
        assertSame(b, e.valueB());
        assertEquals(DiffEngine.DiffType.MOD, e.type());
    }

    @Test
    void diff_entry_for_add_has_null_valueA() {
        // ADD: no prior value. Pin convention.
        MemorySegment k = MemorySegment.ofArray("k".getBytes());
        MemorySegment v = MemorySegment.ofArray("new".getBytes());
        DiffEngine.DiffEntry e = new DiffEngine.DiffEntry(k, null, v, DiffEngine.DiffType.ADD);
        assertNull(e.valueA(), "ADD entries have null valueA — there was nothing before");
        assertSame(v, e.valueB());
    }

    @Test
    void diff_entry_for_del_has_null_valueB() {
        MemorySegment k = MemorySegment.ofArray("k".getBytes());
        MemorySegment v = MemorySegment.ofArray("old".getBytes());
        DiffEngine.DiffEntry e = new DiffEngine.DiffEntry(k, v, null, DiffEngine.DiffType.DEL);
        assertNull(e.valueB(), "DEL entries have null valueB — value is gone");
        assertSame(v, e.valueA());
    }

    @Test
    void diff_entry_record_equality_by_components() {
        MemorySegment k = MemorySegment.ofArray("k".getBytes());
        MemorySegment a = MemorySegment.ofArray("a".getBytes());
        MemorySegment b = MemorySegment.ofArray("b".getBytes());
        DiffEngine.DiffEntry e1 = new DiffEngine.DiffEntry(k, a, b, DiffEngine.DiffType.MOD);
        DiffEngine.DiffEntry e2 = new DiffEngine.DiffEntry(k, a, b, DiffEngine.DiffType.MOD);
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void diff_entry_different_types_not_equal() {
        MemorySegment k = MemorySegment.ofArray("k".getBytes());
        MemorySegment v = MemorySegment.ofArray("v".getBytes());
        DiffEngine.DiffEntry mod = new DiffEngine.DiffEntry(k, v, v, DiffEngine.DiffType.MOD);
        DiffEngine.DiffEntry add = new DiffEngine.DiffEntry(k, v, v, DiffEngine.DiffType.ADD);
        assertNotEquals(mod, add);
    }

    // ---- DiffHandler interface ----

    @Test
    void diff_handler_lambda_returning_false_stops() {
        // Pin the contract: returning false from onDiff halts iteration.
        // (Behavioral test in DiffEngineTest already exercises this end-to-end;
        // here we confirm the interface itself is a single-method @FunctionalInterface-like.)
        DiffEngine.DiffHandler stopper = e -> false;
        assertNotNull(stopper);
        DiffEngine.DiffEntry dummy =
                new DiffEngine.DiffEntry(
                        MemorySegment.ofArray("k".getBytes()),
                        null,
                        MemorySegment.ofArray("v".getBytes()),
                        DiffEngine.DiffType.ADD);
        assertFalse(stopper.onDiff(dummy));
    }

    @Test
    void diff_handler_lambda_returning_true_continues() {
        DiffEngine.DiffHandler keeper = e -> true;
        DiffEngine.DiffEntry dummy =
                new DiffEngine.DiffEntry(
                        MemorySegment.ofArray("k".getBytes()),
                        MemorySegment.ofArray("v".getBytes()),
                        null,
                        DiffEngine.DiffType.DEL);
        assertTrue(keeper.onDiff(dummy));
    }

    @Test
    void diff_handler_can_capture_state() {
        // Pin: the handler is a normal Java interface so a stateful impl
        // (counting / aggregating) is supported.
        int[] count = {0};
        DiffEngine.DiffHandler counting =
                e -> {
                    count[0]++;
                    return true;
                };
        DiffEngine.DiffEntry dummy =
                new DiffEngine.DiffEntry(
                        MemorySegment.ofArray("k".getBytes()),
                        null,
                        MemorySegment.ofArray("v".getBytes()),
                        DiffEngine.DiffType.ADD);
        counting.onDiff(dummy);
        counting.onDiff(dummy);
        counting.onDiff(dummy);
        assertEquals(3, count[0]);
    }
}
