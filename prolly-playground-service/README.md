# prolly-playground-service — the real engine, served

A small Spring Boot backend that gives the web playground (and curl) a window onto the
**actual** engine — where the playground's JS simulates, this serves `MutableMap` →
`TreeMutator` → `Node` for real. One in-memory content-addressed store, one current tree
of int64 keys.

```bash
mvn -pl prolly-playground-service spring-boot:run     # http://localhost:8080
```

(First time on a fresh clone: run `mvn -DskipTests install` from the repo root once, so the
engine modules resolve.)

**Disk engines** — by default the store is in-memory and dies with the process. Two durable
modes run the actual `prolly-storage` engines and survive restarts (the root pointer persists
as a plain `<store-dir>/head` file, loose-refs style):

```bash
# one file per chunk under <dir>/chunks — the content-addressed store, visible to ls
mvn -pl prolly-playground-service spring-boot:run \
  -Dspring-boot.run.arguments="--playground.store=file --playground.store-dir=/tmp/wp-store"

# the production RocksDB store under <dir>/db
mvn -pl prolly-playground-service spring-boot:run \
  -Dspring-boot.run.arguments="--playground.store=rocks --playground.store-dir=/tmp/wp-store"
```

**Any store directory opens in the playground** — point `--playground.store-dir` at an
existing store (a test's leftover, another service's data) and browse it in the browser.
For the terminal, the same jar is a store inspector (no web server, the engine's own
reader — never a second parser):

```bash
java -jar target/prolly-playground-service-*.jar --inspect <store-dir>          # the tree from head
java -jar target/prolly-playground-service-*.jar --inspect <store-dir> <hash>   # one decoded node
```

Auto-detects file vs RocksDB stores (a RocksDB store must not be held by a running
service — single-process). Refuses non-store paths.

Kill the process and start it again over the same directory: the exact tree resumes — same
root hash, every node re-verified from bytes re-read off disk (`POST /api/reset` is the one
thing that wipes it). One honest asymmetry: after a reopen, `GET /api/nodes` lists the whole
store in `file` mode (the store enumerates) but only the current process's writes in `rocks`
mode (no public enumeration); the tree walk (`/api/tree/nodes`) is always complete.

## Prove it yourself (curl)

The engine's two load-bearing claims, checked from your terminal against a running service —
no trust in the service required. (The snippets capture the hash rather than hard-coding one:
the *property* is stable; the exact bytes evolve freely pre-1.0.)

**The name IS the checksum** — fetch a node's raw stored bytes and re-hash them yourself
(SHA-512/20 = the first 20 bytes of SHA-512):

```bash
ROOT=$(curl -s -X PUT localhost:8080/api/tree -H 'Content-Type: application/json' \
  -d "{\"keys\":[$(seq -s, 1 5000)]}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["rootHash"])')

curl -s localhost:8080/api/nodes/$ROOT/bytes | python3 -c '
import json, sys, hashlib
b = json.load(sys.stdin)
mine = hashlib.sha512(bytes.fromhex(b["hex"])).digest()[:20].hex()
print("independently verified:", mine == b["hash"])'
```

Any tampered byte anywhere in the store changes the hash — the lie is detectable by anyone,
with no external truth. (A name the store never minted returns 404, never fabricated data.)

**History-independence** — the same key set, re-inserted in a shuffled order, produces the
byte-identical root (structure is a pure function of content; diff, merge, and sync all rest
on this):

```bash
python3 - "$ROOT" <<'EOF'
import json, random, sys, urllib.request
keys = list(range(1, 5001)); random.shuffle(keys)
req = urllib.request.Request('http://localhost:8080/api/tree', method='PUT',
    data=json.dumps({'keys': keys}).encode(), headers={'Content-Type': 'application/json'})
shuffled = json.load(urllib.request.urlopen(req))['rootHash']
print('sorted-insert root:  ', sys.argv[1])
print('shuffled-insert root:', shuffled)
print('byte-identical:', shuffled == sys.argv[1])
EOF
```

Bonus: `curl -s localhost:8080/api/tree/find/2500` returns `readPath` — the node hashes the
store *actually served* for that lookup (root first, one per level; measured by the store,
not re-derived). Every hash in it resolves via `/api/nodes/{hash}` and verifies.

| endpoint | what it does |
|---|---|
| `PUT /api/tree {"keys":[...]}` | replace the world: build a fresh tree of exactly these longs |
| `POST /api/tree/keys {"keys":[...]}` | insert — the response's `written[]` is the node set this write actually minted (the spine, measured) |
| `DELETE /api/tree/keys {"keys":[...]}` | delete keys |
| `GET /api/tree` | root hash, tree count, height, stored-node count |
| `GET /api/tree/nodes` | every node reachable from the current root, breadth-first (root first) — one call renders the whole live tree |
| `GET /api/nodes` | the content-addressed store in first-write order |
| `GET /api/nodes/{hash}` | one node parsed from its stored bytes: level, keys (decoded longs), children with **cumulative subtree counts**, and `verified` — the bytes re-hashed against the name, live |
| `GET /api/nodes/{hash}/bytes` | the node's raw stored bytes as hex — SHA-512/20 of exactly these bytes IS the name |
| `GET /api/tree/find/{key}` | point lookup via the engine's own `StaticMap.get`; the response carries `readPath` — the node hashes the store actually served (measured, root first) |
| `GET /api/tree/rank/{n}` | 0-based ordinal seek — the counted-B-tree descent over the subtree-count prefix sums, `readPath` measured |
| `GET /api/tree/scan?from=&to=&limit=` | range scan via the engine's `Cursor`: one descent + leaf hops, stop past `to`; `truncated` names any cap |
| `GET /api/tree/nodes?root=<hash>` | the tree under any SUPERSEDED root still in the store (it retains all of them) — time travel from content addressing alone |
| `POST /api/reset` | erase the store and the tree — the one way this store forgets |

Keys use the engine's **binary-parity int64 descriptor** — plain little-endian longs do
not byte-sort numerically; parity mode (big-endian + sign-flip) is what makes byte order
equal numeric order. The descriptor must reach the `TupleBuilder`: this service's first
smoke run decoded garbage keys because it didn't, which is now pinned by a
round-trip assertion in the test suite.

Real chunk geometry applies: 512 B–16 KiB per node means **hundreds of int64 entries per
leaf** — a 300-key tree is ~3 nodes; multi-level structure starts in the thousands (the
sim's toy-scale trees split much sooner by design).

The [web playground](../prolly-web-playground/README.md) consumes this API through its
data-mode switch: **sim + shadow** mirrors the sim's key set here and compares write
sets side by side; **real engine** mode renders and edits this service's own store
directly — the page becomes a client of the engine.

CORS is wide open on purpose: the playground also runs off `file://`, and the backend
holds one in-memory toy tree. Served at `/` (bundled at build time from
`../prolly-web-playground`), the playground needs no CORS at all — same origin.
