# Sync protocol specification

**Status:** normative for `0.2.0-BETA` (pack `PROTOCOL_VERSION = 1`,
gRPC package `prollycore.sync.v1`). Pre-1.0: the wire format changes freely
between versions; readers reject unknown versions rather than migrate — treat
cross-version sync as unsupported until 1.0. Every constant is cited in the
[verification map](#verification-map).

Replication is **pack-based**: the sender builds a self-contained container of
content-addressed chunks plus the commit chain that reaches them; the receiver
verifies everything, then advances a ref by **compare-and-set**. Content
addressing does the heavy lifting — identical chunks dedup by name on arrival,
packs prune to what the receiver already holds, and a received head is
*verifiably* the sender's because the name is the content.

## 1. The pack (`SyncPack` wire format, v1)

```
[ u32 BE   MAGIC = 0x53595020 ]          ← ASCII "SYP " — fails closed on garbage
[ u8       protocolVersion = 1 ]         ← reader rejects any other value
[ u32 BE   chunkCount ]
chunkCount × (
    [ 20 bytes  chunk hash ]             ← content address (SHA-512/20 today)
    [ u32 BE    dataLength ]
    [ dataLength bytes  chunk data ]
)
[ u32 BE   commitSectionLength ]
[ commitSectionLength bytes ]            ← the commit section, below
```

**Chunk verification is explicit:** the parser re-hashes every chunk's data and
rejects the whole pack on any mismatch. Content addressing would catch a
tampered chunk anyway (it would land at a different address than the tree
references); the explicit check fails fast with a clear error instead.

### The commit section

UTF-8 text, one line per commit entry, lines joined by `\n`:

```
<epochMillis> <hexCommitId> <hexMetaTreeHash> <parentCount> <hexParentId>… <base64Message|-> <base64Author|->
```

- Fields are space-separated; parent ids appear `parentCount` times.
- `message` and `author` are standard Base64 (RFC 4648, padded, of the UTF-8
  bytes); a **lone `-` denotes the empty string** (never a valid Base64 token,
  so the encoding is unambiguous).
- The timestamp travels in the line but is **not part of commit identity**
  (see the [format spec §7](on-disk-format.md#7-commit-identity-cross-ring-informative)).

## 2. Semantics

- **Closure completeness:** the pack carries the chunk closure walked from the
  head's tree root, plus the commit chain. Merge commits travel with their
  **full parent lineage** — a first-parent-only closure would silently drop
  side branches (pinned by test).
- **Incremental sync:** the builder takes the set of chunk names the receiver
  already holds and prunes the pack to the delta.
- **Verify-then-advance:** `apply` verifies the received head state — commit
  chain and tree closure — **before** any ref moves. Only then does the head
  advance, by compare-and-set against the expected old value. A lost race
  returns the current head rather than clobbering.

## 3. The gRPC service (`prollycore.sync.v1.PackSync`)

Defined in
[`packsync.proto`](../../prolly-sync-grpc/src/main/proto/packsync.proto)
(proto3):

| rpc | shape | purpose |
|---|---|---|
| `AdvertiseRefs(AdvertiseRefsRequest) → AdvertiseRefsResponse` | unary | list a repo's refs: `map<string, bytes>` of ref name → head hash |
| `FetchPack(FetchPackRequest) → stream FetchPackFrame` | server-streaming | download: request names `repo_id`, `branch`, and the caller's `have` hashes; response is a header frame then data frames |
| `ReceivePack(stream ReceivePackFrame) → ReceivePackResponse` | client-streaming | upload: a header frame (`repo_id`, `branch`, `new_head`, `expected_old`) then data frames; response is the CAS outcome |
| `CompareAndSetRef(CompareAndSetRefRequest) → CompareAndSetRefResponse` | unary | move a ref iff it still equals `expected_old`; returns `updated` and the `current` value either way |

**Framing rule:** in both streaming directions the **first frame is always the
header** (`oneof frame { header; data }`); every subsequent frame carries a
`bytes data` slice of the §1 pack serialization, reassembled by concatenation
on the far side. The pack's own magic + version + per-chunk hashes then verify
the reassembled bytes, so framing adds transport without adding trust.

**CAS outcome:** `ReceivePackResponse` / `CompareAndSetRefResponse` return
`updated: bool` and `current: bytes` — on a lost race the caller sees the
actual current head and can rebuild against it. There is no forced update in
the protocol.

## 4. Trust model

The pack parser is a **trust boundary**: magic and version fail closed before
any length field is trusted; every chunk is re-hashed; the commit section is
parsed field-by-field. A receiver never advances a ref to a head whose commit
chain and tree closure it has not verified chunk-by-chunk. What the protocol
does *not* provide: transport security and peer authentication are the
deployment's concern (run gRPC over TLS with your own authn); there is no
in-protocol signature scheme — the content address *is* the integrity check.

## Verification map

| claim | source |
|---|---|
| magic `0x53595020`, version `1`, layout, fail-closed | `prolly-sync/src/main/java/com/earasoft/prolly/sync/SyncPackCodec.java` (`MAGIC`, `PROTOCOL_VERSION`, class javadoc §layout, `parse`) |
| per-chunk re-hash + whole-pack rejection | `SyncPackCodec.parse` javadoc + implementation |
| commit-line grammar; Base64 std w/ padding; `-` = empty | `SyncPackCodec` (line format javadoc; `Base64.getEncoder` at ~L213) |
| full parent lineage in packs | `prolly-sync/README.md` (pinned by `DatabasePackSyncTest`) |
| verify-before-CAS; incremental `have` pruning | `DatabasePackSync.java`; `prolly-sync/README.md` |
| service, messages, first-frame-header framing | `prolly-sync-grpc/src/main/proto/packsync.proto` |
| pre-1.0 no-compat posture | `prolly-sync/README.md` §Status |

> Historical note: `SyncPackCodec`'s javadoc cites a
> `docs/distributed_sync_protocol.md` that predates the repo split and is not
> present here; this document supersedes that reference.
