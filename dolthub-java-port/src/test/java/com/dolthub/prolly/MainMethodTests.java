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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 *
 *
 * <h3>Main-method Test Suite Driver</h3>
 *
 * <p>Bridges the project's main-method-style tests into JUnit 5 so {@code mvn test} actually runs
 * them. The driver scans the test classpath for any class ending in {@code "Test"} that exposes a
 * {@code public static void main(String[])} method, then emits one {@link DynamicTest} per match.
 * Classes whose name ends in {@code "Demo"} are excluded — they are illustrative and should not
 * gate CI.
 *
 * <p>Why this exists: every test in this codebase was written as a stand-alone {@code main()} that
 * throws on failure. Without this driver Surefire would discover zero tests and CI would silently
 * pass.
 */
public class MainMethodTests {

    @TestFactory
    Stream<DynamicTest> runAllMainMethodTests() throws Exception {
        return discoverMainMethodTestClasses().stream()
                .map(fqcn -> DynamicTest.dynamicTest(fqcn, () -> invokeMain(fqcn)));
    }

    /**
     * Pins that the driver still discovers the load-bearing splitter main-method tests <b>and</b>
     * that each declares a runnable static {@code main} — so they cannot silently go dormant (a
     * green build with an absent test, the exact trap this driver exists to prevent). A rename,
     * deletion, or removed {@code main} now fails the build instead of vanishing quietly. {@code
     * SplitterStressTest} + {@code ChunkerChaosTest} are the splitter's de-dormanted stress/chaos
     * coverage (splitter-productionization Step 6); both call the real {@link RollingHashSplitter}
     * / {@link TreeMutator}, not a reimplemented rule.
     */
    @Test
    void driver_runs_the_load_bearing_splitter_main_method_tests() throws Exception {
        List<String> discovered = discoverMainMethodTestClasses();
        for (String required :
                List.of(
                        "com.dolthub.prolly.SplitterStressTest",
                        "com.dolthub.prolly.ChunkerChaosTest")) {
            assertTrue(
                    discovered.contains(required),
                    required
                            + " not discovered by the main-method driver (dormant-test trap): "
                            + discovered);
            Method main =
                    assertDoesNotThrow(
                            () -> Class.forName(required).getMethod("main", String[].class),
                            required
                                    + " must declare public static void main, or the driver skips it");
            assertTrue(
                    Modifier.isStatic(main.getModifiers()),
                    required + " main(String[]) must be static or the driver silently skips it");
        }
    }

    /**
     * Scans the test classpath for {@code *Test} classes (excluding {@code Demo}s + this driver).
     */
    private static List<String> discoverMainMethodTestClasses() throws Exception {
        URL url = Thread.currentThread().getContextClassLoader().getResource("com/dolthub/prolly");
        if (url == null) return List.of();
        Path pkgRoot = Paths.get(url.toURI());

        List<String> classes = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(pkgRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Test.class"))
                    .filter(p -> !p.getFileName().toString().contains("Demo"))
                    .filter(p -> !p.getFileName().toString().equals("MainMethodTests.class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .sorted()
                    .forEach(
                            p ->
                                    classes.add(
                                            "com.dolthub.prolly."
                                                    + pkgRoot.relativize(p)
                                                            .toString()
                                                            .replace(
                                                                    java.io.File.separatorChar, '.')
                                                            .replaceFirst("\\.class$", "")));
        }
        return classes;
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
