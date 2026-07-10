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
 * Thrown when a transaction's spill-to-disk would push the process-global resident spill-disk bytes
 * past the configured quota ({@code prolly.spill.max-disk-bytes}) — the fail-closed guard ({@code
 * core-resource-bounds-and-metrics.md} D-2) that stops a runaway or un-batched transaction from
 * filling the spill temp filesystem and taking down every tenant. The transaction is aborted with
 * this clear, typed error rather than the disk silently filling; the operator raises the quota,
 * batches the transaction, or points {@code prolly.spill.temp-dir} at a larger disk. The quota is
 * checked <i>before</i> the run file is written, so a trip leaves no partial spill file behind.
 *
 * @apiNote A {@link ProllyException} (the resource-exhaustion member of the family), so a caller
 *     handling engine failures can catch the root — but it is operationally distinct from a
 *     retryable {@link ProllyIoException}: a blind retry hits the same quota, so the response is to
 *     shed load, raise the quota, or batch the transaction. Unchecked — it propagates out of {@code
 *     MutableMap.put} through the Sail write path to abort the transaction (whose rollback then
 *     cleans the buffer's already-spilled runs). The approach toward the quota is observable in
 *     advance via the {@code prolly.tx.spill.disk.bytes} gauge.
 */
public final class SpillQuotaExceededException extends ProllyException {

    public SpillQuotaExceededException(long residentBytes, long attemptedBytes, long quotaBytes) {
        super(
                String.format(
                        "spill-disk quota exceeded: %,d bytes already resident + %,d attempted > %,d quota"
                                + " (prolly.spill.max-disk-bytes). Raise the quota, batch the transaction, or point"
                                + " prolly.spill.temp-dir at a larger disk.",
                        residentBytes, attemptedBytes, quotaBytes));
    }
}
