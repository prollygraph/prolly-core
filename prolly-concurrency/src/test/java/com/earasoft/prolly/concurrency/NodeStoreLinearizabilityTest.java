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

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.InMemoryNodeStore;
import java.lang.foreign.MemorySegment;
import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions;
import org.junit.jupiter.api.Test;

/**
 * Core Step 20 (concurrency, R-2/I-7) — Lincheck linearizability on the engine's
 * genuinely-concurrent primitive: the content-addressed {@link InMemoryNodeStore} (a {@link
 * java.util.concurrent.ConcurrentHashMap} of {@code hash → bytes}, read by many threads + written
 * during builds).
 *
 * <p><b>Plan correction (per CLAUDE.md "plan wording turned out wrong"):</b> Step 20 originally
 * named {@code MutableMap}. But {@code MutableMap} is <em>single-writer by design</em> — a
 * per-transaction write buffer backed by a plain {@code TreeMap} with no synchronization, and every
 * call site creates its own instance (never shared across threads). A "concurrent put/delete"
 * linearizability test on it would only rediscover that {@code TreeMap} isn't thread-safe — not an
 * engine property. The MVCC safety in this system comes from (a) immutability of committed {@code
 * StaticMap}s, (b) the content- addressed store being a linearizable map, and (c) the single-writer
 * lock at the {@code Database}/{@code ProllySail} level (Step 21 + the rdf/rdf4j plans). So the
 * first real linearizability target is the store.
 *
 * <p>Spec: the store behaves as a linearizable map where {@code write} is idempotent +
 * content-addressed ({@code write(data)} always returns {@code hash(data)}), and {@code contains}
 * reflects some sequential order of the concurrent operations. Lincheck derives the sequential spec
 * by running these same {@code @Operation}s serially; a regression that swapped the {@code
 * ConcurrentHashMap} for a non-thread-safe map (or added a non-atomic check-then-act) would surface
 * a non-linearizable interleaving.
 */
@Param(name = "x", gen = IntGen.class, conf = "1:3")
public class NodeStoreLinearizabilityTest {

    private final InMemoryNodeStore store = new InMemoryNodeStore();

    @Operation
    public String write(@Param(name = "x") int x) {
        // Deterministic 1-byte payload per x → content-addressed, idempotent.
        return HashUtils.toHex(store.write(MemorySegment.ofArray(new byte[] {(byte) x})));
    }

    @Operation
    public boolean contains(@Param(name = "x") int x) {
        return store.read(HashUtils.hash(new byte[] {(byte) x})).isPresent();
    }

    /**
     * STRESS mode only — deliberately NOT model-checking. Lincheck's model-checking strategy
     * instruments bytecode to drive interleavings, and {@code write()} reaches {@code
     * HashUtils.hash} → {@code MessageDigest.getInstance("SHA-512")} → the JDK security-provider
     * lookup, which livelocks under that instrumentation ("active lock detected / execution hung").
     * The store's only synchronization IS the {@code ConcurrentHashMap} (already proven
     * linearizable), so model-checking adds little here; stress mode runs real concurrent threads
     * (no instrumentation, no crypto livelock) and verifies the recorded outcomes are linearizable.
     * Reserve model-checking for pure-synchronization targets with no crypto/IO in the operation
     * (e.g. the Database-commit OCC, Step 21).
     */
    @Test
    public void stressIsLinearizable() {
        LinChecker.check(
                this.getClass(),
                new StressOptions()
                        .iterations(10)
                        .threads(3)
                        .actorsPerThread(4)
                        .invocationsPerIteration(1000));
    }
}
