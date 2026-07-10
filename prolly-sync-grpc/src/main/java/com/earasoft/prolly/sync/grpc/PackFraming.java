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

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Frame slicing + limit-guarded reassembly for the streamed {@code SyncPackCodec} bytes — one
 * implementation shared by both directions ({@code FetchPack} server→client, {@code ReceivePack}
 * client→server).
 *
 * @implNote The frame size stays comfortably under gRPC's default 4 MiB inbound message cap so
 *     neither side needs channel/server message-size configuration — removing that footgun is the
 *     point of framing. The accumulator enforces {@link PackLimits#maxBytes} on every append,
 *     BEFORE any parse; the caller maps a breach to {@code RESOURCE_EXHAUSTED}.
 */
final class PackFraming {

    /** 1 MiB frames — 4× headroom under gRPC's default message cap. */
    static final int FRAME_BYTES = 1 << 20;

    private PackFraming() {}

    /** Slice {@code bytes} into ≤{@link #FRAME_BYTES} frames (an empty pack yields no frames). */
    static List<ByteString> slice(byte[] bytes) {
        List<ByteString> frames = new ArrayList<>((bytes.length / FRAME_BYTES) + 1);
        for (int off = 0; off < bytes.length; off += FRAME_BYTES) {
            frames.add(ByteString.copyFrom(bytes, off, Math.min(FRAME_BYTES, bytes.length - off)));
        }
        return frames;
    }

    /** A byte accumulator that refuses to grow past {@code limits.maxBytes()}. */
    static final class Accumulator {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final PackLimits limits;

        Accumulator(PackLimits limits) {
            this.limits = limits;
        }

        /**
         * @throws PackTooLargeException when the accumulated size would exceed the byte cap
         */
        void append(ByteString frame) {
            if (buf.size() + (long) frame.size() > limits.maxBytes()) {
                throw new PackTooLargeException(
                        "pack exceeds the " + limits.maxBytes() + "-byte limit");
            }
            try {
                frame.writeTo(buf);
            } catch (java.io.IOException impossible) {
                // ByteArrayOutputStream never throws
                throw new IllegalStateException(impossible);
            }
        }

        byte[] toByteArray() {
            return buf.toByteArray();
        }
    }

    /** A limits breach — the caller maps it to {@code RESOURCE_EXHAUSTED}. */
    static final class PackTooLargeException extends RuntimeException {
        PackTooLargeException(String message) {
            super(message);
        }
    }
}
