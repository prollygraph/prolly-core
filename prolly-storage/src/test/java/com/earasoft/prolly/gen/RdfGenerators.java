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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Shared domain generators for RDF-shaped property-based tests (the upstream test-strategy plan,
 * Step 4 / D-1; the RDF sibling of the engine module's {@code gen/Generators}). Curated once so
 * boundary cases (empty keys, delete-of-absent, overlapping three-way edits) are never
 * re-forgotten.
 *
 * <p>Values are <b>pure records</b> deliberately decoupled from the {@code Database}/{@code Sail}
 * API — a property test maps an {@link Edit} stream onto whatever surface it drives (string-keyed
 * tuples, quads, …). That keeps the generators reusable across the versioning-algebra (R-1), index
 * (R-5/6), and three-way-merge (R-7) properties without binding them to one call shape.
 *
 * <p>Self-validated by {@code RdfGeneratorsSmokeTest}; do not let a generator go unexercised.
 */
public final class RdfGenerators {

    private RdfGenerators() {}

    /** An RDF term as a string — an IRI, a blank node, or a literal. */
    public record Term(String text, Kind kind) {
        public enum Kind {
            IRI,
            BNODE,
            LITERAL
        }
    }

    /** A quad: subject, predicate, object, graph (graph may be the default). */
    public record Quad(Term s, Term p, Term o, Term g) {}

    /** One mutation in a batch: put {@code key→value}, or delete {@code key}. */
    public record Edit(String key, String value, boolean delete) {}

    /**
     * A three-way scenario: a common base plus two edit overlays whose key sets are <b>disjoint</b>
     * (so the merge is conflict-free by construction — the baseline R-7 property; conflict
     * scenarios are a separate generator).
     */
    public record ThreeWay(Map<String, String> base, List<Edit> left, List<Edit> right) {}

    // ---- terms -----------------------------------------------------------

    /** IRIs from a small curated vocabulary plus generated ones. */
    public static Arbitrary<Term> iris() {
        Arbitrary<String> path =
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
        return path.map(p -> new Term("urn:ex:" + p, Term.Kind.IRI));
    }

    public static Arbitrary<Term> bnodes() {
        return Arbitraries.integers()
                .between(0, 9999)
                .map(i -> new Term("_:b" + i, Term.Kind.BNODE));
    }

    /** Literals including the boundary cases: empty string, unicode, long. */
    public static Arbitrary<Term> literals() {
        return Arbitraries.oneOf(
                        Arbitraries.just(""),
                        Arbitraries.just("café é中"),
                        Arbitraries.strings().ofMaxLength(64))
                .map(t -> new Term(t, Term.Kind.LITERAL));
    }

    public static Arbitrary<Term> terms() {
        return Arbitraries.oneOf(iris(), bnodes(), literals());
    }

    /** A quad; the graph is an IRI or the default graph (null-text sentinel). */
    public static Arbitrary<Quad> quads() {
        Arbitrary<Term> graph =
                Arbitraries.oneOf(
                        iris(), Arbitraries.just(new Term("urn:ex:default", Term.Kind.IRI)));
        return Combinators.combine(iris(), iris(), terms(), graph).as(Quad::new);
    }

    // ---- edits + batches -------------------------------------------------

    /**
     * Keys are drawn from a bounded pool so put/delete/overwrite actually collide (an unbounded key
     * space would make every edit a fresh insert and never exercise overwrite-then-delete).
     */
    public static Arbitrary<String> keys() {
        return Arbitraries.integers().between(0, 31).map(i -> "k" + i);
    }

    public static Arbitrary<Edit> edits() {
        return Combinators.combine(
                        keys(), Arbitraries.strings().ofMaxLength(16), Arbitraries.of(true, false))
                .as(Edit::new);
    }

    /**
     * A sequence of commit batches: each inner list is one commit's mutations, applied in order.
     * Captures put/delete/overwrite churn over time (R-1).
     */
    public static Arbitrary<List<List<Edit>>> editBatches() {
        return edits().list()
                .ofMinSize(1)
                .ofMaxSize(8) // one commit's worth
                .list()
                .ofMinSize(1)
                .ofMaxSize(6); // a sequence of commits
    }

    // ---- three-way -------------------------------------------------------

    /**
     * A conflict-free three-way scenario: a base map, and two edit overlays whose key sets are
     * disjoint (left touches even-indexed keys, right odd), so left and right never write the same
     * key.
     */
    public static Arbitrary<ThreeWay> threeWay() {
        Arbitrary<Map<String, String>> base =
                Combinators.combine(keys(), Arbitraries.strings().ofMaxLength(8))
                        .as(Map::entry)
                        .list()
                        .ofMaxSize(16)
                        .map(
                                entries -> {
                                    Map<String, String> m = new LinkedHashMap<>();
                                    entries.forEach(e -> m.put(e.getKey(), e.getValue()));
                                    return m;
                                });
        return base.flatMap(
                b ->
                        Combinators.combine(disjointEdits(b, true), disjointEdits(b, false))
                                .as((l, r) -> new ThreeWay(b, l, r)));
    }

    /**
     * Edits restricted to keys whose numeric suffix has a fixed parity, so the two sides of a
     * {@link ThreeWay} cannot collide.
     */
    private static Arbitrary<List<Edit>> disjointEdits(Map<String, String> base, boolean even) {
        Arbitrary<Integer> idx =
                Arbitraries.integers().between(0, 15).map(i -> even ? i * 2 : i * 2 + 1);
        Arbitrary<Edit> e =
                Combinators.combine(
                                idx.map(i -> "k" + i),
                                Arbitraries.strings().ofMaxLength(8),
                                Arbitraries.of(true, false))
                        .as(Edit::new);
        return e.list().ofMaxSize(8);
    }

    /**
     * Apply an edit batch sequence to a fresh map — the in-memory oracle a property test compares
     * the engine against.
     */
    public static Map<String, String> applyOracle(List<List<Edit>> batches) {
        Map<String, String> m = new LinkedHashMap<>();
        for (List<Edit> commit : batches) {
            for (Edit e : commit) {
                if (e.delete()) m.remove(e.key());
                else m.put(e.key(), e.value());
            }
        }
        return m;
    }

    /** All keys an {@link Edit} list touches (for disjointness assertions). */
    public static List<String> touchedKeys(List<Edit> edits) {
        List<String> out = new ArrayList<>();
        for (Edit e : edits) out.add(e.key());
        return out;
    }
}
