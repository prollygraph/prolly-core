# Developer skill sets — what working on the engine ring asks of you

A map of the competencies this codebase exercises, module by module, so you can
find the part that matches what you already know — and see honestly what a given
area demands before you start.

This is not a hiring bar. The ring is layered so most contributions need one or
two rows, not the table. It complements [CONTRIBUTING.md](../CONTRIBUTING.md)
(how to submit) by answering a different question: *what will I need to know,
and how hard is it to pick up here?*

This is the **engine** ring — the bottom of the stack. Work here is closer to
data structures and bytes than to any query language, and correctness pressure
is unusually high: everything above depends on these invariants holding.

## The map

| Area | Where it lives | Skills it actually asks for | Ramp |
|---|---|---|---|
| **Prolly trees / content-defined chunking** | `dolthub-java-port` (44 main / 124 test) | Merkle search trees, rolling hashes, why boundaries chosen by *content* make trees history-independent and diffs cheap | Steep — the central idea of the whole project |
| **Content-addressed storage** | `prolly-storage` (19 / 66) | Hash-as-identity, copy-on-write, chunk stores, reachability-based garbage collection | Moderate |
| **Binary serialization** | `dolthub-java-port` | FlatBuffers, wire framing, fail-closed parsing, format versioning — a one-byte change forks every hash above it | Steep — correctness-critical by construction |
| **Off-heap memory (Panama)** | across the port | The Foreign Function & Memory API: `MemorySegment`, `Arena` lifetimes, why a segment outliving its arena is a use-after-free | Steep for most Java developers; the least familiar API here |
| **RocksDB from Java** | `prolly-storage` | Log-structured merge-trees, column families, write batches, and the JNI boundary's real cost | Moderate — [the-chunk-store](../docs/) background helps |
| **Multi-store / tenancy primitives** | `prolly-multistore` (10 / 5) | LRU warm sets, pinned leases, resource lifecycle under eviction | Light to moderate |
| **Replication protocols** | `prolly-sync` (6 / 4), `prolly-sync-grpc` (6 / 1) | Pack construction, reachability closure, compare-and-set ref advancement, gRPC streaming | Moderate |
| **Concurrency verification** | `prolly-concurrency` (0 main / 9 test) | The Java memory model, safe publication, and jcstress/Lincheck — *proving* an interleaving is impossible rather than arguing it | Steep — the hardest row here |
| **Property-based testing** | ring-wide (63 `*Property` files, 34 with jqwik) | Writing generators, finding the invariant worth stating, building an oracle that does **not** mirror the implementation | Moderate — the highest-leverage skill in this repo |
| **Fuzzing** | parser boundaries (Jazzer) | Treating any parse of untrusted bytes as a trust boundary, and thinking like hostile input | Moderate |
| **Performance measurement** | benches across the ring | JMH, paired A/B, naming the bottleneck layer *before* optimising, and distrusting a clean result | Steep — the discipline is harder than the tooling |
| **Build & quality gates** | `pom.xml`, `build/` | Maven multi-module, BOM imports, spotless, license headers with per-file provenance, dependency convergence | Light — but note `mvn test` green is **not** the gate; `mvn verify` is |

## Where to start, by background

- **You know data structures, not databases.** Start with the tree itself in
  `dolthub-java-port`: the chunker, the cursor, the mutator. It is a search tree
  with an unusual boundary rule, and the property tests state the invariants
  plainly.
- **You know storage engines.** Start in `prolly-storage` — RocksDB, chunk
  stores, garbage collection. Familiar territory; the novelty is that identity
  is a hash, not a location.
- **You know distributed systems.** `prolly-sync` and `prolly-sync-grpc` are the
  smallest modules with the most protocol thinking per line: what must a pack
  contain to be applicable, and what makes ref advancement safe.
- **You like proving things.** `prolly-concurrency` is test-only and gated off by
  default (`-Pconcurrency`). It exists because "this is race-free by
  construction" was once asserted here without proof.

## Provenance matters here

This ring contains **ported code** — a Java translation of Dolt's Go prolly
tree, which itself derives from Noms. That has a practical consequence for
contributors: per-file copyright headers are not decoration. A file translating
specific upstream Go code carries the upstream credit; a Java-original file does
not. If you add or substantially rewrite a file, get its header right — see
[NOTICE](../NOTICE) and the header templates under `build/`.

## The non-technical half

- **Ground claims, or mark them ungrounded.** "This is faster" without a
  measurement, or "this is safe" without the test that shows it, will be asked
  for evidence. "I think, but haven't verified" is always acceptable; asserting
  it is not.
- **A refuted hypothesis is a good result.** Optimisations here have been built,
  measured, and reverted — the negative result recorded as the deliverable.
  Reporting that your idea did not work, with the number that shows it, is a
  contribution.
