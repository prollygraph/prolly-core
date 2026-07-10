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
 * Thrown by {@link RepoNameValidator} when a repo name fails well-formedness validation. The {@link
 * #repoId()} + {@link #reason()} power the structured 400 response body {@code
 * {error:"invalid_repo_name", repo, reason}} (Step 3 of the upstream multi-tenant hosting plan).
 */
public class RepoNameInvalidException extends RuntimeException {

    private final String repoId;
    private final String reason;

    public RepoNameInvalidException(String repoId, String reason) {
        super("invalid repoId='" + repoId + "': " + reason);
        this.repoId = repoId;
        this.reason = reason;
    }

    public String repoId() {
        return repoId;
    }

    public String reason() {
        return reason;
    }
}
