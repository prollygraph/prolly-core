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
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

public class MetricsNodeStore implements NodeStore {
    private final NodeStore inner;
    private final LongAdder readCount = new LongAdder();
    private final LongAdder readBytes = new LongAdder();
    private final LongAdder writeCount = new LongAdder();
    private final LongAdder writeBytes = new LongAdder();

    public MetricsNodeStore(NodeStore inner) {
        this.inner = inner;
    }

    public NodeStore unwrap() {
        return inner;
    }

    @Override
    public Optional<MemorySegment> read(byte[] h) {
        readCount.increment();
        var r = inner.read(h);
        r.ifPresent(s -> readBytes.add(s.byteSize()));
        return r;
    }

    @Override
    public byte[] write(MemorySegment d) {
        writeCount.increment();
        writeBytes.add(d.byteSize());
        return inner.write(d);
    }

    @Override
    public byte[] write(byte[] d) {
        writeCount.increment();
        writeBytes.add(d.length);
        return inner.write(d);
    }

    public long getReadCount() {
        return readCount.sum();
    }

    public long getReadBytes() {
        return readBytes.sum();
    }

    public long getWriteCount() {
        return writeCount.sum();
    }

    public long getWriteBytes() {
        return writeBytes.sum();
    }
}
