# Copyright 2026 Earasoft
#
# Licensed under the Apache License, Version 2.0.
"""A Deterministic Merkle Radix dictionary — the tested core behind
``merkle_radix_dictionary.ipynb``.

The structure: a **path-compressed radix trie** over byte strings (URIs,
literals) mapping each to an integer id, with every node **content-addressed**
(canonical serialization → SHA-256, truncated to 20 bytes) and stored in an
append-only address → bytes pool.

Why no "chunker" is needed for history independence: the compressed trie of a
key set is *unique* — structure is a function of the keys alone, never of
insertion order. That canonical shape + canonical serialization (edges sorted
by byte) makes the Merkle root a pure function of the {key: id} mapping. The
property is pinned two ways in ``test_merkle_radix_dict.py``: batch build vs
fold-of-inserts equality under hypothesis-random orders, and lookup
correctness for every inserted key.

Where content-defined chunking DOES earn its keep here: radix nodes are many
and small (a digit-fanout node is ~200 bytes), while storage wants page-sized
blocks. ``pack_pages`` groups the canonical DFS node sequence into pages with
a per-node boundary rule on the node's own address (mask + MIN/MAX clamps) —
the page partition is a deterministic function of the node sequence, and an
edit reshuffles only the pages near the changed nodes (the prolly property,
at the page-packing layer).
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Iterator

ADDR_LEN = 20


def _addr(serial: bytes) -> bytes:
    return hashlib.sha256(serial).digest()[:ADDR_LEN]


def _varlen(b: bytes) -> bytes:
    return len(b).to_bytes(4, "big") + b


@dataclass(frozen=True)
class Leaf:
    """A terminal holding the remaining key suffix and its dictionary id."""

    suffix: bytes
    id: int

    def serialize(self) -> bytes:
        return b"L" + _varlen(self.suffix) + self.id.to_bytes(8, "big")


@dataclass(frozen=True)
class Internal:
    """A path-compressed internal node.

    ``prefix``: the bytes every key below shares at this point.
    ``terminal_id``: the id of a key that ENDS exactly here (a key that is a
    strict prefix of other keys — ``http://a`` beside ``http://a/b``), or None.
    ``edges``: sorted (first byte → child address) — sortedness is part of the
    canonical form and therefore of history independence.
    """

    prefix: bytes
    terminal_id: int | None
    edges: tuple[tuple[int, bytes], ...]

    def serialize(self) -> bytes:
        out = b"I" + _varlen(self.prefix)
        out += b"\x01" + self.terminal_id.to_bytes(8, "big") if self.terminal_id is not None else b"\x00"
        out += len(self.edges).to_bytes(2, "big")
        for byte, child in self.edges:  # constructor guarantees sorted order
            out += bytes([byte]) + child
        return out


Node = Leaf | Internal


class Store:
    """Append-only content-addressed node pool (the mock block store)."""

    def __init__(self) -> None:
        self.nodes: dict[bytes, Node] = {}

    def put(self, node: Node) -> bytes:
        a = _addr(node.serialize())
        self.nodes[a] = node
        return a

    def get(self, addr: bytes) -> Node:
        return self.nodes[addr]


# --------------------------------------------------------------------- build


def build(store: Store, entries: dict[bytes, int]) -> bytes:
    """Canonical batch build: the unique compressed trie of the key set."""
    items = sorted(entries.items())
    if not items:
        return store.put(Internal(b"", None, ()))
    return _build(store, items)


def _lcp(items: list[tuple[bytes, int]]) -> bytes:
    first, last = items[0][0], items[-1][0]
    n = 0
    while n < len(first) and n < len(last) and first[n] == last[n]:
        n += 1
    return first[:n]


def _build(store: Store, items: list[tuple[bytes, int]]) -> bytes:
    if len(items) == 1:
        key, id_ = items[0]
        return store.put(Leaf(key, id_))
    prefix = _lcp(items)
    plen = len(prefix)
    terminal: int | None = None
    groups: dict[int, list[tuple[bytes, int]]] = {}
    for key, id_ in items:
        rest = key[plen:]
        if not rest:
            terminal = id_
        else:
            # The edge CONSUMES the first byte; the child holds what follows.
            groups.setdefault(rest[0], []).append((rest[1:], id_))
    edges = tuple(
        (byte, _build(store, group)) for byte, group in sorted(groups.items())
    )
    return store.put(Internal(prefix, terminal, edges))


# -------------------------------------------------------------------- insert


def insert(store: Store, root: bytes, key: bytes, id_: int) -> bytes:
    """Path-copying insert; returns the new root address.

    Correctness contract (hypothesis-pinned): folding inserts in ANY order
    over any key set yields byte-identical roots to ``build`` of the mapping.
    """
    return _insert(store, root, key, id_)


def _insert(store: Store, addr: bytes, key: bytes, id_: int) -> bytes:
    node = store.get(addr)
    if isinstance(node, Leaf):
        if node.suffix == key:
            return store.put(Leaf(key, id_))
        return _split_and_join(store, node.suffix, node.id, key, id_)
    # Internal
    prefix = node.prefix
    n = 0
    while n < len(prefix) and n < len(key) and prefix[n] == key[n]:
        n += 1
    if n < len(prefix):
        # Key diverges inside this node's prefix: split the prefix. The new
        # edge at prefix[n] consumes that byte, so the lower node keeps only
        # the bytes after it.
        lower = Internal(prefix[n + 1:], node.terminal_id, node.edges)
        lower_addr = store.put(lower)
        rest = key[n:]
        if not rest:
            return store.put(Internal(prefix[:n], id_, ((prefix[n], lower_addr),)))
        new_leaf = store.put(Leaf(rest[1:], id_))
        edges = tuple(sorted(((prefix[n], lower_addr), (rest[0], new_leaf))))
        return store.put(Internal(prefix[:n], None, edges))
    rest = key[n:]
    if not rest:
        return store.put(Internal(prefix, id_, node.edges))
    byte = rest[0]
    edge_map = dict(node.edges)
    if byte in edge_map:
        # A child edge consumes the first byte of the remainder; the child's
        # own prefix/suffix carries the bytes AFTER that byte.
        child = _insert_under(store, edge_map[byte], rest[1:], id_)
    else:
        child = store.put(Leaf(rest[1:], id_))
    edge_map[byte] = child
    return store.put(Internal(prefix, node.terminal_id, tuple(sorted(edge_map.items()))))


def _insert_under(store: Store, addr: bytes, key: bytes, id_: int) -> bytes:
    """Insert where the edge byte has been consumed: key is the remainder."""
    return _insert(store, addr, key, id_)


def _split_and_join(store: Store, a_suffix: bytes, a_id: int, b_key: bytes, b_id: int) -> bytes:
    """Two distinct keys under one point: the canonical two-entry subtree."""
    items = sorted([(a_suffix, a_id), (b_key, b_id)])
    return _build(store, items)


# -------------------------------------------------------------------- lookup


def get(store: Store, root: bytes, key: bytes) -> int | None:
    node = store.get(root)
    while True:
        if isinstance(node, Leaf):
            return node.id if node.suffix == key else None
        if not key.startswith(node.prefix):
            return None
        key = key[len(node.prefix):]
        if not key:
            return node.terminal_id
        edge_map = dict(node.edges)
        child = edge_map.get(key[0])
        if child is None:
            return None
        key = key[1:]
        node = store.get(child)


def iter_entries(store: Store, root: bytes, prefix: bytes = b"") -> Iterator[tuple[bytes, int]]:
    """All (key, id) pairs below root, in sorted key order."""
    node = store.get(root)
    if isinstance(node, Leaf):
        yield prefix + node.suffix, node.id
        return
    base = prefix + node.prefix
    if node.terminal_id is not None:
        yield base, node.terminal_id
    for byte, child in node.edges:
        yield from iter_entries(store, child, base + bytes([byte]))


# ---------------------------------------------------------------------- diff


def diff(store_a: Store, root_a: bytes, store_b: Store, root_b: bytes) -> tuple[dict[bytes, tuple[int | None, int | None]], int]:
    """Entries differing between two dictionaries + nodes visited.

    Prunes every address-equal subtree (Merkle property); the visit count is
    the measurement that the prune actually bites.
    """
    changes: dict[bytes, tuple[int | None, int | None]] = {}
    visited = 0

    def entries_of(store: Store, addr: bytes, prefix: bytes) -> dict[bytes, int]:
        return dict(iter_entries(store, addr, prefix))

    def walk(aa: bytes | None, bb: bytes | None, prefix: bytes) -> None:
        nonlocal visited
        if aa == bb:
            return  # identical subtree — pruned without reading it
        visited += 1
        if aa is None:
            for k, v in entries_of(store_b, bb, prefix).items():
                changes[k] = (None, v)
            return
        if bb is None:
            for k, v in entries_of(store_a, aa, prefix).items():
                changes[k] = (v, None)
            return
        na, nb = store_a.get(aa), store_b.get(bb)
        if isinstance(na, Internal) and isinstance(nb, Internal) and na.prefix == nb.prefix:
            base = prefix + na.prefix
            if na.terminal_id != nb.terminal_id:
                changes[base] = (na.terminal_id, nb.terminal_id)
            ea, eb = dict(na.edges), dict(nb.edges)
            for byte in sorted(set(ea) | set(eb)):
                walk(ea.get(byte), eb.get(byte), base + bytes([byte]))
            return
        # Shapes differ locally — fall back to entry comparison of this subtree.
        left, right = entries_of(store_a, aa, prefix), entries_of(store_b, bb, prefix)
        for k in set(left) | set(right):
            if left.get(k) != right.get(k):
                changes[k] = (left.get(k), right.get(k))

    walk(root_a, root_b, b"")
    return changes, visited


# ------------------------------------------------------------- page packing


def canonical_order(store: Store, root: bytes) -> list[bytes]:
    """Addresses in canonical depth-first order — the packing input."""
    out: list[bytes] = []

    def dfs(addr: bytes) -> None:
        out.append(addr)
        node = store.get(addr)
        if isinstance(node, Internal):
            for _, child in node.edges:
                dfs(child)

    dfs(root)
    return out


def pack_pages(store: Store, root: bytes, mask_bits: int = 5,
               min_nodes: int = 8, max_nodes: int = 128) -> list[list[bytes]]:
    """Group the canonical node sequence into pages content-definedly:
    a page closes after a node whose address masks to zero (target 2^mask_bits
    nodes/page), MIN/MAX clamped. Deterministic in the node sequence, so two
    stores holding the same dictionary pack identical pages — and an edit
    reshuffles only pages near the changed nodes."""
    pages: list[list[bytes]] = []
    cur: list[bytes] = []
    mask = (1 << mask_bits) - 1
    for addr in canonical_order(store, root):
        cur.append(addr)
        natural = len(cur) >= min_nodes and (int.from_bytes(addr[:8], "big") & mask) == 0
        if natural or len(cur) >= max_nodes:
            pages.append(cur)
            cur = []
    if cur:
        pages.append(cur)
    return pages


def node_bytes(store: Store, addr: bytes) -> int:
    return len(store.get(addr).serialize())


def stored_bytes(store: Store, root: bytes) -> int:
    """Total serialized bytes of the tree's unique nodes (structural storage)."""
    return sum(node_bytes(store, a) for a in set(canonical_order(store, root)))
