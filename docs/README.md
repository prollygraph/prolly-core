# Documentation

Exported from the upstream monorepo's onboarding set; the engine-relevant slice.

**Foundations** (read first): [the prolly tree](foundations/the-prolly-tree.md) ·
[the on-disk format](foundations/the-on-disk-format.md) ·
[structural sharing & churn](foundations/structural-sharing-and-churn.md) ·
[the Go port discipline](foundations/the-go-port.md) ·
[engine error taxonomy](foundations/engine-error-taxonomy.md) ·
[boundary-function performance](foundations/boundary-function-performance.md)

**Anatomy** (one concrete invocation, end to end):
[a chunk boundary](anatomy/B1-a-chunk-boundary.md) · [a write](anatomy/B2-a-write.md) ·
[a read](anatomy/B3-a-read.md) · [a commit](anatomy/B4-a-commit.md) ·
[a diff](anatomy/B5-a-diff.md) · [a merge](anatomy/B6-a-merge.md)

**Decision records** ([docs/adr/](adr/)): the read-path node cache + zero-copy direction (0039),
Caffeine for the node cache (0040), the BOM (0042), buffer-pool segment recycling (0062),
read-integrity verification (0064), tree-write re-emit + fast-forwarding (0068), chunker
internal-node constraints (0069), the core merge-base strategy (0070), commit identity includes
parents (0071), node format versioning (0072), commit objects in the node store (0073).
Numbering has gaps by design: the sequence is shared with the upstream monorepo, and
RDF-woven decisions stay there.

**Engineering write-ups** ([docs/write-ups/](write-ups/)): two production bug stories about this
repo's code, warts and post-mortems intact — the
[GC ↔ concurrent-write flush window](write-ups/gc-concurrent-write-flush-window.md) (a silent
data-loss race) and the
[DirectBufferPool off-heap leak](write-ups/direct-buffer-pool-write-path-leak.md) (invisible to
the garbage collector, found by a resident-set sampler).

**More**: [the write-path deep dive](../prolly-web-playground/treemutator-write-path.md) ·
[the read-path deep dive](../prolly-web-playground/cursor-read-path.md) ·
[class roles](../prolly-web-playground/class-roles.md) ·
[the interactive write-path explorer](../prolly-web-playground/write-path-explorer.html)
(best served: `mvn -pl prolly-playground-service spring-boot:run` → http://localhost:8080/ —
that unlocks the real-engine data modes; opening the file directly in a browser also works,
sim-only, if its five sibling `.js` files stay together) ·
[storage usage](../prolly-storage/USAGE.md)

Some links inside these docs were de-linked to plain text at export — they cite
monorepo content (upper layers, plans) that lives upstream.

Up the stack: the [prolly-rdf ring's docs](https://github.com/prollygraph/prolly-rdf/blob/main/docs/README.md)
continue the curriculum over this engine (RDF foundations, anatomy, runnable demos).

- [`developer-skill-sets.md`](developer-skill-sets.md) — what working on this ring asks of you: competencies per module, ramp difficulty, and where to start.
