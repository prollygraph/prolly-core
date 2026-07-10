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

import com.dolthub.prolly.BoundarySplitter;
import com.dolthub.prolly.RollingHashSplitter;
import java.io.FileWriter;
import java.lang.foreign.MemorySegment;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits chunk-boundary offsets from the REAL production {@link RollingHashSplitter} so the Python
 * port in {@code prolly_chunking.py} can assert byte-identical cuts (the cross-language fixture
 * pattern; see {@code cross-lang/} in the repo root).
 *
 * <p>The corpus is a SHA-256 counter stream — {@code SHA256(LE32(0)) || SHA256(LE32(1)) || …} —
 * chosen because both sides can regenerate it deterministically with no shared random-number
 * generator. Entries are fed at two granularities (1-byte and 32-byte keys, null values) for tree
 * levels 0..2, pinning the buzhash mechanics, the per-level salt, the progressive-mask staircase,
 * and the between-entries boundary consultation all at once.
 *
 * <p>Run from {@code prolly-python-notebooks/}:
 *
 * <pre>
 *   java -cp ~/.m2/repository/io/github/prollygraph/dolthub-java-port/0.2.0-BETA/dolthub-java-port-0.2.0-BETA.jar \
 *        fixtures/EmitBoundaryFixture.java
 * </pre>
 *
 * (or {@code make fixture}). Output: {@code fixtures/boundaries.json}, committed so the Python
 * tests run without a JVM; re-run only when the splitter changes (which is format-level news).
 */
public final class EmitBoundaryFixture {

    private static final int CORPUS_BYTES = 300_000;

    public static void main(String[] args) throws Exception {
        byte[] corpus = sha256CounterStream(CORPUS_BYTES);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"corpus\": \"sha256-counter-stream\",\n");
        json.append("  \"corpusBytes\": ").append(CORPUS_BYTES).append(",\n");
        json.append("  \"cases\": [\n");
        boolean first = true;
        for (int level : new int[] {0, 1, 2}) {
            for (int entrySize : new int[] {1, 32}) {
                List<Integer> ends = chunkEndOffsets(corpus, level, entrySize);
                if (!first) json.append(",\n");
                first = false;
                json.append("    {\"level\": ").append(level)
                    .append(", \"entrySize\": ").append(entrySize)
                    .append(", \"chunkEnds\": ").append(ends).append("}");
            }
        }
        json.append("\n  ]\n}\n");

        try (FileWriter w = new FileWriter("fixtures/boundaries.json")) {
            w.write(json.toString());
        }
        System.out.println("wrote fixtures/boundaries.json");
    }

    /** Absolute end offset (exclusive) of each closed chunk, consulting between entries. */
    private static List<Integer> chunkEndOffsets(byte[] corpus, int level, int entrySize) {
        BoundarySplitter sp = new RollingHashSplitter(level);
        List<Integer> ends = new ArrayList<>();
        int pos = 0;
        while (pos < corpus.length) {
            int n = Math.min(entrySize, corpus.length - pos);
            byte[] entry = new byte[n];
            System.arraycopy(corpus, pos, entry, 0, n);
            sp.append(MemorySegment.ofArray(entry), null);
            pos += n;
            if (sp.crossedBoundary()) {
                ends.add(pos);
                sp.reset();
            }
        }
        return ends;
    }

    private static byte[] sha256CounterStream(int n) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] out = new byte[n];
        int pos = 0;
        int counter = 0;
        while (pos < n) {
            byte[] ctr = new byte[4];
            ctr[0] = (byte) counter;
            ctr[1] = (byte) (counter >>> 8);
            ctr[2] = (byte) (counter >>> 16);
            ctr[3] = (byte) (counter >>> 24);
            byte[] block = sha.digest(ctr);
            int take = Math.min(block.length, n - pos);
            System.arraycopy(block, 0, out, pos, take);
            pos += take;
            counter++;
        }
        return out;
    }

    private EmitBoundaryFixture() {}
}
