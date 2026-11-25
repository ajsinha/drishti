# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.


"""Repairs the links of Markdown documents after a merge or a move, from the rows of tools/docs/moved.yaml.

python3 tools/docs/relink_md.py [--write] [--skip GLOB ...] [PATH ...]

A link `](path#anchor)` is rewritten when its target (resolved from the document's own folder) is a document that no longer exists and
has a `moved.yaml` row (the new home; the anchor is kept only when an anchor row, `from: path#anchor`, names its new place, else dropped
and reported), or when an anchor row names the target even though the document still exists (a section that moved out of it). With no
PATH every tracked `*.md` is read, except the history (CHANGELOG, release notes), generated documents (their sources are the place to
edit) and `--skip` globs. Without --write the changes are only counted. Exit 1 when a link into a removed document has no home.
"""
from __future__ import annotations

import fnmatch
import os
import re
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
import moved  # noqa: E402

FENCE = re.compile(r"^(\s*)(```+|~~~+)")
LINK = re.compile(r"(\]\()([^)\s#]*)(#[^)\s]*)?(\))")
HISTORY = ("CHANGELOG.md", "RELEASE_NOTES.md", "THIRD-PARTY-NOTICES.md", "docs/products/", "docs/architecture/pack-resolution-inventory.md")


def tracked_md() -> list[str]:
    out = subprocess.run(["git", "ls-files", "*.md"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
    return [p for p in out if (ROOT / p).is_file()]


def generated() -> set[str]:
    rows = (yaml.safe_load((ROOT / "config" / "docs-corpus.yaml").read_text(encoding="utf-8")) or {}).get("files") or []
    return {r["path"] for r in rows if r["class"] in ("generate", "connector")}


def tables() -> tuple[dict[str, str], dict[str, str]]:
    files, anchors = {}, {}
    for r in moved.load():
        src, dst = r["from"], r.get("to") or ""
        if src.startswith("help:") or not dst:
            continue
        (anchors if "#" in src else files)[src] = dst
    return files, anchors


def relink(rel: str, text: str, files: dict[str, str], anchors: dict[str, str], gone: set[str], lost: list[str]) -> str:
    fence = None
    out = []
    for line in text.split("\n"):
        m = FENCE.match(line)
        if fence is None and m:
            fence = m.group(2)
            out.append(line)
            continue
        if fence is not None:
            if m and m.group(2)[0] == fence[0] and line.strip().strip(fence[0]) == "":
                fence = None
            out.append(line)
            continue
        if "](" not in line:
            out.append(line)
            continue

        def sub(m):
            tgt, frag = m.group(2), (m.group(3) or "")
            if re.match(r"[a-z][a-z0-9+.-]*:", tgt) or tgt.startswith("/"):
                return m.group(0)
            p = rel if tgt == "" else os.path.normpath(os.path.join(os.path.dirname(rel), tgt))
            new, fn = p, frag[1:]
            if fn and f"{p}#{fn}" in anchors:
                dest = anchors[f"{p}#{fn}"]
                new, fn = dest.split("#", 1) if "#" in dest else (dest, "")
            elif p in gone and p in files:
                new = files[p]
                if fn:
                    lost.append(f"{rel}: {p}#{fn} (the section has no row; the link goes to {new})")
                    fn = ""
            elif p in gone:
                lost.append(f"{rel}: {p} (removed, no home)")
                return m.group(0)
            if new == p and fn == frag[1:]:
                return m.group(0)
            if new == rel and fn:
                return f"{m.group(1)}#{fn}{m.group(4)}"
            path = os.path.relpath(new, os.path.dirname(rel)) if new != rel else os.path.basename(new)
            return f"{m.group(1)}{path}{'#' + fn if fn else ''}{m.group(4)}"

        out.append(LINK.sub(sub, line))
    return "\n".join(out)


def main(argv: list[str]) -> int:
    write = "--write" in argv
    skips = [argv[i + 1] for i, a in enumerate(argv) if a == "--skip"]
    paths = [a for i, a in enumerate(argv) if not a.startswith("--") and (i == 0 or argv[i - 1] != "--skip")]
    files, anchors = tables()
    gone = {p for p in files if not (ROOT / p).exists()}
    gen = generated()
    lost: list[str] = []
    changed = 0
    for rel in paths or tracked_md():
        if not paths and (rel.startswith(HISTORY) or rel in gen or any(fnmatch.fnmatch(rel, g) for g in skips)):
            continue
        p = ROOT / rel
        old = p.read_text(encoding="utf-8")
        new = relink(rel, old, files, anchors, gone, lost)
        if new != old:
            changed += 1
            if write:
                p.write_text(new, encoding="utf-8")
    print(f"{'rewrote' if write else 'would rewrite'} {changed} document(s)")
    for x in sorted(set(lost)):
        print("  " + x)
    return 1 if any("no home" in x for x in lost) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
