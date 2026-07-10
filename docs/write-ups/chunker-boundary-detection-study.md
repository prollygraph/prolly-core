# Chunker boundary-detection study — direct-mask vs gear vs buzhash

*2026-07-16 · Intel N150 (4-core), JDK 25, JMH 1.37, 3 forks × 5 iterations ·
`dolthub-java-port/src/test/java/com/dolthub/prolly/chunkbench/`*

Which boundary-detection function should slice prolly-tree leaf chunks? Three
candidates, all keys-only, deterministic, secret-seeded, MIN 512 / MAX 16384 clamped,
targeting ~4 KiB, deciding at entry granularity (the production splitter's
`crossedBoundary` is only consulted between entries, so that is the shared contract):

- **A — direct per-key mask**: boundary when the key's **first term id**'s low bits
  mask to zero. No state across keys. The hypothesis-to-beat.
- **A′ — the minimal repair of A**: XOR all 8-byte lanes of the key, one spreading
  multiply, mask the low bits. Same op class, still a pure per-key function.
- **B — gear (FastCDC lineage)**: `h = (h << 1) + GEAR[byte]` over key bytes, high-bit
  masks. A and A′ and B use FastCDC-style normalized two-mask chunking (strict +2 bits
  before target, loose −2 bits after).
- **C — buzhash reference**: the production `BuzHash` (window 67, level-salt XOR) fed
  keys-only, with the production progressive-mask staircase — the incumbent's geometry.

## Premise corrections (measured against this repo, not assumed)

The task's framing said keys are "already high-entropy" hash-derived term ids. In this
repo **term ids are sequential dictionary ordinals** (the codec's parity encoding is
order-preserving, not entropy-adding), and the production splitter hashes key **and
value** bytes with a staircase mask, not two-mask. The study therefore runs every
candidate on **two stream shapes**: the task's premise (64-byte keys of four
hash-derived 128-bit ids, emulated with SHA-256) and the repo's reality (32-byte keys
of four sequential ordinals) — both with realistic quad-stream reuse (subject pool
~n/10, predicate vocabulary 64, objects unique, sorted).

## Throughput (JMH, ns per key; 1M-key sorted streams; errors are 99.9% CIs)

| candidate | hashed64 ns/key | ordinal32 ns/key | hashed64 GB/s |
|---|---|---|---|
| A direct mask (first id) | 5.16 ± 0.10 | 3.74 ± 0.04 | 12.4 |
| **A′ lane-XOR mask** | **20.59 ± 0.33** | **11.35 ± 0.13** | **3.11** |
| B gear | 90.68 ± 0.84 | 43.32 ± 0.40 | 0.71 |
| C buzhash keys-only | 204.36 ± 2.21 | 101.85 ± 1.38 | 0.31 |
| memory reference (scalar XOR-reduce over the same bytes) | 8.47 ± 0.09 | 4.24 ± 0.05 | 7.56 |

Significance (Welch's t over fork means): gear is **+340.5%** vs A′ (t=443.8), buzhash
**+892.7%** vs A′ (t=285.7); both CIs exclude 0. A beats the scalar memory floor
because it reads only 8 of each key's bytes; A′ sits ~2.4× above the floor.

## Chunk-size distribution (2M keys, target 4096 B, MIN 512, MAX 16384)

**hashed64 (task premise):**

| candidate | chunks | mean | p50 | p95 | p99 | min | max | sd |
|---|---|---|---|---|---|---|---|---|
| A direct mask | 12,560 | 10,191 | 9,792 | 16,384 | 16,384 | 512 | 16,384 | 5,038 |
| A′ lane-XOR | 28,408 | 4,506 | 4,544 | 6,784 | 8,512 | 512 | 12,928 | 1,503 |
| B gear two-mask | 27,991 | 4,573 | 4,608 | 6,912 | 8,576 | 512 | 15,552 | 1,536 |
| B gear single-mask | 27,995 | 4,572 | 3,392 | 12,992 | 16,384 | 512 | 16,384 | 3,777 |
| C buzhash staircase | 28,605 | 4,475 | 4,608 | 6,720 | 7,360 | 512 | 8,640 | 1,530 |

**ordinal32 (repo reality):**

| candidate | chunks | mean | p50 | p95 | p99 | min | max | sd |
|---|---|---|---|---|---|---|---|---|
| A direct mask | 6,263 | 10,218 | 10,240 | 11,200 | 11,616 | 512 | 12,384 | 732 |
| A′ lane-XOR | 14,108 | 4,536 | 4,576 | 6,848 | 8,416 | 512 | 16,192 | 1,523 |
| B gear two-mask | 14,014 | 4,566 | 4,576 | 7,008 | 8,576 | 544 | 16,000 | 1,539 |
| B gear single-mask | 14,086 | 4,543 | 3,360 | 12,736 | 16,384 | 512 | 16,384 | 3,755 |
| C buzhash staircase | 14,355 | 4,458 | 4,576 | 6,688 | 7,328 | 512 | 8,512 | 1,522 |

- **The two-mask normalization delivers as advertised**: relative variance (sd/mean)
  0.336 two-mask vs 0.826 single-mask, and it rescues both tails (p95 6912 vs 12992).
- **The staircase (incumbent) has the tightest tail of all** — p99 7360, max 8640 —
  at 10× the compute of A′. Two-mask trades a modestly fatter tail for the speed.

## The adversarial finding: A-as-specified is structurally degenerate

A's failure is not an entropy problem — it fails **on the task's own high-entropy
premise** (mean 2.5× target, p95 = p99 = MAX). The mechanism: on a **sorted** quad
stream, the first term id is constant across whole subject runs, so a per-key
predicate keyed on it returns the same verdict for every key in a run — boundaries can
only land at run transitions, whatever the ids' entropy. On the repo's ordinal keys it
degrades differently (near-fixed ~10.2 KiB slabs, sd 732 — periodic, not
content-defined in any useful sense). **One lane-XOR + one multiply (A′) fully repairs
it** on both shapes.

## History-independence

Shuffle → re-sort → identical boundary sequences for every candidate (2×200k keys;
stream-level, since `TreeMutator` hard-wires its splitter; the incumbent's tree-level
root-hash determinism is separately pinned by `ChunkerDeterminismGateTest`).

## Recommendation

**A′ — the per-key lane-XOR direct mask with two-mask normalization.** It matches
gear's geometry on both stream shapes, is 4.4× faster than gear and 9.9× faster than
keys-only buzhash (both significant at 3 forks), needs no sliding window, no gear
table, and no cross-key state beyond the running chunk size. A **as literally
specified (first term id only) is rejected on the measurements above** — the repair is
in the same op class, so nothing is lost.

Two adoption caveats, named honestly:

1. **A′ assumes fixed-width keys** (it mixes 8-byte lanes). The engine's tuple keys
   are variable-width in general; per-level fixed-width key spaces (the SPOC index
   trees) fit today, but a general adoption needs either a lane-safe fallback (gear
   over the key when width varies) or a defined lane split. Gear (B) is the
   shape-insensitive fallback and is itself 2.25× faster than the incumbent keys-only.
2. **Adoption is a format-breaking change** — every boundary moves, so every chunk
   hash changes. Pre-1.0 that is allowed and cheap, but it must ship as its own
   deliberate plan (with the geometry gates re-pinned), not ride along.

## Is A′'s parallelizability worth pursuing (Vector API / parallel leaf scan)?

**Not now.** The honest arithmetic: A′ already runs at ~20.6 ns/key (≈48M keys/s
single-threaded) — ~2.4× the scalar memory-read floor. The write path this feeds
spends microseconds per entry (dictionary encoding, tree building, RocksDB writes);
even the incumbent's full key+value buzhash was only worth ~42% of one document
write's *chunker* CPU before the scratch-buffer fix, and the boundary function after
this study would be low-single-digit percent of the end-to-end write. Vectorizing it
would optimize a component that is no longer on the critical path — and the two-mask's
chunk-size dependence makes the boundary *resolution* sequential anyway (only the
per-key predicate vectorizes cleanly). Revisit only if a profile ever shows the
boundary function back above ~10% of the write path.

## Where this lives

- `dolthub-java-port/src/test/java/com/dolthub/prolly/chunkbench/` — candidates,
  streams, distribution/history-independence tests, JMH bench (all `@NullMarked`)
- `RollingHashSplitter` — the production incumbent this study measures against
- The upstream chunker-boundary-detection-study plan — provenance + wrap-ups
