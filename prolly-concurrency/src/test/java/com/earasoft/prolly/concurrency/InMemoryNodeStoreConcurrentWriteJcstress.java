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
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.ZZ_Result;

/**
 * Core Step 22 (concurrency, memory model) — jcstress no-lost-update check on {@link
 * InMemoryNodeStore}. Its Javadoc promises concurrent writes are safe so "the Sail can commit its
 * independent per-transaction trees in parallel"; this pins that promise at the Java-Memory-Model
 * level.
 *
 * <p>Two actors write <em>distinct</em> chunks concurrently; the {@link Arbiter} (running after
 * both) reads both back. The only acceptable outcome is {@code true, true} — both writes survived.
 * Any other outcome is {@link Expect#FORBIDDEN}: a lost update would mean two concurrent puts to
 * the backing map raced destructively (what a plain {@code HashMap} would do under concurrent
 * writes — table resize losing an entry). It does not occur, because the store is a {@link
 * java.util.concurrent.ConcurrentHashMap}.
 *
 * <p>Not a {@code *Test} class — runs under jcstress's forked runner ({@code run-jcstress.sh}), not
 * surefire; test-compiles in a normal build.
 */
@JCStressTest
@Outcome(
        id = "true, true",
        expect = Expect.ACCEPTABLE,
        desc = "both concurrent writes are present — no lost update")
@Outcome(expect = Expect.FORBIDDEN, desc = "a concurrent write was lost — destructive race")
@State
public class InMemoryNodeStoreConcurrentWriteJcstress {

    private final InMemoryNodeStore store = new InMemoryNodeStore();
    private final byte[] a = {10, 11, 12, 13};
    private final byte[] b = {20, 21, 22, 23};
    private final byte[] hashA = HashUtils.hash(a);
    private final byte[] hashB = HashUtils.hash(b);

    @Actor
    public void writerA() {
        store.write(MemorySegment.ofArray(a));
    }

    @Actor
    public void writerB() {
        store.write(MemorySegment.ofArray(b));
    }

    @Arbiter
    public void check(ZZ_Result r) {
        r.r1 = store.read(hashA).isPresent();
        r.r2 = store.read(hashB).isPresent();
    }
}
