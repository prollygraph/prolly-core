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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Rot guard for the repo's markdown documentation: every relative link and every cited repo path
 * must resolve against the working tree. The upstream monorepo's export de-links anything that
 * stays behind, so an unresolved reference here means a doc was moved/renamed without its citations
 * — fail, don't rot. (The upstream analogue is NewcomerDocsLinkTest, which deliberately does not
 * travel; the two-check shape is shared with the prolly-rdf ring's DocsLinkTest.)
 *
 * <p>Two checks:
 *
 * <ul>
 *   <li><b>Relative markdown links</b> ({@code [text](path)}) — the target must exist, fragment
 *       ignored; {@code http}/{@code https}/{@code mailto} skipped. Covers every {@code *.md} in
 *       the repo outside build output.
 *   <li><b>Backtick path citations</b> ({@code `module/src/.../Foo.java`}) — a token that starts
 *       with a top-level directory name and contains a slash must exist, resolved from any of three
 *       natural bases (repo root, the doc's directory, the doc's module root). Exempt: lines
 *       labeled {@code private monorepo} (deliberate references to the upstream work tracker),
 *       {@code docs/adr/} files (point-in-time records, never retroactively rewritten — their links
 *       are still checked), {@code target/} paths (build artifacts, absent after {@code mvn
 *       clean}), and elided ({@code ...}) or qualified ({@code repo:path}) forms.
 * </ul>
 */
class DocsLinkTest {

    private static final Pattern MD_LINK = Pattern.compile("\\]\\(([^)#\\s]+)(#[^)]*)?\\)");
    private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");

    @Test
    void everyRelativeDocLinkResolves() throws IOException {
        Path root = findRepoRoot();
        List<Path> docs = markdownFiles(root);
        assertTrue(docs.size() > 15, "expected the exported doc set, found " + docs.size());

        List<String> broken = new ArrayList<>();
        for (Path doc : docs) {
            String text = Files.readString(doc);
            Matcher m = MD_LINK.matcher(text);
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://")
                        || target.startsWith("https://")
                        || target.startsWith("mailto:")) continue;
                Path resolved = doc.getParent().resolve(target).normalize();
                if (!Files.exists(resolved)) {
                    broken.add(root.relativize(doc) + " -> " + target);
                }
            }
        }
        assertTrue(broken.isEmpty(), "broken doc links:\n  " + String.join("\n  ", broken));
    }

    @Test
    void citedRepoPathsExist() throws IOException {
        Path root = findRepoRoot();
        Set<String> topLevel;
        try (Stream<Path> entries = Files.list(root)) {
            topLevel = entries.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
        List<String> broken = new ArrayList<>();
        for (Path doc : markdownFiles(root)) {
            String rel = root.relativize(doc).toString();
            if (rel.contains("docs/adr/")) {
                continue; // point-in-time records; links still checked above
            }
            Path moduleRoot = root.resolve(rel.split("/", 2)[0]);
            for (String line : Files.readAllLines(doc)) {
                if (line.contains("private monorepo")) {
                    continue;
                }
                Matcher m = BACKTICK.matcher(line);
                while (m.find()) {
                    String token = m.group(1).trim();
                    if (!looksLikeRepoPath(token, topLevel)) {
                        continue;
                    }
                    if (line.contains("[`" + token + "`](")) {
                        continue; // the token is a markdown link's TEXT; the link target is
                        // already verified by everyRelativeDocLinkResolves
                    }
                    String clean =
                            token.endsWith("/") ? token.substring(0, token.length() - 1) : token;
                    boolean exists =
                            Files.exists(root.resolve(clean))
                                    || Files.exists(doc.getParent().resolve(clean))
                                    || (Files.isDirectory(moduleRoot)
                                            && Files.exists(moduleRoot.resolve(clean)));
                    if (!exists) {
                        broken.add(rel + " cites missing " + token);
                    }
                }
            }
        }
        assertTrue(
                broken.isEmpty(),
                "cited repo paths that do not exist:\n  " + String.join("\n  ", broken));
    }

    /** A slash-bearing token whose first segment is a real top-level entry, with no elision. */
    private static boolean looksLikeRepoPath(String token, Set<String> topLevel) {
        if (!token.contains("/") || token.contains("...") || token.contains(":")) {
            return false;
        }
        if (token.contains(" ") || token.contains("*") || token.contains("$")) {
            return false; // command lines, globs, shell fragments
        }
        if (token.startsWith("target/") || token.contains("/target/")) {
            return false; // build artifacts — transient by definition, absent after mvn clean
        }
        String first = token.split("/", 2)[0];
        return topLevel.contains(first);
    }

    private static List<Path> markdownFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.toString().contains("/node_modules/"))
                    .filter(p -> !p.toString().contains("/.git/"))
                    .filter(p -> !p.toString().contains("/__pycache__/"))
                    .sorted()
                    .toList();
        }
    }

    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            // prolly-dependencies/ disambiguates the repo root from the module dir,
            // which also has both a docs/ dir and a pom.xml
            if (Files.isDirectory(dir.resolve("docs"))
                    && Files.exists(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("prolly-dependencies"))) {
                return dir;
            }
        }
        throw new IllegalStateException("repo root with docs/ not found");
    }
}
