# prolly-multistore — the many-repos primitive

Run N independent stores in one process: a per-repo lifecycle registry with an
LRU-bounded warm set, repo-name syntax validation, and a per-repo RocksDB layout.
Deliberately **mechanism, not policy** — tenancy policy (repo metadata, permissions,
organizations, reserved names) lives with the consumer, not here (see the root
README's module table).

## What's inside

| class | what it is |
|---|---|
| `RepoRegistry` | the interface: a registered set of repo ids, with per-repo resources of type `R` opened lazily on first access. `R` is unbounded — an RDF4J Sail uses `shutDown()` rather than `AutoCloseable`, so closing goes through an injected `Consumer<R>` callback instead of a type bound |
| `LruRepoRegistry` | the default implementation: an LRU-bounded warm cache over the registered set — `register` records the id without opening anything; `resolve` opens on first use; eviction closes via the callback |
| `PinnedLruRegistry` | the pin-leased variant: a resolved resource can be pinned against eviction and self-reopens after one |
| `RepoLifecycleState` | a repo's lifecycle state in the registry |
| `RepoNameValidator` | syntax-only validation (`^[a-z][a-z0-9-]{0,62}$` — lowercase, digits, hyphens, letter-first, max 63 chars to match DNS subdomain length), applied before any filesystem or column-family name composition. Knows nothing about which names a product *reserves* — that is routing policy, kept with the consumer |
| `PerRepoRocksDbFactory` / `OpenRepoDb` | opens one independent RocksDB per repo at `<storeRoot>/repos/{repoId}/db/` — own write-ahead log, memtables, compaction; per-instance memory bounds. Per-repo databases (rather than shared-DB-with-per-repo column families) because per-repo dictionaries leave nothing to share across repos anyway |
| `RepoNameInvalidException` / `RepoNotFoundException` / `RepoQuiesceTimeoutException` | the failure vocabulary: malformed name, unregistered id, a close that timed out waiting for in-flight work |

## Usage

From `LruRepoRegistryTest`:

```java
LruRepoRegistry<FakeResource> registry =
    new LruRepoRegistry<>(resourceFactory, FakeResource::close, /*capacity*/ 4);

registry.register("alpha");                 // records the id; opens nothing
FakeResource r = registry.resolve("alpha"); // lazily opened on first access
registry.resolve("missing");                // -> RepoNotFoundException (factory never called)
```

The registry's contract — lazy open, bounded warm set, close-on-evict, fail-fast on
unregistered ids — is pinned by `LruRepoRegistryTest`, `PinnedLruRegistryTest`,
`RepoNameValidatorTest`, and the two `PerRepoRocksDbFactory*Test`s (including the
shared-memory-budget behavior).
