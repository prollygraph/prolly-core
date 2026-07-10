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

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * jcstress wiring sample — proves {@code jcstress-core} resolves and its {@code @JCStressTest} API
 * compiles in this module. Deliberately NOT named {@code *Test}: jcstress tests are NOT run by
 * surefire — they run under jcstress's own forked harness (the {@code jcstress-maven-plugin} shades
 * a runner uber-jar; {@code java -jar target/jcstress.jar}). Wiring that runner is a follow-up when
 * the first real memory-model test lands (e.g. the {@code indexRoots} publication smell,
 * prolly-rdf4j Phase 5 / core Step 22).
 *
 * <p>This sample just pins that the annotations + result types are on the classpath. It documents
 * the canonical shape: two {@link Actor}s racing on shared state, with the permitted interleaving
 * {@link Outcome}s declared.
 */
@JCStressTest
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "reader saw the write twice")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "reader saw the write on the 2nd read")
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "reader ran fully before the write")
@State
public class JcstressSample {

    int x;

    @Actor
    public void writer() {
        x = 1;
    }

    @Actor
    public void reader(II_Result r) {
        r.r1 = x;
        r.r2 = x;
    }
}
