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

import com.dolthub.prolly.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 *
 *
 * <h3>Fault Injection Wrapper</h3>
 *
 * <p>A decorator for {@link NodeStore} used in tests to simulate storage failures mid-operation.
 */
public class ErrorInjectingNodeStore implements NodeStore {
    private final NodeStore inner;
    private int countdown = -1;

    public ErrorInjectingNodeStore(NodeStore inner) {
        this.inner = inner;
    }

    public void injectErrorAfter(int n) {
        this.countdown = n;
    }

    private void checkError() {
        if (countdown > 0) {
            countdown--;
            if (countdown == 0) throw new RuntimeException("Injected IO Failure");
        }
    }

    @Override
    public Optional<MemorySegment> read(byte[] hash) {
        checkError();
        return inner.read(hash);
    }

    @Override
    public byte[] write(MemorySegment data) {
        checkError();
        return inner.write(data);
    }

    @Override
    public byte[] write(byte[] data) {
        checkError();
        return inner.write(data);
    }
}
