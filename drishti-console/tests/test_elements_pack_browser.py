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

"""Drishti Elements packed for another application (``drishti.py elements pack``; docs/guides/EMBED_GUIDE.md): a tiny static
page that loads only the two packed files draws a grid and a chart, with no Drishti server, under a strict CSP (nothing inline), and
asks for nothing but the packed files. The packing itself (dependencies, the banking exclusion, themes) is test_elements_dist.py."""
import re
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.spread

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "elements"))
import dist  # noqa: E402
import pack  # noqa: E402

ORIGIN = "http://app.test"
CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:"
TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "application/javascript", ".json": "application/json"}

PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>An app with Drishti Elements</title>
<link rel="stylesheet" href="vendor/drishti/drishti-elements.css">
<link rel="stylesheet" href="app.css">
<script src="vendor/drishti/drishti-elements.js"></script>
<script src="app.js" defer></script>
</head><body><h1>P&amp;L by desk</h1><div id="grid"></div><div id="chart"></div></body></html>
"""
APP_CSS = "body { background: var(--d-bg); color: var(--d-ink); font-family: var(--d-font-sans); } #grid { height: 260px; } #chart { margin-top: 12px; }\n"
APP_JS = """(function () {
  var E = window.Drishti.elements;
  var rows = [{ desk: 'Rates', pnl: 1250000 }, { desk: 'Credit', pnl: -340000 }, { desk: 'FX', pnl: 560000 }, { desk: 'Equities', pnl: 90000 }];
  try {
    var grid = new E.grid.Grid(document.getElementById('grid'), {
      columns: [{ field: 'desk', header: 'Desk' }, { field: 'pnl', header: 'P&L', type: 'number', format: 'amount0' }],
      rows: rows, getRowId: function (r) { return r.desk; } });
    var chart = new E.chart.Chart(document.getElementById('chart'),
      { kind: 'bar', title: 'P&L by desk', id: 'desk', data: { rows: rows }, x: 'desk', y: { field: 'pnl', format: 'money' } });
    window.__app = { grid: grid, chart: chart, libs: Object.keys(E.libs) };
    Promise.resolve(chart.ready).then(function () { window.__app.ready = true; }, function (e) { window.__app.error = String(e); });
  } catch (e) { window.__app = { error: String((e && e.stack) || e) }; }
})();
"""


def test_a_static_page_with_only_the_packed_files_draws_a_grid_and_a_chart(browsers, tmp_path):
    site = tmp_path / "site"
    rec = pack.pack(["grid", "chart"], site / "vendor" / "drishti", theme="light")
    assert list(rec["libraries"]) == ["drishti-core", "drishti-inputs", "drishti-grid", "drishti-chart"]   # drishti-inputs is a requirement of grid and chart (their Selects)
    (site / "index.html").write_text(PAGE, encoding="utf-8")
    (site / "app.css").write_text(APP_CSS, encoding="utf-8")
    (site / "app.js").write_text(APP_JS, encoding="utf-8")
    fetched: list[str] = []

    async def route(r):
        path = r.request.url[len(ORIGIN):].split("?")[0].lstrip("/") or "index.html"
        f = site / path
        if f.is_file():
            fetched.append(path)
            headers = {"content-type": TYPES.get(f.suffix, "application/octet-stream")}
            if f.suffix == ".html":
                headers["content-security-policy"] = CSP
            return await r.fulfill(status=200, body=f.read_bytes(), headers=headers)
        fetched.append(path + " 404")
        return await r.fulfill(status=404, body="")

    async def go():
        async with browsers.page(1100, 900, route=route) as pg:
            await pg.goto(ORIGIN + "/index.html")
            await pg.wait_for_function("() => window.__app && (window.__app.ready || window.__app.error)", timeout=30000)
            assert await pg.evaluate("window.__app.error || null") is None
            assert set(await pg.evaluate("window.__app.libs")) >= {"drishti-core", "drishti-grid", "drishti-chart"}
            assert await pg.locator("#grid [role=grid]").count() == 1
            await pg.wait_for_function("() => document.querySelectorAll('#grid [role=gridcell]').length >= 8", timeout=15000)
            cells = await pg.eval_on_selector_all("#grid [role=gridcell]", "els => els.map((e) => e.textContent.trim())")
            assert {"Rates", "Credit", "FX", "Equities"} <= set(cells)
            assert any(re.fullmatch(r"1,250,000", c) for c in cells), cells
            assert any(c.startswith(("−", "-", "(")) and "340,000" in c for c in cells), cells        # the true minus or brackets
            assert await pg.locator("#chart .dc-canvas canvas").count() >= 1
            assert await pg.evaluate("() => !!window.__app.chart.engine && window.__app.chart.engine.getOption().series[0].data.length") == 4
            light_bg = dist.themes()[dist.theme_alias("light")]["decls"]["--d-bg"]
            assert await pg.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--d-bg').trim()") == light_bg
            assert pg.errors == [], pg.errors

    browsers.run(go(), timeout=90)
    assert all(not f.endswith(" 404") for f in fetched), fetched
    assert set(fetched) == {"index.html", "app.css", "app.js", "vendor/drishti/drishti-elements.css", "vendor/drishti/drishti-elements.js"}, fetched
