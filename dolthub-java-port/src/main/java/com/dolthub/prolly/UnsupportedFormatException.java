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

/**
 * A serialized blob or store declares a format version this engine does not support — a wrong or
 * future format, caught by the version/magic check on read ({@code core-format-versioning.md}). The
 * bytes are not necessarily corrupt (they may hash fine); they are a version this engine cannot
 * interpret, so reading them would be a <i>silent misparse</i> (an arbitrary-bytes root hash, a
 * fallback to a legacy serializer). Fail closed instead, naming the offending + expected versions.
 *
 * @apiNote A {@link ProllyException} so a caller handling engine failures catches the root — but
 *     operationally distinct from {@link ProllyCorruptionException} (bytes that fail their content
 *     hash) and {@link ProllyIoException} (a transient input/output error): the response is to run
 *     a matching engine version or restore the store from backup, not to retry.
 * @implNote Thrown by the store-open format check and (future steps) the commit/node version
 *     checks. Introduced by {@code core-format-versioning.md}.
 */
public final class UnsupportedFormatException extends ProllyException {

    public UnsupportedFormatException(String message) {
        super(message);
    }
}
