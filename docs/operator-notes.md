# Operator notes — running the engine

Operator-facing notes for the person who has to size, back up, and diagnose a
process that embeds this engine.

**Read this first: nothing here is a server.** This ring is a set of libraries.
You do not deploy prolly-core; you deploy *your* application, which embeds it,
and thereby inherits the storage characteristics below. "Operator" here means
whoever owns the process that holds the store open.

The developer-facing companion is
[developer-skill-sets.md](developer-skill-sets.md).

## What lives on disk

The store is a **content-addressed chunk store over RocksDB** (the `db/`
directory), plus the ref and commit-log state your embedding application
configures. Two consequences that shape every operational decision:

- **Chunks are immutable and named by their hash.** Nothing is updated in
  place; a write appends new chunks and moves a ref. Corruption of an existing
  chunk is therefore detectable — its bytes no longer hash to its name — and
  read-integrity verification exists for exactly that check.
- **Unchanged data is shared between versions.** Disk growth tracks *change*,
  not total history, which is why keeping many versions is affordable and why
  "the database grew unexpectedly" usually means churn, not volume.

## Memory — the part that surprises people

Java heap is not the whole story. A process embedding this engine holds memory
in at least three places, and only one is bounded by `-Xmx`:

| Where | Bounded by | Notes |
|---|---|---|
| Java heap | `-Xmx` | Working set of the in-transaction buffer and parsed nodes |
| RocksDB native | its own block-cache / write-buffer settings | Invisible to `-Xmx` and to most heap dashboards |
| Off-heap segments | the buffer pool in use | On-heap pool is the production default (below) |

**The production write path uses the on-heap buffer pool.** An off-heap
(`DirectBufferPool`) implementation exists and is explicitly *not* the default;
its class documentation states the promotion gate it must pass first. If you are
diagnosing memory, know which pool your build actually runs before attributing
anything to off-heap allocation.

Practical guidance: size `-Xmx` for the transaction working set, then measure
**resident set size**, not heap. A process that looks fine in heap dashboards
and still gets killed is the normal shape of a native or off-heap problem.

## Garbage collection

The collector is **mark-and-sweep over the Merkle reachability graph**: the mark
phase starts at every branch ref and walks; anything unreachable is an orphan and
can be deleted.

Two things to be clear about:

- It reclaims **orphans only** — chunks no live ref can reach. It is not history
  compaction; a chunk that any commit still reaches is retained by design.
- Reachability contributors are pluggable, so an embedding application can add
  its own roots. If your application holds references the collector does not know
  about, that is a data-loss risk, not a space leak — verify your roots are
  registered.

## Backup

Because chunks are immutable and content-addressed, a **cold copy of the store
directory is a valid backup**: stop writes, copy, resume. There is no
log-replay step to get wrong.

Restore is the same operation in reverse. The project is pre-1.0 with no
backwards-compatibility guarantees across format changes, so the honest upgrade
procedure is: back up, upgrade, verify — and keep the backup until you have.

## Diagnosing a stall or a kill

Some hard-won ordering, from real incidents:

1. **Check whether the process was killed or crashed.** A kernel out-of-memory
   kill leaves a kernel-log line; a userspace kill does not. They look identical
   from inside the application, and they have different fixes.
2. **Trust the trace, not the exit code.** A wrapper reporting exit status 0
   directly above a "killed by signal" line is a real thing that has happened
   here. Confirm the run's own completion marker exists.
3. **Attribute memory to a mechanism before filing it.** "Uses more memory" is
   not a finding; *which* memory (heap-live after collection, RocksDB native,
   off-heap borrowed-minus-released) and why is. An unattributed anomaly gets
   filed as a feature and bites later.

## Long-run validation

Leak-freedom is a claim about *hours*, not minutes: a short run cannot see a
slow leak. If you are qualifying a deployment, run a sustained soak with resident
set sampled throughout and look for a flat plateau rather than a rising line.
Launch it detached from any interactive session — a long run tied to a session's
lifetime dies mid-stride when that session ends, which looks like a crash and is
not one.
