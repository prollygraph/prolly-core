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
package com.earasoft.prolly.monitor;

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 *
 *
 * <h3>MetricsNodeStore Decorator Test</h3>
 *
 * <p>Pins the observability contract of {@link MetricsNodeStore}: every read / write through it
 * must increment the corresponding counter, every byte that passes through must be reflected in the
 * byte sums, and {@code unwrap()} returns the underlying store unchanged.
 *
 * <p><b>The Gap:</b> until now, {@code MetricsNodeStore} had zero direct test references — only
 * incidental coverage via {@code MonitoringDemo}. JMX counters are easy to break silently (forget
 * to increment in one of the two {@code write} overloads, or accidentally double-count in {@code
 * IntegrityVerifyingNodeStore} when it composes with the metrics decorator). This test pins the
 * per-overload increments and the read-miss case where {@code readCount} advances but {@code
 * readBytes} does not.
 */
public class MetricsNodeStoreTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- MetricsNodeStore Decorator Test ---");
        Path tempDir = Files.createTempDirectory("prolly-metrics");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore inner = new RocksNodeStore(tempDir.toString())) {

            MetricsNodeStore mx = new MetricsNodeStore(inner);

            // Oracle 1: unwrap returns inner.
            if (mx.unwrap() != inner) {
                throw new RuntimeException("unwrap() returned wrong instance");
            }
            System.out.println("unwrap() returns underlying store. (1/5)");

            // Oracle 2: write(byte[]) increments writeCount + writeBytes by length.
            byte[] payload1 = "hello-world".getBytes();
            byte[] hash1 = mx.write(payload1);
            if (mx.getWriteCount() != 1) {
                throw new RuntimeException(
                        "writeCount=" + mx.getWriteCount() + " after 1 write(byte[])");
            }
            if (mx.getWriteBytes() != payload1.length) {
                throw new RuntimeException(
                        "writeBytes=" + mx.getWriteBytes() + " expected " + payload1.length);
            }
            System.out.println("write(byte[]) increments correctly. (2/5)");

            // Oracle 3: write(MemorySegment) increments by segment.byteSize().
            byte[] payload2 = new byte[1024];
            Arrays.fill(payload2, (byte) 0x42);
            MemorySegment seg = MemorySegment.ofArray(payload2);
            byte[] hash2 = mx.write(seg);
            if (mx.getWriteCount() != 2) {
                throw new RuntimeException("writeCount=" + mx.getWriteCount() + " after 2 writes");
            }
            if (mx.getWriteBytes() != payload1.length + payload2.length) {
                throw new RuntimeException(
                        "writeBytes="
                                + mx.getWriteBytes()
                                + " expected "
                                + (payload1.length + payload2.length));
            }
            System.out.println("write(MemorySegment) increments correctly. (3/5)");

            // Oracle 4: read(hit) increments both readCount and readBytes;
            // read(miss) increments only readCount.
            Optional<MemorySegment> hit = mx.read(hash1);
            if (hit.isEmpty())
                throw new RuntimeException("Read of just-written hash returned empty");
            if (mx.getReadCount() != 1) throw new RuntimeException("readCount after 1 read != 1");
            if (mx.getReadBytes() != payload1.length) {
                throw new RuntimeException("readBytes after hit != payload1.length");
            }

            byte[] missHash = new byte[20];
            Arrays.fill(missHash, (byte) 0xAA);
            Optional<MemorySegment> miss = mx.read(missHash);
            if (miss.isPresent())
                throw new RuntimeException("Read of non-existent hash returned data");
            if (mx.getReadCount() != 2) throw new RuntimeException("readCount after miss != 2");
            // readBytes should NOT have advanced on the miss.
            if (mx.getReadBytes() != payload1.length) {
                throw new RuntimeException(
                        "readBytes incorrectly advanced on miss: "
                                + mx.getReadBytes()
                                + " expected "
                                + payload1.length);
            }
            System.out.println("read counters: hit increments bytes, miss does not. (4/5)");

            // Oracle 5: hashes returned by the metrics decorator equal what the inner
            // store would have produced — i.e. the decorator is byte-transparent.
            byte[] innerHash1 = inner.write(payload1);
            byte[] innerHash2 = inner.write(seg);
            if (!Arrays.equals(hash1, innerHash1) || !Arrays.equals(hash2, innerHash2)) {
                throw new RuntimeException(
                        "Decorator changed the content-addressing — hashes through MetricsNodeStore "
                                + "differ from direct inner-store hashes");
            }
            System.out.println("Decorator is byte-transparent (re-writes hash identically). (5/5)");

            System.out.println("--- MetricsNodeStore Decorator Test PASSED ---");
        }
    }
}
