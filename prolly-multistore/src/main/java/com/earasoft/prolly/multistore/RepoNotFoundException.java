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
 * Thrown when {@link RepoRegistry#resolve(String)} or {@link RepoRegistry#quiesce(String,
 * java.time.Duration)} is called for a {@code repoId} that is not in the registered set. The
 * controller layer maps this to a 404 response with {@code {error: "repo_not_found", repo}}.
 */
public class RepoNotFoundException extends RuntimeException {

    private final String repoId;

    public RepoNotFoundException(String repoId) {
        super("repo not found: " + repoId);
        this.repoId = repoId;
    }

    public String repoId() {
        return repoId;
    }
}
