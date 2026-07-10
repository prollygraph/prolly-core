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
 * Phase 5 Step 20 of the upstream RDF4J test-strategy plan — the JMM publication proof for the
 * <b>root-publication fix</b> (the upstream root-publication-race bug record). It pins the
 * <b>multi-root atomicity</b> the fix guarantees: a reader that observes the published snapshot
 * sees <i>all roots from the same generation</i> — never a torn mix of a new {@code dictRoot} with
 * a stale index root.
 *
 * <p><b>What the step originally targeted, and why it changed.</b> Step 20 was written to expose
 * the documented non-volatile {@code ProllySail.indexRoots} EnumMap publication smell: {@code
 * dictRoot} was {@code volatile} but the four quad-index roots lived in a plain {@code EnumMap}, so
 * a reader could fork a fresh {@code dictRoot} alongside a stale {@code indexRoots[SPOC]} — an
 * internally inconsistent snapshot (the dictionary knows a {@code TermId} the index hasn't got
 * yet). Re-anchoring against the current code (2026-06-11) found that smell <b>already
 * eliminated</b> by the root-publication fix: all four core roots are now published as ONE
 * immutable {@code Snapshot} behind a single {@code volatile publishedSnapshot}, and {@code
 * ProllySailConnection.forkTables()} reads it with one volatile load. So Step 20 is reframed from
 * <i>expose the smell</i> to <i>prove the fix</i> — the adversarial-scheduler layer for a fix that
 * was shipped by-construction (the theorem was Step 13; the functional/stress wiring is {@code
 * ProllySailConcurrencyStressTest}; this jcstress is the third layer).
 *
 * <p><b>Why jcstress here, when Lincheck couldn't touch {@code ProllySail} in Step 19.</b> Lincheck
 * retransforms loaded bytecode and dies on the RDF4J Sail class graph ({@code class redefinition
 * failed: invalid class}). jcstress instruments <i>nothing</i> — it runs {@code @Actor} methods on
 * real threads against a tiny state object. So the right jcstress target is a <b>minimal faithful
 * model</b> of the publication pattern (the same discipline as {@link ManifestPublicationJcstress},
 * which models the manifest head→node contract with {@code int}s), not a real {@code ProllySail} (a
 * full Sail commit per invocation is far too heavy for jcstress's
 * hammer-the-window-millions-of-times regime). {@link Snap} mirrors {@code Snapshot}'s shape
 * exactly: an immutable, final-field, multi-root holder; the {@code volatile published} field
 * mirrors {@code ProllySail.publishedSnapshot}; the writer's single assignment mirrors {@code
 * publishSnapshot()} (one volatile store of a fully-built immutable object). What it proves is the
 * <i>JMM property the fix relies on</i>, not the Sail's business logic — exactly the scope of every
 * other publication harness in this module.
 *
 * <p><b>The contract.</b> The writer advances both roots to generation 1 and publishes the
 * immutable {@code Snap} with one volatile store. The reader does one volatile load and reads both
 * roots off it. Because both roots come from the <i>same</i> immutable object reached through
 * <i>one</i> volatile acquire, the reader sees either generation 0 (both 0) or generation 1 (both
 * 1). A <b>torn</b> read — {@code (1, 0)} or {@code (0, 1)} — is the pre-fix smell and is
 * <b>FORBIDDEN</b>; the bundled-volatile publication makes it impossible. (The pre-fix two-field
 * shape — {@code volatile dictRoot} + non-{@code volatile} index root, published with two separate
 * stores — is precisely what made {@code (1, 0)} ACCEPTABLE-interesting: the reader could acquire
 * the new {@code dictRoot} and still read the stale, never-released index root.)
 *
 * <p>Runs under the jcstress forked harness (its own runner), gated OFF from surefire — like {@link
 * JcstressSample} / {@link ManifestPublicationJcstress}, and deliberately <i>not</i> named {@code
 * *Test} so surefire never tries to run it as a JUnit test.
 */
@JCStressTest
@Outcome(
        id = "0, 0",
        expect = Expect.ACCEPTABLE,
        desc = "reader saw generation 0 (pre-publish) — both roots stale together")
@Outcome(
        id = "1, 1",
        expect = Expect.ACCEPTABLE,
        desc = "reader saw generation 1 (post-publish) — both roots fresh together")
@Outcome(
        id = "1, 0",
        expect = Expect.FORBIDDEN,
        desc =
                "TORN: fresh dictRoot + stale index root — the pre-fix non-volatile-indexRoots smell")
@Outcome(
        id = "0, 1",
        expect = Expect.FORBIDDEN,
        desc = "TORN: stale dictRoot + fresh index root — the other torn ordering")
@State
public class RootSnapshotPublicationJcstress {

    /**
     * Minimal mirror of {@code ProllySail.Snapshot}: an immutable, final-field, multi-root holder.
     * Two fields stand in for the roots whose publication used to disagree — {@code dictRoot} (was
     * {@code volatile}) and {@code indexRoot} (was a non-{@code volatile} EnumMap value).
     */
    static final class Snap {
        final int dictRoot;
        final int indexRoot;

        Snap(int dictRoot, int indexRoot) {
            this.dictRoot = dictRoot;
            this.indexRoot = indexRoot;
        }
    }

    /** The single publication point — mirrors {@code ProllySail.publishedSnapshot}. */
    volatile Snap published = new Snap(0, 0);

    /**
     * Mirrors {@code commitInternal} → {@code publishSnapshot()}: one volatile store of a fresh
     * immutable Snap.
     */
    @Actor
    public void writer() {
        published = new Snap(1, 1);
    }

    /**
     * Mirrors {@code forkTables()}: one volatile load, then both roots read off the same immutable
     * Snap.
     */
    @Actor
    public void reader(II_Result r) {
        Snap s = published; // one volatile acquire
        r.r1 = s.dictRoot;
        r.r2 = s.indexRoot; // same generation as r1 — by construction
    }
}
