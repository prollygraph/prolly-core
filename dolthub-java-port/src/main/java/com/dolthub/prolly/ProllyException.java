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
 * Root of the prolly engine's typed, operational-failure hierarchy: an unchecked exception a caller
 * can branch on to choose <i>retry</i> vs <i>alert-and-restore</i> vs <i>shed-load</i>, instead of
 * pattern-matching the message of an undifferentiated {@link RuntimeException}. Concrete leaves:
 * {@link ProllyCorruptionException} (the store returned bad bytes — alert + restore, do NOT retry),
 * {@link ProllyIoException} (a transient input/output failure — retry with backoff), and {@link
 * SpillQuotaExceededException} (a configured resource limit was hit — shed load / raise the quota /
 * batch the transaction).
 *
 * @apiNote Catch {@code ProllyException} to handle "any engine operational failure" uniformly, or a
 *     specific leaf to react precisely. <b>Caller-fault errors are deliberately NOT under this
 *     root</b>: bad arguments surface as {@link IllegalArgumentException} and use-after-close as
 *     {@code StoreClosedException} — those are programming bugs to fix at the call site, not
 *     operational conditions to retry or alert on, so {@code catch (ProllyException)} correctly
 *     does not swallow them. Unchecked so adding the type costs no signature churn across the
 *     engine — the value is the branchable type, not a checked contract.
 * @implNote Abstract: every engine failure picks a concrete category, so there is no "uncategorized
 *     {@code ProllyException}" that would erode the discipline. Lives in {@code com.dolthub.prolly}
 *     (the substrate) so {@code prolly-storage}'s {@code RocksNodeStore} — which depends on this
 *     module — can throw {@link ProllyCorruptionException} / {@link ProllyIoException}. Introduced
 *     by {@code core-error-taxonomy-and-failpaths.md} (D-1).
 */
public abstract class ProllyException extends RuntimeException {

    protected ProllyException(String message) {
        super(message);
    }

    protected ProllyException(String message, Throwable cause) {
        super(message, cause);
    }
}
