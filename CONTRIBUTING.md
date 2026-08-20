# Contributing

PRs land **here** — this repository is the source of truth for the engine modules. The
upstream private monorepo (where the port is developed alongside product layers) consumes
these modules as published artifacts in version lockstep; that affects release cadence,
not contributions. What you need to know fits on this page.

## Build

**JDK 25** and Maven. `mvn clean install` runs everything.

The quality gate is **`mvn verify`** — `mvn test` green does NOT mean the build is green,
because three gates bind to phases after `test`:

| gate | what it guards | when it fails, run |
|---|---|---|
| spotless (google-java-format, AOSP style) | formatting | `mvn spotless:apply` |
| license-maven-plugin | every file's copyright header matches its provenance (four templates under [`build/`](build/)) | `mvn com.mycila:license-maven-plugin:format` |
| maven-enforcer `dependencyConvergence` | one version per transitive | add a pin to the root `dependencyManagement` |

## Tests

- New behavior comes with a test in the same PR. State counts when you claim green
  ("722/722" beats "tests pass").
- **Property-based tests are named `*Property`** (jqwik) — Maven Surefire does NOT
  discover that pattern by default. Every module here already carries the
  `**/*Property.java` include; if you add a `*Property` file to a module that lacks it,
  add the include too, or your test silently never runs.
- Fuzz-regression seeds (Jazzer) and golden cross-language vectors
  ([`cross-lang/fixtures`](cross-lang/fixtures)) run inside the normal build.

## The rules that surprise people

- **Pre-1.0: no backwards compatibility.** The on-disk format evolves freely. Change a
  format → change the reader to require the new shape. No defensive readers, no
  deprecation shims, no auto-migrators. If a transition matters, it's an explicit
  operator step, not runtime code.
- **Don't hand-edit generated flatbuffers** — the schema is
  [`dolthub-java-port/src/main/fbs/prolly.fbs`](dolthub-java-port/src/main/fbs/prolly.fbs);
  a drift check runs in the build.
- **Docs are load-bearing.** Classes carry why+dependencies Javadoc; cited paths in
  `docs/` are rot-guarded by a test. If your change disproves a doc claim, fix the doc in
  the same PR.
- **No unverified numbers.** A performance claim needs a reproducible benchmark behind
  it; "measured once on my laptop" goes in the PR description, not the docs.

## AI-assisted contributions

AI assistance is welcome — this project itself is developed with it (see the
[AI Disclosure](README.md#ai-disclosure) in the README). Three requirements:

- **Disclose it** in the PR description: which tools, and roughly what they did (code,
  tests, docs).
- **Review it yourself before submitting.** You are the author of your PR; "the model
  wrote it" is not a review. The same bars apply as for any contribution — tests in the
  same PR, `mvn verify` green, docs updated.
- **Confirm you have the right to contribute it** under [Apache-2.0](LICENSE) — don't
  paste in AI output reproducing code whose license or provenance you can't vouch for.

## Versioning

The version is coordinated with the upstream consumer (see
[`RELEASING.md`](RELEASING.md)) — don't bump it in a feature PR; maintainers handle
release versioning.

## Reporting issues

- **Bugs / questions / feature requests:** GitHub issues on this repo. For a bug, the
  version (`0.2.0-BETA` or a commit hash), a minimal reproduction, and the observed vs
  expected behavior make it actionable.
- **Suspected vulnerabilities:** never a public issue — use the private route in
  [`SECURITY.md`](SECURITY.md).
- **Maintainer:** see [`MAINTAINERS.md`](MAINTAINERS.md).
