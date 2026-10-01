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

"""The banking packs' snippets for Calc, Python in the browser (docs/guides/PYTHON_CALC.md): the sources are
snippets/<pack>/<file>.py beside this file, each a copyright header, then its comment lines

    # title: Key-rate DV01 by bump and reprice
    # description: One sentence: what it computes and how (and what it simplifies).
    # kinds: ir-curve, trade            the kinds it is offered on
    # example: CRV CRV-USD-OIS          the sample entity it is checked on (docs and the browser check; not shipped)

then the code. make_packs.py writes each to packs/<pack>/python/<file>.py (header, the generated note, title,
description and kinds, then the code) and switches Calc on in the packs that have some (`python: { enabled: true }`).
Each runs against the sample data as it is; console tests run none of them (they need a browser), so a change here is
checked in a browser (PYTHON_CALC.md, Snippet catalogue)."""
from __future__ import annotations

from pathlib import Path

SOURCES = Path(__file__).resolve().parent / "snippets"
KEYS = ("title", "description", "kinds", "example")
MAX_LINES = 60          # a snippet is a starter, short enough to read whole


def read(path: Path) -> dict:
    """A snippet source: its meta lines (after the copyright header) and its code."""
    lines = path.read_text(encoding="utf-8").splitlines()
    meta, body = {}, 0
    for i, line in enumerate(lines):
        s = line.strip()
        if s and not s.startswith("#"):
            break
        key, _, value = s.lstrip("#").strip().partition(":")
        if key.strip().lower() in KEYS and value:
            meta[key.strip().lower()] = value.strip()
            body = i + 1
    while body < len(lines) and not lines[body].strip():
        body += 1
    code = "\n".join(lines[body:]) + "\n"
    missing = [k for k in KEYS if k not in meta]
    if missing:
        raise SystemExit(f"{path}: missing {', '.join(missing)}")
    if len(lines) - body > MAX_LINES:
        raise SystemExit(f"{path}: {len(lines) - body} lines of code (at most {MAX_LINES})")
    return {"file": path.name, "title": meta["title"], "description": meta["description"],
            "kinds": [k.strip() for k in meta["kinds"].split(",") if k.strip()], "example": meta["example"], "code": code}


def catalogue() -> dict[str, list[dict]]:
    """pack -> its snippets in file name order."""
    return {d.name: [read(f) for f in sorted(d.glob("*.py"))] for d in sorted(SOURCES.iterdir()) if d.is_dir()}


# pack -> [(file name, title, description, kinds, code)]
SNIPPETS: dict[str, list[tuple[str, str, str, list[str], str]]] = {
    pack: [(s["file"], s["title"], s["description"], s["kinds"], s["code"]) for s in items] for pack, items in catalogue().items()}
