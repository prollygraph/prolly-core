
# prolly-concurrency

Test-only module: **linearizability (Lincheck) + memory-model (jcstress)** tests
for the whole stack — `dolthub-java-port` (the tree), `prolly-rdf` (the versioned
quad store), `prolly-rdf4j` (the Sail). It's the single home for the concurrency
phases of every test-strategy plan, so the Kotlin/Lincheck/jcstress toolchain
stays out of every production module's classpath (mirrors `prolly-rdf4j-compliance`).

```bash
# Gated OFF by default (slow). Test-compiles in a normal build; runs only with:
mvn -pl prolly-concurrency -am test -Dprolly.concurrency.skip=false
```

No JaCoCo here on purpose — there's nothing to cover, and a coverage agent
fights Lincheck's bytecode-instrumentation agent (this also sidesteps the
"JaCoCo 5th-module `@{argLine}`" gotcha).

---

## The core lesson: test the *real* concurrency contract, not an assumed one

The headline pitfall in this codebase: **the prolly tree is single-writer, so
most of its mutable structures are NOT thread-safe — and a linearizability test
that assumes "fully concurrent" tests a contract they never promised.**

### `MutableMap` is single-writer by design — do NOT linearity-test it concurrently

`MutableMap` (`dolthub-java-port`) is the per-transaction write buffer. Look at it:

- Its mutable state is a **plain `java.util.TreeMap<Tuple, MemorySegment> edits`**.
- It has **zero synchronization** — no `synchronized`, no `volatile`, no lock,
  no concurrent collection.
- Every call site does `new MutableMap(base, store, descriptor, pool)` — a
  **fresh instance per transaction**, never shared across threads.

So a Lincheck test that runs concurrent `put`/`delete`/`get` on one shared
`MutableMap` would only rediscover that **`TreeMap` isn't thread-safe** — a
known property of `TreeMap`, not a finding about the engine. Worse, it would
"prove a bug" that doesn't exist in how the system actually uses the class.

**The real contract is single-writer + concurrent readers**, and the safety
comes from three places — none of which is `MutableMap` being thread-safe:

1. **Immutability of committed snapshots.** A writer mutates its own
   `MutableMap`, then `flush()`es to an *immutable* `StaticMap`. Readers read
   `StaticMap`s — content-addressed, append-only, never mutated in place.
2. **A linearizable content-addressed store.** New chunks land in the
   `NodeStore`, which *is* concurrent (see below).
3. **A single-writer lock at the `Database`/`ProllySail` level.** At most one
   write transaction mutates at a time (a fair `Semaphore` / a
   `ReentrantReadWriteLock`); readers never block.

If you ever DO want to pin `MutableMap`'s contract, the honest Lincheck shape is
an **operation-group split**: a *non-parallel* writer group (`@Operation`s in a
single `@OpGroupConfig(nonParallel = true)` group) racing against a *parallel*
reader group — i.e. one writer, many readers — and assert the readers stay
linearizable. Never an all-parallel writer scenario.

### So the first real linearizability target is `InMemoryNodeStore`

`InMemoryNodeStore` is backed by a `ConcurrentHashMap<String, byte[]>` and is
explicitly documented concurrent-read/write-safe. Content-addressed storage has
a clean linearizable spec: `write(data)` is idempotent and returns
`hash(data)`; `read(hash)` reflects some sequential order of the concurrent
operations. That's what `NodeStoreLinearizabilityTest` pins.

---

## Methodology gotchas (pinned the hard way)

- **Lincheck artifact: use `org.jetbrains.kotlinx:lincheck-jvm`, NOT `lincheck`.**
  Bare `lincheck` is the Kotlin-Multiplatform root — a **767-byte stub jar with
  no classes**. `lincheck-jvm` (~1.1 MB) has the real API.
- **Java entry point is `LinChecker.check(testClass, options)`** — `Options.check(...)`
  is a Kotlin extension function, invisible from Java.
- **JVM args.** Lincheck's instrumentation needs `--add-opens`/`--add-exports`
  on JDK 9+; this module's surefire `argLine` carries them alongside the
  project's `--enable-native-access=ALL-UNNAMED`.
- **Model-checking ✗ crypto/IO in the operation.** Lincheck's *model-checking*
  strategy instruments bytecode to drive interleavings; if an `@Operation`
  reaches `MessageDigest.getInstance` (the JDK security-provider lookup, e.g.
  via `HashUtils.hash` inside `store.write`), it **livelocks** ("active lock
  detected / execution hung"). Use **stress mode** for any operation that
  hashes or does IO; reserve **model-checking** for pure in-memory
  synchronization (e.g. the `Database` commit OCC, where the synchronized
  region doesn't hash). `NodeStoreLinearizabilityTest` is stress-mode for
  exactly this reason — and the store's only synchronization *is* the
  `ConcurrentHashMap`, so model-checking would add little anyway.
- **Lincheck instrumentation ✗ the RDF4J Sail class graph (measured 2026-06-11).**
  Lincheck's agent retransforms loaded classes to insert its hooks (in *both*
  stress and model-checking modes). It handles JDK classes and our Java-25
  (class-file v69) prolly-core graph fine — `LincheckSmokeTest` (`AtomicInteger`)
  and `DatabaseCommitOccTest` (the lean `Database`/`StaticMap`/`TreeMutator` graph
  with off-heap `MemorySegment`) both pass. But pointing a `@Operation` at
  `ProllySail` drags in the entire RDF4J Sail framework, and the agent dies at
  install: `java.lang.InternalError: class redefinition failed: invalid class`
  (`LincheckJavaAgent.install` → `Instrumentation.retransformClasses0`). It is the
  *Sail* class graph specifically — not a toolchain-wide breakage. **So the
  `ProllySail` single-writer lock is verified by a real-thread stress oracle, not
  Lincheck**: `ProllySailConcurrencyStressTest.mixedWorkloadStress` (in
  `prolly-rdf4j`) runs 6 concurrent writers with unique subjects and asserts the
  final store equals exactly the committed set (no lost / phantom write) plus a
  mutex probe that never observes >1 concurrent writer. That is the instrument
  that *fits* the un-instrumentable target; Lincheck would, even if it loaded, be
  forced into stress mode by the `MessageDigest` livelock above — i.e. real
  threads with a *weaker* oracle than the stress test already has. The
  content-addressed commit compare-and-set underneath (`Database.commit` →
  `manifest.updateRef(expected=parent)`) is the one piece Lincheck *can* reach,
  and `DatabaseCommitOccTest` proves it linearizable. (The general lesson — a
  precise instrument that can't *attach* to the target proves nothing — is the
  measure-the-real-thing discipline applied to concurrency tooling.)
- **jcstress needs `-proc:full` on JDK 23+ (the silent-breakage trap).** jcstress's
  runner is driven by a `TestList` resource its annotation processor generates from
  the `@JCStressTest` classes; `run-jcstress.sh` invokes `org.openjdk.jcstress.Main`
  off `target/test-classes`. **JDK 23 stopped running annotation processors
  discovered on the classpath implicitly**, so after the Java 25 bump (2026-06-05)
  the processor silently did nothing: no `*_jcstress` stubs, no `TestList` — and the
  runner died at startup with `NullPointerException` in `TestList.getTests` (a null
  resource stream). The fix (this module's `maven-compiler-plugin` config) is two
  parts: name `jcstress-core` on `<annotationProcessorPaths>` **and** pass
  `-proc:full` to actually turn processing on. **The trap:** harnesses are named
  `*Jcstress` (not `*Test`) so surefire skips them, and the jcstress *run* is gated
  off by default — so they kept *compiling* (CI's compile-check stayed green) while
  silently not *running* for days. **Compile-green ≠ run-green for a gated suite**;
  CI only catches a broken harness's *compilation*, never its *execution*. Run it
  yourself to know it runs:

  ```bash
  ./run-jcstress.sh                            # all harnesses, quick mode
  ./run-jcstress.sh InMemoryNodeStore sanity   # one target, fastest preset
  ```

---

## What lives here

| Test | Mode | Pins |
|---|---|---|
| `LincheckSmokeTest` | model-checking | the toolchain runs (a trivially-linearizable `AtomicInteger`) |
| `NodeStoreLinearizabilityTest` | stress | `InMemoryNodeStore` is a linearizable content-addressed map |
| `DatabaseCommitOccTest` | stress | concurrent same-parent commits → exactly one wins (no lost update) |
| `InMemoryNodeStoreWriteVisibilityJcstress` | jcstress | a racing reader never sees a torn chunk (safe publication) |
| `InMemoryNodeStoreConcurrentWriteJcstress` | jcstress | concurrent distinct writes never lose an update |
| `NodeCacheWriteVisibilityJcstress` | jcstress | a node cache write is safely published to a racing reader |
| `ManifestPublicationJcstress` | jcstress | manifest-ref safe publication |
| `RootSnapshotPublicationJcstress` | jcstress | `ProllySail`'s root-publication fix: a reader sees all roots from one generation, never a torn mix (the eliminated non-volatile-`indexRoots` smell) |
| `JcstressSample` | compile-only | the jcstress `@JCStressTest` API resolves |

The shared-root (`ProllySail.indexRoots`) publication smell is now covered by
`RootSnapshotPublicationJcstress` (above) — and it did **not** need per-fork Sail
construction after all: the root-publication fix made the property a pure JMM
publication question, so a minimal faithful model (the `Snap` holder mirroring
`Snapshot`'s shape) proves it, exactly as `ManifestPublicationJcstress` models the
head→node contract with `int`s. Still planned (the test-strategy plans' concurrency
phases route here): the `RocksNodeStore` thread-local batch isolation, which *does*
need per-fork RocksDB construction — the next jcstress increment (core Step 22).
