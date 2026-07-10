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
import java.util.Arrays;
import java.util.Optional;

/**
 *
 *
 * <h3>Integrity Verifying Wrapper</h3>
 *
 * <p>A decorator for {@link NodeStore} that re-hashes every read to ensure it matches the requested
 * content hash. <b>Important for New Team Members:</b>
 *
 * <p>This is the first line of defense against hardware failure or corruption in the storage layer.
 * It prevents invalid nodes from poisoning the Merkle DAG.
 */
public class IntegrityVerifyingNodeStore implements NodeStore {
    private final NodeStore inner;

    public IntegrityVerifyingNodeStore(NodeStore inner) {
        this.inner = inner;
    }

    /** Returns the underlying store so callers can unwrap decorator chains. */
    public NodeStore unwrap() {
        return inner;
    }

    @Override
    public Optional<MemorySegment> read(byte[] hash) {
        Optional<MemorySegment> data = inner.read(hash);
        if (data.isPresent()) {
            byte[] actualHash = HashUtils.hash(data.get().asByteBuffer());
            if (!Arrays.equals(hash, actualHash)) {
                throw new ProllyCorruptionException(
                        "DATA CORRUPTION DETECTED at hash "
                                + toHex(hash)
                                + " — actual content hashes to "
                                + toHex(actualHash));
            }
        }
        return data;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Override
    public byte[] write(MemorySegment data) {
        return inner.write(data);
    }

    @Override
    public byte[] write(byte[] data) {
        return inner.write(data);
    }
}
