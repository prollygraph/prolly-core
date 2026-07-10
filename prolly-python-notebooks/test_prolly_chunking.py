# Copyright 2026 Earasoft
"""Property + fixture tests for prolly_chunking.py.

Four groups:
- textbook pins (FNV vectors) and the low-bits shadow property;
- the per-element scheme's invariants (history-independence of the ROOT,
  bounded edit blast radius including internal nodes);
- the production port's invariants (window locality — the proof that the
  engine's hash-from-MIN-minus-W skip is sound; geometry bounds; per-level
  salt divergence; determinism);
- byte-identical parity against the Java-emitted fixture (the real engine).
"""
from __future__ import annotations

import json
import pathlib
import random

import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

import prolly_chunking as pc

# ---------------------------------------------------------------- textbook


def test_fnv_textbook_vectors() -> None:
    assert pc.fnv1a(b"a") == 0xE40C292C
    assert pc.fnv1a(b"foobar") == 0xBF9CF968


@given(key=st.integers(min_value=0, max_value=2**32 - 1), bits=st.integers(1, 16))
def test_low_bits_shadow_equals_full_hash(key: int, bits: int) -> None:
    assert pc.low_bits_fnv(key, bits) == pc.key_hash(key) & ((1 << bits) - 1)


# ------------------------------------------------- per-element scheme + tree

keysets = st.lists(
    st.integers(min_value=0, max_value=100_000), min_size=1, max_size=400, unique=True
)


@given(keys=keysets)
def test_partition_is_history_independent(keys: list[int]) -> None:
    base = pc.partition(sorted(keys), bits=3)
    shuffled = keys[:]
    random.Random(42).shuffle(shuffled)
    assert pc.partition(sorted(shuffled), bits=3) == base


@given(keys=keysets)
@settings(max_examples=50)
def test_root_address_is_history_independent(keys: list[int]) -> None:
    base = pc.root_address(pc.build_tree(sorted(keys)))
    shuffled = keys[:]
    random.Random(7).shuffle(shuffled)
    assert pc.root_address(pc.build_tree(sorted(shuffled))) == base


@given(
    keys=st.lists(
        st.integers(min_value=1, max_value=100_000), min_size=50, max_size=400, unique=True
    ),
    new_key=st.integers(min_value=0, max_value=100_001),
)
@settings(max_examples=50)
def test_insert_blast_radius_is_leaf_plus_path(keys: list[int], new_key: int) -> None:
    """One insert changes at most 2 chunks per level (a split leaf + the
    parent-path effect) — NOT the whole tree. The root always changes."""
    if new_key in keys:
        return
    before = pc.build_tree(sorted(keys))
    after = pc.build_tree(sorted(keys + [new_key]))
    height = max(len(before), len(after))
    changed = pc.changed_chunks(before, after)
    assert 1 <= changed <= 2 * height + 2
    # The root itself must change — the address commits to the content.
    assert pc.root_address(before) != pc.root_address(after)


def test_content_clamp_keeps_blast_radius_local() -> None:
    """The content-deterministic clamp the original notebook only asserted:
    under a MAX-dominated regime, deleting an early key must not shift every
    forced cut downstream (the length-counter clamp does exactly that)."""
    ks = list(range(1, 1001))
    bits, min_size, max_size = 8, 1, 16  # MAX-dominated on purpose
    counter = [tuple(x) for x in pc.clamp_partition(ks, bits, min_size, max_size)]
    counter2 = [tuple(x) for x in pc.clamp_partition([k for k in ks if k != 3], bits, min_size, max_size)]
    content = [tuple(x) for x in pc.content_clamp_partition(ks, bits, min_size, max_size)]
    content2 = [tuple(x) for x in pc.content_clamp_partition([k for k in ks if k != 3], bits, min_size, max_size)]
    counter_changed = sum(1 for x in counter2 if x not in counter) / len(counter)
    content_changed = sum(1 for x in content2 if x not in content) / len(content)
    assert counter_changed > 0.15  # the length counter cascades
    assert content_changed < 0.05  # the content-chosen cut re-locks


# ----------------------------------------------------------- production port


def test_staircase_shape() -> None:
    assert pc.rolling_hash_pattern(512) == (1 << 15) - 1
    assert pc.rolling_hash_pattern(1024) == (1 << 14) - 1
    assert pc.rolling_hash_pattern(pc.PROD_CERTAIN_CUT) == 0
    patterns = [pc.rolling_hash_pattern(off) for off in range(512, 15361)]
    assert patterns == sorted(patterns, reverse=True)  # monotone non-increasing


@given(
    prefix_a=st.binary(min_size=0, max_size=300),
    prefix_b=st.binary(min_size=0, max_size=300),
    suffix=st.binary(min_size=pc.PROD_WINDOW, max_size=pc.PROD_WINDOW + 40),
)
def test_window_locality(prefix_a: bytes, prefix_b: bytes, suffix: bytes) -> None:
    """After >= W bytes, the rolling-hash state depends ONLY on the last W
    bytes — the proof obligation behind the engine's skip-the-min-prefix
    optimization (RollingHashSplitter's HASH_FROM)."""
    def state_after(stream: bytes) -> int:
        bz = pc.BuzHash(pc.PROD_WINDOW)
        for b in stream:
            bz.hash_byte(b)
        return bz.sum32()

    assert state_after(prefix_a + suffix) == state_after(prefix_b + suffix)


def test_geometry_bounds_and_determinism() -> None:
    data = pc.sha256_counter_stream(200_000)
    sizes = pc.production_chunks(data)
    closed = sizes[:-1]  # trailing partial chunk exempt
    assert all(pc.PROD_MIN_CHUNK <= s <= pc.PROD_CERTAIN_CUT for s in closed)
    assert pc.production_chunks(data) == sizes  # deterministic


def test_levels_salt_divergence() -> None:
    data = pc.sha256_counter_stream(120_000)
    b0 = pc.chunk_end_offsets(data, level=0, entry_size=1)
    b1 = pc.chunk_end_offsets(data, level=1, entry_size=1)
    assert b0 != b1  # same bytes, different level -> different cuts


def test_production_tree_root_and_reuse() -> None:
    entries = [
        (k.to_bytes(8, "little"), pc.sha256_counter_stream(64)[:48]) for k in range(4000)
    ]
    a = pc.build_production_tree(entries)
    b = pc.build_production_tree(entries)
    assert pc.prod_root_address(a) == pc.prod_root_address(b)
    # An edit shares almost every chunk with its predecessor:
    edited = list(entries)
    edited[2000] = (edited[2000][0], b"EDITED-VALUE" * 4)
    c = pc.build_production_tree(edited)
    shared = len(pc.prod_tree_addresses(a) & pc.prod_tree_addresses(c))
    total = len(pc.prod_tree_addresses(a))
    assert shared / total > 0.8


# ------------------------------------------------------- Java-engine parity

FIXTURE = pathlib.Path(__file__).parent / "fixtures" / "boundaries.json"


@pytest.mark.skipif(not FIXTURE.exists(), reason="run `make fixture` (needs a JVM + the engine jar)")
def test_boundaries_match_the_java_engine() -> None:
    """Byte-identical chunk ends against the real RollingHashSplitter, for
    levels 0..2 at 1-byte and 32-byte entry granularity."""
    fix = json.loads(FIXTURE.read_text())
    data = pc.sha256_counter_stream(fix["corpusBytes"])
    for case in fix["cases"]:
        mine = pc.chunk_end_offsets(data, case["level"], case["entrySize"])
        assert mine == case["chunkEnds"], (case["level"], case["entrySize"])


def test_level_addresses_partition_tree_addresses() -> None:
    tree = pc.build_tree(list(range(1, 501)))
    per_level = pc.level_addresses(tree)
    assert set().union(*per_level) == pc.tree_addresses(tree)
    assert len(per_level) == len(tree)
    assert pc.chunk_address(tree[-1][0]) == pc.root_address(tree)


@given(data=st.binary(min_size=pc.PROD_WINDOW, max_size=pc.PROD_WINDOW), i=st.integers(0, pc.PROD_WINDOW - 33))
def test_buzhash_rotation_aliasing_collision(data: bytes, i: int) -> None:
    """Swapping two bytes exactly 32 apart inside the window cannot change the
    state: rotation ages alias mod 32, so both orders contribute
    rot^a(T[b1] ^ T[b2]) — the structured collision class the hashes notebook
    demonstrates (boundary hashes never promised collision resistance)."""
    def state(stream: bytes) -> int:
        bz = pc.BuzHash(pc.PROD_WINDOW)
        for b in stream:
            bz.hash_byte(b)
        return bz.sum32()

    swapped = bytearray(data)
    swapped[i], swapped[i + 32] = swapped[i + 32], swapped[i]
    assert state(bytes(swapped)) == state(data)
