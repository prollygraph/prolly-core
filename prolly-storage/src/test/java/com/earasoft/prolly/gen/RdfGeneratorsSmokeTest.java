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
package com.earasoft.prolly.gen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * the upstream test-strategy plan, Step 4 — VIABILITY PROBE + generator self-test.
 *
 * <p>If these {@code @Property} methods run (hundreds of generated cases each), jqwik's engine is
 * discovered under this module's JUnit Platform 6.0.x + the {@code --enable-preview} surefire
 * argLine — so property-based testing (D-1) is viable here and the Phase 1+ property steps can
 * build on it. The methods also <b>validate {@link RdfGenerators}</b> so no generator ships
 * unexercised.
 */
class RdfGeneratorsSmokeTest {

    @Provide
    Arbitrary<List<List<RdfGenerators.Edit>>> batches() {
        return RdfGenerators.editBatches();
    }

    @Provide
    Arbitrary<RdfGenerators.ThreeWay> threeWay() {
        return RdfGenerators.threeWay();
    }

    @Provide
    Arbitrary<RdfGenerators.Quad> quads() {
        return RdfGenerators.quads();
    }

    /** Pure smoke: the engine runs at all. */
    @Property
    void jqwikEngineRuns(@ForAll int n) {
        assertTrue(n == n, "the property engine evaluates a case");
    }

    /** Quads are fully populated — every position is a non-null term. */
    @Property
    void quadsAreWellFormed(@ForAll @From("quads") RdfGenerators.Quad q) {
        assertNotNull(q.s().text());
        assertNotNull(q.p().text());
        assertNotNull(q.o().text());
        assertNotNull(q.g().text());
        // subject + predicate are IRIs by construction.
        assertTrue(q.s().kind() == RdfGenerators.Term.Kind.IRI);
        assertTrue(q.p().kind() == RdfGenerators.Term.Kind.IRI);
    }

    /**
     * The oracle is deterministic + last-write-wins: applying a batch sequence twice yields the
     * same map, and a deleted key is absent.
     */
    @Property
    void oracleIsDeterministicAndLastWriteWins(
            @ForAll @From("batches") List<List<RdfGenerators.Edit>> batches) {
        Map<String, String> a = RdfGenerators.applyOracle(batches);
        Map<String, String> b = RdfGenerators.applyOracle(batches);
        assertTrue(a.equals(b), "oracle is a pure function of the batch sequence");
        // every surviving key's value equals the last non-delete write for it.
        for (var entry : a.entrySet()) {
            String last = null;
            for (List<RdfGenerators.Edit> commit : batches) {
                for (RdfGenerators.Edit e : commit) {
                    if (e.key().equals(entry.getKey())) last = e.delete() ? null : e.value();
                }
            }
            assertTrue(
                    entry.getValue().equals(last),
                    "surviving value must be the last write for the key");
        }
    }

    /**
     * The three-way scenario is conflict-free by construction: the two edit overlays never touch
     * the same key. This is the precondition the R-7 clean-merge property will rely on.
     */
    @Property
    void threeWaySidesAreDisjoint(@ForAll @From("threeWay") RdfGenerators.ThreeWay tw) {
        Set<String> leftKeys = new HashSet<>(RdfGenerators.touchedKeys(tw.left()));
        Set<String> rightKeys = new HashSet<>(RdfGenerators.touchedKeys(tw.right()));
        leftKeys.retainAll(rightKeys);
        assertTrue(
                leftKeys.isEmpty(),
                "left and right edit sets must be key-disjoint; overlap=" + leftKeys);
    }

    /** Generated edit batches are non-empty and structurally valid. */
    @Property
    void batchesAreNonEmpty(@ForAll @From("batches") List<List<RdfGenerators.Edit>> batches) {
        assertFalse(batches.isEmpty(), "a batch sequence has at least one commit");
        for (List<RdfGenerators.Edit> commit : batches) {
            for (RdfGenerators.Edit e : commit) assertNotNull(e.key());
        }
    }
}
