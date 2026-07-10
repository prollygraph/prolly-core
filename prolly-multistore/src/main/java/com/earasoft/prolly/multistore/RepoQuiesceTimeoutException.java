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

import java.time.Duration;

/**
 * Thrown when a repo teardown could not quiesce — in-flight leases (an active read/write/commit)
 * did not drain within the bounded timeout, so the teardown refuses to proceed rather than delete
 * the repo's storage out from under a live operation.
 *
 * @apiNote The faces map this to HTTP 409 ({@code repo_busy}): the repo is intact and the caller
 *     can retry once the in-flight work finishes. It is never correct to force-delete past this —
 *     that is the directory-removed-under-a-live-write race that crashes the server (a native
 *     SIGSEGV).
 * @implNote Unchecked, to match the other repo-lifecycle exceptions ({@link RepoNotFoundException}
 *     etc.). Raised by {@code RepoSailRegistry.quiesce} when the bounded drain wait elapses with
 *     leases still held; see the upstream quiesce plan D-2.
 */
public final class RepoQuiesceTimeoutException extends RuntimeException {
    public RepoQuiesceTimeoutException(String repoId, Duration timeout, int remainingLeases) {
        super(
                "repo '"
                        + repoId
                        + "' did not quiesce within "
                        + timeout.toMillis()
                        + "ms ("
                        + remainingLeases
                        + " in-flight lease(s) still held) — not deleting under live use");
    }
}
