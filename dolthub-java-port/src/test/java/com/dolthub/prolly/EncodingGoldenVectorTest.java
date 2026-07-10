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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * I-5 per-Encoding golden vectors (plans/core-engine-test-strategy.md Step 17).
 *
 * <p>A checked-in table of (Encoding, value → exact encoded bytes) for the order-preserving codec
 * transforms ({@code TypeCodec.encodeInt64} / {@code encodeFloat64} — the subtle, drift-prone
 * sign-flip-to-big-endian functions). Pins the codec's byte output as a regression net: any change
 * to the encoding fails here loudly.
 *
 * <p><b>Bit-compat framing (the bit-compat decision, made the reversible way):</b> the bytes pinned
 * here are <em>Java-self-consistent characterization</em>, NOT a claim of Go parity — the port is
 * not byte-compatible with Dolt v2.0.3 (the Layer-3 tuple-offset divergence in {@code
 * cross-lang/BITCOMPAT_FINDINGS.md}). If a future ADR decides Dolt parity IS a goal, this same
 * table becomes the cross-check target (regenerate from Go, diff). Until then it offline-pins the
 * port's own format with no Go needed at test time.
 *
 * <p><b>Self-bootstrapping</b> (the golden-vector pattern): if the golden file is absent, the test
 * writes it from the current encoder and passes (bootstrap); on every subsequent run it asserts the
 * encoder still produces those exact bytes. The file lives at {@code
 * cross-lang/fixtures/encoding-vectors.txt} — located by walking up from the module-dir working
 * directory to the repo root (surefire's cwd is the module, the fixtures live at the root — the
 * same trap that silently no-op'd CrossLanguageFixtureTest).
 */
class EncodingGoldenVectorTest {

    // Boundary values, ASCENDING by semantic order (so the encoded bytes must
    // come out ascending under unsigned-byte compare — the whole point of the
    // order-preserving transform).
    private static final long[] INT64 = {
        Long.MIN_VALUE, -1L << 32, -2, -1, 0, 1, 2, 1L << 32, 0x0123456789ABCDEFL, Long.MAX_VALUE
    };
    private static final double[] FLOAT64 = {
        Double.NEGATIVE_INFINITY,
        -Double.MAX_VALUE,
        -1.0,
        -Double.MIN_VALUE,
        -0.0,
        0.0,
        Double.MIN_VALUE,
        1.0,
        Double.MAX_VALUE,
        Double.POSITIVE_INFINITY,
        Double.NaN // NaN last — has no order; excluded from the ordering check
    };

    private static String encInt64(long v) {
        MemorySegment s = MemorySegment.ofArray(new byte[8]);
        TypeCodec.encodeInt64(v, s);
        return HashUtils.toHex(s.toArray(ValueLayout.JAVA_BYTE));
    }

    private static String encFloat64(double v) {
        MemorySegment s = MemorySegment.ofArray(new byte[8]);
        TypeCodec.encodeFloat64(v, s);
        return HashUtils.toHex(s.toArray(ValueLayout.JAVA_BYTE));
    }

    /** The current encoder's output, one "Encoding label hex" line per vector. */
    private static List<String> computeVectors() {
        List<String> out = new ArrayList<>();
        for (long v : INT64) out.add("Int64 " + Long.toString(v) + " " + encInt64(v));
        for (double v : FLOAT64) out.add("Float64 " + Double.toString(v) + " " + encFloat64(v));
        return out;
    }

    private static Path locateFixturesDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("cross-lang").resolve("fixtures");
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    @Test
    void encodedBytesMatchTheCheckedInGolden() throws Exception {
        Path fixtures = locateFixturesDir();
        assertNotNull(
                fixtures,
                "cross-lang/fixtures not found by walking up from "
                        + Paths.get("").toAbsolutePath());
        Path goldenFile = fixtures.resolve("encoding-vectors.txt");

        List<String> computed = computeVectors();

        if (!Files.exists(goldenFile)) {
            // Bootstrap: write the golden from the current encoder, then pass.
            List<String> lines = new ArrayList<>();
            lines.add(
                    "# Per-Encoding golden vectors — Java-self-consistent characterization (Step 17).");
            lines.add(
                    "# Format: <Encoding> <valueLabel> <hex of the 8-byte order-preserving encoding>.");
            lines.add(
                    "# NOT a Go-parity claim (port != Dolt v2.0.3 per BITCOMPAT_FINDINGS.md, Layer 3).");
            lines.add("# Regenerate: delete this file and run EncodingGoldenVectorTest.");
            lines.addAll(computed);
            Files.write(goldenFile, lines, StandardCharsets.UTF_8);
            System.out.println(
                    "EncodingGoldenVectorTest: bootstrapped "
                            + goldenFile
                            + " ("
                            + computed.size()
                            + " vectors). Commit it; subsequent runs verify.");
            return;
        }

        List<String> golden = new ArrayList<>();
        for (String line : Files.readAllLines(goldenFile, StandardCharsets.UTF_8)) {
            if (!line.startsWith("#") && !line.isBlank()) golden.add(line);
        }
        assertEquals(
                golden,
                computed,
                "codec byte output drifted from the checked-in golden vectors — if this is an "
                        + "intentional encoding change, delete "
                        + goldenFile
                        + " and regenerate.");
    }

    @Test
    void int64EncodingIsOrderPreserving() {
        for (int i = 0; i + 1 < INT64.length; i++) {
            byte[] a = hexToBytes(encInt64(INT64[i]));
            byte[] b = hexToBytes(encInt64(INT64[i + 1]));
            assertTrue(
                    compareUnsigned(a, b) < 0,
                    "encodeInt64 must preserve ascending order: "
                            + INT64[i]
                            + " < "
                            + INT64[i + 1]);
        }
    }

    @Test
    void float64EncodingIsOrderPreserving() {
        // Exclude the trailing NaN (no meaningful order).
        for (int i = 0; i + 2 < FLOAT64.length; i++) {
            byte[] a = hexToBytes(encFloat64(FLOAT64[i]));
            byte[] b = hexToBytes(encFloat64(FLOAT64[i + 1]));
            assertTrue(
                    compareUnsigned(a, b) < 0,
                    "encodeFloat64 must preserve ascending order: "
                            + FLOAT64[i]
                            + " < "
                            + FLOAT64[i + 1]);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int c = Integer.compareUnsigned(a[i] & 0xff, b[i] & 0xff);
            if (c != 0) return c;
        }
        return Integer.compare(a.length, b.length);
    }
}
