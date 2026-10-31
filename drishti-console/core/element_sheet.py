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

"""The stylesheet of ``<drishti-view>`` (docs/architecture/CLIENT_RENDERER.md, build step 8), generated from the console's own sheets.

The console's sheets are rewritten for a shadow root (``:root``, ``html`` and ``body`` become the host element), the icon
font's ``@font-face`` is left out (the element registers the font through the document's ``FontFace`` API: a face declared
inside a shadow root is not reliably used), and of Bootstrap Icons only the glyphs the view macros and the root-scoped
scripts use are kept (the whole set is about 2000 rules, two thirds of the sheet). The result is content-addressed: its
version is the first 12 hex digits of its SHA-256, so a URL carrying it can be cached for ever. ``tools/elements_sheet.py``
writes it; ``tests/test_element_sheet.py`` fails when it is stale or a macro uses a glyph it does not hold.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import re
from pathlib import Path

WEB = Path(__file__).resolve().parent.parent / "web"
STATIC = WEB / "static"
OUT_DIR = WEB / "elements"
SHEET_FILE, MANIFEST_FILE = "drishti-view.css", "drishti-view.manifest.json"
REPORT_FILE, REPORT_MANIFEST = "drishti-report.css", "drishti-report.manifest.json"
# <drishti-report> adopts the view sheet (tokens, theme, buttons, inputs) and then this one: the BI sheets, rewritten the same way, with the glyphs the report code names
REPORT_SHEETS = ("css/bi.css", "css/bi-report.css", "css/bi-board.css")
REPORT_ICON_SOURCES = ("static/js/bi-visuals.js", "static/js/bi-visuals-format.js", "static/js/bi-grids.js", "static/js/bi-matrix.js", "static/js/bi-multiples.js", "static/js/bi-frame.js", "static/js/bi-live.js", "static/js/bi-phone.js", "static/js/bi-lib.js",
                       "embed/report.js", "embed/report-page.js")
# glyphs the code builds from parts (a class such as "bi bi-" + (x ? a : b)) or hands to a helper by name
REPORT_NAMED_ICONS = ("table", "x-circle", "play-fill", "pause-fill", "caret-down-fill", "caret-up-fill", "dash-square", "plus-square", "arrow-left", "cursor", "funnel", "sliders",
                      "arrow-down-circle-fill", "arrow-right-circle-fill", "arrow-up-circle-fill", "circle-fill", "x-circle-fill", "exclamation-circle-fill", "check-circle-fill", "flag-fill")
REPORT_ICON_CALL = re.compile(r"icon\('([a-z0-9]+(?:-[a-z0-9]+)*)'\)")
# the console's own sheets, in the order base.html loads them (the icon sheet is handled apart: only its used glyphs go in)
SHEETS = ("css/tokens.css", "css/theme.css", "css/terminal.css", "css/layout.css", "css/gradients.css", "css/pivot.css", "css/colfilter.css", "css/controls.css", "css/about.css", "css/touch.css")
ICON_SHEET = "vendor/bootstrap-icons/bootstrap-icons.css"
GZIP_TARGET = 25 * 1024
# where a glyph can be named: the macros every embedded view is rendered with, and the scripts that run inside the element
ICON_SOURCES = ("templates/_macros/panels.html", "templates/_macros/view.html", "static/js/tables.js", "static/js/colfilter.js", "static/js/tree-rows.js",
                "static/js/view.js", "static/js/zoom.js", "static/js/charts.js", "static/js/pivot.js", "embed/enhancers.js", "embed/element.js", "embed/assets.js")
ICON_NAME = re.compile(r"(?<![\w-])bi-([a-z0-9]+(?:-[a-z0-9]+)*)")
BASE_ICON_CLASSES = {"bi"}          # the font's base rule is always kept
_LICENCE = re.compile(r"\A/\*.*?\*/\s*", re.S)


def shadow_css(text: str) -> str:
    """A console sheet for a shadow root: the page-level selectors (:root, html, body) become the host element, and the
    icon font's @font-face goes (the element adds it with FontFace)."""
    text = re.sub(r"@font-face\s*\{[^}]*\}", "", text)
    text = re.sub(r":root\[data-theme=\"([^\"]+)\"\]", r':host([data-theme="\1"])', text)
    text = re.sub(r":root:not\(\[data-theme\]\)", ":host(:not([data-theme]))", text)
    text = re.sub(r"html\[data-theme\]", ":host([data-theme])", text)
    text = re.sub(r"(?<![\w-]):root\b", ":host", text)
    text = re.sub(r"(?m)^(\s*)html,\s*body\b", r"\1:host", text)
    text = re.sub(r"(?m)^(\s*)body\.(terminal|embed)\b", r"\1:host", text)
    text = re.sub(r"(?m)^(\s*)body\b", r"\1:host", text)
    return text


CONTAINER = "drishti"
_WIDTH_MEDIA = re.compile(r"@media\s+((?:\(\s*(?:max|min)-width\s*:\s*[^)]+\)\s*(?:and\s+)?)+)\{")


def container_queries(text: str) -> str:
    """Width-based ``@media`` rules become ``@container drishti`` rules, so an embedded view answers to the width of its
    element, not of the host page's viewport. Non-width media (colour scheme, print, hover, motion, contrast) stay as they
    are. The host element is the container, and a container cannot style itself, so a ``:host`` selector inside such a
    rule moves to ``.view``; viewport units (``vw``) inside become container units (``cqw``)."""
    out, pos = [], 0
    for m in _WIDTH_MEDIA.finditer(text):
        if m.start() < pos:
            continue
        depth, i = 1, m.end()
        while depth:
            depth += (text[i] == "{") - (text[i] == "}")
            i += 1
        body = re.sub(r"(?<![\w-]):host\b(?!\()", ".view", text[m.end():i - 1])
        body = re.sub(r"(\d)vw\b", r"\1cqw", body)
        out += [text[pos:m.start()], f"@container {CONTAINER} {m.group(1).strip()} {{", body, "}"]
        pos = i
    out.append(text[pos:])
    return "".join(out)


# What only an embedded view needs on top of the console's sheets (appended last, so it wins at equal specificity): the About
# drawer sized to the ELEMENT (a fixed box in the host element, whose width is not the viewport's), tables and panels that
# scroll inside themselves, and finger-sized controls where the element is narrow or the device is touch.
ELEMENT_ADDENDUM = """\
.about { width: min(420px, 92cqw); }
.pnl, .vmain > *, .vright > * { min-width: 0; }
.tbl-wrap { max-width: 100%; overflow-x: auto; -webkit-overflow-scrolling: touch; }
@container drishti (max-width: 640px) {
  .about { width: 100cqw; }
  .pnl-help, .tbl-pg-b, tr.tbl-filter button, .about-more, .raw-x, [data-about-open] { min-width: 44px; min-height: 44px; }
}
@media (pointer: coarse) {
  .pnl-help, .tbl-pg-b, tr.tbl-filter button, .about-more, .raw-x, [data-about-open] { min-width: 44px; min-height: 44px; }
}
"""


REPORT_ADDENDUM = """\
:host { min-height: 0; }                       /* the console's page rule (100vh) is not an element's */
.bi-embed { min-width: 0; }
.bi-embed .bi-head { margin: 0 0 .4rem; }
.bi-embed-title { margin: 0; font-size: 1.1rem; }
.bi-embed .visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
.bi-embed .bi-tabs { -webkit-overflow-scrolling: touch; }
@container drishti (max-width: 640px) {
  .bi-embed .bi-vis-btn, .bi-embed .bi-tab, .bi-embed .bi-live-pause { min-width: 44px; min-height: 44px; }
}
"""


def _compact(text: str) -> str:
    """Drops the licence block, comments and blank lines of a sheet; rules are untouched."""
    text = _LICENCE.sub("", text, count=1) if "PROPRIETARY" in text[:600] else text
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(line.rstrip() for line in text.splitlines() if line.strip()) + "\n"


def licence_header() -> str:
    first = (STATIC / SHEETS[0]).read_text(encoding="utf-8")
    return _LICENCE.match(first).group().rstrip() + "\n"


def icons_used() -> dict[str, list[str]]:
    """Every Bootstrap Icons glyph named in the macros and scripts, by the file that names it (sorted)."""
    found: dict[str, set[str]] = {}
    for rel in ICON_SOURCES:
        path = WEB / rel
        if path.exists():
            for name in ICON_NAME.findall(path.read_text(encoding="utf-8")):
                found.setdefault(name, set()).add(rel)
    return {name: sorted(files) for name, files in sorted(found.items())}


def glyph_rules() -> dict[str, str]:
    """The icon sheet's per-glyph rules by glyph name, and its base rule under the key ``""``."""
    text = (STATIC / ICON_SHEET).read_text(encoding="utf-8")
    rules = {m.group(1): m.group(0) for m in re.finditer(r"^\.bi-([a-z0-9-]+)::before \{[^}]*\}", text, re.M)}
    base = re.search(r"^\.bi::before,.*?\}", text, re.M | re.S)
    rules[""] = base.group(0)
    return rules


def build() -> tuple[str, dict]:
    """The sheet text and its manifest. Deterministic: the same sources always give the same bytes."""
    used = icons_used()
    rules = glyph_rules()
    unknown = sorted(n for n in used if n not in rules)
    if unknown:
        raise ValueError(f"not Bootstrap Icons glyphs: {', '.join('bi-' + n for n in unknown)} (named in {sorted({f for n in unknown for f in used[n]})})")
    parts = [_compact(container_queries(shadow_css((STATIC / s).read_text(encoding="utf-8")))) for s in SHEETS]
    parts.append(ELEMENT_ADDENDUM)
    parts.append(_compact(rules[""]) + "".join(_compact(rules[n]) for n in used))
    body = "".join(parts)
    version = hashlib.sha256(body.encode()).hexdigest()[:12]
    text = licence_header() + f"/* drishti-view sheet {version}: generated by tools/elements_sheet.py from {', '.join(SHEETS)}; do not edit. */\n" + body
    raw = text.encode()
    manifest = {"version": version, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
                "gzip": len(gzip.compress(raw, 9, mtime=0)), "sources": list(SHEETS) + [ICON_SHEET],
                "icons": used, "iconFont": {"family": "bootstrap-icons", "registered": "FontFace API (the sheet has no @font-face)"}}
    return text, manifest


def manifest_text(manifest: dict) -> str:
    return json.dumps(manifest, indent=2, sort_keys=True) + "\n"


def committed() -> tuple[str, dict]:
    """The generated sheet and its manifest as committed under web/elements/."""
    return (OUT_DIR / SHEET_FILE).read_text(encoding="utf-8"), json.loads((OUT_DIR / MANIFEST_FILE).read_text(encoding="utf-8"))


def report_icons() -> list[str]:
    """The glyphs the report code names: ``icon('funnel')`` calls and literal ``bi-<glyph>`` classes in the sources <drishti-report> runs."""
    found: set[str] = set(REPORT_NAMED_ICONS)
    for rel in REPORT_ICON_SOURCES:
        path = WEB / rel if rel.startswith("embed/") else STATIC / rel.removeprefix("static/")
        if path.exists():
            text = path.read_text(encoding="utf-8")
            found.update(REPORT_ICON_CALL.findall(text))
            found.update(n for n in ICON_NAME.findall(text) if n not in BASE_ICON_CLASSES)
    return sorted(found)


def build_report() -> tuple[str, dict]:
    """The report element's sheet and its manifest (deterministic, content-addressed like the view sheet)."""
    rules = glyph_rules()
    icons = [n for n in report_icons() if n in rules]      # a class such as bi-vis is not a glyph
    parts = [_compact(container_queries(shadow_css((STATIC / s).read_text(encoding="utf-8")))) for s in REPORT_SHEETS]
    parts.append(REPORT_ADDENDUM)
    parts.append("".join(_compact(rules[n]) for n in icons))
    body = "".join(parts)
    version = hashlib.sha256(body.encode()).hexdigest()[:12]
    text = licence_header() + f"/* drishti-report sheet {version}: generated by tools/elements_sheet.py from {', '.join(REPORT_SHEETS)}; do not edit. */\n" + body
    raw = text.encode()
    manifest = {"version": version, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw), "gzip": len(gzip.compress(raw, 9, mtime=0)),
                "sources": list(REPORT_SHEETS) + [ICON_SHEET], "icons": icons, "after": SHEET_FILE}
    return text, manifest


def report_committed() -> tuple[str, dict]:
    return (OUT_DIR / REPORT_FILE).read_text(encoding="utf-8"), json.loads((OUT_DIR / REPORT_MANIFEST).read_text(encoding="utf-8"))
