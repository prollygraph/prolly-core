# Hash-function study — SHA-512/20 vs SHA-256 vs BLAKE2b vs BLAKE3 (scalar and native)

*2026-07-17 · Intel N150 (4× Gracemont, AVX2 + SHA-NI, no AVX-512), JDK 25, JMH 1.37 ·
`dolthub-java-port/src/test/java/com/dolthub/prolly/chunkbench/`*

Which hash should compute the engine's 20-byte content addresses? The agility half was
already built — `HashAlgorithm` (enum + 1-byte on-disk id) funnels every address
through one compute site, and `RocksNodeStore` stamps + fail-closes the algorithm per
store — so this study added the missing selection hook
(`-Dprolly.hash.algorithm`, default unchanged), the candidates, and the numbers.

Unlike the boundary-function study, a hash A/B is **geometry-clean**: chunk
boundaries, sizes, and tree shape are identical across arms — only address bytes
differ — so ingest deltas isolate hashing compute exactly.

## Microbench: one op = reset + digest + truncate-to-20 (3 forks × 5)

| algorithm | impl | 64 B | 512 B | 4 KiB | 16 KiB | MB/s @ 4 KiB |
|---|---|---|---|---|---|---|
| SHA-512/20 (incumbent) | JDK scalar | 351 ns | 1435 ns | 8784 ns | 33969 ns | 445 |
| **SHA-256/20** | **JDK + SHA-NI** | **102 ns** | **309 ns** | **1941 ns** | **7434 ns** | **2013** |
| BLAKE2b-160 | BouncyCastle scalar | 500 ns | 1644 ns | 12291 ns | 48942 ns | 318 |
| BLAKE3-256/20 | BouncyCastle scalar | 540 ns | 4395 ns | 36086 ns | 133610 ns | 108 |
| BLAKE3-256/20 | **official C, AVX2, via FFM** | 146 ns | 576 ns | 3071 ns | 7938 ns | 1272 |

The ranking is hardware-explained tier by tier: a **dedicated instruction** (SHA-NI)
beats **SIMD vector code** (AVX2 BLAKE3) beats a **64-bit-optimized scalar** (SHA-512)
beats **scalar Java** (BouncyCastle). Three conclusions:

- **SHA-256 wins every cell against every candidate** — 4.5× the incumbent at chunk
  sizes, and still 1.6× ahead of the *real* native BLAKE3 at the 4 KiB target
  (narrowing to ~1.07× at 16 KiB, where BLAKE3's internal tree parallelism begins to
  pay — but 16 KiB is the chunker's MAX; the workload never reaches the sizes where
  BLAKE3 pulls ahead).
- **"BLAKE is fast" is an implementation claim, not an algorithm claim.** The JVM
  ecosystem's BouncyCastle implementations are *slower than the incumbent* — BLAKE3
  scalar Java by 4× — and the official SIMD build requires native packaging
  (measured here through a Panama FFM binding to the Arch-packaged `libblake3`
   1.8.4, correctness-pinned byte-for-byte against BouncyCastle by
  `Blake3NativeProbeTest`).
- On AVX-512 servers or large-input workloads the BLAKE3 story would improve; on
  this class of hardware, with chunk-bounded inputs, it cannot win.

## Ingest A/B: 50k quads/op through a real RocksDB-backed Sail, per-fork JVMs

Initial 3-fork sweep (all four candidates), then a 5-fork resolution of the pair
that mattered:

| arm | 3-fork ms/op | 5-fork ms/op | verdict vs incumbent |
|---|---|---|---|
| SHA-512/20 (incumbent) | 1909 ± 70 | 1913 ± 67 | — |
| **SHA-256/20** | 1835 ± 30 | **1735 ± 26** | **−9.3%, t = −5.86, CI excludes 0 — real** |
| BLAKE2b-160 (BC) | 2162 ± 1073 | — | noise-or-worse; eliminated by the microbench |
| BLAKE3-256/20 (BC) | 1979 ± 149 | — | eliminated by the microbench |

The 3-fork point estimate (−3.9%) was *under* the truth — the sweep ran hot with
other arms; the quiet-box 5-fork resolution measured **−9.3% of whole ingest,
significant** (fork means 1929/1977/1964/1829/1868 vs 1753/1701/1760/1716/1746).
Read together with the microbench, hashing at SHA-512 rates is roughly **a tenth of
whole ingest**, and hardware SHA-256 recovers essentially all of it.

Contrast with the boundary-function study's verdict on the same fixture: the chunker
was ~1.6% of ingest and not worth a format break; the address hash is ~6× bigger and
**the numbers genuinely argue for scheduling the change**.

## Verdict

**SHA-256/20 is the measured successor: 4.5× at the microbench, −9.3% whole ingest,
zero new dependencies, zero native packaging** — the JDK intrinsic dispatches to
SHA-NI by itself. Both BLAKE candidates are rejected on this hardware class: scalar
Java versions lose to the incumbent; the native SIMD build loses to SHA-256 at every
chunk-relevant size *and* would cost per-platform packaging.

**What adoption requires (deliberately NOT done here):** flipping
`HashAlgorithm.CURRENT` is a format break — every content address in every store
changes, goldens re-pin, migration = rebuild (pre-1.0). That is an ADR's decision,
now with the numbers attached. The pragmatic path the enum already supports: adopt
`SHA256_20` the next time a format-breaking change ships anyway; the store marker
makes mixed-algorithm deployments fail closed rather than corrupt.

**Truncation note:** 20-byte addresses keep 160 bits regardless of the digest
truncated; collision resistance is the address length's, unchanged across all
candidates.

## Where this lives

- `HashAlgorithm` / `HashUtils` — the selection hook (sysprop, default unchanged) +
  the reflective BouncyCastle registration (bcprov stays test-scope)
- `chunkbench/HashThroughputBench` — the JVM-candidate microbench
- `chunkbench/Blake3NativeThroughputBench` + `Blake3NativeProbeTest` — the native
  arm and its BouncyCastle-agreement correctness pin (skips without `-Dblake3.lib`)
- `prolly-rdf` ring: `IngestSplitterEliminationBench` — the reused ingest fixture
  (the hash arms are the same bench under `-Dprolly.hash.algorithm`)
- `docs/write-ups/chunker-boundary-detection-study.md` — the sibling study this
  one's fixture and discipline came from
