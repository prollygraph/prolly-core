# ADR-0069: Chunker internal node constraints

## Status

Accepted, 2026-06-26. Guides an upstream plan.

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

The port's content-defined chunker (`RollingHashSplitter` + `TreeMutator.Chunker`) is the port's own,
non-ported boundary recipe — the single most load-bearing piece of *original* engine code, and (per the
`splitter-productionization` plan) its least-guarded one. Dolt's `chunker.append`
(`store/prolly/tree/chunker.go`, v2.0.3) enforces **three** invariants on a new key/value pair; the port
modeled only the first:

1. **Atomicity** — a boundary may fall before/after a pair, never between its key and value. *(Modeled:
   the port appends key+value as a unit and checks `crossedBoundary()` only after both.)*
2. **Byte overflow** — a node's data may not exceed `MaxVectorOffset` (`MaxUint16` = 65535); `hasCapacity`
   forces a boundary *before* a pair that would overflow. *(Not modeled.)*
3. **Non-degenerate internal node** — an internal node (level > 0) must hold ≥2 children; Dolt suppresses
   a boundary on a single-item internal node. *(Not modeled — the `TreeMutator` comment read "does not
   yet model … the re-emit path has run without them".)*

Why now / why it is not cosmetic: **measurement (this plan, Step 5) showed constraint (3) is a real,
reachable crash.** A key whose bytes alone exceed the splitter's ramp force-offset (`15 << 10` = 15360)
crosses a boundary even as a *lone* `(key, childHash)` internal item. Without the guard, each level emits a
single-child node and `appendToParent` creates another level holding the *same* huge key — which crosses
again — cascading upward forever. `DegenerateInternalNodeGuardTest` reproduced it: a **single ~20 KiB
key** through `applyMutations` throws `java.lang.StackOverflowError` in ~0.66 s. That is a
denial-of-service on the **core write path** reachable by one adversarial key (an oversized value /
JSON value as a key component is plausible) — unacceptable to carry into a public release.

## Options

| Option | Crash-safety | Matches Dolt's intent | On-disk format impact | Complexity |
|---|---|---|---|---|
| **A** — Model constraint (3): suppress the boundary on a single-item internal node | **Fixes the crash** (height stays bounded) | Yes (Dolt's `degenerate` check) | Only the pathological huge-key shape changes; normal trees identical (goldens unchanged) | One guard in `Chunker.append` |
| **B** — Descope: document the limitation + a guard test, leave the code as-is | **No** — the StackOverflowError still ships | n/a | none | none, but ships a crash |
| **C** — Reject keys above a size cap at the API boundary | Avoids the crash by refusing input | No (Dolt accepts large keys) | none | pushes the limit onto every caller; breaks legitimate large literals |

## Decision

**D-1 — Model constraint (3) (Option A).** A crash on the core write path is **not** a descopable
limitation, so Option B is off the table; Option C refuses legitimate input and diverges from Dolt's
graceful handling. The port adopts Dolt's *intent* (not its bytes): in `TreeMutator.Chunker.append`, a
boundary is suppressed while `level > 0 && pending.size() == 1`, so a single-item internal node cannot
start an unbounded cascade — it accumulates a second child, or `done()` flushes it as the (allowed)
single-child root. A lone huge key now builds a bounded level-1 tree.

**D-2 — Do *not* model constraint (2) (byte overflow), deliberately.** The port's rolling-hash cap forces a
boundary by offset 15360 — far below Dolt's 65535 structural cap — so multi-item nodes are already bounded
well under it, and a *lone* oversized item is the **defined** large-chunk behavior (it cannot be split
mid-item; pinned by `SplitterGeometryProperty` / Goal 2). Modeling Dolt's `hasCapacity` pre-flush would buy
nothing for multi-item streams and is unnecessary for correctness.

## Consequences

- **Positive:** the adversarial-key StackOverflowError is eliminated (`DegenerateInternalNodeGuardTest`:
  a 20 KiB lone key and a 20 KiB key among 500 normal keys both build bounded, readable trees). The whole
  `dolthub-java-port` suite stays green (**786 tests**) and the determinism goldens
  (`ChunkerDeterminismGateTest`) are **unchanged** — the guard only alters the pathological single-child
  case, which previously crashed, so no normal tree shape moves.
- **Neutral / format:** the port's tree shape for a huge key differs from a hypothetical Dolt build, which
  is fine — bit-compat with Dolt is optional/deferred and the port owns its format (pre-1.0, no
  backwards-compat).
- **Negative / residual (constraint 2, narrowed):** a *single item whose serialized size exceeds the node
  serializer's offset limit* is still untested. The 20 KiB and 1 MiB single-item paths work, but the exact
  ceiling (the Java flatbuffer offset width, which is **not** Dolt's `uint16` 65535) is unverified — see Q1.

## Follow-up / future work

- The splitter-productionization plan's remaining steps (test hygiene, the `@implNote` Go-parity
  correction, the prepublic gate) build on this.

## Resolved questions

- **Q1 — single-item serialized ceiling: RESOLVED (it was a silent-corruption bug, now fail-closed).**
  Measured (`NodeSerializerSizeCeilingTest`): `FlatbufferNodeSerializer`'s per-item end-offset table is
  `uint16` (`(short) offset`), so a node whose key/value byte sum exceeds **65535** truncated the offset —
  a **65536-byte value read back as 0 bytes, no exception** (silent corruption). The `MAX_CHUNK_SIZE`
  splitter cap keeps multi-item nodes far under this, so only a **lone item > 65535** reached it (it
  cannot be split). **Fix:** `toUint16OffsetOrThrow` — fail closed (throw a clear `IllegalArgumentException`),
  matching the existing 2 GiB `toIntSizeOrThrow` guard + Dolt's `MaxVectorOffset`. Values ≤ 65535 round-trip
  unchanged; 786 dolthub-java-port tests green. **Severity correction:** this ADR called Q1 a
  "lower-severity gap than the constraint-(3) crash" — that was **wrong**. A loud crash (the
  StackOverflow) is fail-fast; *silent* corruption is the worse failure by the project's "silently-wrong =
  unrecoverable trust damage" ethic. It is narrow/rare (needs a 64 KiB+ single value) but not low-severity.
  **Future option (not done):** supporting larger single values would mean widening the offset table to
  32-bit — a deliberate format change behind its own ADR.
