# prolly-core — the engine ring

Guidance for anyone — human or agent — working in this repository.
Contribution mechanics are in [CONTRIBUTING.md](CONTRIBUTING.md); this file is the
standard the work is held to.

## What this repository is

The bottom of the stack: a Java port of a content-addressed prolly tree, the storage layer
over it, replication, and the many-repos primitive. Everything above depends on these
invariants holding, so correctness pressure here is higher than anywhere else in the family.

## Ring-specific things to know

- **Provenance is not decoration.** This ring contains ported code. A file that translates
  specific upstream Go code carries the upstream copyright; a Java-original file does not.
  Get a new file's header right — see `NOTICE` and the header templates under `build/`.
  The chain is Earasoft → Dolthub → Attic Labs (Noms) → kch42 (buzhash, MIT).
- **Off-heap memory is real here.** The Foreign Function & Memory API is used directly:
  a `MemorySegment` outliving its `Arena` is a use-after-free that does not crash loudly.
- **The production buffer pool is the on-heap one.** An off-heap implementation exists and
  is deliberately not the default; its class documentation states the gate it must pass
  before it can be promoted.
- **`prolly-concurrency` is test-only and gated off** (`-Pconcurrency`, and a separate
  script for the jcstress half). It exists because "race-free by construction" was once
  asserted here without proof.
- **Usage documentation is executable.** `USAGE.md` snippets in `dolthub-java-port` and
  `prolly-storage` are backed by a `UsageExampleTest`; keep them that way rather than
  letting a snippet drift.

## The build IS the quality gates

**`mvn test` passing is not the bar — `mvn verify` is.** Gates bind to lifecycle phases
that run *after* `test`, so a formatting, licensing, or dependency-convergence violation
sails straight through a green `mvn test`:

| Gate | Phase | Fix a failure |
|---|---|---|
| dependency convergence (enforcer) | `validate` | add a convergence pin to the root `dependencyManagement` |
| spotless (google-java-format AOSP) | `verify` | `mvn spotless:apply` |
| license headers | `verify` | `mvn com.mycila:license-maven-plugin:4.6:format` |

Trust the `BUILD` line and the artifacts, not a bare exit code.

## How this project writes and reasons

These conventions are the reason the code and the prose can be trusted without
re-deriving them. They are written as instructions to you, the contributor — human or
agent.

### Ground every claim, or mark it ungrounded — in the sentence

A factual claim names its evidence: a `file:line`, a measured number, a cited document.
A claim you cannot ground, you label as reasoning *where you make it*. "I think, but
haven't verified" is always acceptable; asserting it is not. Never let an inference wear
the clothes of a fact.

Four moves follow from that:

- **Never fabricate to fill a gap.** If the input is missing — the file isn't there, the
  number was never measured — say so and stop. A confident guess presented as fact is the
  worst failure available, because one invented detail costs the reader's trust in
  everything else.
- **No invented quantities.** A number is either verified by re-running or re-reading, or
  explicitly flagged as order-of-magnitude intuition with the reasoning shown. Prefer
  ratios to absolutes, and name the machine.
- **Scrutinise your own superlatives.** "Always", "every", "the fastest" — each absolute
  invites a counterexample. Defend it or narrow it before it ships.
- **Retract in place, visibly.** When a claim turns out wrong, correct it *and record that
  you did*. The retraction is the credible artifact, not an embarrassment to bury.

### Measure the real thing

Benchmarking does not substitute for understanding the system; you need the understanding
to design the benchmark. Before measuring, name three things — if you cannot, you do not
yet understand the system well enough to measure it:

1. **The variable** you are changing.
2. **The regime where it can act.** A cache policy only matters when the cache is smaller
   than the working set; a lock only under real concurrency. Measuring outside the regime
   measures "no effect" and is a false negative.
3. **The confounds** to isolate.

Two further rules, both learned expensively: **real data is not a real workload** (the
access *sequence* usually decides the result), and **the instrument must be cheaper and
cleaner than the system under test, or you measure the instrument.** Distrust a clean
result — ask what workload would flip it, then go test that.

### Deterministic work gets a script, not the model

If an operation must be repeatable, format-exact, or is run more than a couple of times,
encode it in a tested script and run that. A model asked to perform the same mechanical
edit twice produces subtly different output. The test: *if I did this twice, would I want
byte-identical results?* If yes, script it.

### Reuse hardened infrastructure — build only the novel value

For a solved, commodity problem — above all one that parses untrusted bytes — use the
mature library rather than hand-rolling. The deciding question is not "library or
hand-rolled" (this project's whole worth is hand-built) but: *is the hard part here
**hardening**, or **novelty**?* If hardening, reuse; if it is the thing nothing
off-the-shelf ships, build it.

### Search the record before you investigate

Before chasing a bug or writing a design, grep the repository's own documents — the
architecture decision records, the changelog, the docs tree. The thing you are about to
study may already be decided, documented, or fixed. Re-deriving a solved problem is the
most avoidable way to spend an afternoon.

### Pre-1.0: no backwards-compatibility code

New fields are required; readers do not accept old shapes. No defensive readers, no
deprecation shims, no auto-migration in a boot path. If a format change needs a migration,
it belongs in an operator-run one-shot tool. When in doubt, remove the old code cleanly.

### Spell out abbreviations

Write "property-based testing", not "PBT"; "garbage collection", not "GC". Exempt are
terms everyone decodes (HTTP, JSON, API, RDF, SPARQL) and literal identifiers, which keep
their real spelling. The rule exists because you cannot fluently discuss what you cannot
pronounce.

### Test the production primitive

A test that exercises a non-production implementation proves nothing about production.
Where a primitive is swappable, parameterise over both; before promoting a non-production
one to default, its own test must be green first.

## Where this came from

These conventions were distilled in the private monorepo this ring was extracted from,
where they were learned from the times the discipline actually mattered. They are
reproduced here because a ring is where the *public* artifacts live — which is exactly
where a confidently-wrong claim costs the most.
