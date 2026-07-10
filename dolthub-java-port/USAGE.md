# Using `dolthub-java-port`

`dolthub-java-port` (`com.dolthub.prolly`) is the **prolly tree** — a faithful Java port of Dolt's Go
implementation. It is a **content-addressed, history-independent sorted map**: a probabilistic B-tree whose
*shape is determined by its content*, so equal data always produces an equal tree and an equal root hash,
regardless of the order you inserted it. It has **no upper layers and no versioning** — just the tree, byte tuples, and
a content-addressed chunk store. Versioning (`Database`, commits, branches) lives one layer up in
`prolly-storage`; this guide is the tree itself.

This is the layer everything upstream is built on: index stores key their composite indexes with it,
document stores shred documents into it, and a `Database` (prolly-storage) wraps it with commit history.

## The mental model

A prolly map is a sorted `key → value` map where both key and value are **byte sequences** (`MemorySegment`s).
Three ideas:

1. **Tuples.** A key (and optionally a value) is a `Tuple` — a packed, order-preserving byte layout of typed
   fields described by a `TupleDescriptor`. The descriptor's field order *is* the sort order.
2. **Content addressing.** The tree is made of immutable `Node` chunks; each is stored under its own content
   hash in a `NodeStore` (`hash → bytes`). Writing a chunk that already exists is a no-op. Equal data ⇒ equal
   chunks ⇒ equal root hash.
3. **Immutable + edit buffer.** A `StaticMap` is an immutable snapshot (identified by its root). To change it,
   you wrap it in a `MutableMap`, stage `put`/`delete`s, and `flush()` to get a new `StaticMap` (the old one is
   untouched — structural sharing).

## The vocabulary (the types you touch)

| Type | Role |
|---|---|
| `Encoding` (enum) · `Type` (record) · `TupleDescriptor` | the key/value **schema** — field types + order |
| `TupleBuilder` · `Tuple` | **build** a key/value tuple; **read** a field back (`getField(i)`) |
| `NodeStore` (interface) · `InMemoryNodeStore` | the **chunk store** (`hash → bytes`); in-memory impl for embedding/tests |
| `BufferPool` · `HeapBufferPool` | the **buffer pool** the builders/maps allocate from |
| `StaticMap` | an **immutable** map snapshot — `get`, `iter`, `iterRange` |
| `MutableMap` | an **edit buffer** over a `StaticMap` — `put`, `delete`, `flush()` |
| `Cursor` · `MapIterator` | **read** a map — seek a key, advance, iterate a range |
| `DiffEngine` · `MergeEngine` | structural **diff** and three-way **merge** of two `StaticMap`s |
| `Commit` | the versioning primitive (a record); orchestration is `prolly-storage`'s `Database` |

## Hello world (pure tree, no versioning)

Pinned by `UsageExampleTest` — this exact round-trip runs in the suite, so every constructor/method below is
real and verified, not paraphrased:

```java
import com.dolthub.prolly.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

try (HeapBufferPool pool = new HeapBufferPool();
     InMemoryNodeStore store = new InMemoryNodeStore()) {

    // 1. Define the schema: a single non-nullable String (i.e. raw-bytes) key field.
    TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    // 2. Start from an empty immutable map (null root = empty), wrap it in an edit buffer.
    StaticMap empty = new StaticMap(store, /* root */ null, desc);
    MutableMap m = new MutableMap(empty, store, desc, pool);

    // 3. Build a key tuple and put a value (values are opaque bytes).
    MemorySegment key = key(pool, "alice");
    m.put(key, MemorySegment.ofArray("hello".getBytes()));

    // 4. Materialise the new immutable tree. THIS is the content-addressed snapshot.
    StaticMap result = m.flush();

    // 5. Read it back.
    byte[] v = result.get(key).orElseThrow().toArray(ValueLayout.JAVA_BYTE);  // "hello"
}

// build a one-field key tuple (cf. JsonLeafStore.keyTuple / MutableMapTest.key)
static MemorySegment key(BufferPool pool, String s) {
    TupleBuilder tb = new TupleBuilder(pool);
    tb.putField(0, s.getBytes());
    return tb.build().segment();
}
```

`result` is a *new* immutable map; `empty` is unchanged. `result`'s root hash is the content address of the
whole tree — store it, and `new StaticMap(store, rootNode, desc)` reconstitutes exactly this map.

## Defining your schema

A `TupleDescriptor` is an ordered list of `Type(Encoding, nullable)`. The field order is the sort order, and
each `Encoding` carries an order-preserving byte layout. The vocabulary includes `Null`, the signed/unsigned
integers (`Int8…Int64`, `Uint8…Uint64`), `Float32/64`, `String`, `Bytes`, `JSON`, temporal types
(`Date`/`Time`/`Datetime`/`Year`), `Decimal`, `IRI`, `Hash128`, and out-of-line `*Addr` variants for large
values. Build with `TupleBuilder.putField(i, …)`; read with `new Tuple(segment).getField(i)`.

## Example A — a document store: a single byte-key map

An upstream document store shreds a JSON document into many `key → leaf-value` rows and stores them in one prolly map. Its
schema is a single opaque byte key (`JsonLeafStore.java:56`):

```java
public static final TupleDescriptor DESCRIPTOR =
    new TupleDescriptor(List.of(new Type(Encoding.String, false)));   // one raw-bytes key field

// write: stage mutations into a MutableMap, then commit (the Database adds versioning)
MutableMap mm = new MutableMap(db.getBranch(branch), db.store(), DESCRIPTOR, pool);
mm.put(keyTuple(m.key()), MemorySegment.ofArray(m.value()));   // value = shredded-leaf bytes
mm.delete(keyTuple(m.key()));
// db.commit(branch, mm, parent, author, message)  ← prolly-storage wraps flush() + history

// read a contiguous key-prefix range (keys are sorted, so a prefix's rows are adjacent)
MapIterator it = sm.iterRange(keyTuple(prefix));
while (it.next()) {
    byte[] k = new Tuple(it.key()).getField(0);
    if (!startsWith(k, prefix)) break;          // past the prefix → done
    byte[] v = it.value().toArray(ValueLayout.JAVA_BYTE);
}
```

The teaching point: **the prolly map is just a sorted byte-key store.** The document layer owns "what the bytes mean"
(the JSON shredder + leaf codec); port-core owns "keep these tuples sorted, content-addressed, and diffable."
`iterRange` + the sorted-key invariant is how it does an efficient prefix scan.

## Example B — a quad index: a composite-key sorted key

An upstream index layer stores each quad permutation as a **four-field composite key** of 64-bit
term ids — a subject/predicate/object/context index:

```java
public static final TupleDescriptor DESCRIPTOR = new TupleDescriptor(List.of(
    new Type(Encoding.Int64, false),    // subject TermId
    new Type(Encoding.Int64, false),    // predicate TermId
    new Type(Encoding.Int64, false),    // object TermId
    new Type(Encoding.Int64, false)));  // graph/context TermId
```

A pattern query (e.g. "all `?o` for a fixed `s,p`") becomes a **range seek**: position a `Cursor` at the
lower-bound key and advance while the prefix matches. From `TrieIterator` (the worst-case-optimal-join trie
view over a `StaticMap` index):

```java
// seek to the first key >= a target tuple, then walk forward
cursor = Cursor.atKey(map.store(), map.root(), tb.build().segment(), desc);
...
if (!cursor.advance()) return;   // end of map
```

The teaching point: **the composite key + `Cursor` give you ordered range access for free.** Because the four
fields sort lexicographically in descriptor order, a bound prefix (`s`, then `s,p`, …) selects a contiguous
slice — which is exactly what an index seek needs. (One sharp edge to know: `TermId.compareTo` is *unsigned*
but the `Int64` column comparator is *signed* `Long.compare`, so collision-extension ids sort the opposite way
inside the index — see `foundations/the-termid-ordering-trap.md`.)

## Persistence and production

- **Chunk store.** `InMemoryNodeStore` (above) keeps chunks in a `Map` — perfect for embedding and tests. For
  durable storage, `prolly-storage`'s `RocksNodeStore` implements the same `NodeStore` interface over RocksDB.
  The map code doesn't change — only which `NodeStore` you pass.
- **Buffers.** `HeapBufferPool` (heap) is the simple default; `prolly-storage`'s `DirectBufferPool` is the
  off-heap, `Arena`-backed pool for the hot path. Both implement `BufferPool`.
- **Versioning.** Commits/branches/merge are not here — they are `prolly-storage`'s `Database`, which wraps a
  `MutableMap.flush()` with a `Commit` graph. A consumer that wants history uses `Database`;
  a consumer that just wants a content-addressed map (an index) uses `StaticMap`/`MutableMap` directly.

## Invariants worth relying on

- **Content addressing:** equal data ⇒ equal root hash. Two maps built from the same key/value set are the
  same tree, byte-for-byte — the basis for cheap equality, dedup, and diff.
- **History independence:** insertion order does not affect the result. Put `a` then `b`, or `b` then `a` —
  same tree.
- **Idempotent writes:** `NodeStore.write(bytes)` returns the content hash and is a no-op if the chunk already
  exists (`NodeStore.java:30`).
- **Structural sharing:** `flush()` rewrites only the path from the changed leaves to the root; unchanged
  subtrees are shared with the prior `StaticMap`.

## Where this lives

- `dolthub-java-port/src/main/java/com/dolthub/prolly/StaticMap.java` · `MutableMap.java` — the map + edit buffer
- `dolthub-java-port/src/main/java/com/dolthub/prolly/TupleDescriptor.java` · `Tuple.java` · `TupleBuilder.java` · `Encoding.java` — the schema + tuples
- `dolthub-java-port/src/main/java/com/dolthub/prolly/NodeStore.java` · `InMemoryNodeStore.java` · `Cursor.java` — store + read
- `dolthub-java-port/src/test/java/com/dolthub/prolly/UsageExampleTest.java` — the hello-world, run as a test (this guide is compile-pinned)
- `dolthub-java-port/src/test/java/com/dolthub/prolly/MutableMapTest.java` — the broader edit-buffer coverage
- `prolly-storage/src/main/java/com/earasoft/prolly/storage/RocksNodeStore.java` — the production `NodeStore`; `Database.java` — the versioning layer on top
