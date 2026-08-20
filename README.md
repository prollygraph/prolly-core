# prolly-core — a Java port of Dolt's prolly tree

[![build](https://github.com/prollygraph/prolly-core/actions/workflows/build.yml/badge.svg)](https://github.com/prollygraph/prolly-core/actions/workflows/build.yml)

![The write-path explorer after one insert: the narration names the commit and its measured
write set, the tree pane shows the rewritten spine in amber with everything else shared by
reference, the operation journal narrates the write level by level, and the leaf ribbon shows
the keys boxed by chunk](docs/img/write-path-explorer.png)

*One insert into a 32-key tree: 6 of 12 chunks written — the amber set **is** the spine.
([Try it live](#try-it); re-capture with [`docs/img/capture-explorer.cjs`](docs/img/capture-explorer.cjs).)*

A faithful Java port of the **prolly tree** — the content-addressed, probabilistically
balanced search tree that [Dolt](https://github.com/dolthub/dolt) (Go) uses as its storage
primitive — plus the dependency-light durable substrate that makes it a usable versioned store.

> **Not affiliated with DoltHub, Inc.** Dolt and DoltHub are trademarks of DoltHub, Inc.
> This is an independent port; ported files retain their original DoltHub copyright under
> Apache-2.0 (see [`NOTICE`](NOTICE) and the per-file headers).

## Testing and verification, up front

- **1,112 tests as of 2026-07-16** across six modules (722 in the tree port itself) —
  unit, **property-based (jqwik)**, **fuzz-regression (Jazzer seeds)**, and **golden
  cross-language encoding vectors** under [`cross-lang/fixtures`](cross-lang/fixtures):
  the lowest layers (hash function, tuple value encodings) are pinned byte-identical to
  upstream Go Dolt; the layers above deliberately diverge (see
  [Relationship to Dolt](#relationship-to-dolt)).
- **Concurrency net**: Lincheck linearizability suites and jcstress memory-model
  harnesses over the shared-state seams (node stores, manifest compare-and-set, commit
  optimistic concurrency) in [`prolly-concurrency`](prolly-concurrency/README.md) —
  slow by design, gated behind `-Pconcurrency`.
- **It cannot read or write Dolt databases.** The on-disk format is this port's own.
- Build gates on every `mvn verify`: google-java-format, per-file license headers,
  dependency convergence.

Maintainer and contact routes: [`MAINTAINERS.md`](MAINTAINERS.md).

## Why a prolly tree

A prolly tree is a B-tree whose node boundaries are decided by a **rolling hash over the
serialized content** (content-defined chunking) instead of by insertion order. That one
change buys properties a classic B-tree cannot offer:

- **History-independence** — the same key/value set produces the *same tree bytes*, no
  matter the insertion order. Structure is a pure function of content — and you can
  [prove it live with curl](prolly-playground-service/README.md#prove-it-yourself-curl)
  against the playground backend.
- **Content addressing** — every node is stored under the hash of its bytes (20-byte
  SHA-512/20 here). A node's name *is* its checksum; identical subtrees are stored once,
  shared by reference across any number of versions.
- **O(changed) diff and cheap three-way merge** — two versions' trees agree on every
  subtree hash they share, so diff walks only where hashes differ.
- **O(log n) by key *and* by rank** — internal nodes carry a subtree-count vector
  (a counted B-tree), so ordinal lookups descend without touching skipped children.
- **Locality under edits** — one changed byte moves chunk boundaries only locally; the
  stream resyncs at the next surviving boundary. A single insert rewrites one spine
  (root-to-leaf path), never the tree.

Chunk boundaries roll a 67-byte BuzHash window over serialized entries, with a 512 B
minimum and 16 KiB cap per chunk; balance is statistical, with deterministic guards for
the pathological tails: a *staircase* boundary mask that loosens progressively as a
chunk grows, inside the hard min/cap bounds — measured tail at a 4,096 B target:
p99 = 7,360 B, max = 8,640 B
([boundary-function study](docs/foundations/boundary-function-performance.md)). If you
are checking for the well-known chunk-imbalance flaw of naive content-defined chunking:
that is the mitigation, and it is measured, not assumed.

**Who this is for:** anyone building versioned, diffable, syncable storage on the JVM —
a git-for-data experiment, a store that needs cheap point-in-time reads and O(changed)
diffs, a replication layer that wants content addressing to do the deduplication — or
anyone who wants to *study* a prolly tree with tests, docs, and a live instrument
attached.

## What's in this repo

| module | what it is |
|---|---|
| [`dolthub-java-port`](dolthub-java-port/README.md) | the tree engine: node model (flatbuffer-serialized, zero-copy reads via the Java FFM API), the rolling-hash splitter, tree building/mutation with a fast-forward walk that skips unchanged runs by reference, cursors, diff, and three-way merge |
| [`prolly-storage`](prolly-storage/README.md) | the durable substrate: RocksDB- and file-backed node stores, manifest/root management — no upper layers, no server, just the store |
| [`prolly-sync`](prolly-sync/README.md) | pack-based replication: build a pack of content-addressed chunks + commit chain from one store, apply to another with verified compare-and-set |
| [`prolly-sync-grpc`](prolly-sync-grpc/README.md) | the pack protocol on a socket: a gRPC server hosting one or more stores + a client with push/pull choreography — framed pack streaming (no message-size ceiling), resource limits enforced before parse, lease-shaped multi-repo resolution, pluggable auth |
| [`prolly-multistore`](prolly-multistore/README.md) | the many-repos primitive: run N stores in one process — an id + lifecycle repo registry with an LRU-bounded warm set (plain and pin-leased variants), repo-name syntax validation, per-repo RocksDB layout. Tenancy *policy* (metadata, permissions, orgs) deliberately lives with the consumer, not here |
| [`prolly-concurrency`](prolly-concurrency/README.md) | the concurrency net: Lincheck linearizability suites + jcstress memory-model harnesses over the engine's shared-state seams (node stores, manifest compare-and-set, commit optimistic concurrency, cache write visibility). Test-only; gated off by default (`-Pconcurrency` to run, `./prolly-concurrency/run-jcstress.sh` for the jcstress half — slow by design) |
| [`prolly-dependencies`](prolly-dependencies/README.md) | a Maven BOM pinning a coherent dependency set, importable by consumers |
| [`prolly-playground-service`](prolly-playground-service/README.md) | a small Spring Boot backend exposing the REAL engine to the web playground: build a tree of longs, see exactly which nodes a write mints, browse the node content-addressed store, re-verify every node's name against its bytes |

The Java packages are `com.dolthub.prolly.*` in the ported core — kept deliberately, as
attribution for ported code. Maven coordinates are `io.github.prollygraph:*`.

## Status

**v0.2.0-BETA — pre-1.0.** The on-disk format is this port's own: deterministic and
internally consistent, but **not byte-compatible with Dolt** and **evolving freely** —
no backwards compatibility, no defensive readers, until 1.0. Not yet published to Maven
Central. Treat every format and API surface as changeable.

The port is developed in an upstream private monorepo; this repository is a one-way
export of the engine layers (history starts fresh at the export). GitHub is the
canonical remote; a private CI mirror receives the same `main` (push-only, so upstream
pipelines can build against these modules) — contributors never need it.

## Relationship to Dolt

This is a from-scratch **Java port of the storage ideas** in [Dolt](https://github.com/dolthub/dolt)
(Go) — the prolly tree, content addressing, structural sharing — not a client, fork, or
affiliate of it (see the trademark note above). Ported files retain DoltHub's copyright;
the per-file provenance judgment is auditable in
[`build/dolt-provenance-ledger.md`](build/dolt-provenance-ledger.md).

**It cannot read or write Dolt databases.** The on-disk format is this port's own: the
lowest layers (hash function, tuple value encodings) are pinned byte-identical to Dolt by
golden vectors under [`cross-lang/fixtures`](cross-lang/fixtures), but the layers above
(tuple offset layout, node framing, chunk boundaries) deliberately diverge — full
byte-parity would be a format-breaking, multi-layer project with no consumer today. The
layer-by-layer story: [the Go port](docs/foundations/the-go-port.md).

## Using it in your project

Not on Maven Central yet (gated on a per-file attribution audit — see
[`RELEASING.md`](RELEASING.md)). Two ways to consume today:

**Build from source** (single command, then depend on the coordinates):

```bash
git clone <this repo> && cd prolly-core && mvn -DskipTests install
```

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.prollygraph</groupId>
      <artifactId>prolly-dependencies</artifactId>
      <version>0.2.0-BETA</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>io.github.prollygraph</groupId>
    <artifactId>dolthub-java-port</artifactId>   <!-- the tree engine -->
  </dependency>
  <dependency>
    <groupId>io.github.prollygraph</groupId>
    <artifactId>prolly-storage</artifactId>      <!-- + durable stores, commits, merge -->
  </dependency>
</dependencies>
```

**GitHub Packages** (token required even for public packages — GitHub's constraint, not
ours): consumer snippets in [`RELEASING.md`](RELEASING.md). Start with
[`dolthub-java-port/USAGE.md`](dolthub-java-port/USAGE.md), then
[`prolly-storage/USAGE.md`](prolly-storage/USAGE.md).

## Try it

The fastest way to *see* the engine is the web playground served by the playground backend:

```bash
mvn -DskipTests install
mvn -pl prolly-playground-service spring-boot:run
```

(The first command installs the reactor's modules locally so the single-module run can
resolve them — `package` alone would not.)

Open **http://localhost:8080/** — the write-path playground, served by the engine itself. The
header's **store** switch picks the backend: **sim** is a byte-level teaching model (commits, branches,
a rolling-hash boundary lens), and **real engine** renders and edits the actual Java engine's tree
over HTTP — real root hashes, the measured write set of every insert, reads with their measured
descent, and a session root-log that time-travels through superseded roots (the store never
deletes). See [`prolly-web-playground/README.md`](prolly-web-playground/README.md).

## Learn it

- [`docs/`](docs/README.md) — foundations first (the prolly tree, the on-disk format, the
  Go-port discipline, the engine error taxonomy), then six anatomy walkthroughs tracing one
  concrete invocation each (a chunk boundary → a write → a read → a commit → a diff → a
  merge), and the architecture decision records.
- [`prolly-web-playground/`](prolly-web-playground/README.md) — the interactive write-path
  explorer (the Try-it above serves it at `/`), with the
  [write-path deep dive](prolly-web-playground/treemutator-write-path.md) and the
  [class-roles map](prolly-web-playground/class-roles.md) beside it.
- Per-module READMEs, plus [`dolthub-java-port/USAGE.md`](dolthub-java-port/USAGE.md) and
  [`prolly-storage/USAGE.md`](prolly-storage/USAGE.md) for copy-paste API shapes.
- Heading up the stack? The [prolly-rdf ring's docs](https://github.com/prollygraph/prolly-rdf/blob/main/docs/README.md)
  continue the curriculum over this engine: RDF foundations, five anatomy
  walkthroughs, and 13 runnable versioned-SPARQL demos.

## Build

Requires **JDK 25** and Maven:

```bash
mvn clean install
```

That runs the full net — 1112 tests as of 2026-07-16: 722 in `dolthub-java-port`, 288 in
`prolly-storage`, 18 in `prolly-sync`, 10 in `prolly-sync-grpc`, 58 in
`prolly-multistore`, and 16 in `prolly-playground-service` (unit,
property-based via jqwik, fuzz-regression via Jazzer seeds, and golden cross-language
encoding vectors under `cross-lang/fixtures`) — plus the build gates: google-java-format
check, per-file license-header check, and dependency convergence enforcement.

## License

[Apache-2.0](LICENSE). Ported files carry DoltHub's original copyright alongside this
project's; Java-original files carry theirs — see [`NOTICE`](NOTICE) and the four header
templates under [`build/`](build/).

## AI Disclosure

This project was developed with AI assistance.

- **Tools used:** Claude Opus 4.7, Claude Opus 4.8, and Claude Fable (Anthropic), and
  Gemini (Google).
- **Scope of use:** AI assisted with writing code (including porting Go sources to
  Java), tests, and documentation.
- **Human oversight:** All AI-assisted output is reviewed by a human maintainer before
  it is committed, and the maintainers take full responsibility for everything in this
  repository, regardless of how it was produced.
- **Verification:** All contributions, AI-assisted or not, must pass the full test
  suite and build gates described in [Build](#build), including property-based tests,
  fuzz-regression seeds, and golden cross-language encoding vectors checked against the
  upstream Go implementation.
- **Licensing:** AI-assisted contributions are released under the same
  [Apache-2.0](LICENSE) terms as the rest of the project; copyright and attribution are
  handled as described in [License](#license).
- **Contributions:** If you use AI tools to help prepare a pull request, please say so
  in the PR description, review the output yourself before submitting, and confirm you
  have the right to contribute it under the project license.
