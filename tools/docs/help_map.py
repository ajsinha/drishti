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

"""Writes docs/README.md's map of the help centre from drishti-console/config/help.yaml (and every module's Help): the intro's counts
("**N pages in M sections**") and the table "The help centre, section by section", one row per section in Help's order naming every page
everyone may see (a linked document, or the title of a console guide). drishti-console/tests/test_docs_help_map.py checks the result.

    drishti-console/.venv/bin/python tools/docs/help_map.py           # rewrite the map
    drishti-console/.venv/bin/python tools/docs/help_map.py --check   # fail when the map differs"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
README = ROOT / "docs" / "README.md"
HEADING = "## The help centre, section by section"


def categories() -> list[dict]:
    sys.path.insert(0, str(CONSOLE))
    from core import menus, modules
    data = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text(encoding="utf-8"))
    pages = {s["id"]: s for s in [*data["sections"], *modules.help_pages()]}
    out = []
    for h in menus.help_sections(CONSOLE):
        if h["id"] not in pages or h.get("roles"):
            continue
        sec = {**pages[h["id"]], **h}
        guides = [g for grp in sec.get("groups") or [] for g in grp.get("guides") or [] if not g.get("roles")]
        if guides:
            out.append({"name": sec["name"], "guides": guides})
    return out


def render(text: str) -> str:
    cats = categories()
    total = sum(len(c["guides"]) for c in cats)
    text = re.sub(r"\*\*\d+ pages in \d+ sections\*\*", f"**{total} pages in {len(cats)} sections**", text, count=1)
    head, rest = text.split(HEADING, 1)
    body, tail = (rest.split("\n## ", 1) + [""])[:2]
    rows = []
    for c in cats:
        items = [f"[{g['title']}]({g['doc']})" if g.get("doc") else f"{g['title']} (console guide)" for g in c["guides"]]
        rows.append(f"| **{c['name']}** | " + " · ".join(items) + " |")
    lines = body.split("\n")
    idx = [i for i, l in enumerate(lines) if re.match(r"^\|\s*\*\*[^*]+\*\*\s*\|", l)]
    if idx:
        lines[idx[0]:idx[-1] + 1] = rows
    body = "\n".join(lines)
    return head + HEADING + body + ("\n## " + tail if tail else "")


def main() -> int:
    old = README.read_text(encoding="utf-8")
    new = render(old)
    if "--check" in sys.argv:
        if new != old:
            print("docs/README.md's help-centre map is stale: drishti-console/.venv/bin/python tools/docs/help_map.py")
            return 1
        print("docs/README.md's help-centre map is current")
        return 0
    README.write_text(new, encoding="utf-8")
    print("docs/README.md's help-centre map written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
