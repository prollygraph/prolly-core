# prolly-storage — durable node stores

The persistence layer under the engine: `NodeStore` implementations that put the prolly
tree on disk, plus manifest (root-pointer) management. Deliberately dependency-light and
server-free — this is the boundary between "a tree in memory" and "a versioned store".

## What's inside

| class | what it is |
|---|---|
| `RocksNodeStore` | RocksDB-backed chunk store (the production store); batched writes with a configurable flush threshold (`setBatchFlushBytes`) |
| `FileNodeStore` | one-file-per-chunk store — simple, inspectable, useful for tests and tooling |
| `RocksManifest` | root-pointer + refs persistence over a RocksDB handle |
| `SharedRocksDb` / `SharedRocksResources` | one RocksDB instance shared across column families, with lifecycle management |
| `StoreClosedException` | fail-fast reads/writes after close |

## Usage

A `RocksNodeStore` is a drop-in `NodeStore` for the engine (from
`RocksNodeStoreBatchFlushDifferentialTest`):

```java
try (DirectBufferPool pool = new DirectBufferPool();
     RocksNodeStore store = new RocksNodeStore(dir.toString())) {

    MutableMap mm = new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
    mm.put(key, value);
    StaticMap snapshot = mm.flush();                    // chunks now durable in RocksDB
    byte[] rootHash = store.write(snapshot.root().segment()); // content hash of the root

    Manifest manifest = new RocksManifest(store.db());  // refs beside the chunks
    manifest.updateRef("repo", "main", rootHash, null); // compare-and-set (expected=null: create)
    manifest.getRef("repo", "main");                    // Optional<byte[]>
}
```

Everything is `AutoCloseable`; reads and writes after `close()` throw
`StoreClosedException` rather than corrupting.

## Notes

- Pulls `org.rocksdb:rocksdbjni` (native library; all common platforms bundled).
  `--enable-native-access=ALL-UNNAMED` on the JVM command line silences the native-access
  warning on JDK 25.
- The manifest implementations are pinned by a shared contract test
  (`ManifestContractTest`) so file- and Rocks-backed behavior cannot drift apart.
