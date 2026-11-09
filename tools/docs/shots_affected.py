#!/usr/bin/env python3
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
"""Which screenshots a diff can have changed: a page -> script map read from tools/docs/shots/, used to retake only those.

    python tools/docs/shots_affected.py [--base REF]          the guides to retake (one line per screenshots.py run)
    python tools/docs/shots_affected.py [--base REF] --run    retake them (the console must be running; tools/docs/screenshots.py --base names it)

The pages a shot script opens are the paths it names (``/var/...``, ``/v/...``, ``/build``). A changed template, script or style selects the scripts
whose paths or file name share its words. A changed shared piece (the layout, the core styles, Drishti Elements), or a changed page no script opens,
selects every script: when unsure, retake.

Standard library only.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHOTS = ROOT / "tools" / "docs" / "shots"
PAGES = ("drishti-console/drishti_console/templates/", "drishti-console/drishti_console/static/")
# pieces every page draws on
SHARED = re.compile(r"(^|/)(base|layout|_layout|shell|topbar|nav)[^/]*$|drishti_(core|elements|grid|chart|inputs|data|insight|renderer|theme|tokens)|/vendor/")
WEAK = {"drishti", "js", "css", "html", "min", "page", "pages", "index", "main", "the", "new", "view"}
PATH = re.compile(r"""["'](/[a-z0-9_\-/.]*)""")


def words(text: str) -> set[str]:
    return {w for w in re.split(r"[^a-z0-9]+", text.lower()) if len(w) > 1 and w not in WEAK}


def page_map(shots: Path = SHOTS) -> dict[str, set[str]]:
    """{guide: the words of the pages it opens, and of its own name}; helper modules (``_x``) are read into the guides that import them."""
    helpers = {p.stem: p.read_text(encoding="utf-8") for p in shots.glob("_*.py")}
    out = {}
    for p in sorted(shots.glob("*.py")):
        if p.stem.startswith("_"):
            continue
        text = p.read_text(encoding="utf-8")
        for name, body in helpers.items():
            if re.search(rf"\b{name}\b", text):
                text += "\n" + body
        out[p.stem] = words(p.stem) | {w for m in PATH.findall(text) for w in words(m)}
    return out


def select(changed: list[str], pages: dict[str, set[str]]) -> tuple[list[str], list[str]]:
    """(the guides to retake, why each changed page selected them); a page shared or unplaced selects every guide."""
    picked: set[str] = set()
    why = []
    for f in changed:
        if not f.startswith(PAGES):
            continue
        if SHARED.search(f):
            why.append(f"{f}: shared by every page: all guides")
            return sorted(pages), why
        w = words(Path(f).stem)
        hit = {g for g, ws in pages.items() if w & ws}
        if not hit:
            why.append(f"{f}: no script opens it: all guides")
            return sorted(pages), why
        picked |= hit
        why.append(f"{f}: {', '.join(sorted(hit))}")
    return sorted(picked), why


def changed_files(base: str) -> list[str]:
    def git(*a: str) -> list[str]:
        return [x for x in subprocess.run(["git", *a], cwd=ROOT, capture_output=True, text=True, check=True).stdout.splitlines() if x]
    return sorted(set(git("diff", "--name-only", git("merge-base", base, "HEAD")[0])) | set(git("ls-files", "--others", "--exclude-standard")))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--base", default="origin/develop")
    ap.add_argument("--run", action="store_true", help="retake them now")
    ap.add_argument("--server", default="", help="--run: the console's URL (screenshots.py --base)")
    a = ap.parse_args(argv)
    guides, why = select(changed_files(a.base), page_map())
    for w in why:
        print("# " + w)
    py = [sys.executable, "tools/docs/screenshots.py"] + (["--base", a.server] if a.server else [])
    rc = 0
    for g in guides:
        print(" ".join([*py, "--guide", g]))
        if a.run:
            rc |= subprocess.run([*py, "--guide", g], cwd=ROOT).returncode
    return 1 if rc else 0


if __name__ == "__main__":
    sys.exit(main())
