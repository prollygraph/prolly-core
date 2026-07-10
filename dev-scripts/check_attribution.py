#!/usr/bin/env python3
"""Attribution-coverage audit: every source file's copyright header must MATCH its
provenance-ledger disposition (build/dolt-provenance-ledger.md) — not merely exist.

The license gate (license-maven-plugin) enforces header PRESENCE per file; this audit
enforces header CORRECTNESS against the recorded per-file provenance judgment, in both
failure directions:

  * a ledger-Dolt/adapted/kch42 file carrying the wrong header (missing credit), and
  * a file carrying Dolt/kch42 credit that the ledger does not grant (over-credit).

Ledger names that do not resolve in this repo are reported informationally (the ledger
predates the repo split and covers upstream files too). serial/** is skipped (flatc-
generated bindings, excluded from the license gate by the same reasoning).

Exit 0 = every file matches; exit 1 = mismatches (listed). Run from the repo root:

    python3 dev-scripts/check_attribution.py [--write-report]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

MODULES = ["dolthub-java-port", "prolly-storage", "prolly-sync"]
LEDGER = pathlib.Path("build/dolt-provenance-ledger.md")
REPORT = pathlib.Path("build/attribution-audit.txt")

DOLT_LINE = "Copyright 2021 Dolthub"
ADAPTED_LINE = "Derived from Dolt's design"
KCH42_LINE = "kch42/buzhash"
EARASOFT_LINE = "Copyright 2026 Earasoft"


def parse_ledger(text: str) -> tuple[set[str], set[str], set[str]]:
    """Return (plain_dolt, adapted, kch42) class-name sets from the ledger's sections."""
    dolt_section = text.split("## DOLT")[1].split("## KCH42")[0]
    kch42_section = text.split("## KCH42")[1].split("## EARASOFT")[0]

    dolt: set[str] = set()
    adapted: set[str] = set()
    for row in dolt_section.splitlines():
        if not row.startswith("|") or row.startswith("|---") or "| File |" in row:
            continue
        cell = row.split("|")[1]
        for name in cell.split(","):
            name = name.replace("**", "").replace("`", "").strip()
            if not re.fullmatch(r"[A-Za-z0-9]+†?", name):
                continue
            if name.endswith("†"):
                adapted.add(name[:-1])
            else:
                dolt.add(name)
    kch42 = set(re.findall(r"`([A-Za-z0-9]+)`", kch42_section.split("\n\n")[1]))
    return dolt, adapted, kch42


def classify_header(path: pathlib.Path) -> str:
    head = path.read_text(errors="replace")[:2000]
    if ADAPTED_LINE in head:
        return "dolt-adapted"
    if KCH42_LINE in head:
        return "kch42"
    if DOLT_LINE in head:
        return "dolt"
    if EARASOFT_LINE in head:
        return "earasoft"
    return "MISSING"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write-report", action="store_true", help=f"also write {REPORT}")
    args = ap.parse_args()

    dolt, adapted, kch42 = parse_ledger(LEDGER.read_text())

    lines: list[str] = []
    mismatches: list[str] = []
    seen_names: set[str] = set()
    counts = {"dolt": 0, "dolt-adapted": 0, "kch42": 0, "earasoft": 0}

    for mod in MODULES:
        for path in sorted(pathlib.Path(mod, "src").rglob("*.java")):
            if "serial" in path.parts:  # flatc-generated bindings — license-excluded
                continue
            name = path.stem
            seen_names.add(name)
            actual = classify_header(path)
            if name in adapted:
                expected = "dolt-adapted"
            elif name in dolt:
                expected = "dolt"
            elif name in kch42:
                expected = "kch42"
            else:
                expected = "earasoft"
            if actual == expected:
                counts[actual] += 1
            else:
                mismatches.append(f"  {path}: header={actual}, ledger says {expected}")

    upstream = sorted(n for n in (dolt | adapted | kch42) if n not in seen_names)

    lines.append("Attribution-coverage audit — header vs build/dolt-provenance-ledger.md")
    lines.append(f"modules: {', '.join(MODULES)} (serial/** skipped: flatc-generated)")
    lines.append(
        "matched: "
        + ", ".join(f"{k}={v}" for k, v in counts.items())
        + f" (total {sum(counts.values())})"
    )
    if upstream:
        lines.append(
            f"ledger names not in this repo (stayed upstream at the split): {', '.join(upstream)}"
        )
    if mismatches:
        lines.append("MISMATCHES:")
        lines.extend(mismatches)
    else:
        lines.append("MISMATCHES: none — every file matches its ledger disposition")

    out = "\n".join(lines)
    print(out)
    if args.write_report:
        REPORT.write_text(out + "\n")
        print(f"\nreport written: {REPORT}")
    return 1 if mismatches else 0


if __name__ == "__main__":
    sys.exit(main())
