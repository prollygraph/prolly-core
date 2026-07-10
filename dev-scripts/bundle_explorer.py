#!/usr/bin/env python3
"""Bundle the write-path explorer into one self-contained HTML file.

The maintained SOURCE is the set in prolly-web-playground/:
write-path-explorer.html (markup + styles + vendored d3) loading five sibling
scripts via <script src> tags (core/state/render/controls/app, in order).
Publishing targets need one file; this script inlines every such tag, in
document order. Deterministic: byte-stable output for byte-stable inputs;
fails loud if no tags are found or a referenced file is missing.
"""

from __future__ import annotations

import argparse
import pathlib
import re

SRC_TAG_RE = re.compile(r'<script src="(write-path-explorer[^"]+\.js)"></script>')


def bundle(html: str, read_js) -> str:
    """Inline every explorer script tag (pure; raises when none are present)."""
    if not SRC_TAG_RE.search(html):
        raise ValueError("no write-path-explorer script tags found in the html shell")
    return SRC_TAG_RE.sub(lambda m: "<script>\n" + read_js(m.group(1)) + "</script>", html)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    default_docs = (
        pathlib.Path(__file__).resolve().parent.parent / "prolly-web-playground"
    )
    parser.add_argument("--docs-dir", type=pathlib.Path, default=default_docs)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args(argv)

    html = (args.docs_dir / "write-path-explorer.html").read_text(encoding="utf-8")
    out = bundle(html, lambda name: (args.docs_dir / name).read_text(encoding="utf-8"))
    args.out.write_text(out, encoding="utf-8")
    print(f"bundled -> {args.out} ({args.out.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
