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
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Optional;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * Core Step 22 (concurrency, memory model) — jcstress publication check on {@link
 * InMemoryNodeStore}, the engine's genuinely lock-free primitive (a {@link
 * java.util.concurrent.ConcurrentHashMap} of {@code hash → bytes}).
 *
 * <p>The question jcstress answers here is a Java-Memory-Model one that Lincheck's linearizability
 * check ({@code NodeStoreLinearizabilityTest}) does <em>not</em>: when a writer publishes a chunk
 * and a reader races to read it, can the reader observe a <em>torn</em> chunk — present in the map
 * but with partially-visible bytes? For a content-addressed store this must be impossible: the
 * {@code byte[]} is fully written before {@code put}, never mutated after, and {@code
 * ConcurrentHashMap} provides safe publication, so a reader sees either nothing yet or the whole,
 * correct chunk.
 *
 * <p>Acceptable outcomes: {@code 0} (reader ran before the write was visible) and {@code 1} (reader
 * saw the fully-published chunk). The {@code -1} outcome — present but byte-wise wrong — is {@link
 * Expect#FORBIDDEN}: observing it would mean publication is unsafe (a regression that swapped the
 * {@code ConcurrentHashMap} for a plain {@code HashMap}, or mutated a stored chunk in place). It
 * does not occur.
 *
 * <p>Not a {@code *Test} class: jcstress harnesses run under jcstress's own forked runner (see
 * {@code run-jcstress.sh}), never surefire. It still test-compiles in a normal build, so a broken
 * harness fails the build.
 */
@JCStressTest
@Outcome(
        id = "0",
        expect = Expect.ACCEPTABLE,
        desc = "reader ran before the write was published — absent")
@Outcome(
        id = "1",
        expect = Expect.ACCEPTABLE,
        desc = "reader saw the fully-published, byte-correct chunk")
@Outcome(
        id = "-1",
        expect = Expect.FORBIDDEN,
        desc = "reader saw a torn / corrupt chunk — publication is unsafe")
@State
public class InMemoryNodeStoreWriteVisibilityJcstress {

    private final InMemoryNodeStore store = new InMemoryNodeStore();
    private final byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
    private final byte[] hash = HashUtils.hash(data);

    @Actor
    public void writer() {
        store.write(MemorySegment.ofArray(data));
    }

    @Actor
    public void reader(I_Result r) {
        Optional<MemorySegment> seg = store.read(hash);
        if (seg.isEmpty()) {
            r.r1 = 0; // not yet published — fine
            return;
        }
        byte[] got = seg.get().toArray(ValueLayout.JAVA_BYTE);
        r.r1 = Arrays.equals(got, data) ? 1 : -1;
    }
}
