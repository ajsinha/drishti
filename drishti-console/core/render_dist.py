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

"""How the client-side renderer is distributed (docs/architecture/CLIENT_RENDERER.md section 13).

The library lives in one folder per version, ``web/static/render/<version>/``: the entry ``drishti-render.js``, its modules ``m/*.js``,
the types ``drishti-render.d.ts``, the sheet ``drishti-render.css`` (generated here from the console's own sheets, rewritten for a
shadow root like the Elements' sheet) and ``integrity.json`` (SHA-384 of every file, for subresource integrity). A version's folder is
immutable: ``tests/test_render_library.py`` fails when a file changed and its ``integrity.json`` did not, so a change means a new folder.
``tools/render_sheet.py`` writes the sheet and the record.
"""
from __future__ import annotations

import base64
import gzip
import hashlib
import json
import re
from pathlib import Path

from core import element_sheet as es

WEB = Path(__file__).resolve().parent.parent / "web"
STATIC = WEB / "static"
ROOT = STATIC / "render"
VERSION = "0.1.0"                   # the preview; 1.0.0 when the console draws its pages with it (phase 2)
FOLDER = ROOT / VERSION
SHEET = "drishti-render.css"
ENTRY = "drishti-render.js"
RECORD = "integrity.json"
DEMO = ("demo.html", "demo.js", "demo.css")      # the playground: served with the library, not part of a host's integrity list
# the console's sheets the view page loads, in its order, plus the panel sheets of terminal/view.html and the grid's
SHEETS = es.SHEETS + ("css/drill.css", "css/wire.css", "css/refresh.css", "css/compute.css", "css/screen-grid.css")
ICON_SOURCES = ("static/render/{v}/m/kinds.js", "static/render/{v}/m/grid.js", "static/render/{v}/m/screen.js", "static/render/{v}/m/view.js")

ADDENDUM = """\
*, ::before, ::after { box-sizing: border-box; }
[hidden] { display: none !important; }
:host { display: block; position: relative; contain: content; container: drishti/inline-size; }
:host([hidden]) { display: none; }
.view { min-height: 0; }
.view:focus { outline: none; }
.pnl, .vmain > *, .vright > * { min-width: 0; }
.tbl-wrap { max-width: 100%; overflow-x: auto; -webkit-overflow-scrolling: touch; }
.r-back { padding: .5rem 1.25rem 0; }
.r-back .fk { cursor: pointer; }
.r-deleted > :not(.r-deleted-banner) { opacity: .55; filter: grayscale(.7); }
.r-fix { display: block; margin-top: .3rem; color: var(--d-muted); }
.dv-error { margin: .8rem 1.25rem; }
.tbl-bar { display: flex; flex-wrap: wrap; gap: .4rem; align-items: center; margin: 0 0 .4rem; }
.tbl-pg-i { color: var(--d-muted); font-size: .8rem; }
.tbl-sort { background: none; border: 0; padding: 0; font: inherit; color: inherit; cursor: pointer; text-align: inherit; }
.tbl-sort:focus-visible, .dr-step:focus-visible { outline: 2px solid var(--d-accent); outline-offset: 2px; }
th[aria-sort="ascending"] .tbl-sort::after { content: " \\25B2"; font-size: .7em; }
th[aria-sort="descending"] .tbl-sort::after { content: " \\25BC"; font-size: .7em; }
.dr-step { background: none; border: 0; padding: 0; font: inherit; color: var(--d-link); cursor: pointer; }
@container drishti (max-width: 640px) {
  .pnl-help, .tbl-pg-b, .tbl-sort, .tr-tog, .tab-head, .sg-tab, .sg-fold, .md-btn, .dr-step, .dr-up, .r-back .fk, .pv-switch button { min-width: 44px; min-height: 44px; }
}
@media (pointer: coarse) {
  .pnl-help, .tbl-pg-b, .tbl-sort, .tr-tog, .tab-head, .sg-tab, .sg-fold, .md-btn, .dr-step, .dr-up, .r-back .fk, .pv-switch button { min-width: 44px; min-height: 44px; }
}
"""


def icons_used(version: str = VERSION) -> list[str]:
    found: set[str] = set()
    for rel in ICON_SOURCES:
        path = WEB / rel.format(v=version)
        if path.exists():
            text = path.read_text(encoding="utf-8")
            found.update(re.findall(r"icon\('([a-z0-9]+(?:-[a-z0-9]+)*)'\)", text))
            found.update(n for n in es.ICON_NAME.findall(text) if n != "bi")
    # the console macros' glyphs too: the renderer draws the same panels
    for rel in ("templates/_macros/panels.html", "templates/_macros/view.html", "templates/terminal/_grid.html"):
        found.update(es.ICON_NAME.findall((WEB / rel).read_text(encoding="utf-8")))
    return sorted(found)


def build_sheet(version: str = VERSION) -> tuple[str, dict]:
    """The renderer's sheet (deterministic) and its facts."""
    rules = es.glyph_rules()
    icons = [n for n in icons_used(version) if n in rules]
    parts = [es._compact(es.container_queries(es.shadow_css((STATIC / s).read_text(encoding="utf-8")))) for s in SHEETS]
    parts.append(ADDENDUM)
    parts.append(es._compact(rules[""]) + "".join(es._compact(rules[n]) for n in icons))
    body = "".join(parts)
    digest = hashlib.sha256(body.encode()).hexdigest()[:12]
    text = es.licence_header() + f"/* drishti-render {version} sheet {digest}: generated by tools/render_sheet.py from {', '.join(SHEETS)}; do not edit. */\n" + body
    raw = text.encode()
    return text, {"sheet": digest, "bytes": len(raw), "gzip": len(gzip.compress(raw, 9, mtime=0)), "icons": icons}


def sri(data: bytes) -> str:
    return "sha384-" + base64.b64encode(hashlib.sha384(data).digest()).decode()


def files(version: str = VERSION) -> dict[str, Path]:
    """The distributed files of a version by their path in its folder (the demo is not one of them)."""
    folder = ROOT / version
    out = {}
    for p in sorted(folder.rglob("*")):
        rel = p.relative_to(folder).as_posix()
        if p.is_file() and rel != RECORD and rel not in DEMO:
            out[rel] = p
    return out


def record(version: str = VERSION) -> dict:
    fs = files(version)
    js = [p for r, p in fs.items() if r.endswith(".js")]
    return {"name": "@drishti/render", "version": version, "contracts": [1],
            "bytes": {"js": sum(p.stat().st_size for p in js), "js_gzip": sum(len(gzip.compress(p.read_bytes(), 9, mtime=0)) for p in js),
                      "css": (ROOT / version / SHEET).stat().st_size if (ROOT / version / SHEET).exists() else 0},
            "files": {rel: {"integrity": sri(p.read_bytes()), "bytes": p.stat().st_size} for rel, p in fs.items()}}


def record_text(rec: dict) -> str:
    return json.dumps(rec, indent=2, sort_keys=True) + "\n"


def versions() -> list[str]:
    return sorted(p.name for p in ROOT.iterdir() if p.is_dir() and re.fullmatch(r"\d+\.\d+\.\d+", p.name)) if ROOT.exists() else []
