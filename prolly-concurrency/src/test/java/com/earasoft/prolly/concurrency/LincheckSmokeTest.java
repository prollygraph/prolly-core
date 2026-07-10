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
package com.earasoft.prolly.concurrency;

import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions;
import org.junit.jupiter.api.Test;

/**
 * Wiring smoke for the prolly-concurrency module: proves the Lincheck model-checking engine
 * actually loads + runs under this build (JDK 21 + {@code --enable-preview} + surefire + the
 * JVM-open set in the pom).
 *
 * <p>It checks a trivially-linearizable structure (an {@link AtomicInteger}) on purpose — the goal
 * here is to validate the TOOLCHAIN, not to find a bug. The real linearizability targets are the
 * test-strategy plans' concurrency phases: {@code MutableMap} and the {@code Database}/{@code
 * ProllySail} commit paths (core-engine Step 20-21, prolly-rdf Phase 5, prolly-rdf4j Phase 5) —
 * those live in this same module once this smoke proves the rig works.
 */
public class LincheckSmokeTest {

    private final AtomicInteger counter = new AtomicInteger();

    @Operation
    public int incrementAndGet() {
        return counter.incrementAndGet();
    }

    @Operation
    public int get() {
        return counter.get();
    }

    @Test
    public void modelCheckingEngineRuns() {
        // LinChecker.check(testClass, options) is the Java entry point;
        // Options.check(...) is a Kotlin extension not visible from Java.
        LinChecker.check(
                this.getClass(),
                new ModelCheckingOptions().iterations(1).invocationsPerIteration(50));
    }
}
