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
package com.earasoft.prolly.sync.grpc;

/**
 * Resource caps for a pack crossing the wire — the RESOURCE half of the untrusted-byte boundary.
 *
 * @apiNote {@code maxBytes} is enforced while frames accumulate, BEFORE any parse; {@code
 *     maxChunks} after parse (a parse over a byte-capped input is bounded work). Integrity — every
 *     chunk's content address — is the codec's job at parse and is not configurable here. Both the
 *     server ({@code ReceivePack}) and the client ({@code FetchPack} reassembly) enforce the same
 *     limits: a hostile SERVER is also untrusted input.
 * @param maxChunks maximum chunks a pack may carry
 * @param maxBytes maximum serialized pack size in bytes
 */
public record PackLimits(int maxChunks, long maxBytes) {

    public PackLimits {
        if (maxChunks <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }

    /** The defaults the in-process protocol's consumers use: 1M chunks, 1 GiB. */
    public static PackLimits defaults() {
        return new PackLimits(1_000_000, 1L << 30);
    }
}
