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

import com.dolthub.prolly.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 *
 *
 * <h3>ErrorInjectingNodeStore Countdown Semantics Test</h3>
 *
 * <p>Pins the countdown behaviour of {@link
 * com.earasoft.prolly.ErrorInjectingNodeStore#injectErrorAfter(int)}: when {@code N} is set, the
 * next {@code N - 1} I/O operations succeed and the {@code N}th throws {@code
 * RuntimeException("Injected IO Failure")}. Subsequent operations succeed (countdown stays at zero,
 * predicate {@code > 0} is false).
 *
 * <p><b>The Gap:</b> {@code ErrorInjectingNodeStore} is the project's fault-injection primitive.
 * It's used by {@code FaultInjectionTest} and {@code CrashRecoveryAtomicityTest} but neither of
 * those pin the precise countdown semantics. If a regression made the trip happen at the {@code
 * N+1}th call (or never), or made the wrapper start retrying after the trip, those tests would
 * still pass because they only need <i>some</i> failure during a long edit stream.
 *
 * <p><b>Oracles:</b>
 *
 * <ol>
 *   <li>{@code injectErrorAfter(3)} → calls 1 and 2 succeed; call 3 throws; calls 4+ succeed.
 *   <li>{@code injectErrorAfter(1)} → call 1 throws immediately.
 *   <li>{@code injectErrorAfter(0)} (or unset) → no calls throw.
 *   <li>The countdown is shared across {@code read}, {@code write(byte[])}, and {@code
 *       write(MemorySegment)} — interleaving them counts toward the same trip point.
 * </ol>
 */
public class ErrorInjectingNodeStoreTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- ErrorInjectingNodeStore Countdown Semantics Test ---");
        Path tempDir = Files.createTempDirectory("prolly-error-injecting");

        try (RocksNodeStore inner = new RocksNodeStore(tempDir.toString())) {
            // Pre-write a hash we can read back so reads succeed when the
            // countdown isn't tripping.
            byte[] hash = inner.write("payload".getBytes());

            // Oracle 1: injectErrorAfter(3) trips on the 3rd call, not before, not after.
            ErrorInjectingNodeStore eis = new ErrorInjectingNodeStore(inner);
            eis.injectErrorAfter(3);
            eis.read(hash); // call 1 — ok
            eis.read(hash); // call 2 — ok
            try {
                eis.read(hash); // call 3 — must throw
                throw new RuntimeException("call 3 should have thrown");
            } catch (RuntimeException e) {
                if (!"Injected IO Failure".equals(e.getMessage())) {
                    throw new RuntimeException("wrong exception message: " + e.getMessage());
                }
            }
            // Calls 4 and 5 — must succeed (countdown is now 0).
            eis.read(hash);
            eis.read(hash);
            System.out.println("injectErrorAfter(3) trips on the 3rd call only. (1/4)");

            // Oracle 2: injectErrorAfter(1) trips on the very first call.
            ErrorInjectingNodeStore eis2 = new ErrorInjectingNodeStore(inner);
            eis2.injectErrorAfter(1);
            try {
                eis2.read(hash);
                throw new RuntimeException("injectErrorAfter(1) should have tripped on call 1");
            } catch (RuntimeException e) {
                if (!"Injected IO Failure".equals(e.getMessage())) {
                    throw new RuntimeException("wrong exception: " + e.getMessage());
                }
            }
            System.out.println("injectErrorAfter(1) trips on the first call. (2/4)");

            // Oracle 3: injectErrorAfter(0) (or unset) never throws.
            ErrorInjectingNodeStore eis3 = new ErrorInjectingNodeStore(inner);
            eis3.injectErrorAfter(0);
            for (int i = 0; i < 100; i++) eis3.read(hash);

            ErrorInjectingNodeStore eis4 = new ErrorInjectingNodeStore(inner);
            // Don't call injectErrorAfter at all — default countdown is -1.
            for (int i = 0; i < 100; i++) eis4.read(hash);
            System.out.println("Default and N=0 never throw. (3/4)");

            // Oracle 4: countdown shared across read / write(byte[]) / write(seg).
            ErrorInjectingNodeStore eis5 = new ErrorInjectingNodeStore(inner);
            eis5.injectErrorAfter(4);
            eis5.read(hash); // 1 ok
            eis5.write("payload-2".getBytes()); // 2 ok
            eis5.write(MemorySegment.ofArray("payload-3".getBytes())); // 3 ok
            try {
                eis5.read(hash); // 4 — must throw
                throw new RuntimeException("4th interleaved op should have thrown");
            } catch (RuntimeException e) {
                if (!"Injected IO Failure".equals(e.getMessage())) {
                    throw new RuntimeException("wrong exception: " + e.getMessage());
                }
            }
            System.out.println("Countdown is shared across read/write overloads. (4/4)");

            System.out.println("--- ErrorInjectingNodeStore Countdown Semantics Test PASSED ---");
        }
    }
}
