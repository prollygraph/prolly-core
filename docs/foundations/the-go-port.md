---
tags:
  - architecture
---
# The Go port

*What was ported from Dolt, what "bit-compatible" really means here, and how
the cross-language check actually turned out.*

> **What you'll learn** — the full attribution: which Go files from
> [Dolt](https://github.com/dolthub/dolt) became which Java classes, where the
> ported code ends and original work begins, and the honest current state of
> byte-for-byte parity with Dolt — including why parity turns out *not* to be
> required.
>
> _Reading time: ~11 minutes._

## Why it matters

`prolly-port` did not invent the prolly tree. It is a **port** — a
file-by-file translation of Dolt's Go implementation to Java. Knowing that
changes how you read the code: parts of it are deliberately *not idiomatic
Java*, because they mirror a specific Go source file line for line, and
"improving" them can silently change behaviour the port is trying to preserve.

This doc is the in-depth attribution that the Credits
line and `about-prolly-port` point at. It also
corrects a misconception a newcomer will otherwise pick up from the README:
that the port is byte-for-byte identical to Dolt. It is not — and that is fine.

## What was ported

[Dolt](https://github.com/dolthub/dolt) is DoltHub, Inc.'s open-source "Git for
data" SQL database, Apache-2.0 licensed. Its `go/store/prolly` packages are the
reference implementation of the prolly tree. The port translates them into the
**`com.dolthub.prolly`** package of `dolthub-java-port` — and the package name
is the boundary:

- **`com.dolthub.prolly`** — *derivative work* of Dolt. These files are ports
  of specific Go sources; they carry `Copyright 2021 Dolthub, Inc.` alongside
  this project's notice. The `serial.*` Flatbuffers classes (generated from
  Dolt's `prolly.fbs`) are derivative too.
- **`com.earasoft.prolly.*`** — *original work* for this Java project: the
  RocksDB-backed runtime and every upstream layer built on it.

> **Key idea** — the `com.dolthub` / `com.earasoft` package split is not
> cosmetic. It is the legal and architectural line between *ported* and
> *original*. The module boundary enforces the direction: `dolthub-java-port` is
> an upstream module, so the `com.earasoft` classes — which live in downstream
> modules — are not on its compile classpath and cannot be referenced. (This is
> module layering, not a dedicated banned-import rule.)

## The key types — ported file by file

The documented Go-source attribution for each core type (only some name it in-code; the rest carry the `Copyright 2021 Dolthub, Inc.` header):

| Java class (`com.dolthub.prolly`) | Ported from (Dolt Go) |
|---|---|
| `BuzHash` | `kch42/buzhash` — the rolling hash |
| `RollingHashSplitter` | `go/store/prolly/tree` chunker boundary logic |
| `Cursor` | `tree/node_cursor.go` |
| `TreeMutator` | `tree/mutator.go` + `chunker.go` |
| `Tuple`, `TupleBuilder`, `TupleDescriptor` | `val/*` |
| `FlatbufferNodeSerializer` | `message/serialize.go` |
| `Varints` | `message/varint.go` |
| `ZOrder` | `tree/z_encoding.go` |
| `serial.ProllyTreeNode` (generated) | `prolly.fbs` schema |

Runtime dependencies of the ported core are minimal by design: **JDK 25,
Flatbuffers, and Caffeine** (which backs the `NodeCache`) — no RocksDB, no
upstream layers. Everything heavier is `com.earasoft` work layered on top.

## How parity with Dolt is verified

The port *aspires* to bit-compatibility — the same SHA-512/20 hashing, BuzHash
chunking, and Flatbuffers layout, so a tree built by either side hashes the
same. That claim is checked by the **cross-language fixture loop** in
`cross-lang/`:

1. `ChunkerDeterminismGateTest` (Java) pins the port's *own* root hash for a
   fixed 1000-tuple corpus — proving the port is internally deterministic.
2. `gen_fixture.go` (Go) builds the *same* corpus through Dolt v2.0.3 and
   writes `cross-lang/fixtures/` — a `manifest.txt` (`ROOT <hex>`, then
   key/value lines) plus one `nodes/<hash>.bin` per chunk.
3. `CrossLanguageFixtureTest` (Java) loads that fixture and runs four
   **oracles**: hash self-consistency, node-parse + tree-walk, content matches
   the manifest, and Go-root vs Java-pin. If the fixture is absent the test
   prints "skipping" and passes — so CI is green without a Go toolchain.

> **Gotcha** — `CrossLanguageFixtureTest` is dormant by default. A green build
> does **not** mean cross-language parity was checked. It is a `main(String[])`
> driver, not a JUnit `@Test`, so `mvn test` never runs it — even with a fixture
> present. Real verification means generating the fixture (`go run
> gen_fixture.go` against a local Dolt checkout) and running the driver by hand.

### The honest verdict

The loop has been *run* — first on 2026-05-15, recorded in an upstream findings
document (this section carries its conclusions).
The result: **the port is not byte-compatible with Dolt v2.0.3.** The
divergence is layered:

| Layer | Status |
|---|---|
| **0 · Hashing** (SHA-512/20) | ✅ Go and Java agree |
| **1 · Chunk framing** | ✅ fixed in that run — Dolt wraps each node in a 4-byte `serial`-message prefix; `Node.fromBytes` now strips it |
| **2 · Node Flatbuffer schema** | ✅ compatible for the fields the tree-walk exercises |
| **3 · `val.Tuple` field layout** | ❌ **open** — Dolt omits the first field offset (stores `count − 1` offsets); the port expects `count`. An off-by-one in `Tuple.getFieldSegment`. |
| **4 · BuzHash root / boundaries** | not reached — the Go root (`b96e85d1…`) and Java pin (`1d9d81f4…`) differ, but Layers 1 + 3 already explain that |

Layer 3 is diagnosed but unfixed: `getFieldSegment` is the decode path for
*every* tuple read, and switching it to Dolt's omit-first-offset scheme could
break the port reading its *own* data — the port's tuple *writer* has to be
audited first. A follow-up experiment (2026-05-29) fixed the tuple layer alone
on a branch: the corpus root *changed* (`d4540690…`) but still differed from
Go's (`b96e85…`) — confirming the divergence is **multi-layer**, so parity is a
deliberate, format-breaking project, never a patch.

> **Key idea — bit-compatibility with Dolt is not actually required.** The
> findings doc reaches this explicitly. For the upstream layers to work correctly on prolly
> trees the engine needs three things: **internal consistency** (the port's own
> writer and reader agree — `ChunkerDeterminismGateTest` confirms this),
> **determinism** (identical input ⇒ identical trees and hashes — the property
> that makes content-addressing and structural diff/merge work), and **correct
> algorithms** (sound CDC chunking, cursors, mutation, Merkle structure).
> "Match Dolt's exact bytes" is on none of that list. Dolt parity is a *nice to
> have* — useful for importing Dolt data someday — not a correctness
> requirement. (An earlier README claimed bit-compatibility as an aspiration;
> today's README states the truth plainly: the port cannot read or write Dolt
> databases, and the layers that ARE pinned — 0 through 2 — are held by the
> golden vectors under `cross-lang/fixtures`.)

## Rules & gotchas

- > **Gotcha** — ported `com.dolthub.prolly` code mirrors Go line for line.
  > Before "cleaning it up", check the named Go source — a non-idiomatic
  > construct is often a faithful translation, and changing it can break
  > determinism.
- > **Gotcha** — a green CI run does not prove Dolt parity; the cross-language
  > test self-skips when no fixture is present.
- > **Trade-off** — the project chose internal determinism over Dolt
  > byte-parity. That keeps it free to fix the Layer-3 tuple bug in whichever
  > direction is safest for the port's own data.
- Keep the `com.dolthub` / `com.earasoft` boundary intact — it is the
  attribution line. It holds by module layering: `dolthub-java-port` is upstream,
  so the original `com.earasoft` work is not on its compile classpath to reach
  into.

## Takeaways

- `dolthub-java-port`'s `com.dolthub.prolly` package is a file-by-file **port**
  of Dolt's Go prolly-tree code; `com.earasoft.*` is original work.
- The cross-language fixture loop (`cross-lang/`) checks Go↔Java parity through
  four oracles, but is dormant unless the Go fixture is generated.
- As of the one real run, the port is **not** byte-identical to Dolt v2.0.3:
  hashing and framing agree; the `Tuple` offset layout (Layer 3) diverges.
- That is acceptable — the port needs internal consistency, determinism, and
  correct algorithms, **not** Dolt byte-parity.

## Where this lives

- `dolthub-java-port/src/main/java/com/dolthub/prolly/` — the ported core
- `cross-lang/README.md` — how to run the fixture loop
- `cross-lang/gen_fixture.go` *(private monorepo)* — the Go fixture generator
- `cross-lang/BITCOMPAT_FINDINGS.md` *(private monorepo; a public copy lives in the [prolly-rdf ring docs](https://github.com/prollygraph/prolly-rdf/blob/main/docs/bitcompat-findings.md))* — the recorded run and verdict
- Builds on: the-prolly-tree,
  the-on-disk-format
