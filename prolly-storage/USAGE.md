# Using `prolly-storage`

`prolly-storage` (`com.earasoft.prolly`) is the **data-shape-agnostic versioned-store substrate**. It turns
`dolthub-java-port`'s content-addressed tree into a **durable, versioned store**: a commit graph, branch refs,
three-way merge, garbage collection, and incremental sync — persisted to RocksDB. It depends *down* on
`dolthub-java-port` and has **no upper-layer dependencies** (a build guard enforces it). This is the layer every
upstream domain store builds on.

> **Read [`dolthub-java-port/USAGE.md`](../dolthub-java-port/USAGE.md) first.** That guide covers the tree
> (`StaticMap`/`MutableMap`, `TupleDescriptor`, the chunk store). This one adds **history + durability** on top:
> where port-core gives you an immutable content-addressed map, prolly-storage gives you *commits over a branch
> of those maps, stored on disk*.

## Where it sits

```
dolthub-java-port   StaticMap / MutableMap            an immutable, content-addressed sorted map
        │                                            (a snapshot, identified by its root hash)
        ▼
prolly-storage     Database  ── commit graph + branch refs ──►  RocksNodeStore ──► RocksDB
        │          (versioning: commit · branch · merge · garbage-collect · sync; optimistic concurrency)
        ▼
your store         documents · graphs · tabular · domain formats · …
```

A `Database` wraps a `NodeStore` (the chunk store) and a `Manifest` (the branch/tag ref table). Each commit
captures one `StaticMap` snapshot plus its parent links; a branch is a named pointer into that commit graph.

## The pieces (vocabulary)

| Type | Role |
|---|---|
| **`Database`** | the entry point — *"versioned store over a content-addressed chunk store: the commit graph + branch refs"*. `AutoCloseable`. |
| `RocksNodeStore` | the durable `NodeStore` (port-core's interface) over RocksDB. `AutoCloseable`. |
| `DirectBufferPool` | the off-heap (Project Panama, `Arena`-backed) `BufferPool` for the hot path. `AutoCloseable`. |
| `RocksManifest` | the RocksDB-backed `Manifest` — the branch-and-tag reference table (derived for you by the convenience constructor). |
| `GarbageCollector` | Merkle mark-and-sweep — reclaims chunks no live commit reaches. |
| `VCUtils` | version-control utilities — `blame` (who last changed a key) and `bisect` (find the first-bad commit). |
| `SyncEngine` · `RemoteNodeStoreClient` | incremental chunk sync — copy only the chunks reachable from a remote root that you don't already have. |
| `IntegrityVerifyingNodeStore` · `ErrorInjectingNodeStore` · `TreeIntegrityChecker` | `NodeStore` decorators: verify content hashes on read; inject faults (tests); deep-check a tree. |
| `SharedRocksDb` | one RocksDB engine hosting several column families — the multi-tenant / shared-engine pattern. |

## Lifecycle (hello world)

Pinned by `UsageExampleTest` — this exact lifecycle runs in the suite, so the wiring + constructors below are
real and verified:

```java
import com.earasoft.prolly.Database;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import com.dolthub.prolly.*;
import java.util.List;

// 1. Open the off-heap pool + a durable chunk store, and a Database over them.
try (DirectBufferPool pool = new DirectBufferPool();
     RocksNodeStore store = new RocksNodeStore("/var/data/myrepo/rocks")) {

    TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    Database db = new Database(store, "myrepo", desc, pool);   // repoId is just a label

    // 2. A fresh Database has NO branches — create "main" from the empty base ("EMPTY").
    db.createBranch("main", "EMPTY");

    // 3. Build a change set against the branch's current state, then commit it.
    StaticMap base = db.getBranch("main");                     // empty for a new branch
    MutableMap mm = new MutableMap(base, store, desc, pool);
    mm.put(key(pool, "alice"), MemorySegment.ofArray("v1".getBytes()));

    byte[] parent = db.getHeadHash("main").orElse(null);       // the compare-and-set token
    boolean ok = db.commit("main", mm, parent, "me", "first write");
    // ok == false  ⇒  another writer advanced HEAD since you read it (retry; see below)

    // 4. Read it back at the current HEAD.
    StaticMap now = db.getBranch("main");
    byte[] v = now.get(key(pool, "alice")).orElseThrow().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
}
```

`RocksNodeStore` and `DirectBufferPool` are `AutoCloseable` — open them in a try-with-resources so the RocksDB
engine and the off-heap arena are released. `Database` is `AutoCloseable` too.

## Committing — the compare-and-set model

`commit(branch, next, expectedParentHash, author, message)` returns a **`boolean`**, not void. It is an
**optimistic compare-and-set**: it lands `next` only if the branch's current HEAD still equals
`expectedParentHash`. If another writer advanced HEAD in between, it returns **`false`** and changes nothing —
you re-read and retry. This is exactly how `JsonLeafStore.commit` guards a write
(`JsonLeafStore.java:126`):

```java
MutableMap mm = new MutableMap(db.getBranch(branch), db.store(), DESCRIPTOR, pool);
// ... stage put/delete ...
byte[] parent = db.getHeadHash(branch).orElse(null);
if (!db.commit(branch, mm, parent, author, message)) {
    throw new ConcurrentModificationException("HEAD moved (concurrent writer)");
}
return db.getHeadHash(branch).orElseThrow();   // the new commit hash
```

There are `StaticMap` and `MutableMap` overloads of `commit` — pass whichever you have (a `MutableMap` is
flushed for you).

## Branching, merging, history

```java
db.createBranch("feature", "main");           // fork a branch from another ref
db.listBranches();                            // List<String>

// two-parent merge: parents.get(0) MUST equal the current HEAD (the compare-and-set precondition);
// parents.get(1) is the source being merged in.
db.commitMerge("main", mergedMap, List.of(targetHead, sourceHead), "me", "merge feature");

db.cherryPick("main", someCommitHash, "me");  // replay one commit onto a branch
db.revert("main", someCommitHash, "me");      // apply the inverse of a commit
MutableMap rebased = db.rebase(pending, db.getBranch("main"));  // re-base a pending change set
```

Read history with `db.getHead(branch)` (the `Commit`), `db.getHeadHash(branch)`, and `db.getBranch(branch)`
(the current `StaticMap`). To read the tree **as of an arbitrary commit**, deserialize the `Commit` and rebuild
a `StaticMap` from its root (`JsonLeafStore.stateAt`, `JsonLeafStore.java:182`):

```java
Commit c = db.store().read(commitHash).map(s -> Commit.deserialize(s.toArray(BYTE))).orElseThrow();
byte[] rootHash = c.getRootValueHash();
Node root = rootHash == null ? null : db.store().read(rootHash).map(Node::fromBytes).orElse(null);
StaticMap asOf = new StaticMap(db.store(), root, DESCRIPTOR);
```

`VCUtils` answers two version-control questions over the commit graph:
- `blame(branch, key)` → the `Commit` that last changed a key.
- `bisect(goodHash, badHash, isBad)` → the first commit on which the `Predicate<Commit>` flips to bad.

## Garbage collection

Commits and branch HEADs are the garbage-collection roots; any chunk no live commit reaches is garbage. Run a mark-and-sweep:

```java
new GarbageCollector(db, store).collect();    // Merkle mark + sweep
```

Garbage collection and writes are coordinated by a read/write lock: **`Database` commits hold the garbage-collection *read* lock across the
flush** (so a sweep can't reclaim a chunk a commit just wrote), and `GarbageCollector.collect()` takes the
*write* lock. That window was a real data-loss bug once — see `bugs/gc-concurrent-write-flush-window.md`.
One caveat for upstream stores: the garbage collector marks only what commits reach, so out-of-band roots (an upstream Sail's aux
trees) need explicit pinning — that is the owning layer's concern, not the substrate's.

## Persistence options

- **Standalone:** `new RocksNodeStore(path)` opens and *owns* a RocksDB at `path`. Simple single-store case.
- **Shared engine (multi-tenant):** `new RocksNodeStore(rocksDb, columnFamilyHandle)` puts the chunk store in
  *one column family* of an externally-opened RocksDB — so many repos share a single engine (one write-ahead
  log, one block cache). `SharedRocksDb` opens such a multi-column-family engine; this is how an upstream multi-tenant store's
  an upstream per-repo factory hosts many tenant repos.
- **Hardening / testing decorators:** wrap any `NodeStore` in `IntegrityVerifyingNodeStore` (re-hash on read to
  catch disk corruption) or `ErrorInjectingNodeStore` (deterministic fault injection in tests).
- **Sync:** `SyncEngine` + `RemoteNodeStoreClient` copy only the chunks reachable from a remote root that the
  local store lacks — incremental push/pull.

## Worked example — a document store

An upstream `VersionedProllyDocumentStore` is a thin layer over a `Database`: it wraps one with a byte-key
`TupleDescriptor` and shreds each JSON document into `key → leaf` rows. The whole versioned-document API
(create/patch/delete/read/history/diff) is `Database` commits underneath
(`ProposeMergeTest.java:41`):

```java
var docs = new VersionedProllyDocumentStore(
    new Database(store, "merge-test", JsonLeafStore.DESCRIPTOR, pool));
```

`JsonLeafStore.commit` (above) is its write path; `commitMerge` is its merge path; reads are `StaticMap`
range scans. The substrate supplies *all* the versioning; the document layer supplies only the document model.

## Worked example — a multi-tenant graph store

An upstream graph store wires a `Database` per tenant repo over a **shared-engine**
`RocksNodeStore` (upstream wiring): `new RocksNodeStore(openDb.db(),
openDb.chunkColumnFamily())`. Each write becomes a `Database` commit; commit/branch parameters
time-travel is `getBranch`/`stateAt`. Runnable end-to-end demos live in

## Invariants & gotchas

- **Commits are compare-and-set.** A `false` return means HEAD moved — re-read and retry; never assume a commit
  landed without checking.
- **Content addressing is inherited** from port-core: equal data ⇒ equal root hash ⇒ structural sharing across
  commits, so history is cheap (only changed paths are new chunks).
- **Garbage-collection roots are commits + branch heads only.** Anything you want kept that *isn't* reachable from a commit
  must be pinned by the layer that owns it (the aux-root case).
- **Close your resources.** The pool (off-heap arena) and `RocksNodeStore` (RocksDB engine) are `AutoCloseable`
  — leaking them leaks native memory / file handles.

## Where this lives

- `prolly-storage/src/main/java/com/earasoft/prolly/Database.java` — the versioned store (commit / branch / merge)
- `prolly-storage/src/test/java/com/earasoft/prolly/UsageExampleTest.java` — the hello-world, run as a test (this guide is compile-pinned)
- `prolly-storage/src/main/java/com/earasoft/prolly/storage/RocksNodeStore.java` · `RocksManifest.java` · `SharedRocksDb.java` — durable persistence
- `prolly-storage/src/main/java/com/earasoft/prolly/pool/DirectBufferPool.java` — the off-heap pool
- `prolly-storage/src/main/java/com/earasoft/prolly/GarbageCollector.java` · `VCUtils.java` — garbage collection + blame/bisect
- `prolly-storage/src/main/java/com/earasoft/prolly/sync/SyncEngine.java` · `RemoteNodeStoreClient.java` — incremental sync
- `dolthub-java-port/USAGE.md` — the tree layer this builds on
