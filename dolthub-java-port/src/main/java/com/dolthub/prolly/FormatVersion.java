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
 * The single on-disk/wire format-version line for the prolly engine's core serialized types. Bumped
 * <b>once, coordinated</b> across a format change ({@code core-format-versioning.md} D-2) — the
 * store marker, the commit record, and the node format all stamp this one number and reject
 * anything not equal, so a wrong/future-format blob fails closed (a typed {@link
 * UnsupportedFormatException}) instead of being silently misparsed.
 *
 * @apiNote Pre-1.0 there is <b>no migration shim</b>: an incompatible store is handled by back-up +
 *     restore (the project is beta; every user can {@code tar} the store), not a defensive reader
 *     that accepts old shapes.
 * @implNote A single constant, not per-type versions, so the format is one auditable line rather
 *     than a creep of independently-drifting numbers.
 */
public final class FormatVersion {

    /** The current core format version. Bumped only by a coordinated, format-breaking change. */
    public static final int CORE_FORMAT_VERSION = 1;

    private FormatVersion() {}
}
