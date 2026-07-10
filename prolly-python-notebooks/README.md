# Content-Defined Chunking & Prolly Trees

Runnable, self-checking notebooks for the chunking layer of the prolly-tree engine that lives in
this repository (the `dolthub-java-port` module). Every claim is *computed*, not asserted in
prose — and the production notebook ends with a parity certificate: the Python port reproduces
the real Java `RollingHashSplitter`'s chunk boundaries **byte for byte**.

## What's inside

```
.
├── chunking_prolly_trees.ipynb   # first principles: FNV low bits, masks, clamps, windows,
│                                 #   tree height, multi-level trees, Merkle-DAG commits
├── production_chunker.ipynb      # the ACTUAL engine chunker, layered — start here if you
│                                 #   already know CDC basics; ends with Java parity
├── boundary_hashes.ipynb         # FNV-1a vs the mask rule vs buzhash (vs gear): the
│                                 #   influence-horizon lens, measured pros and cons
├── influence_horizon.ipynb       # the horizon concept itself: profiles, horizon→healing,
│                                 #   both failure poles, per-element vs rolling
├── merkle_radix_dictionary.ipynb # a Deterministic Merkle Radix dictionary, built + proven:
│                                 #   history independence by trie canonicity, NCIt measurements
├── merkle_radix_dict.py          # its tested core (canonical build ≡ any insert order)
├── test_merkle_radix_dict.py     # hypothesis pins (7)
├── prolly_chunking.py            # the tested core both notebooks import (stdlib-only)
├── test_prolly_chunking.py       # pytest + hypothesis property tests (14 chunking)
├── fixtures/
│   ├── EmitBoundaryFixture.java  # emits boundaries from the REAL RollingHashSplitter
│   └── boundaries.json           # the committed fixture the tests assert against
├── Makefile                      # make venv | test | run | fixture | lab
└── requirements.txt              # full lockfile (Python 3.12)
```

## Quickstart

```bash
make venv        # uv-managed Python 3.12 + pinned deps (host python lacks ensurepip)
make test        # 21 property/fixture tests — includes byte-identical Java parity
make run         # execute ALL THREE notebooks headless; every in-notebook assert must pass
make lab         # open JupyterLab
```

`make fixture` re-emits `fixtures/boundaries.json` from the engine jar (requires a JVM and a
built `dolthub-java-port` in the local Maven repository). Re-run it only when the splitter
changes — a boundary change is format-level news, not routine drift.

## The notebooks

**`chunking_prolly_trees.ipynb` — first principles.** FNV-1a asserted against textbook vectors;
why only the low `k` bits decide boundaries (the sealed mod-2^k lane); target size as mask width
(geometric distribution, mean 2^k, verified against *real* hashes, not just a synthetic model);
MIN/MAX clamping and its measured cost; window size as edit-healing distance (a *distribution*
over insertion sites, not one anecdote — plus the repetitive-input degenerate case); shuffle →
sort → a byte-identical **root address** (the tree is built, not just the leaf partition); and
honest edit blast radius: one leaf **plus the path to the root** — the root always changes.

**`production_chunker.ipynb` — the engine's chunker, layer by layer.** The rolling window and
the locality property that makes the engine's skip-the-prefix optimization sound; fixed mask +
clamp and its tension; the **progressive-mask staircase** (`patt = (1 << (15 − offset/1024)) − 1`
— boundary probability doubles every KiB, a cut is certain by 15 KiB, the hard MAX is
unreachable) with its closed-form size distribution matched against measurement; the **per-level
salt** (SHA-512 of the level byte) that decorrelates cuts across tree heights; a real multi-level
tree with a root address and measured cross-edit chunk reuse; and the **parity certificate**
against the Java engine (levels 0–2, two entry granularities, byte-identical).

**`boundary_hashes.ipynb` — how the hashes themselves work.** The distinction the other two
notebooks lean on without proving: FNV-1a is an *accumulator* (infinite influence horizon —
measured — which is why it cannot roll and why per-element chunking hashes items whole); buzhash
is a *rolling window* (horizon exactly W, by construction — the cliff is measured at 67); gear's
horizon is structurally ≤ 32 (the state width), and masking its low bits shrinks the horizon to
the mask width — why FastCDC masks high bits. Plus: the mod/mask test is a *decision rule*, not a
hash (same geometry from any fair source); FNV's position-dependent mixing (fine for the boundary
coin, disqualifying for addresses); and buzhash's swap-at-32 structured collision, demonstrated
and property-tested.

**`influence_horizon.ipynb` — the concept, promoted to a notebook.** The horizon profile
measured as a curve per scheme (cliffs at exactly 13/32/67; FNV flat at 100%); the causal link
horizon → healing (median healing distance tracks W in a sweep, ≈ W + one target chunk); both
failure poles measured as distributions — the FNV accumulator's bijective lane orbits never
merge (median recovery 0%, with the delicious ≈1/64 fixed-point fluke where an edit gets lucky
*at insertion time*), fixed-size never recovers, the window recovers ~everything; and the
supplement: per-element checking needs no window because *the item is the horizon* (its profile
is a 1-item cliff — and the d=0 rate itself re-derives the sealed lane: only 12 of 32 bit-flips
are visible to a low-3-bit decision), while the window *manufactures* a horizon where no item
structure exists. Production is deliberately both: byte-denominated geometry, item-aligned cuts.

**`merkle_radix_dictionary.ipynb` — a proposed structure, put on trial.** A prefix-compressed
radix trie with content-addressed nodes ("Deterministic Merkle Radix Prolly Tree") as a
triple-store dictionary, implemented for real and measured on an NCIt-shaped 50k-term corpus.
What survived: history independence (by trie canonicity — no chunker needed; hypothesis-pinned
byte-identical roots over arbitrary insert orders), bounded geometry, pruned diffs (~0% of nodes
visited), page-level edit locality (6 of ~1400 pages changed by 100 inserts). What didn't: the
prefix-dedup footprint claim — 20-byte addresses cost about what prefix sharing saves, and a
flat sorted layout + zlib is ~10× smaller. The structure earns its keep operationally, not on
disk bytes.

## The three knobs (stop conflating them)

| knob            | set by                       | controls                                   | regime           |
|-----------------|------------------------------|--------------------------------------------|------------------|
| **target size** | mask width `k` → `2^k`       | mean chunk size = fanout = tree depth      | always           |
| **MIN / MAX**   | clamp thresholds *or* the staircase | tail shape (kills runts and giants)  | always           |
| **window `W`**  | rolling-hash span            | edit-healing distance / decorrelation      | byte-stream only |

Production's staircase folds the second knob into the first: instead of a length-counter MAX
(which cascades — measured in both notebooks), the boundary *probability* rises with offset, so
late cuts are still content-chosen and history stays local.

## Numbers

The specific measured values (means, healing distances, blast-radius percentages) live in the
executed notebook outputs and regenerate on every `make run` — they are deliberately **not**
duplicated here, where they would rot. If a number in a notebook surprises you, re-run it; if it
still surprises you, that's a finding.

## Relationship to the engine

The chunker these notebooks model is `RollingHashSplitter` + `BuzHash` in `dolthub-java-port`
(this repository). Two engine write-ups carry the measured design context:

- [`docs/write-ups/chunker-boundary-detection-study.md`](../docs/write-ups/chunker-boundary-detection-study.md)
  — candidate boundary functions (direct mask / gear / buzhash) benchmarked, and the adoption
  verdict: the chunker is a small share of ingest, so **geometry, not compute, is the lever** —
  which is exactly why the staircase's *shape* (modeled here) is the part worth understanding.
- `cross-lang/BITCOMPAT_FINDINGS.md` *(private monorepo; public copy in the [prolly-rdf ring docs](https://github.com/prollygraph/prolly-rdf/blob/main/docs/bitcompat-findings.md))* — why the engine's chunker is its own deterministic rule
  rather than Dolt-parity (pre-1.0, internal determinism is the contract).

Use these notebooks to reason about a change to mask policy, window, or salt *before* touching
the Java — and re-emit the fixture after, so the parity test tells you exactly what moved.

## Roadmap

- [x] Cross-check against the Java implementation's boundaries (byte-identical fixture, levels
      0–2, two entry granularities).
- [x] Secret-seeded variant (production's per-level SHA-512 salt, modeled + parity-checked).
- [x] Property-based tests for history-independence, blast radius, window locality.
- [x] Serialize real quads and chunk over their bytes (production notebook §7: SPOC-sorted
      ordinal-key quads with the study's realistic shape — including the reproduction of the
      direct-mask failure and the 88%-reuse update measurement).

## License

Apache-2.0 — inherited from this repository (see the root `LICENSE`). The buzhash table values
derive from the kch42/buzhash reference (MIT), as attributed in `prolly_chunking.py` and the
Java `BuzHash`.
