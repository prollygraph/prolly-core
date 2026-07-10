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
 * Step 25 of the upstream prolly-rdf test-strategy plan (invariant R-2, the JMM publication point):
 * the manifest <b>head</b> → <b>node bytes</b> safe-publication contract.
 *
 * <p>The real shape: a commit (1) writes a chunk's bytes to the store, then (2) publishes the new
 * head via the manifest. A reader that <i>observes the new head</i> must then see the
 * <b>fully-written</b> node bytes — never a torn or stale view. That holds iff the head is
 * published with <b>release</b> semantics and read with <b>acquire</b> semantics (a {@code
 * volatile} head ref), so the node write <i>happens-before</i> the head publish
 * <i>happens-before</i> any reader that acquired the new head. This is the JMM smell flagged for
 * the non-volatile {@code ProllySail.indexRoots} publication (the core-engine plan's deferred
 * jcstress item); this harness pins the contract the manifest head must uphold.
 *
 * <p>Minimal by design — JMM smells only (jcstress wants tiny, fast state). The {@code volatile
 * head} models the manifest/head ref; {@code nodeBytes} models the chunk persisted before the
 * publish. <b>{@code (head=1, node=0)} — observed the new head but a stale node — is FORBIDDEN</b>;
 * the {@code volatile} release/acquire makes it impossible. A plain (non-{@code volatile}) head
 * would make it ACCEPTABLE-interesting — the bug this guards against.
 *
 * <p>Runs under the jcstress forked harness (its own runner / uber-jar), gated OFF from surefire —
 * like {@link JcstressSample}, and deliberately <i>not</i> named {@code *Test} so surefire never
 * tries to run it as a JUnit test.
 */
@JCStressTest
@Outcome(
        id = "0, -1",
        expect = Expect.ACCEPTABLE,
        desc = "reader observed the old head; did not read the node")
@Outcome(
        id = "1, 42",
        expect = Expect.ACCEPTABLE,
        desc = "observed the new head AND the fully-written node bytes")
@Outcome(
        id = "1, 0",
        expect = Expect.FORBIDDEN,
        desc = "observed the new head but a STALE node — unsafe publication")
@State
public class ManifestPublicationJcstress {

    /** The chunk's bytes, persisted to the store BEFORE the head is published. */
    int nodeBytes = 0;

    /** The published manifest head — release on write, acquire on read. */
    volatile int head = 0;

    @Actor
    public void writer() {
        nodeBytes = 42; // (1) persist the node bytes
        head = 1; // (2) publish the new head (volatile release)
    }

    @Actor
    public void reader(II_Result r) {
        int h = head; // (1) acquire the head
        r.r1 = h;
        // (2) having observed the head, the node bytes it references must be fully visible.
        r.r2 = (h == 1) ? nodeBytes : -1;
    }
}
