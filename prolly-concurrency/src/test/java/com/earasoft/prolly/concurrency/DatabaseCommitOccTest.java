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

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryManifest;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TreeMutator;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.Database;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions;
import org.junit.jupiter.api.Test;

/**
 * Core Step 21 (concurrency, R-2) — Lincheck on the {@code Database} commit OCC: concurrent commits
 * from the SAME parent must be linearizable to a compare-and-set on the branch head, i.e.
 * <b>exactly one wins, no lost update</b>. The authoritative gate is {@code manifest.updateRef(...,
 * expected=parent)} (synchronized CAS); the test verifies that even though the pre-check
 * (read-head-then-act) is racy and commits hold only a shared GC read-lock (so they genuinely
 * race), two commits from one parent can never both succeed.
 *
 * <p>STRESS mode, not model-checking: the commit path hashes (writes the root + Commit object via
 * {@code MessageDigest}), which livelocks Lincheck's model-checker on the JDK security-provider
 * lookup (see the Step-20 lesson in {@code prolly-concurrency/README.md}). Stress runs real threads
 * + verifies the recorded outcomes are linearizable.
 *
 * <p>Spec: with all ops committing expecting the fixed {@code genesis} head, a serial execution
 * returns {@code true} for the first and {@code false} for the rest (head moved); a linearizable
 * concurrent execution must therefore have exactly one {@code true}.
 */
@Param(name = "x", gen = IntGen.class, conf = "1:4")
public class DatabaseCommitOccTest {

    private final InMemoryNodeStore store = new InMemoryNodeStore();
    private final DirectBufferPool pool = new DirectBufferPool();
    private final TupleDescriptor desc =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private final Database db = new Database(store, new InMemoryManifest(), "repo", desc, pool);
    private final byte[] genesis;

    public DatabaseCommitOccTest() {
        db.createBranch("main", "EMPTY");
        // Establish a fixed genesis head H0 that every @Operation commits against.
        db.commit(
                "main",
                oneEntry("seed", "0"),
                db.getHeadHash("main").orElse(null),
                "setup",
                "genesis");
        genesis = db.getHeadHash("main").orElseThrow();
    }

    @Operation
    public boolean commitFromGenesis(@Param(name = "x") int x) {
        return db.commit("main", oneEntry("key-" + x, "val-" + x), genesis, "author", "m" + x);
    }

    private StaticMap oneEntry(String key, String val) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes(StandardCharsets.UTF_8));
        Node root =
                new TreeMutator(store, desc, pool)
                        .applyMutations(
                                null,
                                List.of(
                                                new TreeMutator.Mutation(
                                                        tb.build().segment(),
                                                        MemorySegment.ofArray(
                                                                val.getBytes(
                                                                        StandardCharsets.UTF_8))))
                                        .iterator());
        return new StaticMap(store, root, desc);
    }

    @Test
    public void commitOccIsLinearizable() {
        LinChecker.check(
                this.getClass(),
                new StressOptions()
                        .iterations(10)
                        .threads(3)
                        .actorsPerThread(3)
                        .invocationsPerIteration(500));
    }
}
