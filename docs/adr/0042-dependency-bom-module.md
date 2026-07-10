# ADR-0042: Dependency BOM module (`prolly-dependencies`)

## Status

Accepted, 2026-06-03. Guides an upstream plan.

## Context

Version numbers for the project's dependencies live in two overlapping places
in the root `pom.xml` (`prolly-parent`): a `<properties>` block of `*.version`
literals, and a `<dependencyManagement>` block that either references those
properties or imports upstream BOMs (`spring-boot-dependencies`, `grpc-bom`).
`prolly-parent` is simultaneously the **reactor aggregator** (the `<modules>`
list), the **inheritance parent** every module points at, and the **version
source of truth**. Three jobs, one file.

Two pressures make consolidating the version source-of-truth worth doing now:

1. **A single source of truth that is itself an artifact.** A consumer that
   wants to build against the port's modules today has to copy our version
   choices by hand — there is nothing importable that says "these versions go
   together". We want to publish `com.earasoft:prolly-dependencies` so a
   downstream project can `import` one pinned set, the way Spring Boot ships
   `spring-boot-dependencies`.

2. **A naming collision that has to be navigated, not ignored.** The obvious
   name `prolly-bom` is **already taken** — by a *product feature* (a Software
   Bill of Materials generator for supply-chain compliance: SPDX/CycloneDX,
   EO 14028; `packaging=jar`, serving `/api/v1/bom/*`, marked "SKELETON ONLY").
   "BOM" means two different things in this repo: a *Software* Bill of Materials
   (the product) and a *Maven dependency* Bill of Materials (this ADR). The new
   module must not reuse `prolly-bom`.

The non-trivial part is **not** "write a BOM" — it is that the most intuitive
structure (a child module that the parent imports) forms a Maven import cycle.
The decision is which structure avoids that cycle while keeping the BOM both
the internal source of truth *and* an externally publishable artifact.

## Options

The deciding axes: does it even build (cycle-safety), how much of the existing
build is disturbed, and whether the result is cleanly publishable to external
consumers (a consumer importing the BOM must not inherit our build config).

| Option | Cycle-safe? | Build disturbance | Externally publishable? |
|---|---|---|---|
| **A** — Child BOM whose parent is `prolly-parent`, imported by `prolly-parent` | **No** — Maven rejects: `prolly-parent → prolly-dependencies → prolly-dependencies` self-import cycle (the BOM inherits the parent that imports it). Empirically confirmed (see Decision). | Low | n/a (doesn't build) |
| **B** — Standalone *parentless* BOM, listed in the reactor, imported by `prolly-parent` | **Yes** — empirically confirmed building. Aggregator membership (`<modules>`) and inheritance (`<parent>`) are orthogonal, so the BOM can be in the reactor without inheriting it. | Low — add one module + one import; modules unchanged | **Yes** — a consumer imports `prolly-dependencies` with zero `prolly-parent` build config dragged along |
| **C** — Spring-style 3-pom split: new minimal `prolly-build` root (aggregator only), `prolly-dependencies` (versions), `prolly-parent` (build config, imports BOM) | Yes | High — every one of ~30 modules repoints its `<parent>`; the root pom's three jobs are physically separated | Yes |

## Decision

**Option B — a standalone, parentless `prolly-dependencies` module** (`packaging=pom`,
`groupId=com.earasoft`), listed first in the reactor, holding all version
management, imported by `prolly-parent` via `<scope>import</scope>`.

The deciding tradeoff is **cycle-safety at minimum disturbance, validated by
measurement rather than assumed.** Before writing this Decision the cycle
question was probed empirically (the project's measure-the-real-thing ethic):

- Option A was *built* — Maven failed model-building with
  `The dependencies of type=pom and with scope=import form a cycle:
  com.dolthub:prolly-parent → com.earasoft:prolly-dependencies →
  com.earasoft:prolly-dependencies`. The cycle is real, not theoretical.
- Option B was then *built* with the BOM made parentless — the largest upstream module
  compiled with caffeine's version resolving **only** through the imported
  child-module BOM (caffeine's management was removed from `prolly-parent` for
  the probe so the import was the sole source). No cycle.

Option C is the "textbook" structure (it is what Spring Boot does) and is
strictly cleaner in separation-of-concerns, but it forces a `<parent>` rewrite
on every module — a high-revisitation-cost, error-prone churn whose only payoff
over B is cosmetic role-separation of the root pom. B already delivers both of
the goals that motivated the work (single importable source of truth +
external publishability) without that churn. If the root pom's triple role ever
becomes a real maintenance problem, C remains available as a follow-on; B does
not foreclose it.

Sub-decisions:

- **D-1 — Name `prolly-dependencies`, not `prolly-bom`.** `prolly-bom` denotes
  the Software-Bill-of-Materials *product*; reusing it would conflate two
  unrelated concepts. `prolly-dependencies` follows the `spring-boot-dependencies`
  convention and reads unambiguously as "the version source of truth."
- **D-2 — Parentless, by necessity and by design.** Necessity: inheriting
  `prolly-parent` recreates the cycle (Option A). Design: a publishable BOM
  *should* be parentless so consumers importing it inherit only version
  management, never our compiler/surefire/jacoco config.
- **D-3 — BOM holds version `<properties>` + `<dependencyManagement>`; the
  parent keeps `<pluginManagement>` + build config.** Versions move; build
  behavior stays where it is. `prolly-parent` imports the BOM and retains only
  what a consumer should *not* inherit.
- **D-4 — Internal module versions are managed in the BOM too** (e.g.
  `dolthub-java-port`, and any inter-module coordinates currently in
  `prolly-parent`), so the published BOM lets a downstream consumer pin the
  port's own modules as one coherent set — the externally-publishable half of
  the scope.
- **D-5 — `${project.version}` import indirection.** `prolly-parent` imports
  `prolly-dependencies` at `${project.version}`; the BOM declares its own
  version literally (it has no parent to inherit it from). The two are released
  in lockstep from the same reactor, so they never skew.

## Consequences

Positive:

- One file owns every version. A bump is a one-line edit in `prolly-dependencies`,
  visible to every module and to external consumers identically.
- External consumers gain an importable, pinned, coherent set — the
  publishability goal. The BOM is a thin, parentless artifact with no transitive
  build baggage.
- The naming collision is resolved cleanly; "Software BOM" (product) and
  "dependency BOM" (build) stop competing for one name.

Negative / cost:

- **A new published artifact is a contract.** Once consumers import
  `prolly-dependencies`, removing or renaming a managed coordinate is a breaking
  change for them. This is the format-stability obligation the "externally
  publishable" scope buys (cf. the pre-public-moat D-8 gate: publish on
  boundary + format stability). Pre-1.0 we still evolve freely, but each removal
  from the BOM is now a *visible* downstream break, not a silent internal one.
- **The root pom still has two jobs** (aggregator + inheritance parent). B does
  not separate those; it only extracts the *third* (versions). Accepted as the
  price of low disturbance; Option C is the escape hatch if it ever bites.
- **Reactor-order subtlety.** `prolly-dependencies` must be present for
  `prolly-parent`'s model to resolve. It is listed first in `<modules>` and, being
  parentless + dependency-free, builds instantly; a clean reactor build resolves
  the import from the reactor (confirmed by the Option B probe).

Neutral:

- Module poms are untouched by B — they keep `prolly-parent` as `<parent>` and
  keep declaring dependencies *without* versions exactly as today. The change is
  invisible to them.

## Follow-up / future work

- **ADR-(future) — extract `prolly-build`** (Option C) only if the root pom's
  aggregator-plus-parent dual role becomes a genuine maintenance burden. Trigger:
  a change that needs the aggregator and the parent to diverge.
- **Publish pipeline** — actually deploying `prolly-dependencies` to a repository
  for external consumers is out of scope here (this ADR makes it *publishable*,
  not *published*); a release-process plan owns that.

## Open questions

- Q1 — Should `prolly-dependencies` also manage the *upstream BOM imports*
  (`spring-boot-dependencies`, `grpc-bom`) so a consumer importing
  `prolly-dependencies` transitively pins those too? **Answered NO (2026-06-03,
  during the plan's Step 2).** Originally leaning yes, but the migration disproved
  it: `spring-boot.version`/`grpc.version`/`protobuf.version` are consumed by
  *build-section plugin coordinates* (`spring-boot-maven-plugin`, `protoc`,
  `protoc-gen-grpc-java`), which cannot read an imported BOM's `<properties>`, and
  the parentless BOM cannot inherit them from `prolly-parent`. Moving the imports
  would force those version properties to be duplicated in both poms. The imports
  stay in `prolly-parent`. The deeper principle this surfaced: **a dependency BOM
  manages dependency versions, not plugin/build-tool versions** (see
  an upstream plan D-6). The "single source of truth for all
  versions" framing narrows honestly to "all *dependency* versions."
