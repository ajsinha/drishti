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

"""docs/admin/UNDER_CONSTRUCTION.md from config/under-construction.yaml (the under-construction registry), and its checks.

    python3 tools/under_construction.py            write the document
    python3 tools/under_construction.py --check    fail on a stale document, or a location the registry names that does not exist

A location exists when: a Sutra of the kind binds each listed bind (strip or column) and has each listed panel id (the packs' Sutras); a
menu item id is in drishti-console/config/sitemap.yaml; a help slug is a repository document's slug (docs/<path>.md -> docs-<path>); a
contract's file (and its #anchor, as GitHub makes it) exists.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "config" / "under-construction.yaml"
DOC = ROOT / "docs" / "admin" / "UNDER_CONSTRUCTION.md"

HEADER = """<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Under construction: what depends on work not built yet

Generated from [`config/under-construction.yaml`](../../config/under-construction.yaml) by `tools/under_construction.py` (do not edit by
hand; `--check` fails when this page is stale or the registry names a place that does not exist).

Some figures, panels, menu items and pages depend on engines or decisions that are not built yet: a figure that is stored rather than
computed, or a contract still to come. The registry lists each one with the release that builds it and how a person meets it until then:

- **under-construction**: shown, with the words *Under construction* (a chip) in place of its value;
- **hidden**: left out.

The data stays where it is; only what is displayed changes. **One mechanism:** the server rewrites a kind's Sutra when it is matched to a
document (strip items and kv or table columns by their bind, panels by their id), so the console, the phone display, pivots and the client
renderer read the same answer (the figure's tone is `uc`, drawn as the chip); the console reads the same file for its menu items and help
pages. A new item that depends on unbuilt work is registered in the same change
([PACK_DEVELOPER_GUIDE.md, Unbuilt work](../guides/PACK_DEVELOPER_GUIDE.md#unbuilt-work-the-under-construction-registry)).

Settings: `drishti.under-construction.file` (server, `DRISHTI_UNDER_CONSTRUCTION_FILE`, default `./config/under-construction.yaml`, else the
file beside the packs folder) and `under_construction.file` (console, default `../config/under-construction.yaml`).

"""


def load() -> list[dict]:
    data = yaml.safe_load(REGISTRY.read_text(encoding="utf-8")) or {}
    return [i for i in data.get("items") or [] if isinstance(i, dict)]


def render(items: list[dict]) -> str:
    out = [HEADER, "| Item | What | Treatment | Built in | Where it shows |", "|---|---|---|---|---|"]
    for i in items:
        where = []
        for s in i.get("sutras") or []:
            parts = [f"`{b}`" for b in s.get("binds") or []] + [f"panel `{p}`" for p in s.get("panels") or []]
            where.append(f"the `{s['kind']}` page: " + ", ".join(parts))
        where += [f"menu item `{m}`" for m in i.get("menus") or []]
        where += [f"help page `/help/{h}`" for h in i.get("help") or []]
        where += [f"[{c}](../../{c})" for c in i.get("contracts") or []]
        where += [f"screenshot to retake: `{p}`" for p in i.get("screenshots") or []]
        cell = "; ".join(where).replace("|", "\\|")
        about = str(i.get("about") or "").replace("|", "\\|")
        out.append(f"| **{i['id']}**: {about} | {i.get('what', '')} | {i.get('treatment', '')} | {i.get('release', '')} | {cell} |")
    return "\n".join(out) + "\n"


def _slug(text: str) -> str:
    """A heading's anchor as GitHub makes it."""
    t = re.sub(r"[^\w\- ]", "", text.strip().lower())
    return t.replace(" ", "-")


def _sutras_of(kind: str) -> list[str]:
    texts = []
    for f in (ROOT / "config" / "packs").glob("*/sutras/**/*.sutra.yaml"):
        t = f.read_text(encoding="utf-8")
        if re.search(r"match:\s*\{\s*kind:\s*" + re.escape(kind) + r"\s*[,}]", t):
            texts.append(t)
    return texts


def problems(items: list[dict]) -> list[str]:
    out: list[str] = []
    menus = (ROOT / "drishti-console" / "config" / "sitemap.yaml").read_text(encoding="utf-8")
    ids = set(re.findall(r"\bid:\s*([\w.-]+)", menus))
    seen = set()
    for i in items:
        iid = str(i.get("id") or "")
        if not iid or iid in seen:
            out.append(f"an item without an id, or a repeated id '{iid}'")
        seen.add(iid)
        if i.get("treatment") not in ("hidden", "under-construction"):
            out.append(f"{iid}: treatment must be hidden or under-construction")
        for k in ("what", "about", "release"):
            if not i.get(k):
                out.append(f"{iid}: no {k}")
        for s in i.get("sutras") or []:
            texts = _sutras_of(str(s.get("kind")))
            if not texts:
                out.append(f"{iid}: no Sutra of the kind {s.get('kind')}")
            for b in s.get("binds") or []:                    # a page of the kind binds it (a kind may have several pages)
                if texts and not any(f"bind: {b}" in t or f'bind: "{b}"' in t for t in texts):
                    out.append(f"{iid}: no {s.get('kind')} Sutra binds {b}")
            for p in s.get("panels") or []:
                if texts and not any(re.search(r"\bid:\s*" + re.escape(p) + r"\b", t) for t in texts):
                    out.append(f"{iid}: no {s.get('kind')} Sutra has a panel {p}")
        for m in i.get("menus") or []:
            if m not in ids:
                out.append(f"{iid}: no menu item {m} in sitemap.yaml")
        for h in i.get("help") or []:
            if not h.startswith("docs-") or not any(_doc_slug(p) == h for p in (ROOT / "docs").rglob("*.md")):
                out.append(f"{iid}: no help page {h}")
        for p in i.get("screenshots") or []:
            if not (ROOT / p).is_file():
                out.append(f"{iid}: no screenshot {p}")
        for c in i.get("contracts") or []:
            path, _, anchor = str(c).partition("#")
            f = ROOT / path
            if not f.is_file():
                out.append(f"{iid}: no file {path}")
            elif anchor and anchor not in {_slug(h) for h in re.findall(r"^#+\s+(.*)$", f.read_text(encoding="utf-8"), re.M)}:
                out.append(f"{iid}: no section #{anchor} in {path}")
    return out


def _doc_slug(p: Path) -> str:
    rel = p.relative_to(ROOT)
    return re.sub(r"[^a-z0-9]+", "-", str(rel.with_suffix("")).lower()).strip("-")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Write or check docs/admin/UNDER_CONSTRUCTION.md from config/under-construction.yaml.")
    ap.add_argument("--check", action="store_true", help="fail on a stale document or a location that does not exist")
    a = ap.parse_args(argv)
    items = load()
    text = render(items)
    bad = problems(items)
    if a.check:
        if DOC.is_file() and DOC.read_text(encoding="utf-8") == text and not bad:
            print(f"{DOC.relative_to(ROOT)} is current ({len(items)} items)")
            return 0
        for b in bad:
            print("under-construction: " + b, file=sys.stderr)
        if not DOC.is_file() or DOC.read_text(encoding="utf-8") != text:
            print(f"{DOC.relative_to(ROOT)} is stale: run python3 tools/under_construction.py", file=sys.stderr)
        return 1
    DOC.write_text(text, encoding="utf-8")
    print(f"wrote {DOC.relative_to(ROOT)} ({len(items)} items)")
    for b in bad:
        print("under-construction: " + b, file=sys.stderr)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
