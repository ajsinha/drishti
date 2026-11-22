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

"""How the client-side renderer is distributed (docs/guides/EMBED_GUIDE.md section 13).

The library lives in one folder per version, ``web/static/render/<version>/``: the entries ``drishti-render.js`` and ``drishti-elements.js``,
the host part ``m/*.js`` (Drishti's: where a ViewModel comes from, sign-in, navigation, About; every panel is drawn by Drishti Elements'
painter, drishti-views, from the vendored tree), the types ``drishti-render.d.ts``, the sheet ``drishti-render.css`` (generated here from
Drishti Elements' tokens and the painter's sheets, rewritten for a shadow root) and ``integrity.json`` (SHA-384 of every file, for
subresource integrity). A version's folder is immutable: ``tests/test_render_library.py`` fails when a file changed and its
``integrity.json`` did not, so a change means a new folder. ``tools/render_sheet.py`` writes the sheet and the record.
"""
from __future__ import annotations

import base64
import gzip
import hashlib
import json
import re
from pathlib import Path

from core import element_sheet as es
from core import elements_pin as _pin

WEB = Path(__file__).resolve().parent.parent / "web"
STATIC = WEB / "static"
ROOT = STATIC / "render"
VERSION = _pin.VERSION              # the one renderer: the pinned Drishti Elements version (core/elements_pin.py)
FOLDER = ROOT / VERSION
LIBRARY = FOLDER / "m"              # the host part of the current version (its modules; every panel is the painter's)
SHEET = "drishti-render.css"
ENTRY = "drishti-render.js"
RECORD = "integrity.json"
DEMO = ("demo.html", "demo.js", "demo.css")      # the playground: served with the library, not part of a host's integrity list
# Drishti Elements' sheets the painter draws with, in the order of their libraries (the tokens with every theme first), and the console's
# About sheet (the host's About this page): all of them adopted by the screen's shadow root, so a host page loads no stylesheet
SHEETS = ("css/drishti_tokens.css", "css/drishti_resize.css", "css/drishti_chips.css", "css/drishti_states.css", "elements/drishti-inputs/drishti_inputs.css",
          "elements/drishti-chart/drishti_chart.css", "elements/drishti-graph/drishti_graph.css", "elements/drishti-tree/drishti_tree.css",
          "elements/drishti-views/drishti_views.css", "css/drishti_about.css")
ICON_SOURCES = tuple(f"static/render/{VERSION}/m/drishti_{m}.js" for m in ("screen", "about", "signin"))

ADDENDUM = """\
*, ::before, ::after { box-sizing: border-box; }
[hidden] { display: none !important; }
:host { display: block; position: relative; contain: content; container: drishti/inline-size; color: var(--d-ink); }
:host([hidden]) { display: none; }
:host([data-theme]) { background: var(--d-bg); }
.r-view { min-width: 0; padding: .75rem 1.25rem; }
.r-bar { display: flex; flex-wrap: wrap; gap: .4rem; align-items: center; justify-content: space-between; margin: 0 0 .4rem; }
.fk { display: inline-flex; align-items: center; gap: .3rem; border: 1px solid var(--d-border); background: var(--d-surface); color: var(--d-link); border-radius: var(--d-radius); padding: .15rem .6rem; font: inherit; font-size: .8rem; cursor: pointer; }
.fk:focus-visible { outline: 2px solid var(--d-accent); outline-offset: 2px; }
.r-about-btn { margin-left: auto; }
.r-problem { padding: .8rem 1.25rem; }
.dv-error { padding: .6rem .8rem; border: 1px solid var(--d-neg); border-radius: var(--d-radius); background: var(--d-surface); color: var(--d-ink); }
.r-fix { display: block; margin-top: .3rem; color: var(--d-muted); }
.about { position: absolute; top: 0; right: 0; bottom: 0; width: min(420px, 92cqw); overflow: auto; z-index: 5; background: var(--d-surface); border-left: 1px solid var(--d-border); padding: .6rem .9rem; }
.about header { display: flex; justify-content: space-between; align-items: center; }
.raw-x { background: none; border: 0; color: var(--d-muted); font-size: 1.3rem; cursor: pointer; }
.mono { font-family: var(--d-font-mono); }
.t-warn { color: var(--d-warn); }
.r-signin { display: flex; justify-content: center; padding: 2rem 1rem; }
.r-signin-card { max-width: 32rem; text-align: center; background: var(--d-surface); border: 1px solid var(--d-border); border-radius: var(--d-radius); padding: 1.25rem 1.5rem; }
.r-signin-title { font-weight: 600; margin: 0 0 .4rem; }
.r-signin-text, .r-signin-note { color: var(--d-muted); margin: .45rem 0; }
.r-signin-note:empty { display: none; }
.r-signin-btn { min-height: 44px; padding: .5rem 1.2rem; border-radius: var(--d-radius); border: 1px solid var(--d-accent); background: var(--d-accent); color: var(--d-on-accent); font: inherit; font-weight: 600; cursor: pointer; }
.r-signin-btn:disabled { opacity: .55; cursor: not-allowed; }
.r-signin-btn:focus-visible { outline: 2px solid var(--d-accent); outline-offset: 2px; }
.r-signin-why { margin: .7rem 0 0; color: var(--d-neg); }
@container drishti (max-width: 640px) {
  .about { width: 100cqw; }
  .fk, .raw-x { min-width: 44px; min-height: 44px; }
}
@media (pointer: coarse) {
  .fk, .raw-x { min-width: 44px; min-height: 44px; }
}
"""


def icons_used(version: str = VERSION) -> list[str]:
    found: set[str] = set()
    for rel in ICON_SOURCES:
        path = WEB / rel
        if path.exists():
            text = path.read_text(encoding="utf-8")
            found.update(re.findall(r"icon\('([a-z0-9]+(?:-[a-z0-9]+)*)'\)", text))
            found.update(n for n in es.ICON_NAME.findall(text) if n != "bi")
    return sorted(found)


def build_sheet(version: str = VERSION) -> tuple[str, dict]:
    """The renderer's sheet (deterministic) and its facts."""
    rules = es.glyph_rules()
    icons = [n for n in icons_used(version) if n in rules]
    parts = [es._compact(es.container_queries(es.shadow_css(_pin.static_path(s).read_text(encoding="utf-8")))) for s in SHEETS]
    parts.append(ADDENDUM)
    parts.append(es._compact(rules[""]) + "".join(es._compact(rules[n]) for n in icons))
    body = es.prune_tokens(es.inline_tokens("".join(parts), es.fixed_tokens()))   # the scale tokens written in, as in the view sheet
    digest = hashlib.sha256(body.encode()).hexdigest()[:12]
    text = es.licence_header() + f"/* drishti-render {version} sheet {digest}: generated by tools/render_sheet.py from {es.public_names(SHEETS)}; do not edit. */\n" + body
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
