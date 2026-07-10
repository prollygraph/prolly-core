# ADR-0075: Adopt SHA-256/20 content addresses at the next format-breaking release

## Status

**Proposed**, 2026-07-17 — drafted from the hash-function study's measurements
(`docs/write-ups/hash-function-study.md`); acceptance is an owner decision because the
flip is format policy. Canonical copy in the engine repo; mirrored into the RDF ring's
series because a flip re-pins that ring's goldens and fixtures.

## Context

**Every content address is a 20-byte truncated digest computed at one site.**
`HashAlgorithm.CURRENT` (today `SHA512_20`, Dolt lineage) feeds `HashUtils`, the single
funnel for node hashes, commit ids, and store keys; `RocksNodeStore` stamps the
algorithm's 1-byte id into each store's format marker and **fail-closes on mismatch**.
The study added the remaining agility piece: `CURRENT` is selected per-process at
class-init (`-Dprolly.hash.algorithm`, default unchanged), and `SHA256_20`,
`BLAKE2B_160`, `BLAKE3_20` exist in the enum with on-disk ids.

**The measurements (Intel N150 — Gracemont, SHA-NI + AVX2; JMH, significance-checked):**

- Microbench at the 4 KiB target chunk: **SHA-256 2013 MB/s** (the JDK intrinsic
  dispatches to SHA-NI) vs the incumbent's 445 — **4.5×**, and the gap holds at every
  size from 64 B commit objects to the chunker's 16 KiB MAX.
- Whole ingest, geometry-clean A/B (identical tree shapes, only address bytes differ):
  **−9.3%** (5 forks, t = −5.86, 95% CI excludes 0). Hashing at incumbent rates is
  roughly a tenth of ingest; hardware SHA-256 recovers essentially all of it.
- Every BLAKE candidate was rejected *by measurement*: BouncyCastle's scalar Java
  BLAKE2b/BLAKE3 are slower than the incumbent; the official native AVX2 `libblake3`
  (bound via Panama FFM, correctness-pinned against an independent implementation)
  still loses to SHA-256 at every chunk-bounded size (1272 MB/s at 4 KiB) and would
  cost per-platform native packaging.

**The catch:** changing the address hash is a **format break** — every chunk address,
commit id, and store key changes; golden vectors and cross-language fixtures re-pin;
existing stores cannot be read by a flipped process (the marker refuses, by design).
Pre-1.0 policy allows this freely, but each break spends operator rebuilds and
fixture churn — breaks should be bundled, not dribbled.

## Options

| | A — flip now, its own release | B — flip at the NEXT format-breaking release | C — stay on SHA-512/20 | D — per-store algorithm (marker-driven) |
|---|---|---|---|---|
| ingest win arrives | immediately | at the next break (opt-in via the sysprop today for fresh stores) | never | per store, gradually |
| format-break cost | a whole break spent on ONE change | amortized into a break that was happening anyway | none | none up front |
| engineering beyond today's hook | goldens/fixtures re-pin | same, bundled | none | REAL: `HashUtils` is a per-process static funnel — per-store hashing means threading a hash context through every compute site (115 referencing files' worth of call paths) |
| operational risk | one more rebuild cycle for operators | zero marginal (riding an existing rebuild) | keeps paying ~10% of ingest | mixed-algorithm estates; sync/replication between stores of different algorithms needs address-translation or refusal rules |
| hardware caveat | the 4.5× is SHA-NI's; on cores WITHOUT it (pre-Zen AMD, pre-Goldmont/Ice-Lake Intel), scalar SHA-256 can LOSE to SHA-512 | same caveat, but the sysprop remains the per-deployment escape hatch | immune | per-store choice could in principle match hardware, at large complexity |

## Decision

**Option B — `SHA256_20` becomes the default `CURRENT` bundled into the next
format-breaking release, whatever that release is.** Until then it ships as the
measured, ready successor: new deployments that want the win today can set
`-Dprolly.hash.algorithm=SHA256_20` on a **fresh store** (the marker stamps it;
mixed-process access fails closed rather than corrupting).

Rejected: A (a −9.3% ingest win is worth having but not worth an *isolated* break's
operator and fixture cost when pre-1.0 breaks are still occurring anyway); C (leaves
a measured ~10% of ingest on the table indefinitely); D (the per-store hash context is
a large refactor of the one-funnel design that exists precisely to keep hashing
semantics in one place — and it creates mixed-estate replication semantics nobody has
asked for).

## Consequences

- **At the flip**: golden vectors, cross-language fixtures (`cross-lang/`), and every
  address-pinning test re-pin in the same change; commit ids and hex renderings change
  value but not shape (20 bytes / 40 hex, unchanged — no schema or UI impact);
  operators rebuild stores (the standing pre-1.0 migration story).
- **Security posture unchanged**: addresses keep 160 bits regardless of which digest
  is truncated; truncation also removes any length-extension consideration. The
  collision bound is the address length's, not the algorithm's.
- **The hardware caveat is named, not hidden**: the win is SHA-NI's (ubiquitous on
  AMD Zen+, Intel Goldmont+/Ice Lake+, and ARMv8 crypto-extension cores — i.e.
  essentially all deployment-relevant hardware since ~2017, but not all). The sysprop
  stays as the per-deployment override, and the study's bench is rerunnable on any
  target host before committing a fleet.
- **BLAKE3 is closed as a question on this hardware class** — revisit only for
  AVX-512 server fleets or if a maintained, packaged JVM binding materializes AND a
  target host's measurements beat its own SHA hardware. BouncyCastle remains
  test-scope; nothing here adds a runtime dependency.
- The RDF ring's obligation (why this ADR is mirrored there): its compliance goldens
  and any address-literal fixtures re-pin at the flip; nothing changes there before it.
