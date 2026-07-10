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
package com.earasoft.prolly.multistore;

/**
 * Lifecycle state of a repo in the {@link RepoRegistry}.
 *
 * <p>Legal transitions:
 *
 * <pre>
 *   ACTIVE → QUIESCING → DELETED
 * </pre>
 *
 * <p>CASed by the registry; consumers observe the current state via the registry's resolve /
 * state-introspection methods. Step 12 of the upstream multi-tenant hosting plan extends QUIESCING
 * to do real in-flight request drain (a per-repo {@link java.util.concurrent.Phaser}); Step 1 ships
 * the enum + the CAS plumbing only.
 */
public enum RepoLifecycleState {
    /** Normal serving state — reads + writes accepted. */
    ACTIVE,
    /**
     * Mid-delete: new requests get 410 Gone; in-flight requests drain before storage is dropped.
     */
    QUIESCING,
    /**
     * Storage has been removed; the repo no longer exists. Lookups for a DELETED repo return 404 as
     * if it had never existed.
     */
    DELETED
}
