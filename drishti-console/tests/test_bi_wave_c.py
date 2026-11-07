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
in words with what to use instead. Here: the two shipped specifications translate whole and draw what Vega drew (the chart's data and
encodings), a refused feature's message, and a guard: no file references the Vega library any more."""
import json
import re
import shutil
import subprocess

import pytest
import yaml

from conftest import CONSOLE

ROOT = CONSOLE.parent
NODE = shutil.which("node")
CHART = CONSOLE / "web" / "static" / "elements" / "drishti-chart" / "drishti_chart.js"

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


def _shipped():
    """The two shipped specifications: the report's vega-lite visual (its rows the wells') and what the pnl-vega Python item returns."""
    report = yaml.safe_load((ROOT / "config/bi/reports/python-desk-risk.report.yaml").read_text(encoding="utf-8"))
    bar = next(v for p in report["pages"] for v in p["visuals"] if v["id"] == "mtm-by-desk")["format"]["spec"]
    item = yaml.safe_load((ROOT / "config/bi/python/pnl-vega.python.yaml").read_text(encoding="utf-8"))
    pd = pytest.importorskip("pandas")
    scope = {}
    exec(compile(item["source"], "pnl-vega", "exec"), scope)       # the shipped item's own function, over a known table
    table = pd.DataFrame({"DESK": ["FX", "FX", "RATES", "RATES", "CREDIT"], "PNL_1D": [10.0, 30.0, -5.0, 7.0, 2.5]})
    strip = scope["render"](table, {"max_points": 400})
    return bar, strip


def _translate(spec, rows):
    code = ("const C = require(%s); const t = C.fromVegaLite(%s, {rows: %s});"
            " const out = {t}; if (t.spec) { const layers = t.spec.layers || [t.spec]; out.built = layers.map((l) => { const s = C.normalizeSpec(l);"
            " const b = C.kindOf(s.kind).build(s, C.rowsOf(s.data), {theme: C.chartTheme({}), fmt: C.formatterFor(null), xfmt: C.formatterFor(null)});"
            " return {xAxis: b.option.xAxis, yAxis: b.option.yAxis, series: b.option.series.map((x) => ({type: x.type, data: x.data}))}; }); }"
            " process.stdout.write(JSON.stringify(out));") % (json.dumps(str(CHART)), json.dumps(spec), json.dumps(rows))
    run = subprocess.run([NODE, "-e", code], capture_output=True, text=True, timeout=60)
    assert run.returncode == 0, run.stderr[-3000:]
    return json.loads(run.stdout)


@pytest.mark.skipif(NODE is None, reason="needs Node.js")
def test_the_two_shipped_specifications_draw_identically_through_the_translator():
    bar, strip = _shipped()
    rows = [{"DESK": "FX", "MTM": -3.0, "Trades": 2}, {"DESK": "RATES", "MTM": 5.0, "Trades": 3}, {"DESK": "CREDIT", "MTM": 1.5, "Trades": 1}]
    got = _translate(bar, rows)
    t = got["t"]
    assert t["refused"] is None and t["unsupported"] == []
    s = t["spec"]
    # the encodings: a bar per desk (x the category DESK, y the value MTM, the titles kept), sorted by the value descending (sort: -y),
    # red where MTM < 0 and blue elsewhere (the condition and the default value)
    assert (s["kind"], s["x"]["field"], s["x"]["type"], s["x"]["title"], s["y"]["field"], s["y"]["type"], s["y"]["title"]) == \
        ("bar", "DESK", "category", "Desk", "MTM", "value", "Net MTM")
    assert (s["sort"], s["tone"], s["colourWhen"]) == ("value desc", "link", [{"when": "datum.MTM < 0", "tone": "bad"}])
    assert s["data"]["rows"] == rows                                                            # the data: the wells' rows, unchanged
    b = got["built"][0]
    assert b["xAxis"]["data"] == ["RATES", "CREDIT", "FX"]                                     # what Vega drew: sorted by MTM descending
    assert [d["value"] if isinstance(d, dict) else d for d in b["series"][0]["data"]] == [5.0, 1.5, -3.0]

    got = _translate(strip, [])
    t = got["t"]
    assert t["refused"] is None and t["unsupported"] == []
    dots, ticks = t["spec"]["layers"]
    # the data: a dot for every trade and a tick at every desk's median, the rows the item returned
    assert dots["data"]["rows"] == strip["layer"][0]["data"]["values"] and ticks["data"]["rows"] == strip["layer"][1]["data"]["values"]
    assert sorted((r["desk"], r["median"]) for r in ticks["data"]["rows"]) == [("CREDIT", 2.5), ("FX", 20.0), ("RATES", 1.0)]
    # the encodings: x the quantitative P&L (and the median), y the nominal desk; circles and ticks, the ticks red
    assert (dots["kind"], dots["symbol"], dots["x"]["field"], dots["y"]["field"], dots["x"]["title"]) == ("scatter", "circle", "pnl1d", "desk", "One-day P&L")
    assert (ticks["kind"], ticks["symbol"], ticks["x"]["field"], ticks["y"]["field"], ticks["tone"]) == ("scatter", "tick-v", "median", "desk", "bad")
    built_dots = got["built"][0]
    assert built_dots["yAxis"]["data"] == ["CREDIT", "FX", "RATES"]                            # Vega-Lite's ascending order of a nominal axis
    assert len(built_dots["series"][0]["data"]) == 5


@pytest.mark.skipif(NODE is None, reason="needs Node.js")
def test_an_unsupported_feature_is_refused_in_words_with_the_alternative():
    got = _translate({"facet": {"row": {"field": "DESK"}}, "mark": "bar", "encoding": {"x": {"field": "DESK", "type": "nominal"}, "y": {"field": "MTM", "type": "quantitative"}}},
                     [{"DESK": "FX", "MTM": 1}])
    t = got["t"]
    assert t["spec"] is None and "built" not in got                                            # never drawn wrong: nothing is drawn
    assert t["unsupported"] == ["$.facet"]
    assert t["refused"].startswith("Not drawn: this Vega-Lite specification uses what drishti-chart does not draw")
    assert "$.facet: use small multiples: a BI chart's split well" in t["refused"]
    got = _translate({"mark": "text", "encoding": {"text": {"field": "DESK"}}}, [{"DESK": "FX"}])
    assert "$.encoding.text: use a table or card visual" in got["t"]["refused"] and "$.mark (text): use a BI visual of that kind" in got["t"]["refused"]
