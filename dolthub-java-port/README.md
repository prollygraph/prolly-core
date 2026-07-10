# dolthub-java-port — the tree engine

The Java port of Dolt's prolly tree: a content-addressed, probabilistically balanced
search tree over `MemorySegment` tuples. This module is the pure engine — no disk, no
server, no upper layers. Its only store is the in-memory `NodeStore`; durable stores live in
`prolly-storage`.

## What's inside

| area | classes |
|---|---|
| node model | `Node` (a zero-copy view over flatbuffer bytes; leaf vs internal by `level`), `FlatbufferNodeSerializer` |
| chunking | `RollingHashSplitter` (67-byte BuzHash window, 512 B min / 16 KiB cap), `BuzHash` |
| writing | `TreeMutator` (the write path: applies sorted edits, fast-forwards unchanged runs by reference), `MutableMap` (edit buffer + flush), `SpillableSortedBuffer` |
| reading | `StaticMap`, `Cursor` (key + ordinal descent over the counted tree), `MapIterator`, `TreeIter` |
| versioning | `DiffEngine` (walks only where hashes differ), `MergeEngine` (three-way), `Commit`, `Manifest`, `ReachabilityWalker` (GC reachability) |
| tuples | `TupleBuilder`, `TupleDescriptor`, `Encoding`/`TypeCodec` |

Keys and values are serialized tuples (`MemorySegment`), not Java objects — the engine
never deserializes nodes on the read path.

## Usage

The shape every test in this module uses (see `OracleModelTest` for the full version):

```java
TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));

try (HeapBufferPool pool = new HeapBufferPool()) {
    NodeStore store = new InMemoryNodeStore();

    // build a key/value as serialized tuples
    TupleBuilder tb = new TupleBuilder(pool);
    tb.putField(0, "alice".getBytes(StandardCharsets.UTF_8));
    MemorySegment key = tb.build().segment();

    // edit buffer over an (empty) base, flush to an immutable snapshot
    StaticMap base = new StaticMap(store, null, desc);
    MutableMap mm = new MutableMap(base, store, desc, pool);
    mm.put(key, value);
    StaticMap snapshot = mm.flush();       // writes chunks; root hash = tree identity

    snapshot.get(key);                     // Optional<MemorySegment>
    snapshot.iter();                       // ordered scan; iterRange/iterPrefix/reverseIter too
}
```

Two snapshots with the same content have the same root hash, whatever the edit order —
that invariant (history-independence) is what `DiffEngine`/`MergeEngine` exploit.

## Notes

- Requires JDK 25 (the Foreign Function & Memory API is used for zero-copy reads; final,
  no preview flags).
- The test-jar published by this module carries shared, contract-tested doubles
  (e.g. the in-memory manifest) consumed by downstream modules' tests.
- `cross-lang/fixtures` at the repo root holds golden encoding vectors the tests verify
  against — they travel with the repo.
