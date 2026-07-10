# cross-lang — golden vectors

Byte-level fixtures generated from Dolt's Go implementation, pinning the layers where
this port IS byte-identical to Dolt: the hash function and the tuple **value encodings**
(Layers 0–2). `EncodingGoldenVectorTest` replays them in every build.

They deliberately do NOT pin whole-tree bytes — the layers above (tuple offset layout,
node framing, chunk boundaries) diverge by design. The full layer story:
[the Go port](../docs/foundations/the-go-port.md); the README's
[Relationship to Dolt](../README.md#relationship-to-dolt) is the short version.
