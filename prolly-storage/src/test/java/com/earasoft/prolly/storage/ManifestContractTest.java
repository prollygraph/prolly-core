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
package com.earasoft.prolly.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.InMemoryManifest;
import com.dolthub.prolly.Manifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@link Manifest} contract, pinned across the <b>production</b> {@link RocksManifest} and the
 * shared {@link InMemoryManifest} test double — so the double can never silently drift from
 * production semantics (the test-only-stand-in audit's class-1 fix: the audit found TWO independent
 * in-memory copies, one of which had already diverged on read-side defensive copying, and neither
 * was contract-tested; plans/prepublic/test-only-standin-audit.md Step 3).
 *
 * <p>The contract (from the {@code Manifest} interface + {@code Database}'s optimistic-concurrency
 * commit path): {@code updateRef} is an atomic compare-and-set — a {@code null} expected hash means
 * <em>create-only</em> (fails if the ref exists), a wrong expected fails and leaves the value
 * untouched, a {@code null} new hash deletes; {@code listRefs} is isolated per {@code repoId}; and
 * stored bytes are defensively copied in both directions (mutating a caller's array after the call,
 * or an array returned by {@code getRef}, must not corrupt the store). Mirrors {@code
 * NodeStoreContractTest}'s kind-parameterized shape ({@code @TestFactory} — this module has no
 * junit-jupiter-params).
 */
final class ManifestContractTest {

    private enum Kind {
        ROCKS,
        INMEMORY
    }

    private static byte[] h(int seed) {
        byte[] out = new byte[20];
        out[0] = (byte) seed;
        out[1] = (byte) (seed >>> 8);
        return out;
    }

    @TestFactory
    Stream<DynamicTest> the_manifest_contract_holds_for_every_implementation() {
        return Stream.of(Kind.values())
                .flatMap(
                        kind ->
                                Stream.of(
                                        DynamicTest.dynamicTest(
                                                kind + ": compare-and-set lifecycle",
                                                () ->
                                                        withManifest(
                                                                kind,
                                                                ManifestContractTest
                                                                        ::casLifecycle)),
                                        DynamicTest.dynamicTest(
                                                kind + ": per-repo isolation",
                                                () ->
                                                        withManifest(
                                                                kind,
                                                                ManifestContractTest
                                                                        ::repoIsolation)),
                                        DynamicTest.dynamicTest(
                                                kind + ": defensive byte copies",
                                                () ->
                                                        withManifest(
                                                                kind,
                                                                ManifestContractTest
                                                                        ::defensiveCopies))));
    }

    private interface ContractCase {
        void run(Manifest m) throws Exception;
    }

    private static void withManifest(Kind kind, ContractCase body) throws Exception {
        switch (kind) {
            case INMEMORY -> body.run(new InMemoryManifest());
            case ROCKS -> {
                Path dir = Files.createTempDirectory("manifest-contract-");
                try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                    body.run(new RocksManifest(store.db()));
                }
            }
        }
    }

    /** Create-only, wrong-expected rejection, correct-expected update, delete-by-null-newHash. */
    private static void casLifecycle(Manifest m) {
        // Absent ref reads empty.
        assertTrue(m.getRef("repo", "main").isEmpty(), "absent ref must read empty");

        // Create-only (expected=null) succeeds once...
        assertTrue(
                m.updateRef("repo", "main", h(1), null), "create-only on an absent ref succeeds");
        assertArrayEquals(h(1), m.getRef("repo", "main").orElseThrow());
        // ...and fails while the ref exists, leaving the value untouched.
        assertFalse(
                m.updateRef("repo", "main", h(9), null),
                "create-only on an existing ref must fail (null expected = expect absent)");
        assertArrayEquals(h(1), m.getRef("repo", "main").orElseThrow(), "value untouched");

        // Wrong expected fails and leaves the value untouched.
        assertFalse(m.updateRef("repo", "main", h(2), h(42)), "wrong expected must fail");
        assertArrayEquals(h(1), m.getRef("repo", "main").orElseThrow(), "value untouched");

        // Correct expected succeeds.
        assertTrue(m.updateRef("repo", "main", h(2), h(1)), "correct expected must succeed");
        assertArrayEquals(h(2), m.getRef("repo", "main").orElseThrow());

        // Delete via null newHash (still compare-and-set guarded).
        assertFalse(m.updateRef("repo", "main", null, h(42)), "delete with wrong expected fails");
        assertTrue(
                m.updateRef("repo", "main", null, h(2)), "delete with correct expected succeeds");
        assertTrue(m.getRef("repo", "main").isEmpty(), "deleted ref reads empty");

        // deleteRef removes unconditionally.
        assertTrue(m.updateRef("repo", "main", h(3), null));
        m.deleteRef("repo", "main");
        assertTrue(m.getRef("repo", "main").isEmpty());
    }

    /** Refs are scoped per repoId: names never leak across repos. */
    private static void repoIsolation(Manifest m) {
        assertTrue(m.updateRef("repoA", "main", h(1), null));
        assertTrue(m.updateRef("repoA", "feature", h(2), null));
        assertTrue(m.updateRef("repoB", "main", h(3), null));

        List<String> a = m.listRefs("repoA");
        List<String> b = m.listRefs("repoB");
        assertTrue(a.contains("main") && a.contains("feature"), "repoA lists its own refs: " + a);
        assertTrue(b.contains("main") && !b.contains("feature"), "repoB sees only its own: " + b);
        assertTrue(m.getRef("repoB", "feature").isEmpty(), "repoB has no 'feature' ref");
        assertArrayEquals(h(1), m.getRef("repoA", "main").orElseThrow());
        assertArrayEquals(h(3), m.getRef("repoB", "main").orElseThrow());
    }

    /**
     * Defensive copies both directions — the exact axis on which the audit found one in-memory copy
     * had silently diverged (no read-side copy).
     */
    private static void defensiveCopies(Manifest m) {
        byte[] written = h(7);
        assertTrue(m.updateRef("repo", "main", written, null));

        // Write-side: mutating the caller's array after the call must not change the store.
        written[0] = (byte) 0xEE;
        assertArrayEquals(h(7), m.getRef("repo", "main").orElseThrow(), "write-side copy");

        // Read-side: mutating a returned array must not change the store.
        byte[] read = m.getRef("repo", "main").orElseThrow();
        read[0] = (byte) 0xDD;
        assertArrayEquals(h(7), m.getRef("repo", "main").orElseThrow(), "read-side copy");
    }
}
