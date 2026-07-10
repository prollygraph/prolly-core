
# prolly-sync-grpc — the pack protocol on a socket

`prolly-sync` owns the pack semantics (build a pack of content-addressed chunks +
commit chain from one store, apply to another with verified compare-and-set); this
module is the wire: a gRPC server that hosts one or more `Database`s and a client
that pushes/pulls branches against it.

```java
// host
try (var server = PackSyncServer.start(
        50051, RepoResolver.singleRepo(db), PackLimits.defaults(), List.of())) {
    server.awaitTermination();
}

// replicate
var channel = ManagedChannelBuilder.forAddress("host", 50051).usePlaintext().build();
try (var client = new PackSyncClient(channel, "", PackLimits.defaults())) {
    client.push(local, "main");   // build pack vs remote refs → stream → CAS the ref
    client.pull(local, "main");   // fetch → fast-forward/create via integrate
}
```

## Design

- **The wire adds framing, limits, and status mapping — nothing else.** All pack
  semantics stay in `DatabasePackSync`: `ReceivePack` runs its full *apply* (stage
  chunks → verify the new head's closure is fully readable → compare-and-set under
  the store's write lock), so the ref can never move onto a torn pack, and a lost
  race is a normal `updated=false` response, not an error.
- **Framed streaming, no message-size ceiling.** `FetchPack` server-streams and
  `ReceivePack` client-streams ~1 MiB slices of the exact `SyncPackCodec` bytes —
  one codec, one integrity surface (every chunk's content address is re-verified at
  parse). Honest limitation: the pack still materializes in memory on both sides;
  incremental pack streaming is future work.
- **Limits before parse, on both sides.** `PackLimits` (default 1M chunks / 1 GiB)
  is enforced as frames accumulate — breach is `RESOURCE_EXHAUSTED` before any
  parsing happens. The client enforces the same caps on what it receives: a hostile
  server is untrusted input too.
- **Lease-shaped repo resolution.** `RepoResolver` hands out a per-request lease so
  a registry-backed multi-repo host can pin a store open for the request's duration
  (eviction must never close a store under an in-flight pack build). Single-store
  hosts use `RepoResolver.singleRepo(db)`.
- **No auth baked in.** The server takes `ServerInterceptor`s; the client takes
  whatever credentials the caller puts on the channel. Authentication policy belongs
  to the host — the test suite carries a static-token interceptor as the worked
  example.

## Error surface

| Condition | Status |
|---|---|
| unknown repo id | `NOT_FOUND` |
| pack over the byte/chunk caps | `RESOURCE_EXHAUSTED` (before parse) |
| corrupt pack (codec/content-address failure) | `INVALID_ARGUMENT` |
| torn pack (head closure unreadable — the ref never moves) | `FAILED_PRECONDITION` |
| lost ref compare-and-set race | **not an error** — `updated=false` + the winner's value |
| diverged pull | `IllegalStateException` client-side, same as the in-process protocol (`mergeBase` in hand for resolution) |

## Where this lives

- `src/main/proto/packsync.proto` — the wire format (`prollycore.sync.v1`)
- `PackSyncService` / `PackSyncServer` — the host side
- `PackSyncClient` — raw RPCs + the push/pull choreography
- `PackSyncEndToEndTest` — the acceptance contract over a real socket
