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

"""The stylesheet of ``<drishti-view>`` (docs/architecture/ELEMENTS.md, build step 8), generated from the console's own sheets.

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
# the console's own sheets, in the order base.html loads them (the icon sheet is handled apart: only its used glyphs go in)
SHEETS = ("css/tokens.css", "css/theme.css", "css/terminal.css", "css/layout.css", "css/gradients.css", "css/pivot.css", "css/about.css")
ICON_SHEET = "vendor/bootstrap-icons/bootstrap-icons.css"
GZIP_TARGET = 25 * 1024
# where a glyph can be named: the macros every embedded view is rendered with, and the scripts that run inside the element
ICON_SOURCES = ("templates/_macros/panels.html", "templates/_macros/view.html", "static/js/tables.js", "static/js/tree-rows.js",
                "static/js/view.js", "static/js/charts.js", "static/js/pivot.js", "embed/drishti-elements.js")
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
    parts = [_compact(shadow_css((STATIC / s).read_text(encoding="utf-8"))) for s in SHEETS]
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
