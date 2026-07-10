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
package com.dolthub.prolly;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 *
 *
 * <h3>Tuple Ordering Edge Test</h3>
 *
 * <p>Asserts that {@link TupleDescriptor#compare(Tuple, Tuple)} obeys the four ordering rules a
 * Prolly Tree's B-tree invariant requires:
 *
 * <ol>
 *   <li><b>Unsigned bytes:</b> {@code 0x00 &lt; 0xFF}, not the other way round (signed comparison
 *       would put {@code 0xFF == -1} first and silently destroy the B-tree invariant on
 *       negative-byte payloads).
 *   <li><b>NULLs sort first within a column.</b>
 *   <li><b>Prefix tuples sort first:</b> if {@code a} is a strict prefix of {@code b}, then {@code
 *       a &lt; b}.
 *   <li><b>Boundary bytes:</b> tuples differing only in {@code 0x00} or {@code 0xFF} fields sort
 *       the obvious way without overflow.
 * </ol>
 *
 * <p><b>The Gap:</b> {@code TupleChaosTest} fuzzes random tuples; nothing pins a hand-crafted
 * adversarial corpus. The bugs that hide here look obvious once seen — a {@code byte} vs {@code
 * int} sign mistake, a {@code count} tiebreak in the wrong direction — but they only surface when
 * keys share long prefixes or live near the byte-range edges. Pure randomness almost never lands on
 * those cases.
 *
 * <p><b>Oracle:</b> a hand-computed reference order of 12 tuples (documented inline below). The
 * test:
 *
 * <ul>
 *   <li>Sorts a shuffled copy of the corpus using {@code TupleDescriptor.compare} and asserts
 *       equality with the reference order.
 *   <li>Cross-checks {@code compare} against itself: for every pair {@code (i, j)}, {@code
 *       sign(compare(i,j)) == sign(refIdx[i] - refIdx[j])}.
 *   <li>Repeats the sort 50 times under a different RNG seed each pass to prove ordering is total
 *       and stable, not data-order-dependent.
 * </ul>
 */
public class TupleOrderingEdgeTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Tuple Ordering Edge Test ---");

        HeapBufferPool pool = new HeapBufferPool();
        {
            TupleDescriptor desc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.String, true),
                                    new Type(Encoding.String, true)));

            // Reference order — derived by hand from the four rules above.
            //   index | description                          | bytes
            //   ------+--------------------------------------+----------------
            //     0   | (NULL)                               | 1 col, NULL
            //     1   | (NULL, "a")                          | NULL sorts first in col0
            //     2   | (0x00)                               | smallest non-null byte
            //     3   | (0x00, 0x00)                         | strict suffix of (0x00)
            //     4   | ("a")                                |
            //     5   | ("a", NULL)                          | NULL sorts before any byte in col1
            //     6   | ("a", 0x00)                          |
            //     7   | ("a", "b")                           |
            //     8   | ("b")                                |
            //     9   | (0xFF)                               | unsigned: 0xFF > "b"
            //    10   | (0xFF, 0x00)                         |
            //    11   | (0xFF, 0xFF)                         |
            List<LabelledTuple> reference = new ArrayList<>();
            reference.add(new LabelledTuple("(NULL)", buildTuple(pool, (byte[]) null)));
            reference.add(new LabelledTuple("(NULL,a)", buildTuple(pool, null, "a".getBytes())));
            reference.add(new LabelledTuple("(0x00)", buildTuple(pool, new byte[] {0x00})));
            reference.add(
                    new LabelledTuple(
                            "(0x00,0x00)", buildTuple(pool, new byte[] {0x00}, new byte[] {0x00})));
            reference.add(new LabelledTuple("(a)", buildTuple(pool, "a".getBytes())));
            reference.add(new LabelledTuple("(a,NULL)", buildTuple(pool, "a".getBytes(), null)));
            reference.add(
                    new LabelledTuple(
                            "(a,0x00)", buildTuple(pool, "a".getBytes(), new byte[] {0x00})));
            reference.add(
                    new LabelledTuple("(a,b)", buildTuple(pool, "a".getBytes(), "b".getBytes())));
            reference.add(new LabelledTuple("(b)", buildTuple(pool, "b".getBytes())));
            reference.add(new LabelledTuple("(0xFF)", buildTuple(pool, new byte[] {(byte) 0xFF})));
            reference.add(
                    new LabelledTuple(
                            "(0xFF,0x00)",
                            buildTuple(pool, new byte[] {(byte) 0xFF}, new byte[] {0x00})));
            reference.add(
                    new LabelledTuple(
                            "(0xFF,0xFF)",
                            buildTuple(pool, new byte[] {(byte) 0xFF}, new byte[] {(byte) 0xFF})));

            Comparator<LabelledTuple> cmp =
                    (x, y) -> desc.compare(new Tuple(x.seg), new Tuple(y.seg));

            // Oracle 1: pairwise comparator agrees with reference index ordering.
            System.out.print("Pairwise comparator vs reference order... ");
            for (int i = 0; i < reference.size(); i++) {
                for (int j = 0; j < reference.size(); j++) {
                    int got = Integer.signum(cmp.compare(reference.get(i), reference.get(j)));
                    int want = Integer.signum(Integer.compare(i, j));
                    if (got != want) {
                        throw new RuntimeException(
                                "Pairwise mismatch: "
                                        + reference.get(i).label
                                        + " vs "
                                        + reference.get(j).label
                                        + " — comparator="
                                        + got
                                        + " expected="
                                        + want);
                    }
                }
            }
            System.out.println("PASS (" + (reference.size() * reference.size()) + " pairs).");

            // Oracle 2: 50 shuffled sorts converge to reference order.
            System.out.print("Shuffled-sort stability over 50 seeds... ");
            for (int seed = 0; seed < 50; seed++) {
                List<LabelledTuple> shuffled = new ArrayList<>(reference);
                Collections.shuffle(shuffled, new Random(seed));
                shuffled.sort(cmp);
                for (int i = 0; i < reference.size(); i++) {
                    if (!shuffled.get(i).label.equals(reference.get(i).label)) {
                        throw new RuntimeException(
                                "Sort with seed "
                                        + seed
                                        + " produced wrong order: pos "
                                        + i
                                        + " has "
                                        + shuffled.get(i).label
                                        + " expected "
                                        + reference.get(i).label);
                    }
                }
            }
            System.out.println("PASS.");

            // Oracle 3: targeted unsigned-vs-signed sentinel.
            System.out.print("Unsigned byte comparison sentinel (0xFF > 0x00)... ");
            int sign = cmp.compare(reference.get(11), reference.get(2));
            if (sign <= 0) {
                throw new RuntimeException(
                        "Signed-byte comparison detected: "
                                + "(0xFF,0xFF) sorted at-or-before (0x00); cmp="
                                + sign);
            }
            System.out.println("PASS.");

            // Oracle 4: prefix invariant — (a) < (a, *) for any non-empty col1.
            System.out.print("Prefix invariant... ");
            for (LabelledTuple t : reference) {
                if (t.label.startsWith("(a,")) {
                    if (cmp.compare(reference.get(4) /* (a) */, t) >= 0) {
                        throw new RuntimeException(
                                "Prefix invariant broken: " + "(a) is not less than " + t.label);
                    }
                }
            }
            System.out.println("PASS.");

            System.out.println("--- Tuple Ordering Edge Test PASSED ---");
        }
    }

    /**
     * Builds a tuple with the given (possibly null) field values. Each argument becomes a separate
     * field; a {@code null} argument materialises as a NULL field in the encoding (start offset ==
     * end offset).
     */
    private static MemorySegment buildTuple(HeapBufferPool pool, byte[]... fields) {
        TupleBuilder tb = new TupleBuilder(pool);
        for (int i = 0; i < fields.length; i++) {
            tb.putField(i, fields[i]);
        }
        return tb.build().segment();
    }

    private record LabelledTuple(String label, MemorySegment seg) {}
}
