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

"""Vega-Lite without Vega (the product owner's decision O1: one drawing engine). A report's `vega-lite` visual and a Python visual's
`output: vega-lite` are translated by drishti-chart's fromVegaLite and drawn by drishti-chart; a feature it does not translate is refused
in words with what to use instead. Here, quick file checks: no file references the Vega library any more, and the BI pages load
drishti-chart. The translation of the two shipped specifications and a refused feature's message: test_vega_translate.py (Node)."""
import re

import pytest

from conftest import CONSOLE

ROOT = CONSOLE.parent

# The Vega library by its own names: the vendored folder and files, its globals and the console script that loaded it. "Vega-Lite" the
# specification format is not here (it stays an input), nor "vega" the option Greek.
LIBRARY = re.compile(r"vendor/vega\b|vega-embed|vegaEmbed|vega-interpreter|vegaInterpreter|\bvega(?:-lite)?\.min\.js|drishti_bi-vega|\bbi-vega\b|"
                     r"\bB\.vega\b|window\.vega\b|\bvega@\d")
# the history keeps its record of what was vendored; this file names what it forbids
HISTORY = {"CHANGELOG.md", "RELEASE_NOTES.md", "drishti-console/tests/test_bi_wave_c.py"}
SKIP_DIRS = {".git", "node_modules", "target", ".venv", "data", "dist", "__pycache__", ".claude"}
TEXT = {".js", ".mjs", ".py", ".html", ".css", ".md", ".yaml", ".yml", ".json", ".java", ".txt", ".sh", ".ps1", ".toml", ".xml", ".properties"}


def _files():
    stack = [ROOT]
    while stack:
        d = stack.pop()
        for p in d.iterdir():
            if p.is_dir():
                if p.name not in SKIP_DIRS and not p.is_symlink():
                    stack.append(p)
            elif p.suffix in TEXT:
                yield p


@pytest.mark.static
def test_no_file_references_the_vega_library():
    hits = []
    for p in _files():
        rel = p.relative_to(ROOT).as_posix()
        if rel in HISTORY:
            continue
        try:
            text = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for i, line in enumerate(text.splitlines(), 1):
            if LIBRARY.search(line):
                hits.append(f"{rel}:{i}: {line.strip()[:140]}")
    assert not hits, "the Vega library is retired (O1): draw with drishti-chart (fromVegaLite)\n" + "\n".join(hits[:40])
    assert not (CONSOLE / "web/static/vendor/vega").exists()


@pytest.mark.static
def test_the_report_and_designer_pages_load_drishti_chart():
    for page in ("report", "design", "notebook", "design_theme"):
        html = (CONSOLE / f"web/templates/bi/{page}.html").read_text(encoding="utf-8")
        assert "/static/elements/drishti-chart/drishti_chart.js" in html and "/static/elements/drishti-chart/drishti_chart.css" in html, page
