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
package com.earasoft.prolly;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.FileNodeStore;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * REGENERATOR for the golden storage-layer fixture (roadmap T7) — inert unless {@code
 * -Dgolden.regen=true}; driven by the forge's {@code dev-scripts/regen-golden-fixtures.sh}.
 * Protocol in the fixture README: a failing {@link GoldenStoreOpenTest} is a format break — fix the
 * code or regenerate DELIBERATELY with a CHANGELOG note; commit hashes embed timestamps, so bytes
 * churn on regen and the open test's semantic assertions are the review surface.
 */
class GoldenStoreFixtureRegen {

    /**
     * File-backed test manifest (branch -> head hex in a properties file). Production ships only
     * RocksManifest; the fixture's REAL pinned surface is the node-chunk, tree, and commit
     * encodings in chunks/ — the manifest here is test glue, documented in the README.
     */
    static final class FixtureManifest implements com.dolthub.prolly.Manifest {
        private final Path file;
        private final java.util.Properties props = new java.util.Properties();

        FixtureManifest(Path file) throws java.io.IOException {
            this.file = file;
            if (Files.exists(file)) {
                try (var in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
        }

        private void save() {
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "golden fixture manifest");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public java.util.Optional<byte[]> getRef(String repoId, String name) {
            String v = props.getProperty(repoId + "/" + name);
            return (v == null || v.isEmpty())
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(java.util.HexFormat.of().parseHex(v));
        }

        @Override
        public synchronized boolean updateRef(
                String repoId,
                String name,
                byte @org.jspecify.annotations.Nullable [] newHash,
                byte @org.jspecify.annotations.Nullable [] expected) {
            var cur = getRef(repoId, name);
            boolean match =
                    expected == null
                            ? cur.isEmpty()
                            : cur.isPresent() && java.util.Arrays.equals(expected, cur.get());
            if (!match) return false;
            // newHash == null: the branch exists with no head yet (createBranch EMPTY)
            props.setProperty(
                    repoId + "/" + name,
                    newHash == null ? "" : java.util.HexFormat.of().formatHex(newHash));
            save();
            return true;
        }

        @Override
        public synchronized void deleteRef(String repoId, String name) {
            props.remove(repoId + "/" + name);
            save();
        }

        @Override
        public java.util.List<String> listRefs(String repoId) {
            return props.stringPropertyNames().stream()
                    .filter(k -> k.startsWith(repoId + "/"))
                    .map(k -> k.substring(repoId.length() + 1))
                    .sorted()
                    .toList();
        }
    }

    static final Path FIXTURE = Path.of("src", "test", "resources", "golden-store", "v-current");
    static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Test
    void regenerate() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("golden.regen"), "regen tool");
        if (Files.exists(FIXTURE)) {
            try (Stream<Path> walk = Files.walk(FIXTURE)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(FIXTURE);
        try (DirectBufferPool pool = new DirectBufferPool();
                FileNodeStore store = new FileNodeStore(FIXTURE.resolve("chunks"))) {
            Database db =
                    new Database(
                            store,
                            new FixtureManifest(FIXTURE.resolve("manifest.properties")),
                            "golden",
                            DESC,
                            pool);
            db.createBranch("main", "EMPTY");
            commit(db, pool, "alpha", "one");
            commit(db, pool, "beta", "two");
            Files.writeString(
                    FIXTURE.resolve("head.txt"),
                    java.util.HexFormat.of().formatHex(db.getHeadHash("main").orElseThrow()),
                    StandardCharsets.UTF_8);
        }
        Files.writeString(
                FIXTURE.resolve("README.md"),
                """
                # Golden storage fixture (v-current)
                Written by GoldenStoreFixtureRegen; opened by GoldenStoreOpenTest every build.
                A failing open test = the storage on-disk format changed (node chunks, Database
                commit encoding, branch refs): fix the code, or regenerate deliberately
                (mvn -pl prolly-storage test -Dtest=GoldenStoreFixtureRegen -Dgolden.regen=true)
                and write the CHANGELOG format note. head.txt records the expected main head at
                regen time (commit hashes embed timestamps — bytes churn per regen; the open
                test's semantic assertions are the review surface).
                """,
                StandardCharsets.UTF_8);
    }

    static void commit(Database db, DirectBufferPool pool, String key, String value)
            throws Exception {
        byte[] parent = db.getHeadHash("main").orElse(null);
        StaticMap base =
                parent == null ? new StaticMap(db.store(), null, DESC) : db.getBranch("main");
        MutableMap mm = new MutableMap(base, db.store(), DESC, pool);
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes(StandardCharsets.UTF_8));
        mm.put(tb.build().segment(), MemorySegment.ofArray(value.getBytes(StandardCharsets.UTF_8)));
        if (!db.commit("main", mm, parent, "golden", "put " + key)) {
            throw new IllegalStateException("golden commit failed for " + key);
        }
    }
}
