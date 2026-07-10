# ADR-0072: Node format versioning

## Status

Accepted, 2026-06-27. Guides `dolthub-java-port/an upstream plan.

## Context

<!--
What's the problem? Sketch the situation + constraints. Sub-sections OK.
Include the strategic position when relevant — "what makes this decision
non-trivial and why are we deciding NOW".

Bad: "We need to pick a graph layout library."
Better: "Commit counts in this codebase's deployments are in the thousands,
not the millions. SVG nodes are inspectable + animatable + screenreader-
traversable; Canvas would scale further but degrades all three. The
decision is whether to optimize for current scale + iterability or the
eventual scale."
-->

The prolly **`Node`** is the engine's fundamental on-disk record: every prolly-tree chunk (leaf and
internal) is a content-addressed `Node` blob. Its payload is a FlatBuffers `ProllyTreeNode` table
tagged with the `"TUPM"` file-identifier (shared layout with Dolt). `Node.fromBytes` dispatches on
that identifier (bare, or behind Dolt's 4-byte serial-message prefix) and, for anything else,
**silently falls back to `SimpleNodeSerializer`** — a TLV "bootstrap/testing" serializer production
never writes.

The engine's other versioned-on-disk types already **self-describe**: the store marker, the `Commit`
record (`COMMIT_MAGIC = 'PCMT'`), and an upstream root-meta structure (ADR-0067, upstream/not exported —
`'PRMT'`) each carry a `[4-byte magic][1-byte version]` header verified *before any field is read*,
failing closed with `UnsupportedFormatException` rather than mis-reading arbitrary bytes. **`Node` is
the one core type still missing it** — it has a magic (`"TUPM"`) but no version, and a non-`TUPM` blob
is silently TLV-parsed instead of rejected.

**Why now:** the on-disk format stabilizes ahead of a public release, and the project's discipline is
that every versioned on-disk type self-describes so a wrong / foreign / **future-incompatible** blob
fails closed instead of mis-parsing. The specific live gap: a future `TUPM` *v2* with an incompatible
field meaning would be **additive-misparsed** by FlatBuffers' default field-tolerance (an unknown
node read with this engine's accessors, yielding wrong data, not an error).

**Constraints:** (1) pre-1.0 **no-backwards-compat** — no defensive multi-version readers; a format
change is back-up + restore. (2) The generated `serial.*` FlatBuffers binding is **checked-in /
vendored — flatc is not in the build.** (3) `Node` is a **core** type (unlike `RootMetaTree`, a
an upstream-layer type), so its version is the core's. (4) The **Dolt-node read path must keep working**:
the port reads Dolt-`TUPM` chunks for the Layers 0–2 cross-language characterization.

## Options

Deciding axes: does a wrong/future blob **fail closed before the payload is parsed** (the live
additive-misparse gap); does it need the **flatc** toolchain (absent); and is it **consistent** with
the existing `Commit` / `RootMetaTree` header pattern.

| Option | Fail-closed *before* parse | Needs flatc | Consistent with `Commit`/`RootMetaTree` |
|---|---|---|---|
| **A** — status quo (`"TUPM"` identifier only, silent `SimpleNodeSerializer` fallback) | No — a non-`TUPM` blob is TLV-misread; a future `TUPM` v2 additive-misparses | No | No — the one core type that diverges |
| **B** — in-schema `version:uint8` field in `prolly.fbs` | **No** — the version is a *flatbuffer field*, read **by** the parser *after* the table is parsed; the additive-misparse it aims to stop has already happened | **Yes** — edit `prolly.fbs` + regen `serial.*` | Partial — self-describes, but via a different (in-band) mechanism |
| **C** — external `[4-byte magic][1-byte version]` header *(chosen)* | **Yes** — magic+version checked before the flatbuffer is touched | **No** — a plain byte prefix outside the flatbuffer | **Yes** — matches `Commit` / `RootMetaTree` exactly |

## Decision

Prepend an external `[4-byte NODE_MAGIC][1-byte version]` header to every `Node` the port writes,
verified before any field is read, failing closed with `UnsupportedFormatException` — **Option C**,
mirroring `Commit`.

- **D-1. The deciding tradeoff: external header, not an in-schema field — even though flatc could be
  obtained.** The plan that drove this ADR originally preferred the in-schema field (Option B) and
  treated the external header as a flatc-unavailable *fallback*. That was backwards. Option B's version
  is parsed *by* the FlatBuffers machinery, so a hostile / future-incompatible node is **already
  partially parsed** before the version is checked — the exact additive-misparse this change exists to
  prevent. Option C checks the magic + version on the **raw bytes first**, so the misparse never
  happens. Option C is therefore chosen **on the merits** (fail-closed-before-parse + consistency),
  and the absence of flatc is merely a second, independent reason it is convenient. (This dissolved the
  plan's "blocked on flatc" status entirely.)
- **D-2. `NODE_MAGIC = {'P','N','O','D'}`**, distinct from `Commit`'s `'PCMT'`, `RootMetaTree`'s
  `'PRMT'`, and the inner flatbuffer's `"TUPM"`. Lets `fromBytes` reject a non-node / pre-versioning /
  foreign blob cleanly.
- **D-3. Reuse `FormatVersion.CORE_FORMAT_VERSION` (starts at 1) — NOT a node-local version.**
  *Deciding tradeoff (the mirror of ADR-0067 D-2):* `Node`
  is a **core** type, so it shares the core version line with `Commit` and the store marker — a core
  format break legitimately invalidates all of them together. `RootMetaTree` took a *local* version
  precisely because it is an upstream-layer type that must evolve independently of the core; that reasoning
  does not apply to `Node`.
- **D-4. Remove the silent `SimpleNodeSerializer` fallback — AND the bare-`TUPM` read path.** A blob
  matching neither the `PNOD` header nor a **Dolt-serial-framed** `TUPM` flatbuffer now throws
  `UnsupportedFormatException` instead of being TLV-parsed. `SimpleNodeSerializer` becomes **test-only**
  — tests that exercised it through `Node.fromBytes` call `SimpleNodeSerializer.deserialize` directly.
  Only **one** non-`PNOD` shape is read: the Dolt **serial-prefixed** `TUPM` (a `NomsKind` byte +
  3-byte size — the cross-language characterization; all `cross-lang/fixtures/nodes/*.bin` are this
  shape). The port's own **pre-ADR-0072 *bare* `TUPM`** format is deliberately **not** read — accepting
  it would be a defensive reader for an old on-disk shape, which the pre-1.0 no-backwards-compat rule
  forbids; an old store is re-ingested, not silently parsed. (The first implementation kept a bare-`TUPM`
  branch, mislabeling it "cross-language"; corrected here — it had no producer and contradicted the
  "old store fails closed" consequence below.)

## Consequences

- **(Negative, accepted) A total-store format break — every node's bytes change.** The 5-byte header
  changes every `Node` blob → its content hash → every tree root → every commit id → the **whole**
  content-addressed store. This is a **larger** blast radius than ADR-0067, which deliberately left
  `Node`s untouched (only the metatree/commit-id moved). Per the pre-1.0 no-backwards-compat rule the
  migration is **back-up + restore** (re-ingest); the project is v0.2-BETA, where every user can `tar`
  the store. An old store read by the new engine fails closed (`UnsupportedFormatException` on the
  header-less old node) — it does not mis-read.
- **(Cost) Golden-hash pins re-baseline, once.** Internal determinism/bootstrap hash pins
  (`ChunkerDeterminismGateTest`, `BootstrapHashesTest`, `SmallTreeRootPersistsTest`) and the Layers 0–2
  cross-language characterization (`CrossLanguageFixtureTest`) are re-pinned to the new bytes as
  *characterization* (not a contract) in the same change. Re-pinning is done **only after** round-trip +
  semantic tests confirm the new node parses correctly — a re-pin of a buggy format would be
  self-concealing.
- **(Positive) `Node` now matches the core self-describing pattern**, and a future node-format change
  is detectable (a version bump → clean `UnsupportedFormatException` on an old engine) rather than
  additive-misparsed.
- **(Neutral — honest framing) The marginal *security* gain on the wired/stored path is modest.**
  Content-addressing already guarantees a reader fetches the chunk whose hash it asked for, so on the
  normal path the bytes are already the right node. The header's real value is robustness against
  **type-confusion / future-incompatible / non-content-addressed** reads (and closing the silent-TLV
  fallback), plus consistency — it is a fail-closed + future-proofing change, **not** a live-exploit
  fix. The pre-existing `SimpleNodeSerializer` fallback was already fuzz-hardened to fail closed on
  garbage (`NodeDeserializerFuzzTest`), so this does not patch a crash/out-of-memory hole.
- **(Neutral) `node.segment()` is now the header-stripped flatbuffer**, as it already was for
  Dolt-prefixed nodes. The only in-engine consumers that hash/compare it (`MergeEngine.fastPath`,
  `DiffEngine`) do **relative** node-vs-node comparisons, which are preserved; none compares it against
  a stored content-address.

## Follow-up / future work

- The next core-format-breaking change bumps `FormatVersion.CORE_FORMAT_VERSION` once for all three
  core types (store marker, `Commit`, `Node`) together — that is the point of the shared core line
  (D-3).
- Byte-for-byte Dolt parity remains a separate, deliberate project (CLAUDE.md); this ADR keeps the
  port's own format and only re-pins the existing Layers 0–2 characterization.
