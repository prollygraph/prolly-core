# Changelog

Pre-1.0: **the on-disk format evolves freely and without backwards compatibility.** A
version bump may mean old store directories cannot be read; export/re-import is the
supported transition. Entries below are release-level; day-to-day history is the git log.

## Unreleased

- Fixed: resizing the browser window in REAL mode redrew the SIM's trees and stats over
  the engine's panes (the resize listener called the sim renderers unconditionally; now
  routed by mode — caught by the README capture script, whose fullPage screenshots
  resize the viewport). Spec-pinned.
- Sim inspector migrated to Bind: five one-item `data-each` sections (message / key
  blame / tag object / commit object / node) — one shape stamped at a time, so shapes
  never collide in strict locators or `textContent`; conditional fragments are stamped
  sections, never `data-show` (hidden static text would leak into `textContent`). The
  ~130 lines of inspector HTML strings in render.js became data builders; chip clicks
  (blame-keep-key vs node-keep-chunk vs tree navigation vs locate) delegate once on the
  container, distinguished by which `data-*` each chip carries. Script order: `bind`
  now loads BEFORE `render` (which mounts on it).
- Bind standalone-repo extraction FOLDED BACK same day (was: published as `plainbind`):
  a one-file, zero-dependency microlib is consumed by copying the file, and with no
  second consumer a separate repo bought only twin-copy drift tax — the original plan's
  own "extraction is a later decision if it earns it" bar, applied late. Salvaged from
  the attempt: `bind-tests.html`, a dependency-free in-browser harness (7/7, includes a
  one-item-array idiom pin the Playwright spec lacks). Reuse story: copy the file.
- Root-log migrated to Bind: the session root chips are stamped from a chips view-model
  (class/title/label strings precomputed); clicks delegated on the container. The one
  imperative survivor is the newest-root auto-scroll, queued to run after Bind's render
  microtask. With this, every REAL-mode panel surface renders declaratively — the
  string-built HTML remaining in controls.js is sim-side only.
- Narration + inspector migrated to Bind: `#narration`/`#inspector` split into
  mode-owned children (the sim's ~45 imperative writers keep `#simnarr`/`#siminspect`;
  the REAL twins are templates whose every string is data). Each REAL surface is a
  data-each over a one-item array — `[]` removes the content from the DOM entirely, so
  strict single-element locators and negative text assertions hold; each inspect stamps
  fresh (details closed, hexdump reset — the old rebuild semantics). The mode-switch
  narration stash/restore is deleted: the sim's narration simply sits untouched.
- Declarative binding: `write-path-explorer.bind.js` (~150 lines, global `Bind`) — a
  no-build binding microlibrary; dynamic HTML now lives as `<template>`s with `data-*`
  attributes in the .html file and scripts assign plain-data view-models. Templates are
  logic-free (paths + named formatters only; logic stays in testable JS builders);
  `data-text` is textContent-only (no injection path); shallow reactivity, one-microtask
  batching. The bench + compare surfaces are the pilot migration — the full spec net
  passes unchanged through the new render path. The page is now a six-script set.
- Store-comparison p95 layer + max ticks: latency rows layer solid p50 → mid p95 → pale
  p99, and a tick marks max per arm. The panel scale STAYS at the slowest p99 — a single
  straggler is often 50-200× the p99 and would squash every bar to a sliver — so an
  off-scale max clips to the edge (dashed, value + how-far-off in the tooltip): shown
  honestly, never dominant. `p95Nanos` joins the bench responses.
- Store-comparison tail bars: the latency panels are now layered — a pale bar runs to
  p99 with the solid p50 bar on top, so the pale overhang past the solid IS the tail,
  per arm, on one scale (the panel rescales to the slowest p99). Row labels show both
  ends of the span (`p50 ⋯ p99`).
- Store-comparison latency bars: two p50 panels join the ops/s panels (read p50, write
  p50 — shorter = faster, scaled to each panel's SLOWEST arm). The inverted reading gets
  its own bar hue and an explicit title so no panel scans as "long is good" by habit;
  ops/s (mean-derived, outlier-dragged) and p50 (median) intentionally tell different
  stories on a cold arm.
- Store-comparison bar chart: the compare output leads with two bar panels (read ops/s,
  write ops/s — longer = faster), one bar per arm, each panel scaled to ITS fastest arm
  (read and write throughput differ by orders of magnitude; one shared scale would
  flatten the write bars invisible). Plain CSS bars; the numbers stay in the table.
- Store comparison in REAL mode: `⚖ compare stores` (`POST /api/bench/compare`) runs both
  benches on fresh ephemeral memory / file / rocks stores, each seeded by COPYING the live
  tree's reachable chunk set — content addressing makes replication a byte copy, and the
  identical per-arm root hash proves the arms are byte-equal; only the storage layer
  differs. The memory arm is the no-disk control. Live tree untouched.
- Benchmark section in REAL mode: `⏱ read bench` / `⏱ write bench` run measured loops
  SERVER-side (`POST /api/bench`; per-op nanos around the engine work alone — a browser
  loop would measure HTTP+JSON, not the engine) and render ops/s + p50/p90/p99/max +
  nodes/op, with a session history table for comparing runs across tree sizes. The write
  bench inserts absent keys one flush each, then deletes them all and asserts the root
  hash returns byte-identical — history-independence as a live invariant on every run.
- Op timings in REAL mode: every write and read shows two labeled durations — `engine`
  (measured server-side around the engine work alone, reported as `engineMicros` on the
  op responses) and `round-trip` (measured client-side around the fetch). Deliberately
  separate so wire+JSON cost is never misread as engine cost, or vice versa.
- Pane cap + zoom-to-subtree in REAL mode: panes render whole levels up to a 320-node
  budget and collapse the frontier internals into dashed ⋯ stubs (level-granular);
  clicking a stub descends into that subtree, breadcrumbs ascend; stats/ribbon/reads
  keep using the full pool — only the drawing is capped.
- Bulk random insert in REAL mode: a count input (default 10,000, cap 1,000,000) +
  preset buttons (+50k/+100k/+500k/+1M) — one POST, one flush, one measured write set;
  keys generated unique and clear of the hand-insert range. 1M measured at ~5 s
  (height 2, ~4,500 nodes). The REAL history axis moved into the commit graph's slot
  as a session-root chain (the dimmed sim strip hides in REAL mode).
- Field-annotated hex viewer in the REAL chunk inspector: `GET /api/nodes/{hash}/layout`
  returns byte regions computed by the engine's own parse (envelope / per-key / values /
  addresses / subtree varints / honest scaffolding residue), guaranteed to tile the byte
  array exactly; the page paints regions and never parses the format.
- `--inspect <store-dir> [head|hash]` terminal mode on the playground-service jar: decode
  any on-disk store with the engine's own reader (tree render + per-node view, hash
  verification aggregated). The store switch moved to the page header; chunk-config
  controls are labeled honestly (entries at toy scale vs the engine's fixed byte
  geometry) and inert in real-engine mode.

## 0.2.0-BETA — 2026-07-13 (current, unpublished)

The first coherent public shape of the repo:

- **`dolthub-java-port`** — the tree engine: flatbuffer node model (zero-copy reads),
  BuzHash content-defined chunking (67-byte window, 512 B–16 KiB), tree
  build/mutation with fast-forward structural sharing, cursors (key + ordinal over the
  counted tree), diff, three-way merge. 722 tests.
- **`prolly-storage`** — durable substrate: RocksDB- and file-backed node stores,
  manifest/refs, commit graph, garbage collection, merge. 288 tests.
- **`prolly-sync`** — pack-based replication with verified compare-and-set. 18 tests.
- **`prolly-dependencies`** — the BOM.
- **`prolly-playground-service` + `prolly-web-playground`** — the live instrument: a
  byte-level sim and a REAL-engine mode (measured write sets, measured read descents,
  time travel over retained roots, disk engines that survive restarts). 10 API tests.
- Docs: foundations + anatomy walkthroughs, the write-path and read-path deep dives,
  decision records, USAGE guides, the prove-it-yourself curl transcript.
