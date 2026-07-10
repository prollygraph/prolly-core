# prolly-sync — pack-based replication

The replication layer over the substrate: build a **pack** (a self-contained container
of content-addressed chunks + the commit chain that reaches them) from one `Database`,
apply it to another with compare-and-set head advancement. Content addressing does the
heavy lifting — identical chunks dedup by name on arrival, packs can be pruned to what
the receiver already holds, and a received head is *verifiably* the sender's because the
name is the content.

## What's inside

| class | what it is |
|---|---|
| `SyncPack` / `SyncPackCodec` | the pack wire format (magic + version header, fail-closed on unknown versions) |
| `SyncCommitEntry` | one commit-history entry as the wire owns it — decoupled from any store's log type |
| `DataTreeReachability` | the chunk-closure walk from a tree root |
| `DatabasePackSync` | build / apply / integrate over `prolly-storage`'s `Database`, with head-state verification before any compare-and-set |
| `SubstrateSyncContributor` | the SPI a data-shape layer implements to join a sync operation |

## Usage

From `DatabasePackSyncTest` — replicate branch history from database `a` to `b`:

```java
DatabasePackSync.PackAndHead built = DatabasePackSync.buildPack(a, "main", Set.of());
DatabasePackSync.apply(b, "main", built.pack(), built.head().orElseThrow(), null);

// the receiver's head IS the sender's — content-addressed, so equality is identity
b.getHeadHash("main");   // == built.head()
```

Incremental sync passes the chunk names the receiver already holds as the third
argument to `buildPack` — the pack prunes to the delta. Merge commits travel with their
full parent lineage (a first-parent-only closure would silently drop side branches —
pinned by the test suite). `apply` verifies the received head state (commit chain +
tree closure) **before** the compare-and-set advances anything.

## Status

The pack format is young and **pre-1.0**: it carries a version header and refuses
unknown versions, and it will change without compatibility shims. Treat cross-version
sync as unsupported until 1.0.
