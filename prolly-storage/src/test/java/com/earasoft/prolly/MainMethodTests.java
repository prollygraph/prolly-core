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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 *
 *
 * <h3>Main-method Test Suite Driver (prolly-storage substrate copy)</h3>
 *
 * <p>Bridges the substrate's main-method-style tests into JUnit 5 so {@code mvn test} actually runs
 * them. The driver scans the test classpath for any class ending in {@code "Test"} that exposes a
 * {@code public static void main(String[])} method, then emits one {@link DynamicTest} per match.
 * Classes whose name ends in {@code "Demo"} are excluded — they are illustrative and should not
 * gate CI.
 *
 * <p>This is the substrate-module sibling of an upstream {@code MainMethodTests}: the
 * versioned-store substrate moved to {@code prolly-storage} per an upstream extraction decision,
 * and several of its tests ({@code ErrorInjectingNodeStoreTest}, {@code
 * ParallelReachabilityWalkerTest}, {@code TreeIntegrityCheckerTest}, {@code VCUtilsBlameTest},
 * {@code RocksManifestTest}, {@code MetricsNodeStoreTest}, {@code DirectBufferPoolMxBeanTest}) are
 * written as stand-alone {@code main()} walkthroughs — so the driver has to travel with them (D-5),
 * or those smoke tests would silently run nowhere. The driver scans only THIS module's own package
 * root, so it never reaches the RDF tests that stayed behind. (The upstream original carried eight
 * vestigial wildcard imports — reflection needs none of them; this copy keeps only what the code
 * uses.)
 */
public class MainMethodTests {

    @TestFactory
    Stream<DynamicTest> runAllMainMethodTests() throws Exception {
        // Scan THIS module's own package root (com.earasoft.prolly). If the
        // classes are resolved from a JAR (Paths.get(URI) would throw
        // FileSystemNotFoundException), skip rather than fail; under a normal
        // `mvn test` the test-classes directory is a "file:" URL.
        URL url = Thread.currentThread().getContextClassLoader().getResource("com/earasoft/prolly");
        if (url == null) return Stream.empty();
        if (!"file".equals(url.getProtocol())) return Stream.empty();
        Path pkgRoot = Paths.get(url.toURI());

        // Optional focus: -Dprolly.mainmethod.only=<SimpleClassName> runs just
        // that one main-method test. Unset = run all.
        String only = System.getProperty("prolly.mainmethod.only");

        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(pkgRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Test.class"))
                    .filter(p -> !p.getFileName().toString().contains("Demo"))
                    .filter(p -> !p.getFileName().toString().equals("MainMethodTests.class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .filter(
                            p ->
                                    only == null
                                            || only.isBlank()
                                            || p.getFileName().toString().equals(only + ".class"))
                    .sorted()
                    .forEach(
                            p -> {
                                String fqcn =
                                        "com.earasoft.prolly."
                                                + pkgRoot.relativize(p)
                                                        .toString()
                                                        .replace(java.io.File.separatorChar, '.')
                                                        .replaceFirst("\\.class$", "");
                                tests.add(DynamicTest.dynamicTest(fqcn, () -> invokeMain(fqcn)));
                            });
        }
        return tests.stream();
    }

    private static void invokeMain(String fqcn) throws Throwable {
        Class<?> cls = Class.forName(fqcn);
        Method main;
        try {
            main = cls.getMethod("main", String[].class);
        } catch (NoSuchMethodException nsme) {
            return; // class matched the *Test pattern but isn't a main-style test
        }
        if (!Modifier.isStatic(main.getModifiers())) return;
        try {
            main.invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException ite) {
            throw ite.getCause() != null ? ite.getCause() : ite;
        }
    }
}
