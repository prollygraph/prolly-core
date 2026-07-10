# Copyright 2026 Earasoft
"""Pins for merkle_radix_dict.py — the Deterministic Merkle Radix dictionary.

The load-bearing property is HISTORY INDEPENDENCE: the Merkle root is a pure
function of the {key: id} mapping. It is pinned the strong way — folding
path-copying inserts in hypothesis-random orders must produce a root
byte-identical to the canonical batch build. Any structural mistake in either
code path breaks the equality.
"""
from __future__ import annotations

import random

from hypothesis import given, settings
from hypothesis import strategies as st

import merkle_radix_dict as mrd

URIS = st.dictionaries(
    st.binary(min_size=1, max_size=40),
    st.integers(min_value=0, max_value=2**63 - 1),
    min_size=1,
    max_size=60,
)


def build_root(entries: dict[bytes, int]) -> tuple[mrd.Store, bytes]:
    store = mrd.Store()
    return store, mrd.build(store, entries)


@given(entries=URIS)
@settings(max_examples=150)
def test_insert_any_order_equals_batch_build(entries: dict[bytes, int]) -> None:
    _, batch_root = build_root(entries)
    items = list(entries.items())
    random.Random(sum(entries.values()) & 0xFFFF).shuffle(items)
    store = mrd.Store()
    root = mrd.build(store, {items[0][0]: items[0][1]})
    for key, id_ in items[1:]:
        root = mrd.insert(store, root, key, id_)
    assert root == batch_root


@given(entries=URIS)
@settings(max_examples=100)
def test_lookup_finds_every_entry_and_only_them(entries: dict[bytes, int]) -> None:
    store, root = build_root(entries)
    for key, id_ in entries.items():
        assert mrd.get(store, root, key) == id_
    probe = b"\xffnot-a-key\xff"
    if probe not in entries:
        assert mrd.get(store, root, probe) is None
    assert dict(mrd.iter_entries(store, root)) == entries


def test_prefix_of_another_key() -> None:
    entries = {b"http://a": 1, b"http://a/b": 2, b"http://a/b/c": 3}
    store, root = build_root(entries)
    for k, v in entries.items():
        assert mrd.get(store, root, k) == v
    # And the insert path handles the same shape:
    store2 = mrd.Store()
    r = mrd.build(store2, {b"http://a/b": 2})
    r = mrd.insert(store2, r, b"http://a", 1)
    r = mrd.insert(store2, r, b"http://a/b/c", 3)
    assert r == root


def test_update_changes_root_and_only_that_entry() -> None:
    entries = {f"http://x/{i}".encode(): i for i in range(200)}
    store, root = build_root(entries)
    root2 = mrd.insert(store, root, b"http://x/17", 9999)
    assert root2 != root
    changes, visited = mrd.diff(store, root, store, root2)
    assert changes == {b"http://x/17": (17, 9999)}
    # The prune must bite: far fewer nodes visited than exist.
    total = len(set(mrd.canonical_order(store, root)))
    assert visited < total // 2


@given(entries=URIS, extra=st.dictionaries(st.binary(min_size=1, max_size=20),
                                           st.integers(min_value=0, max_value=2**32),
                                           min_size=1, max_size=10))
@settings(max_examples=60)
def test_diff_reports_exactly_the_delta(entries: dict[bytes, int], extra: dict[bytes, int]) -> None:
    extra = {k: v for k, v in extra.items() if k not in entries}
    if not extra:
        return
    store_a, root_a = build_root(entries)
    store_b, root_b = build_root({**entries, **extra})
    changes, _ = mrd.diff(store_a, root_a, store_b, root_b)
    assert changes == {k: (None, v) for k, v in extra.items()}


def test_fanout_bounded_by_byte_alphabet() -> None:
    entries = {bytes([b]) + b"tail": b for b in range(256)}
    store, root = build_root(entries)
    node = store.get(root)
    assert isinstance(node, mrd.Internal)
    assert len(node.edges) == 256                 # the structural maximum
    assert mrd.node_bytes(store, root) < 6000     # fan-out caps node size


def test_pack_pages_deterministic_bounded_and_complete() -> None:
    entries = {f"http://purl.obolibrary.org/obo/NCIT_C{i}".encode(): i for i in range(3000)}
    store, root = build_root(entries)
    pages = mrd.pack_pages(store, root)
    again = mrd.pack_pages(store, root)
    assert pages == again                                       # deterministic
    flat = [a for p in pages for a in p]
    assert flat == mrd.canonical_order(store, root)             # complete, in order
    assert all(len(p) <= 128 for p in pages)                    # bounded
    assert all(len(p) >= 8 for p in pages[:-1])                 # clamped (last exempt)
