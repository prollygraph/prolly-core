# ADR-0064: Node read integrity verification

## Status

Accepted, 2026-06-16. Guides `dolthub-java-port/an upstream plan.

## Context

The store is content-addressed: a node's key **is** the hash of its bytes (SHA-512 truncated to 20
bytes — the Noms/Dolt content-address). That makes integrity verification cheap *in principle* — re-hash
a node read from disk and compare it to the key it was fetched by. Without that check, a bit-rotted or
wrong RocksDB block deserializes to a silently-wrong `Node` and the corruption propagates through the
Merkle graph undetected — the worst failure class (correct-looking, wrong data).

Two findings (from `core-read-integrity-default.md` Steps 1–2) reframed the decision:

1. **Not wired in production.** An `IntegrityVerifyingNodeStore` decorator (re-hash on read, throw on
   mismatch) exists, but every construction site is in *test* code; the production read seam
   (the upstream per-repo Sail factory) builds a raw `RocksNodeStore` with no verification. **Production reads
   are unverified today.**
2. **Verifying *every* read is expensive.** Measured (`ReadIntegrityCostBench`, 4 KiB nodes): the
   re-hash adds **~9.1 µs/read → +25.7× on a cache-hit read, +4.4× on a RocksDB-Get read**. Attributed
   to genuine SHA-512 compute (~450 MB/s, non-intrinsic on this host), not a fixable overhead
   (`HashUtils.hash` already reuses a `ThreadLocal<MessageDigest>`).

The structural reason the naive approach is so costly: `RocksNodeStore` has an internal `NodeCache`, and
the decorator sits **above** it, so it re-hashes even cache hits — re-verifying bytes that are already
trusted (hashed at write time, or verified on their first disk read). The expensive work lands on
exactly the reads that don't need it. **The decision: where should verification sit, and should it be
the default**, given a content-hash whose per-read cost is ~9 µs.

## Options

| Option | warm (cache-hit) read | cold (disk) read | what's verified | default-on viable? | code shape / format impact |
|---|---|---|---|---|---|
| **A** — outer decorator, verify every read (plan's original D-3) | **+26×** (re-hash) | +4.4× | every read, incl. trusted cache hits | **no** — 26× hot-path tax | clean composable decorator; no format change |
| **B** — verify below the cache, in `RocksNodeStore` (**chosen**) | **free** (hit skips verify) | +4.4× warm-block → ~0 disk-cold | only the untrusted disk reads; cache hits trusted | **yes** — hot path free | verify moves into the store (gated); decorator retires from production; no format change |
| **C** — faster content-hash (xxh3) + decorator A | ~+0.3× | ~negligible | every read | yes (cheap) | **format-breaking** — every persisted hash changes; a separate, large project |
| **D** — sample / async verification | reduced | reduced | a fraction / off the hot path | yes, but weakens the guarantee | added complexity; probabilistic detection |

## Decision

**Option B — verify below the cache, inside `RocksNodeStore.read`.**

**D-1. Verify on the disk-Get branch, not as an outer decorator.** `RocksNodeStore.read` re-hashes the
bytes returned by `db.get` and compares to the requested key *before* caching them; a cache hit returns
trusted bytes with no re-hash. **The deciding tradeoff:** this verifies *exactly the untrusted reads*
(the disk path — the only place bit-rot enters) and trusts the in-memory cache, eliminating option A's
dominant 26× warm tax while keeping detection where corruption actually originates. The cost is that
verification moves *into* `RocksNodeStore` (gated by a flag) rather than staying a clean outer decorator
— an acceptable coupling, since the store already owns content-addressing (it knows the key is the
hash). `IntegrityVerifyingNodeStore` is kept for tests / non-Rocks stores but retires from production.

**D-2. Default ON, disable-able via the upstream `verify-integrity` property (default `true`).** With the hot
(cache-served) path now free, default-on is viable and is the safe-by-default choice the fail-closed
ethic wants — restoring the plan's original default-on *intent* on a sound basis (the measurement that
refuted "verification is cheap" is answered by making it free *where it is paid most*). **The accepted
cost:** cache-miss (disk) reads still pay the re-hash (+4.4× block-cache-warm; ~negligible truly
disk-cold), so a cold bulk scan (a garbage-collection sweep, a post-restart re-read) is taxed — but
those *are* the untrusted reads, and an operator with a cold-read-dominated, throughput-critical
workload sets the knob to `false`. (Contrast A, where default-on was untenable at 26× on *every* read.)

**D-3. Verify *before* caching — the cache stores verified bytes.** Today `RocksNodeStore.read`
populates the cache on a read-miss with **unverified** bytes (contradicting its own javadoc's "not
populated by reads … verify … before it is cached"). Verifying before the cache `put` makes each node
verified exactly once (first disk read), then trusted from cache — the guarantee the javadoc always
claimed but never delivered. (Fixing that stale javadoc is part of the implementation.)

## Consequences

- **Hot-path cost removed; cold-read cost remains, by design.** Cache-served reads pay nothing (the
  common steady state); disk reads pay the SHA-512 (+4.4× warm-block-cache, ~negligible disk-cold). A
  cold bulk scan is taxed — documented; the knob disables it.
- **Verification lives in `RocksNodeStore`** (gated by `verifyOnRead`), not a decorator — slightly less
  composable, but matches where content-addressing already lives and avoids the re-verify-cache-hits
  waste. `Database`'s existing `instanceof IntegrityVerifyingNodeStore` unwrap becomes dead-for-production
  but harmless (kept for the test paths).
- **The cache now stores verified bytes** + the stale javadoc is corrected (a real latent bug: unverified
  cache population).
- **Cost stays hash-bound.** SHA-512 at ~450 MB/s is the per-disk-read price; option C (xxh3) would make
  even cold reads ~free and **compounds** with B (B decides *where* to verify, C decides *how fast* the
  hash is) — a future, format-breaking ADR.
- **Mismatch fails closed** with a clear, typed corruption error (expected vs actual hash) — never serve
  wrong bytes.
- **Measured (Step 3c, `ReadIntegrityCostBench`), not just predicted.** On a primed `RocksNodeStore` +
  `NodeCache` with the all-hit regime *proven* (`misses=0` over the timed window), Option B's warm
  cache-hit tax is **~0** — at the noise floor, ≪ the **+~9 µs** Option A re-hashes onto *every* hit.
  The cold cache-miss tax is **+~8.9 µs (≈+4.3×)**, matching the predicted +4.4×. Two internal
  consistency checks held: the re-hash delta agrees warm-decorator ≈ cold (same SHA-512, same node),
  and ~9 µs / 4 KiB ≈ 455 MB/s matches the ~450 MB/s SHA-512 figure above. (One caught artifact: the
  first run read "verify-on faster than raw" — a causally-impossible JIT-warmup ordering effect, since
  on a hit on/off run byte-identical code; a global warmup resolved it. The hot-path-free claim is
  about the *absolute* ~0 tax, not the noise-floor ratio.)

## Follow-up / future work

- **xxh3 content-hash** (the planned ~50 GB/s hash): compounds with this ADR to make cold reads ~free
  too. Its own format-breaking ADR.
- **Re-measure after B lands** (plan Step 3c): confirm the warm tax is structurally gone (cache hits
  skip verify) — the redesign's whole point.
