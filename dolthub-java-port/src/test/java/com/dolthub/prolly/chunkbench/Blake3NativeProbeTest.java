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
package com.dolthub.prolly.chunkbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.Security;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The correctness pin for the hash-function study's NATIVE arm ({@link
 * Blake3NativeThroughputBench}): the official C {@code libblake3}, loaded through Panama Foreign
 * Function &amp; Memory downcalls, must agree BYTE-FOR-BYTE with BouncyCastle's independent Java
 * BLAKE3 across sizes spanning the chunk range — two implementations from two codebases agreeing is
 * the cross-check that makes the native bench numbers trustworthy.
 *
 * <p>SKIPS (does not fail) when {@code -Dblake3.lib} is unset or the library is absent — the native
 * lib is a bench-host artifact (the Arch-packaged official build under {@code ~/.local/lib}, its
 * TBB dependency on {@code LD_LIBRARY_PATH}), not a build dependency; a plain {@code mvn test} on a
 * machine without it stays green. (Named {@code *Test}, not the probe's scratch name — a {@code
 * Blake3ProbeNative.java} would be silently undiscovered by Surefire's include patterns, the
 * dormant-test trap.)
 */
class Blake3NativeProbeTest {

    @Test
    void nativeLibblake3_agreesWithBouncyCastle_acrossChunkSizes() throws Throwable {
        String libPath = System.getProperty("blake3.lib");
        assumeTrue(
                libPath != null && Files.exists(Path.of(libPath)),
                "native libblake3 not configured (-Dblake3.lib) — probe skipped");

        if (Security.getProvider("BC") == null) {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
        MessageDigest bc = MessageDigest.getInstance("BLAKE3-256");

        Linker linker = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(libPath, Arena.global());
        MethodHandle init =
                linker.downcallHandle(
                        lib.find("blake3_hasher_init").orElseThrow(),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        MethodHandle update =
                linker.downcallHandle(
                        lib.find("blake3_hasher_update").orElseThrow(),
                        FunctionDescriptor.ofVoid(
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        MethodHandle finalize =
                linker.downcallHandle(
                        lib.find("blake3_hasher_finalize").orElseThrow(),
                        FunctionDescriptor.ofVoid(
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment hasher = arena.allocate(2048); // sizeof(blake3_hasher) = 1912
            MemorySegment out = arena.allocate(32);

            // The known-input smoke plus randomized inputs at chunk-relevant sizes.
            byte[][] inputs = new byte[5][];
            inputs[0] = "ab".getBytes(StandardCharsets.UTF_8);
            SplittableRandom rnd = new SplittableRandom(0x5EED_CAFE_F00DL);
            int[] sizes = {64, 512, 4096, 16384};
            for (int i = 0; i < sizes.length; i++) {
                inputs[i + 1] = new byte[sizes[i]];
                rnd.nextBytes(inputs[i + 1]);
            }

            for (byte[] input : inputs) {
                MemorySegment data = arena.allocate(Math.max(1, input.length));
                MemorySegment.copy(input, 0, data, ValueLayout.JAVA_BYTE, 0, input.length);
                init.invoke(hasher);
                update.invoke(hasher, data, (long) input.length);
                finalize.invoke(hasher, out, 32L);
                byte[] nativeDigest = new byte[32];
                MemorySegment.copy(out, ValueLayout.JAVA_BYTE, 0, nativeDigest, 0, 32);
                assertArrayEquals(
                        bc.digest(input),
                        nativeDigest,
                        "native libblake3 and BouncyCastle diverged at " + input.length + " bytes");
            }
        }
    }
}
