# The cost of a chunk boundary — a performance anatomy of boundary functions

Content-defined chunking asks one question per position in the input: *does a boundary
land here?* Every design answers it with a different amount of work per byte — and the
differences are mechanical enough to read off the inner loops before ever running a
benchmark. This doc walks the cost model of the four boundary functions the
[boundary-detection study](../write-ups/chunker-boundary-detection-study.md) measured,
explains *why* each costs what it does, and ends with the numbers that confirm the
model. Companion anatomy: [B1 — a chunk boundary](../anatomy/B1-a-chunk-boundary.md)
(what a boundary *is*); this doc is about what one *costs*.

All measurements: Intel N150 (4-core), JDK 25, JMH 1.37, 3 forks × 5 iterations,
1M sorted 64-byte quad keys per invocation, scores are ns **per key**.

## The yardstick: the memory floor

Before comparing hashes, establish the floor: a loop that reads every byte once and
XORs it into an accumulator — no tables, no state, no decisions.

```java
for (int i = 0; i < f.length; i++) acc ^= f[i];
```

**8.47 ns per 64-byte key ≈ 0.13 ns/byte.** No boundary function that examines every
byte can beat this on the same hardware; how far above it a method sits is a direct
reading of its per-byte instruction budget. (This scalar loop is itself well short of
DRAM bandwidth — the just-in-time compiler only partially vectorizes a byte-XOR
reduction — but that makes it the *right* floor: every candidate is compiled by the
same compiler under the same constraints.)

## C — buzhash: paying for a sliding window (3.19 ns/byte)

The incumbent (`BuzHash`, window 67, wrapped by `RollingHashSplitter`). Per byte, in
steady state, the code does:

1. a ring-buffer bounds check and store (the window has to *remember* 67 bytes),
2. one table lookup for the byte **entering** the window,
3. one table lookup for the byte **leaving** it, plus a rotate to age it correctly,
4. a rotate of the whole state,
5. — and in the splitter wrapper: a per-byte salt XOR, an offset increment, and a
   mask comparison against the staircase pattern.

Two data-dependent loads per byte (both L1-resident but on the critical path), two
rotates, several XORs, one store. Measured: **204.36 ns/key = 3.19 ns/byte**, 24× the
floor.

What that budget buys is the *removability* property: the hash is a function of
exactly the last 67 bytes, so a boundary decision has a strictly bounded look-behind.
That is what makes buzhash a true **rolling** hash — you can slide the window forward
in O(1) regardless of history. The study's other observation about C: the production
*staircase* mask (progressively looser as the chunk grows) produced the tightest tail
of the whole field (p99 = 7,360 B, max = 8,640 B at a 4,096 B target) — geometry
control is where this design spends its dividend.

## B — gear: half the loads, all the serialization (1.42 ns/byte)

FastCDC's gear hash drops the window buffer entirely:

```java
h = (h << 1) + GEAR[b & 0xFF];   // one lookup, one shift, one add — per byte
```

A byte's influence is shifted out of the 64-bit register after 64 steps, so the hash
has an *implicit* ~64-byte window — no ring buffer, no second lookup, no rotate-out.
The per-byte budget halves relative to buzhash: one data-dependent load and a
shift-add. But note the shape of what remains: every step depends on the previous
`h`, a **serial dependency chain** through a load — the processor cannot overlap
iterations much. Measured: **90.68 ns/key = 1.42 ns/byte**, 2.25× faster than
buzhash, still 11× the floor.

Gear's mask must test the **high** bits (the freshest entropy — low bits are dominated
by the most recent byte alone); with FastCDC's two-mask normalization (strict +2 bits
before target, loose −2 after) its chunk-size distribution matches buzhash's mean and
sigma, with a slightly fatter tail than the staircase.

## A — direct per-key mask: the cost of not hashing (5.16 ns/key — and wrong)

The hypothesis candidate: keys are concatenations of hash-derived term ids, so skip
hashing — read the first id, mask its low bits, boundary on zero. Per **key** (not per
byte): one 8-byte load, an XOR, a mask, a compare. Measured: **5.16 ns/key** —
*below the memory floor*, because it reads 8 of every 64 bytes.

The same corner-cutting breaks it. Chunking runs over the **sorted** key stream, and
sorted quad keys hold their first id (the subject) constant across whole runs — so a
predicate of the first id gives one verdict per *run*, not per key. Boundaries can
only land at run transitions: on the study's own high-entropy premise stream the mean
chunk was 2.5× target with p95 = p99 = MAX; on this engine's real key shape
(sequential dictionary ordinals — a second premise correction) it degenerated to
near-fixed ~10 KiB slabs. The lesson generalizes: **marginal entropy is not
conditional entropy.** Bytes that are uniformly distributed across the keyspace are
nearly deterministic given the *previous sorted key* — and the boundary function
consumes them in sorted order.

## A′ — lane-XOR direct mask: the repair, and the winner (0.32 ns/byte)

Mix *all* fixed-width lanes so the varying lane (the object id) always contributes:

```java
long mix = salt;
for (int w = 0; w + 8 <= len; w += 8) mix ^= readLong(key, w);
mix *= 0x9E3779B97F4A7C15L;          // spread lane bits into the masked low bits
boundary = (mix & mask) == 0;        // two-mask: strict below target, loose above
```

Still a pure per-key function — no table, no cross-key state beyond the running chunk
size — but now every byte of the key is read (plain loads, *independent* of each
other, no serial chain, no data-dependent table lookup) and one multiply diffuses
them. Measured: **20.59 ns/key = 0.32 ns/byte** — 2.4× the memory floor, 4.4× faster
than gear (t = 443.8), 9.9× faster than keys-only buzhash (t = 285.7), with geometry
statistically indistinguishable from gear's on both stream shapes (mean ≈ 4.5 KiB,
sd ≈ 1.5 KiB at the 4 KiB target).

Why it wins, in one sentence each:

- **vs buzhash**: no window to maintain — per-entry decisions never needed O(1)
  removability, so the ring buffer and double lookups were paying for an unused
  property.
- **vs gear**: no per-byte serial dependency through a table load — eight independent
  8-byte loads and one multiply pipeline far better than 64 dependent shift-adds.
- **vs A**: it actually reads the key.

## The trade summary

| method | work per byte | window | tail control | ns/key (64 B) |
|---|---|---|---|---|
| C buzhash + staircase | 2 loads + 2 rotates + store | explicit, 67 B | best (max 8.6 KiB) | 204.4 |
| B gear + two-mask | 1 load + shift-add (serial) | implicit, ~64 B | good | 90.7 |
| A′ lane-XOR + two-mask | ⅛ load + XOR; 1 mul/key | one key | good | 20.6 |
| A first-id mask | reads ⅛ of the key | one lane | broken | 5.2 |

Two caveats travel with A′: it assumes **fixed-width** keys (variable-width key spaces
need a lane-safe fallback — gear is the natural one, itself 2.25× faster than the
incumbent), and adopting *any* new boundary function moves every boundary and
therefore every chunk hash — a format-breaking change that must ship as its own
deliberate plan, pre-1.0 or not.

And the parallelism postscript: A′'s per-key predicate is embarrassingly parallel
(and Vector-API friendly), but boundary *resolution* is inherently sequential — the
two-mask needs the running chunk size, which depends on every earlier boundary. At
0.32 ns/byte the predicate is already low-single-digit percent of the write path, so
vectorizing it is a solution ahead of its problem. Revisit if a profile ever shows
the boundary function above ~10% of a write again.

## Where this lives

- [`../write-ups/chunker-boundary-detection-study.md`](../write-ups/chunker-boundary-detection-study.md) — the study: full tables, seeds, methodology
- [`../anatomy/B1-a-chunk-boundary.md`](../anatomy/B1-a-chunk-boundary.md) — what a boundary is and why chunking is content-defined at all
- `dolthub-java-port/src/main/java/com/dolthub/prolly/BuzHash.java` + `RollingHashSplitter.java` — the production incumbent
- `dolthub-java-port/src/test/java/com/dolthub/prolly/chunkbench/` — every candidate, stream generator, and bench in this doc
