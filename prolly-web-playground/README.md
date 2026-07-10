# prolly-web-playground — the interactive write-path explorer

A self-contained, browser-based teaching instrument for the prolly tree: build a live
tree, watch the rolling hash decide every chunk boundary byte by byte, insert/update/
delete and see exactly which chunks a write mints versus shares, walk reads and ordinal
seeks step by step, branch/merge/diff a real commit DAG, corrupt a chunk and watch the
names catch it, and garbage-collect with a gauge that predicts the sweep exactly.

**Open [`write-path-explorer.html`](write-path-explorer.html) in any browser** — no
server, no build step; `file://` works. The page is a six-script *set*: keep
[`core`](write-path-explorer.core.js) (the pure simulation),
[`state`](write-path-explorer.state.js),
[`bind`](write-path-explorer.bind.js) (the declarative binding microlibrary — below),
[`render`](write-path-explorer.render.js),
[`controls`](write-path-explorer.controls.js), and [`app`](write-path-explorer.app.js)
beside the html, loaded in that order (`bind` precedes `render`, which mounts on it). Sessions persist to IndexedDB (per browser);
"Reset storage" is the escape hatch.

To produce a single self-contained file for sharing or embedding:

```bash
python3 ../dev-scripts/bundle_explorer.py --docs-dir . --out explorer-bundle.html
```

## What it models honestly (and where it simplifies)

The chunking is a real BuzHash rolling over serialized entry bytes — boundary latched
mid-entry, closed at entry granularity, per-chunk hash reset — but at toy scale (8-byte
window vs the engine's 67; ~10-byte minimum vs 512 B). Node identities are FNV-64 for
display, not truncated SHA-512. Internal nodes carry the engine's subtree-count vector
(per-child counts in the hashed bytes, cumulative prefix sums at the accessor), the
leaf-level fast-forward walk is simulated and differentially pinned to the from-scratch
build, and everything is deterministic — no wall clock, no randomness outside seeded
generators. The full simplifications ledger is in the page footer.

The instrument holds itself to the project's honesty bar: every cost claim it narrates
(O(height) seeks, O(changed) diffs, skip-without-fetch) is structured so the claim could
fail, and is pinned by a 60-spec Playwright net (49 browser + 11 node-side invariants)
in the upstream monorepo — the node-side specs evaluate `write-path-explorer.core.js`
verbatim, so the net cannot drift from the shipped model.

## Data modes — which store is this page?

The header's **store** switch (beside the theme + tour controls) picks the page's store. **Switching never migrates data:**
each mode renders its own store's contents on entry, and what you edit in one mode is
untouched by the other.

![the page header: the store switch — sim, sim + shadow, real engine — with real
engine active](../docs/img/mode-switch.png)

- **sim** (default) — everything above: the in-page toy engine with commits, branches,
  the byte-level boundary lens, GC. All local.
- **sim + shadow** — the sim still drives, but the current tree's **key set** mirrors to
  [`prolly-playground-service`](../prolly-playground-service/README.md) — the actual
  Java engine — after every world change (one funnel with a key-set-diff guard). The
  panel shows the comparison that IS the lesson:

  > mirrored: 1 put → **real engine wrote 1 node** (sim wrote 5 chunks) — the sim splits
  > at toy scale (~35 B chunks); the engine's 512 B–16 KiB chunks hold hundreds of keys.

- **real engine** — the backend IS the store. The panes draw the engine's actual tree
  (before/after across your last write, with the freshly minted spine in amber), the
  ribbon shows its keys, the store panel shows its content-addressed node store
  (including nodes no longer reachable — nothing is ever deleted: copy-on-write, no
  sweep), and every insert/delete/rebuild goes straight over HTTP. Click any node and
  the inspector shows the **actual stored node** — parsed from its bytes by the real
  `Node` reader, its name re-hashed live (`verified ✓`), with cumulative subtree counts —
  and its stored bytes as a **field-annotated hex grid**: every byte painted by the role
  the engine's parse assigned it (envelope · keys · values/addresses · subtree varints ·
  honest flatbuffer scaffolding), with hover-decode and field ↔ byte cross-highlighting.
  The annotation comes from the server's own parse; the page never interprets a byte.

  ![the field-annotated hex viewer on the 1M-key tree's root: the PNOD envelope,
  32 child addresses, subtree-count varints, and a hovered key decoding to its
  int64](../docs/img/hex-viewer.png)

  The read instruments work too: point lookup, ordinal seek (the counted-B-tree
  descent), and range scan run on the backend and light the **measured descent** — the
  node hashes the store actually served — in the tree pane. A session **root-log**
  collects every root the engine has had; click an old one and it renders read-only
  from the same store (nothing is ever deleted — retention IS history; durable history
  is a commit layer's job, which this backend deliberately doesn't have).

  ![the session root chain after an insert and its deleting write: three chips, and
  the first and last are the SAME root hash, both marked HEAD — the restoration is
  visible in the chain](../docs/img/root-chain.png)

  Big trees stay drawable: panes render whole levels up to a ~320-node budget and
  collapse the frontier into dashed ⋯ **zoom-stubs** (click one to descend; breadcrumbs
  ascend) — a 1M-key tree draws as its root plus 32 stubs, not 4,500 nodes.

  ![the pane cap on a 1M-key tree: the root, 32 dashed zoom-stubs, and the breadcrumb
  narrating 'showing 33 of 4,588 nodes'](../docs/img/pane-cap.png)
 Two
  **benchmark buttons** run measured loops *server-side* (a browser loop would measure
  HTTP + JSON, not the engine): ⏱ read bench times N point lookups per-op (percentiles +
  nodes-read/op — grow the tree and watch latency track HEIGHT, not key count), ⏱ write
  bench times N single-key insert+flush commits, then deletes every bench key and
  asserts the root hash returns **byte-identical** (history-independence, live). Labeled
  honestly: your machine, warm page cache — a teaching measurement, not a product
  benchmark.

  ![the write bench on a 1M-key tree: percentile strip, the root-restored
  celebration, and the session history table](../docs/img/bench.png)
 A third button, **⚖ compare stores**, runs both benches on fresh
  memory / file / RocksDB stores server-side, each seeded by *copying* the live tree's
  reachable chunks — content addressing makes replication a byte copy, and every arm
  re-deriving the identical root hash is the proof the arms are byte-equal (the memory
  arm is the no-disk control; your tree is untouched).

  ![compare stores on the same 1M-key tree: throughput and layered p50/p95/p99
  latency bars per store, max ticks, and the per-arm identity
  proof](../docs/img/compare-stores.png)

  Sim-only
  instruments (commits, branches, lens, GC) dim out: the backend has none, and faking
  them would defeat the point. Values are sim-side only — the backend models a tree of
  longs.

If the backend isn't running: shadow degrades to a run-me hint and drops back to sim;
REAL mode shows an explicit **unreachable + retry** state and stays put (a clear switch
stays where you put it). Boot it with
`mvn -pl prolly-playground-service spring-boot:run` — or just open
`http://localhost:8080/`, which serves this playground with the backend already wired
same-origin. The backend defaults to `localhost:8080`; `?real=<base-url>` on the page
URL overrides it (the end-to-end tests run the service on an isolated port this way).
The chosen mode persists across reloads (`wp-data-mode`).

## The binding pattern (`write-path-explorer.bind.js`)

Dynamic HTML for the bench/compare surfaces lives as `<template>`s **in the .html
file**; JavaScript builds plain-data view-models and assigns them to a reactive object
(`Bind.mount`) — no HTML strings in the scripts for those surfaces. The library is ~150
lines, deliberately: **templates are logic-free** (a binding names a dotted path plus at
most one registered formatter — no expressions, no ternaries; Alpine.js moves JavaScript
*into* the HTML, this moves only data), rendering is a walking re-evaluator (any
assignment re-renders the mounted subtree in one microtask — no virtual DOM, at this
page's scale the dumb pass is unmeasurable), and `data-text` writes `textContent`, never
innerHTML (no injection path by construction). Directives: `data-text` / `data-show` /
`data-each="item of path"` (nestable, `<template>` child) / `data-attr-*` /
`data-class-*`. Reactivity is shallow — **replace, don't mutate** (`vm.rows = [...]`).
Errors are loud: an unknown formatter or malformed `data-each` throws rather than
rendering silently wrong.

**What deliberately does NOT bind — the view/event rule.** Bind is for **views of
state**: surfaces re-derived from data that changes (the REAL narration re-renders from
`realWorld` on every load — that's why it migrated well). The sim narration is the
opposite shape — an **event-log utterance**: each of its 42 messages is one-shot prose
with mid-sentence emphasis, fired by the action it describes and never updated. There
is no state to bind, and logic-free templates can't express variable inline markup
except as a segments-as-data JSON format that would make every call site longer than
the sentence it encodes — inverting the library's purpose (Bind exists to get structure
out of strings; prose IS the string). Assessed and declined 2026-07-14: bind state
views, write event messages. (The D3/SVG panes stay imperative for the same
right-tool reason.) Pinned by its own spec (`bind-library.spec.ts`) plus the full
explorer net running unchanged through it — and by [`bind-tests.html`](bind-tests.html),
a dependency-free in-browser harness: open the file, see 7/7. To reuse the library in
another project, copy `write-path-explorer.bind.js` — one file, no dependencies, no
build. (A standalone-repo extraction was tried and folded back the same day: with no
second consumer, a separate repo bought only twin-copy drift tax.)

## Companions in this folder

- [`treemutator-write-path.md`](treemutator-write-path.md) — the write path through the
  engine's layers, and why an insert only touches its spine (the prose twin of the
  explorer; its "named simplifications" match the footer).
- [`cursor-read-path.md`](cursor-read-path.md) — the read sibling: the point
  lookup, rank over the counted tree (the subtree-count descent), scan leaf-hops, the
  iterator family, and where caching does (and does not) sit
- [`class-roles.md`](class-roles.md) — the class-role index for `dolthub-java-port`.

Concept background lives in [`../docs/`](../docs/README.md) — start with
[the prolly tree](../docs/foundations/the-prolly-tree.md).
