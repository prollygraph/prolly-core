# Copyright 2026 Earasoft
#
# The BUZ_TABLE values are taken from the kch42/buzhash reference implementation
# (github.com/kch42/buzhash, MIT), via this repository's Java port
# (dolthub-java-port/src/main/java/com/dolthub/prolly/BuzHashTable.java).
#
# Licensed under the Apache License, Version 2.0.
"""The tested core behind the chunking notebooks.

Two layers live here:

- a **pedagogy layer** (FNV-1a, the low-bits shadow, per-element partitions,
  MIN/MAX clamping, a content-deterministic clamp) backing
  ``chunking_prolly_trees.ipynb``;
- a **production port layer** — a faithful Python port of the engine's
  ``RollingHashSplitter`` + ``BuzHash`` (window 67, MIN 512 / MAX 16384,
  per-level SHA-512 salt, the progressive-mask staircase) backing
  ``production_chunker.ipynb``. Fidelity is pinned two ways: the
  window-locality property test (state after >= W bytes depends only on the
  last W bytes) and, when the Java-emitted fixture is present, byte-identical
  boundary offsets against the real engine (``fixtures/``).

The notebooks import from here so the logic is testable
(``test_prolly_chunking.py``: pytest + hypothesis) while the notebooks stay
narrative.
"""
from __future__ import annotations

import hashlib
from typing import Callable, Sequence

MASK32 = 0xFFFFFFFF

# --------------------------------------------------------------------------
# Pedagogy layer: FNV-1a and the per-element scheme
# --------------------------------------------------------------------------

FNV_OFFSET = 0x811C9DC5
FNV_PRIME = 0x01000193


def fnv1a(data: bytes) -> int:
    """32-bit FNV-1a: per byte, XOR then multiply mod 2^32."""
    h = FNV_OFFSET
    for b in data:
        h ^= b
        h = (h * FNV_PRIME) & MASK32
    return h


def low_bits_fnv(key: int, bits: int) -> int:
    """The mod-2^bits shadow of FNV-1a over the key's 4 little-endian bytes.

    Equal to ``fnv1a(key_bytes) & (2^bits - 1)`` for every key — the sealed
    low-bits lane (XOR is bitwise; multiply is low-end-local mod 2^k).
    """
    mask = (1 << bits) - 1
    h = FNV_OFFSET & mask
    p = FNV_PRIME & mask
    for b in key.to_bytes(4, "little"):
        h = ((h ^ (b & mask)) * p) & mask
    return h


def key_hash(key: int) -> int:
    """FNV-1a of the key's 4 little-endian bytes — the per-element atom."""
    return fnv1a(key.to_bytes(4, "little"))


def is_boundary(key: int, bits: int = 2) -> bool:
    """Per-element boundary test: the hash's low ``bits`` are all zero."""
    return (key_hash(key) & ((1 << bits) - 1)) == 0


def partition(sorted_keys: Sequence[int], bits: int = 2) -> list[list[int]]:
    """Split sorted keys into leaves; a chunk closes at each boundary key."""
    leaves: list[list[int]] = []
    cur: list[int] = []
    for k in sorted_keys:
        cur.append(k)
        if is_boundary(k, bits):
            leaves.append(cur)
            cur = []
    if cur:
        leaves.append(cur)
    return leaves


def clamp_partition(
    sorted_keys: Sequence[int], bits: int, min_size: int, max_size: int | None
) -> list[list[int]]:
    """MIN/MAX-clamped partition: suppress boundaries below MIN, force at MAX."""
    leaves: list[list[int]] = []
    cur: list[int] = []
    for k in sorted_keys:
        cur.append(k)
        forced = max_size is not None and len(cur) >= max_size
        natural = len(cur) >= min_size and is_boundary(k, bits)
        if forced or natural:
            leaves.append(cur)
            cur = []
    if cur:
        leaves.append(cur)
    return leaves


def _h2(key: int) -> int:
    """Secondary weak hash for the content-deterministic clamp (seed-tweaked FNV)."""
    return fnv1a(b"h2:" + key.to_bytes(4, "little"))


def content_clamp_partition(
    sorted_keys: Sequence[int], bits: int, min_size: int, max_size: int
) -> list[list[int]]:
    """The clamp the original notebook *asserted* but never built: when MAX
    looms, the cut position is chosen by content (the key with the minimal
    secondary hash inside the overlong run), not by the raw length counter.

    A length-counter MAX cut counts from the previous cut, so an early edit
    shifts every forced cut downstream (fixed-size behavior). Choosing the
    forced cut by a per-key secondary hash re-locks it to content: the same
    keys pick the same cut no matter what preceded them, so the damage heals
    at the next natural boundary just like MIN suppression does.
    """
    leaves: list[list[int]] = []
    cur: list[int] = []
    for k in sorted_keys:
        cur.append(k)
        natural = len(cur) >= min_size and is_boundary(k, bits)
        if natural:
            leaves.append(cur)
            cur = []
        elif len(cur) >= max_size:
            # Forced cut — but at the content-chosen position, not at MAX.
            cut = min(range(len(cur)), key=lambda i: (_h2(cur[i]), cur[i]))
            leaves.append(cur[: cut + 1])
            cur = cur[cut + 1 :]
    if cur:
        leaves.append(cur)
    return leaves


# --------------------------------------------------------------------------
# Per-element prolly TREE (addresses + root), for the pedagogy notebook
# --------------------------------------------------------------------------


def _chunk_address(entries: Sequence[tuple[int, bytes]]) -> bytes:
    """Address = FNV-independent digest of the chunk's serialized entries."""
    h = hashlib.sha256()
    for k, payload in entries:
        h.update(k.to_bytes(4, "little"))
        h.update(len(payload).to_bytes(4, "little"))
        h.update(payload)
    return h.digest()[:20]


def _entry_boundary(key: int, payload: bytes, bits: int) -> bool:
    """Content-defined boundary for a (key, payload) entry at any level.

    Hashing key+payload (not just the key) keeps internal-level boundaries
    from aligning vertically with leaf boundaries — the same job the
    production splitter's per-level salt does.
    """
    return (fnv1a(key.to_bytes(4, "little") + payload) & ((1 << bits) - 1)) == 0


def build_tree(sorted_keys: Sequence[int], bits: int = 3) -> list[list[list[tuple[int, bytes]]]]:
    """Build the full prolly tree bottom-up; returns levels of chunks of entries.

    Leaf entries are ``(key, b"")``; internal entries are
    ``(last key of child, child address)``. Chunking at every level uses the
    same content-defined test, so the whole shape is a function of content.
    """
    entries: list[tuple[int, bytes]] = [(k, b"") for k in sorted_keys]
    levels: list[list[list[tuple[int, bytes]]]] = []
    while True:
        chunks: list[list[tuple[int, bytes]]] = []
        cur: list[tuple[int, bytes]] = []
        for k, payload in entries:
            cur.append((k, payload))
            if _entry_boundary(k, payload, bits):
                chunks.append(cur)
                cur = []
        if cur:
            chunks.append(cur)
        levels.append(chunks)
        if len(chunks) == 1:
            return levels
        entries = [(chunk[-1][0], _chunk_address(chunk)) for chunk in chunks]


def root_address(levels: list[list[list[tuple[int, bytes]]]]) -> bytes:
    """The root chunk's address — THE hash two equal trees must share."""
    return _chunk_address(levels[-1][0])


def chunk_address(entries: Sequence[tuple[int, bytes]]) -> bytes:
    """Public name for a chunk's address — the notebooks' lookup walk needs it."""
    return _chunk_address(entries)


def tree_addresses(levels: list[list[list[tuple[int, bytes]]]]) -> set[bytes]:
    """Every chunk address in the tree (all levels)."""
    return {_chunk_address(c) for level in levels for c in level}


def level_addresses(levels: list[list[list[tuple[int, bytes]]]]) -> list[set[bytes]]:
    """Per-level address sets (leaf first) — for spine-rewrite accounting."""
    return [{_chunk_address(c) for c in level} for level in levels]


def changed_chunks(
    a: list[list[list[tuple[int, bytes]]]], b: list[list[list[tuple[int, bytes]]]]
) -> int:
    """How many of b's chunks are new relative to a — the honest blast radius
    (a changed leaf changes its whole path: expect ~1 leaf + height)."""
    return len(tree_addresses(b) - tree_addresses(a))


# --------------------------------------------------------------------------
# Production port layer: BuzHash + RollingHashSplitter, faithfully
# --------------------------------------------------------------------------

# The kch42/buzhash 256-entry table, verbatim via the Java port. Changing one
# bit moves every boundary (the engine pins this with ChunkerDeterminismGateTest).
BUZ_TABLE: tuple[int, ...] = (
    0x12bd9527, 0xf4140cea, 0x987bd6e1, 0x79079850, 0xafbfd539, 0xd350ce0a,
    0x82973931, 0x9fc32b9c, 0x28003b88, 0xc30c13aa, 0x6b678c34, 0x5844ef1d,
    0xaa552c18, 0x4a77d3e8, 0xd1f62ea0, 0x6599417c, 0xfbe30e7a, 0xf9e2d5ee,
    0xa1fca42e, 0x41548969, 0x116d5b59, 0xaeda1e1a, 0xc5191c17, 0x54b9a3cb,
    0x727e492a, 0x5c432f91, 0x31a50bce, 0xc2696af6, 0x217c8020, 0x1262aefc,
    0xace75924, 0x9876a04f, 0xaf300bc2, 0x3ffce3f6, 0xd6680fb5, 0xd0b1ced8,
    0x6651f842, 0x736fadef, 0xbc2d3429, 0xb03d2904, 0x7e634ba4, 0xdfd87d8c,
    0x7988d63a, 0x4be4d933, 0x6a8d0382, 0x9e132d62, 0x3ee9c95f, 0xfec05b97,
    0x6907ad34, 0x8616cfcc, 0xa6aabf24, 0x8ad1c92e, 0x4f2affc0, 0xb87519db,
    0x6576eaf6, 0x15dbe00a, 0x63e1dd82, 0xa36b6a81, 0xeead99b3, 0xbc6a4309,
    0x3478d1a7, 0x2182bcc0, 0xdd50cfce, 0x7cb25580, 0x73075483, 0x503b7f42,
    0x4cd50d63, 0x3f4d94c9, 0x385fcbb7, 0x90daf16c, 0xece10b8e, 0x11c1cb04,
    0x816a899b, 0x69a29d06, 0xfb090b37, 0xf98ef13c, 0x07653435, 0x9f15dc42,
    0x3b43abdf, 0x1334283f, 0x93f3d9af, 0x0cbdfe71, 0xa788a614, 0x4f54d2f0,
    0xd4374fc7, 0x70557ce7, 0xf741fce8, 0xe4b6f661, 0xc630cb98, 0x387a6366,
    0x72f428fd, 0x539009db, 0xc53e3810, 0x1e1a52e5, 0x7d6816b0, 0x040f9b81,
    0x9c99c9fb, 0x9f3af3d2, 0x774d1061, 0xd5c840ea, 0x8e1480fe, 0x6ee4023c,
    0x2fbda535, 0xd88eff7a, 0xd8632a2a, 0x43c4e024, 0x3ef27971, 0xc72866fd,
    0xe35cc630, 0x46d96220, 0x437a8384, 0xe92caf0c, 0x6290a47e, 0xa7bb9238,
    0x0e1000f9, 0x49e76bdc, 0x3acfb4b8, 0x03582b8e, 0x6ea2de4e, 0x2ec1008d,
    0xfcc8df69, 0x91c2fe0a, 0xb471c7d9, 0x778be812, 0x70d29ad1, 0x76411cbf,
    0xc302e81c, 0x4e445194, 0x22e3aa72, 0xb65762e9, 0xa280db05, 0x827aa70e,
    0x4c531a9d, 0x7a60bf4a, 0x8fd95a44, 0x2289aef0, 0xcd50ddc4, 0x639aae69,
    0x5fe85ed6, 0x4ed724ff, 0x00f04f7d, 0x95a5fcb0, 0x88255d15, 0xa603d2c9,
    0xf6956a5b, 0x53ea7f3e, 0xb570f225, 0x2b3be203, 0xa181e40e, 0xc413cdce,
    0xa7cb1ebb, 0xcf258b1f, 0x516eb016, 0xca204586, 0xd1e69894, 0xe85a73d3,
    0x7db2d382, 0xae73b463, 0x3598d643, 0x5087c864, 0xd91f30b6, 0xe1d4d1e7,
    0x73b3b337, 0xceac1233, 0x8edf7845, 0xa69c45c9, 0xdb5db3ab, 0x28cfade8,
    0xebfa49e7, 0xcbc2a659, 0x59cce971, 0x959a01af, 0x8ee9aae7, 0xfb2f01c6,
    0x5a752836, 0x9ed12981, 0x618d05b6, 0x93ec12b3, 0x4590c779, 0xed1317a2,
    0x03fe5835, 0x7ad3c6f7, 0xd4aad5b5, 0x1a995ed7, 0x247bfaa4, 0x69c2c799,
    0x745fa405, 0xc5b9f239, 0xc3d9aebc, 0xa6f60e0b, 0xdf1e91d7, 0xab8e041c,
    0xee3188c6, 0x37377a9e, 0xc0e1a3bf, 0x19a5a9e4, 0x56cb9556, 0xc4d33d3f,
    0xfb1eb03e, 0xf9557057, 0x1be31d37, 0xd1fa65f1, 0xf518d714, 0x570ac722,
    0xf26cf66a, 0x24794d47, 0x8ba2e402, 0x3f5137e6, 0x35be1453, 0x43350478,
    0x9f05ee88, 0x364cf9cf, 0x39a23ee7, 0xa4db8d49, 0xc2ebb3d2, 0xc6fb99d5,
    0xe014dfb0, 0x7156d425, 0xe090a87a, 0x4cc12f78, 0x1b30f503, 0x06694a7a,
    0x68198cd1, 0x2f8345bd, 0x9d79198e, 0xd871943f, 0x22ef6cf4, 0xe81b1c15,
    0x067b61d8, 0xfc4ea4f5, 0xfe6dab57, 0x1bf744ba, 0xa70b6a25, 0xafe6e412,
    0xc6c1a05c, 0x8ffbe3ce, 0xc4270af1, 0xf3f36373, 0xc4507dd8, 0x5e6fd1e2,
    0x58cd9739, 0x47d3c5b5, 0xe1d5a343, 0x3d4dea4a, 0x893d91ae, 0xbb2a5e2a,
    0x0d57b800, 0x652a7cc9, 0x6a68ccfd, 0x62529f0b, 0xec5f36d6, 0x766cceda,
    0x96ca63ef, 0xa0499838, 0xd9030f59, 0x8185f4d2,
)

PROD_MIN_CHUNK = 1 << 9  # 512
PROD_MAX_CHUNK = 1 << 14  # 16384
PROD_WINDOW = 67
# The staircase reaches pattern 0 (boundary certain) at offset 15 * 1024:
PROD_CERTAIN_CUT = 15 * 1024  # 15360 — the *effective* max chunk size


def _rotl32(x: int, n: int) -> int:
    n %= 32
    return ((x << n) | (x >> (32 - n))) & MASK32


class BuzHash:
    """Faithful port of the engine's cyclic-polynomial rolling hash.

    Per byte: rotate state left 1; if the window is full, XOR out the leaving
    byte's table word rotated by ``window % 32`` (exactly cancelling the
    rotation it accumulated); XOR in the entering byte's table word.
    """

    def __init__(self, window: int) -> None:
        self.n = window
        self.bshiftn = window % 32
        self.buf = bytearray(window)
        self.reset()

    def reset(self) -> None:
        self.state = 0
        self.bufpos = 0
        self.overflow = False

    def hash_byte(self, b: int) -> int:
        if self.bufpos == self.n:
            self.overflow = True
            self.bufpos = 0
        state = _rotl32(self.state, 1)
        if self.overflow:
            state ^= _rotl32(BUZ_TABLE[self.buf[self.bufpos]], self.bshiftn)
        self.buf[self.bufpos] = b & 0xFF
        self.bufpos += 1
        state ^= BUZ_TABLE[b & 0xFF]
        self.state = state
        return state

    def sum32(self) -> int:
        return self.state


def salt_from_level(level: int) -> int:
    """SHA-512 of the single level byte, first 8 bytes little-endian — the
    per-level seed that keeps boundaries from aligning vertically across
    tree heights."""
    digest = hashlib.sha512(bytes([level & 0xFF])).digest()
    return int.from_bytes(digest[:8], "little")


def rolling_hash_pattern(offset: int) -> int:
    """The progressive-mask staircase: ``(1 << (15 - offset//1024)) - 1``.

    At offset 512 the boundary needs 15 hash bits set (p = 2^-15 per byte);
    every KiB the requirement drops one bit (p doubles); at 15 KiB the
    pattern is 0 and the cut is certain. The staircase is simultaneously the
    tail-tamer (no giants past 15360) and the reason the forced-MAX branch at
    16384 is nearly unreachable.
    """
    shift = 15 - (offset >> 10)
    return (1 << shift) - 1


class RollingHashSplitter:
    """Faithful port of the engine's splitter (simple always-hash variant).

    The Java implementation skips hashing the first ``MIN - W`` bytes of each
    chunk (they roll out of the window before the first check at MIN, so they
    can never affect a boundary — a pure work-elimination optimization). This
    port hashes every byte; the decisions are byte-identical, which the
    window-locality test proves in general and the Java-emitted fixture
    re-proves against the real engine (the Java side *runs* the skip).
    """

    def __init__(self, level: int) -> None:
        self.bz = BuzHash(PROD_WINDOW)
        self.salt = salt_from_level(level)
        self.reset()

    def reset(self) -> None:
        self.offset = 0
        self.crossed_boundary = False
        self.bz.reset()

    def append(self, key: bytes, value: bytes = b"") -> None:
        for b in key:
            self._hash_byte(b)
        for b in value:
            self._hash_byte(b)

    def _hash_byte(self, b: int) -> None:
        self.offset += 1
        if self.crossed_boundary:
            return
        self.bz.hash_byte((b ^ self.salt) & 0xFF)
        if self.offset < PROD_MIN_CHUNK:
            return
        if self.offset >= PROD_MAX_CHUNK:
            self.crossed_boundary = True
            return
        patt = rolling_hash_pattern(self.offset)
        if (self.bz.sum32() & patt) == patt:
            self.crossed_boundary = True


def production_chunks(data: bytes, level: int = 0) -> list[int]:
    """Chunk a byte stream with the production splitter at 1-byte entry
    granularity; returns chunk lengths (the trailing partial chunk included)."""
    sp = RollingHashSplitter(level)
    sizes: list[int] = []
    run = 0
    for b in data:
        sp.append(bytes([b]))
        run += 1
        if sp.crossed_boundary:
            sizes.append(run)
            run = 0
            sp.reset()
    if run:
        sizes.append(run)
    return sizes


def production_boundaries(data: bytes, level: int = 0) -> list[int]:
    """Absolute end offsets (exclusive) of each chunk, including the trailing
    partial chunk (whose end is simply ``len(data)``, boundary or not)."""
    out, pos = [], 0
    for size in production_chunks(data, level):
        pos += size
        out.append(pos)
    return out


# --------------------------------------------------------------------------
# Production-model tree (serialized entries, per-level salt) — root + reuse
# --------------------------------------------------------------------------


def _prod_entry_bytes(key: bytes, payload: bytes) -> bytes:
    return len(key).to_bytes(4, "little") + key + len(payload).to_bytes(4, "little") + payload


def build_production_tree(
    entries: Sequence[tuple[bytes, bytes]],
) -> list[list[list[tuple[bytes, bytes]]]]:
    """Chunk (key, value) entries with the production splitter, level by level
    (each level gets its own salt), until a single root chunk remains."""
    level = 0
    cur_entries = list(entries)
    levels: list[list[list[tuple[bytes, bytes]]]] = []
    while True:
        sp = RollingHashSplitter(level)
        chunks: list[list[tuple[bytes, bytes]]] = []
        cur: list[tuple[bytes, bytes]] = []
        for key, value in cur_entries:
            sp.append(key, value)
            cur.append((key, value))
            if sp.crossed_boundary:
                chunks.append(cur)
                cur = []
                sp.reset()
        if cur:
            chunks.append(cur)
        levels.append(chunks)
        if len(chunks) == 1:
            return levels
        cur_entries = [
            (chunk[-1][0], _prod_chunk_address(chunk)) for chunk in chunks
        ]
        level += 1


def _prod_chunk_address(chunk: Sequence[tuple[bytes, bytes]]) -> bytes:
    h = hashlib.sha256()
    for key, value in chunk:
        h.update(_prod_entry_bytes(key, value))
    return h.digest()[:20]


def prod_tree_addresses(levels: list[list[list[tuple[bytes, bytes]]]]) -> set[bytes]:
    return {_prod_chunk_address(c) for level in levels for c in level}


def prod_root_address(levels: list[list[list[tuple[bytes, bytes]]]]) -> bytes:
    return _prod_chunk_address(levels[-1][0])


# --------------------------------------------------------------------------
# Analysis helpers shared by the notebooks
# --------------------------------------------------------------------------


def toy_buzhash_boundaries(
    data: bytes, window: int = 48, mask_bits: int = 6, table: Sequence[int] | None = None
) -> list[int]:
    """The original notebook's toy buzhash (fixed mask, == 0 test, no salt) —
    kept for the pedagogy sections; the production splitter above is the one
    the engine runs."""
    tbl = list(table) if table is not None else list(BUZ_TABLE)
    mask = (1 << mask_bits) - 1
    outs = []
    wr = window % 32
    h = 0
    buf = bytearray(window)
    bufpos = 0
    overflow = False
    for i, b in enumerate(data):
        if bufpos == window:
            overflow = True
            bufpos = 0
        h = _rotl32(h, 1)
        if overflow:
            h ^= _rotl32(tbl[buf[bufpos]], wr)
        buf[bufpos] = b
        bufpos += 1
        h ^= tbl[b]
        if i >= window and (h & mask) == 0:
            outs.append(i)
    return outs


def healing_distance(
    data: bytes, insert_at: int, window: int = 48, mask_bits: int = 6
) -> tuple[int | None, float]:
    """Insert one byte at ``insert_at``; return (resync distance in bytes or
    None if downstream never fully recovers, fraction of downstream
    boundaries recovered). The distribution of this over many offsets is what
    justifies 'W is the edit-healing distance'."""
    edited = data[:insert_at] + bytes([0x5A]) + data[insert_at:]
    b0 = toy_buzhash_boundaries(data, window, mask_bits)
    b1 = toy_buzhash_boundaries(edited, window, mask_bits)
    orig = {x for x in b0 if x > insert_at}
    shifted = {x - 1 for x in b1 if x > insert_at}
    if not orig:
        return None, 1.0
    recovered = len(orig & shifted) / len(orig)
    resync = None
    for x in sorted(orig):
        if all(y in shifted for y in orig if y >= x):
            resync = x - insert_at
            break
    return resync, recovered


def sha256_counter_stream(n: int) -> bytes:
    """The fixture corpus: ``SHA256(LE32(0)) || SHA256(LE32(1)) || …`` — both
    the Java emitter and this module regenerate it with no shared RNG."""
    out = bytearray()
    counter = 0
    while len(out) < n:
        out += hashlib.sha256(counter.to_bytes(4, "little")).digest()
        counter += 1
    return bytes(out[:n])


def chunk_end_offsets(data: bytes, level: int, entry_size: int) -> list[int]:
    """Absolute end offsets of closed chunks, consulting between entries of
    ``entry_size`` bytes — the exact protocol the Java fixture emitter uses."""
    sp = RollingHashSplitter(level)
    ends: list[int] = []
    pos = 0
    while pos < len(data):
        entry = data[pos : pos + entry_size]
        sp.append(entry)
        pos += len(entry)
        if sp.crossed_boundary:
            ends.append(pos)
            sp.reset()
    return ends
