# ADR-0073: Commit objects in the nodestore

## Status

Accepted, 2026-07-01. Guides an upstream implementation plan.
Builds directly on [ADR-0071](0071-commit-identity-includes-parents.md) (which made the commit *identity* a
Merkle directed acyclic graph node but deliberately scoped out *where the commit is stored*) — its
`commit-identity-redesign` plan is **complete**, so the sync/graph surface this plan touches is stable.

## Context

**Two layers store commits two different ways today.**

- **`prolly-storage` `Database`** already treats a commit as a content-addressed Merkle directed acyclic graph node: a
  `Commit` is serialized and written into the `NodeStore` (`Database.java:350`,
  `store.write(newCommit.serialize())`), carrying parent-commit hashes (`Commit.getParents()`) and the
  data-tree root (`Commit.getRootValueHash()`); the mutable branch HEAD lives separately in a
  compare-and-set `Manifest`. That is git's model — immutable objects in a content-addressed store,
  mutable refs in a side table.
- **The upstream RDF4J Sail** does **not** reuse `Database`. It records commits as **text rows in a
  flat append-only `commits.log`** file beside the store (`CommitLog`, `beside(dir)`), with a mutable
  `RefsStore` (branch→id) and a `RootMetaTreeStore` (durable current root). The commit record is a log
  row, not a chunk.

**The identity is already Merkle; only the storage is not.** [ADR-0071](0071-commit-identity-includes-parents.md)
(D-1) made the RDF4J commit id `hash(metaTreeHash ‖ ordered parent-ids ‖ author ‖ message)` — *"Like git,
the id is a Merkle-DAG node."* So **the commit id is already the content-address of the commit's logical
content** (its `CommitId.of(...)`); the object bytes simply are not stored in the chunk
bag. ADR-0071 explicitly widened *what content the id covers*, "not *whether* it is content-addressed" —
leaving exactly this decision open.

**The heavy data is already a chunk.** The `RootMetaTree` (the actual RDF snapshot) is content-addressed
in the `NodeStore` already; the commit *record* is a thin envelope over a tree-pointer + parent-pointers +
author/message/time. So this decision moves a small envelope, not the data.

**Two hard constraints bound any solution.** They are the reason "put everything in the NodeStore" is not
literally achievable:

1. **Mutable branch refs cannot be content-addressed.** A branch HEAD moves on every commit; content
   addressing is immutable-by-construction. Refs must stay in a mutable store (`RefsStore`/`Manifest`) —
   git keeps refs out of the object store for exactly this reason.
2. **The wall-clock timestamp is deliberately hash-excluded** (ADR-0071 D-2 — a deterministic id makes the
   *same logical commit* on two peers get the *same id*, so merge-sync converges by construction; a
   timestamp-in-id would make a fixed-point merge loop diverge). Because a commit chunk's hash *is* its id,
   the timestamp can never live inside the chunk — it must sit in a sidecar.

**Not the ADR-0006 recursion.** The upstream ADR-0006 (commit-log-as-RDF, not exported) rejected materializing the commit log
as **RDF triples in the versioned graph** (writing commit-N's triples is itself a write → commit N+1 →
recursion). That is the *data plane*. Storing the commit *record* as a content-addressed *chunk* is the
*object plane* and does **not** recurse — the `prolly-storage` `Database` does it today with zero
recursion. ADR-0006 does not bear on this decision; it is cited here so a future reader does not conflate
them.

**Why now.** The `FileNodeStore` backend (a git-loose-objects `NodeStore`; see
`prolly-storage/an upstream plan) makes concrete the story that *everything reachable is a
content-addressed chunk you can inspect / `tar` / `rsync`* — and the flat `commits.log` is now the one part
of the object graph that is not one. ADR-0071 just did the identity groundwork, so moving the storage is a
small, natural follow-on rather than a green-field change.

## Options

Axes chosen for the deciding tradeoffs: whether commits become a content-addressed Merkle directed acyclic
graph *in the NodeStore*; whether the option preserves ADR-0071's deterministic (timestamp-free) id; whether
it removes the two-layer commit-machinery duplication; and blast radius.

| Option | Content-addressed in the NodeStore | Preserves ADR-0071 deterministic id | Removes 2-layer duplication | Blast radius |
|---|---|---|---|---|
| **A** — Status quo: commit records stay rows in the flat `commits.log` | No — flat text rows (identity is Merkle, storage is not) | Yes | No | None |
| **B** — Commit objects become content-addressed chunks (hash = the ADR-0071 id); timestamp + ordering peeled into a thin sidecar; refs stay in `RefsStore` | **Yes** | **Yes** | Partial — RDF4J adopts the storage model but keeps its own orchestration | Medium — the Sail's commit-log machinery + a one-shot migration |
| **C** — Delete the Sail's bespoke commit machinery; delegate to the `prolly-storage` `Database` | Yes | **No** — `Database`'s `Commit` serializes the timestamp (`Commit.java:73,134`) so its id *includes* wall-clock time; reusing it regresses ADR-0071 D-2 | Yes (full) | Large + a cross-layer identity conflict to resolve first |
| **D** — Dual-write: commit chunks **and** keep the full flat log as a redundant index | Yes | Yes | No — adds a second source of truth | Medium+ (two writers to keep consistent) |

## Decision

**Adopt Option B.** The deciding tradeoff: B gets the uniform "a commit is just a content-addressed chunk"
object model *while preserving* ADR-0071's deterministic id (the property the whole merge-sync convergence
story rests on), at a bounded blast radius. C is the architecturally purest (one commit model for both
layers) but is **refused now** because the storage-layer `Commit` bakes the timestamp into its hash — the
exact thing ADR-0071 forbade at the RDF4J layer — so C would trade a storage win for an identity
regression. D is refused because a second source of truth is the drift risk content-addressing exists to
eliminate.

**D-1 — A commit is a content-addressed chunk; the id *is* its NodeStore address.** Define the commit-object
wire format as the canonical serialization of `{metaTreeHash, ordered parent-ids, author, message}`, chosen
so that `NodeStore.write(commitObject)` returns exactly the ADR-0071 `CommitId.of(...)` value. Identity and
storage location collapse into one fact: the commit id is the address of the commit chunk, and reading a
commit is `store.read(id)`. This makes commit identity *self-verifying* (a chunk that does not hash to its
id is corrupt, like every other chunk).

**D-2 — The wall-clock timestamp lives in a thin ordered sidecar, never in the chunk.** Because the chunk's
hash is the id and the timestamp is hash-excluded (ADR-0071 D-2), time cannot ride in the chunk. `commits.log`
shrinks from the full record to an append-only *time-index* — `<datetime> <id>` per row — that Memento /
`/sparql/timemap` scan in time order. The chunk holds the durable, deterministic content; the sidecar holds
the non-content-addressable metadata (time + observation order).

**D-3 — Mutable branch refs stay in `RefsStore`/`Manifest`, unchanged.** Content addressing is
immutable-only; a branch HEAD is a moving pointer. This is not a regression — it is the same objects-vs-refs
split git uses and that `Database` already uses.

**D-4 — Do not reuse the `prolly-storage` `Database` (Option C withdrawn now).** Its `Commit` serializes the
timestamp (`Commit.java:73` field, `:134` `bb.putLong(timestamp)`), so its content-hash includes wall-clock
time and is non-deterministic across peers — incompatible with ADR-0071. Full cross-layer unification (make
`Database` timestamp-free too, so one `Commit` model serves both layers) is real and desirable but is a
*separate, cross-layer* decision deferred to its own ADR (see Follow-up).

**D-5 — One-shot migration, no defensive reader (pre-1.0).** A deterministic operator-run script: for each
existing `commits.log` row, serialize its `{metaTreeHash, parents, author, message}` into a commit chunk,
`write` it to the `NodeStore`, and **assert the resulting hash equals the row's stored id** — a free
consistency check that the log was internally sound; refuse on any mismatch (an ambiguous/corrupt log →
operator reimports from source, exactly the escape hatch ADR-0071 D-5 used for collisions). Then rewrite
`commits.log` to the thin `<datetime> <id>` form. No boot-path auto-migrator.

**D-6 — Commit chunks participate in chunk garbage collection / sync / pack like any chunk.** Reachability marks commit
chunks from the `RefsStore` heads and follows parent-ids + the metaTree root (the `GcRootReachability`
model, already proven over the `NodeStore` at the storage layer); `CommitClosure` / `PackBuilder` /
`CommitLogSync` transfer a commit by fetching its chunk by id, uniform with tree chunks — one transfer
mechanism instead of a bespoke log-batch protocol.

## Consequences

- **Positive — one object model.** Everything reachable is a content-addressed chunk; the commit id equals
  its address (self-verifying); commits dedup, garbage-collect, and sync as chunks; the RDF4J layer stops reimplementing
  what `Database` already does; inspectability parity with `FileNodeStore` (a commit is a hash-named file
  you can `cat`).
- **Positive — no storage-size change.** The heavy `RootMetaTree` data was already content-addressed; only
  the thin envelope moves. The sidecar is smaller than today's log (it drops the fields now in the chunk).
- **Cost — blast radius + migration.** Touches `CommitLog`, `CommitClosure`, `CommitGraph`,
  the Sail's sync/merge surface — the same surface ADR-0071 already
  named, so the two are natural to sequence together. A one-shot migration is required (D-5).
- **Cost — the two-commit-format tension persists.** Until the cross-layer unification ADR lands, the RDF4J
  commit chunk and the storage-layer `Database.Commit` are two distinct serializations (they must differ:
  one excludes the timestamp, one includes it). This decision does not fix that; it makes the RDF4J side
  *ready* for it (both would be NodeStore chunks; only the timestamp treatment differs).
- **Neutral — refs unchanged.** `RefsStore`/`Manifest` semantics are untouched; branch operations behave
  identically.

## Follow-up / future work

- **A cross-layer commit-identity ADR** — reconcile `prolly-storage` `Database`'s timestamp-in-id `Commit`
  with ADR-0071's timestamp-free RDF4J id, so a *single* commit-object model serves both layers (this is
  the Option C that D-4 defers). Trigger: a desire to delete the RDF4J-vs-storage commit duplication
  outright, or a second consumer of the storage `Database` that also needs deterministic ids.
- **Retire `commits.log` entirely** (rebuild time-order from an `id→timestamp` table) if the flat file's
  residual role proves not worth a file — folds into Q1.

## Open questions

- **Q1 — Sidecar form.** Keep a thin append-only `commits.log` (`<datetime> <id>`), or a dedicated
  `id→timestamp` table? Memento/`timemap` need a time-ordered scan; append order ≈ time order for a single
  writer but diverges after a pull (older commits appended late), so either form must sort by the stored
  datetime. The thin log is lower-churn; a table is cleaner but new.
- **Q2 — Shared vs distinct chunk format.** Does the RDF4J commit chunk share serialization code with
  `prolly-storage`'s `Commit` (minus the timestamp field), or is it a separate RDF4J format? Sharing is a
  down payment on the cross-layer unification (Follow-up); distinct is lower-risk now. Recommend distinct
  now, converge under the future ADR.
- **Q3 — Sequencing against the ADR-0071 sync work.** The commit-identity-redesign plan ADR-0071 guides
  touches the same files (`CommitLog`, `CommitClosure`, `CommitLogSync`, `PackBuilder`). This should land
  *after* that plan is green (don't serialize two format changes over the same surface at once), or be
  merged into it as a final phase.
