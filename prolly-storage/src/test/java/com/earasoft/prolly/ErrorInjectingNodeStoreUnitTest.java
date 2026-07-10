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
package com.earasoft.prolly;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.InMemoryNodeStore;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/**
 * JUnit unit-level coverage for {@link ErrorInjectingNodeStore}. The countdown semantics are subtle
 * (fires AFTER decrementing to zero, not BEFORE), and {@code FaultInjectionTest} relies on them —
 * pin the exact contract so a refactor can't silently change the trip point.
 */
class ErrorInjectingNodeStoreUnitTest {

    // ---- default state ----

    @Test
    void default_no_error_injection() {
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(new InMemoryNodeStore());
        // 100 ops should pass without injection.
        for (int i = 0; i < 100; i++) {
            s.write(("x-" + i).getBytes());
        }
    }

    // ---- countdown contract ----

    @Test
    void injectErrorAfter_one_fires_on_first_call() {
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(new InMemoryNodeStore());
        s.injectErrorAfter(1);
        RuntimeException e = assertThrows(RuntimeException.class, () -> s.write("trip".getBytes()));
        assertTrue(
                e.getMessage().contains("Injected IO Failure"),
                "must surface the documented failure marker");
    }

    @Test
    void injectErrorAfter_three_fires_on_third_call() {
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(new InMemoryNodeStore());
        byte[] h1 = s.write("a".getBytes());
        s.injectErrorAfter(3);
        // First two calls pass; third trips.
        assertDoesNotThrow(() -> s.read(h1));
        assertDoesNotThrow(() -> s.read(h1));
        assertThrows(RuntimeException.class, () -> s.read(h1));
    }

    @Test
    void after_trip_subsequent_calls_pass() {
        // Implementation: countdown decrements until 0, then triggers ONCE.
        // After the trip, countdown is 0 and the if-block guard
        // `if (countdown > 0)` short-circuits subsequent calls.
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(new InMemoryNodeStore());
        byte[] h = s.write("x".getBytes());
        s.injectErrorAfter(1);
        assertThrows(RuntimeException.class, () -> s.read(h));
        // After: countdown == 0, so further reads pass.
        assertDoesNotThrow(() -> s.read(h));
        assertDoesNotThrow(() -> s.read(h));
    }

    @Test
    void can_inject_again_after_trip() {
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(new InMemoryNodeStore());
        byte[] h = s.write("a".getBytes());
        s.injectErrorAfter(1);
        assertThrows(RuntimeException.class, () -> s.read(h));
        s.injectErrorAfter(2);
        assertDoesNotThrow(() -> s.read(h));
        assertThrows(
                RuntimeException.class, () -> s.read(h), "re-arming the countdown must trip again");
    }

    // ---- read/write parity ----

    @Test
    void error_injects_on_read() {
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(new InMemoryNodeStore());
        byte[] hash = s.write("a".getBytes());
        s.injectErrorAfter(1);
        assertThrows(RuntimeException.class, () -> s.read(hash));
    }

    @Test
    void error_injects_on_write_byte_array() {
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(new InMemoryNodeStore());
        s.injectErrorAfter(1);
        assertThrows(RuntimeException.class, () -> s.write("trip".getBytes()));
    }

    @Test
    void error_injects_on_write_memory_segment() {
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(new InMemoryNodeStore());
        s.injectErrorAfter(1);
        assertThrows(
                RuntimeException.class, () -> s.write(MemorySegment.ofArray("trip".getBytes())));
    }

    @Test
    void all_op_types_share_the_same_countdown() {
        // 2 reads then 1 write should burn through countdown=3 and trip on
        // the write — regression check that the counter isn't per-op-type.
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(new InMemoryNodeStore());
        byte[] h = s.write("seed".getBytes());
        s.injectErrorAfter(3);
        assertDoesNotThrow(() -> s.read(h));
        assertDoesNotThrow(() -> s.read(h));
        assertThrows(RuntimeException.class, () -> s.write("3rd op".getBytes()));
    }

    // ---- passthrough ----

    @Test
    void delegates_to_inner_when_not_tripped() {
        InMemoryNodeStore inner = new InMemoryNodeStore();
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(inner);
        byte[] hash = s.write("real".getBytes());
        assertTrue(
                inner.read(hash).isPresent(),
                "writes must reach the inner store before any injected failure");
        assertArrayEquals(
                "real".getBytes(),
                inner.read(hash).orElseThrow().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }

    @Test
    void injectErrorAfter_zero_never_trips() {
        // Edge case: countdown=0 with the strict-greater guard means no trip.
        ErrorInjectingNodeStore s = new ErrorInjectingNodeStore(new InMemoryNodeStore());
        s.injectErrorAfter(0);
        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> s.write("safe".getBytes()));
        }
    }
}
