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

import org.junit.jupiter.api.Test;

/**
 * Asserts the <b>structural</b> invariants of the {@link BootstrapHashes} golden constants — the
 * properties any valid golden vector must hold regardless of the chunker's exact output.
 *
 * @apiNote <b>The byte-for-byte drift catch lives in {@link ChunkerDeterminismGateTest}, not
 *     here.</b> This test deliberately no longer asserts the constants against hand-copied literals
 *     of themselves — that was a tautological self-pin a chunker regression could not trip (it
 *     pinned the constant to a literal, never to the real chunker output), removed in Step 7 of
 *     {@code plans/prepublic/splitter-productionization.md}. {@link ChunkerDeterminismGateTest} now
 *     <i>recomputes</i> both goldens through the production {@link RollingHashSplitter} / {@link
 *     TreeMutator} and compares them to these constants, so a moved boundary or changed hash fails
 *     there. To re-derive the constants after a <i>deliberate</i> format change, run that gate —
 *     its assertion failure prints the recomputed actual values to paste back in. {@code
 *     CrossLanguageFixtureTest} consumes the same constants as the cross-language characterization
 *     oracle.
 */
class BootstrapHashesTest {

    @Test
    void boundary_golden_root_is_20_bytes() {
        assertTrue(
                BootstrapHashes.BOUNDARY_GOLDEN_ROOT.length == 20,
                "SHA-512/20 → 20 bytes always; any other length is a format change");
    }

    @Test
    void buzhash_offsets_are_strictly_increasing() {
        // A boundary set has positions: offsets must be unique and ascending.
        int[] offsets = BootstrapHashes.BOUNDARY_BUZHASH_OFFSETS;
        for (int i = 1; i < offsets.length; i++) {
            assertTrue(
                    offsets[i] > offsets[i - 1],
                    "boundary " + i + " (" + offsets[i] + ") must be after " + offsets[i - 1]);
        }
    }

    @Test
    void buzhash_offsets_fit_in_corpus_size() {
        // Corpus is 64 KiB = 65536; every offset must be inside it.
        for (int o : BootstrapHashes.BOUNDARY_BUZHASH_OFFSETS) {
            assertTrue(o >= 0 && o < 65536, "offset " + o + " out of corpus range");
        }
    }
}
