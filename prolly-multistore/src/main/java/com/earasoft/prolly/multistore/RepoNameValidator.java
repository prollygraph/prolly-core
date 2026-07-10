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

import java.util.regex.Pattern;

/**
 * Syntax validation for repo names: enforces well-formedness (regex + length + no path-traversal
 * characters). Called from the controller boundary (the {@code RepoRoutingInterceptor}) and by the
 * per-repo storage factory to reject malformed input BEFORE any filesystem / CF-name composition.
 *
 * <p>Deliberately knows nothing about which names a hosting product RESERVES — that is routing
 * policy, not syntax; see {@code ReservedRepoNames} (platform) for the creation-time reserved-name
 * rejection layered on top of {@link #validate}.
 *
 * <p>The regex is {@code ^[a-z][a-z0-9-]{0,62}$}: lowercase ASCII, digits, hyphens; starts with a
 * letter; max 63 chars (matches DNS subdomain length in case future operators want subdomain
 * routing, per D-1's Option C re-eval note).
 *
 * <p>Step 7 of the upstream multi-tenant hosting plan; forward- pulled into Step 3 because the
 * path-routing interceptor needs the request-time validator.
 */
public final class RepoNameValidator {

    /** Lowercase ASCII + digits + hyphens; starts with letter; max 63 chars. */
    public static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");

    private RepoNameValidator() {}

    /**
     * Well-formedness check only. Accepts names a product may treat as reserved, because requests
     * may legitimately target them (e.g. {@code /repos/default/sparql/...}).
     *
     * @throws RepoNameInvalidException on malformed input. Structured {@code reason()} indicates
     *     the failure mode for the 400 response.
     */
    public static void validate(String repoId) {
        if (repoId == null) {
            throw new RepoNameInvalidException(repoId, "null repoId");
        }
        if (repoId.isEmpty()) {
            throw new RepoNameInvalidException(repoId, "empty repoId");
        }
        if (repoId.length() > 63) {
            throw new RepoNameInvalidException(
                    repoId, "repoId too long (max 63 chars, got " + repoId.length() + ")");
        }
        // Catch path-traversal shapes that Spring's path-variable
        // decoder may pass through. The regex below rejects them too,
        // but explicit named errors help debugging.
        if (repoId.contains("..")
                || repoId.contains("/")
                || repoId.contains("\\")
                || repoId.indexOf('\0') >= 0) {
            throw new RepoNameInvalidException(repoId, "repoId contains path-traversal characters");
        }
        if (!PATTERN.matcher(repoId).matches()) {
            throw new RepoNameInvalidException(repoId, "repoId must match " + PATTERN.pattern());
        }
    }
}
