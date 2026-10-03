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

"""Panels at narrow widths in a real browser: a status/kv panel's labels wrap and never run into each other, and a waterfall's
value labels never overlap (they turn to read upward when the columns are narrow and are left out when not even that fits).
Uses the console's own CSS and chart script on a bare page; skipped when Playwright's Chromium is not installed."""
import json
from pathlib import Path

import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

STATIC = Path(__file__).resolve().parent.parent / "web" / "static"

KV = """<div class="pnl" style="width:{w}px"><div class="pnl-b"><dl class="kv kv-4">
<div class="kv-item"><dt>Confirmation</dt><dd class="mono">MATCHED</dd></div>
<div class="kv-item"><dt>Clearing</dt><dd class="mono">CLEARED</dd></div>
<div class="kv-item"><dt>Reporting status</dt><dd class="mono">REPORTED</dd></div></dl></div></div>"""

STEPS = [{"label": f"Step {i}", "value": 1234567, "from": 0, "to": 1234567, "text": f"+{1234567 + i:,}",
          "total": False} for i in range(12)]


@pytest.fixture(scope="module")
def page():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed: skip
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        pg = b.new_page()
        yield pg
        b.close()


def _doc(tmp_path, body: str, scripts: bool = False) -> str:
    css = "".join(f'<link rel="stylesheet" href="{(STATIC / "css" / f).as_uri()}">' for f in ("tokens.css", "theme.css", "terminal.css", "layout.css"))
    js = "".join(f'<script src="{(STATIC / s).as_uri()}"></script>' for s in ("vendor/echarts/echarts.min.js", "js/charts.js")) if scripts else ""
    f = tmp_path / "p.html"
    f.write_text(f"<!doctype html><meta charset=utf-8>{css}<body>{body}{js}", encoding="utf-8")
    return f.as_uri()


@pytest.mark.parametrize("width", [120, 180, 240, 360])
def test_status_labels_wrap_and_never_overlap(page, tmp_path, width):
    page.set_viewport_size({"width": 800, "height": 600})
    page.goto(_doc(tmp_path, KV.format(w=width)))
    boxes = page.evaluate("""() => [...document.querySelectorAll('.kv-item')].map(i => {
        const r = i.getBoundingClientRect(), t = i.querySelector('dt'), d = i.querySelector('dd');
        return {l: r.left, r: r.right, t: r.top, b: r.bottom, dtOver: t.scrollWidth - t.clientWidth, ddOver: d.scrollWidth - d.clientWidth};
    })""")
    for b in boxes:
        assert b["dtOver"] <= 0 and b["ddOver"] <= 0, f"a label spills out of its cell at {width}px: {b}"
    for i, a in enumerate(boxes):
        for c in boxes[i + 1:]:
            apart = a["r"] <= c["l"] + 0.5 or c["r"] <= a["l"] + 0.5 or a["b"] <= c["t"] + 0.5 or c["b"] <= a["t"] + 0.5
            assert apart, f"two cells overlap at {width}px: {a} {c}"


def test_waterfall_labels_fit_flat_when_wide_turn_up_when_narrow_and_go_when_tiny(page, tmp_path):
    page.goto(_doc(tmp_path, "<p>x</p>", scripts=True))
    fit = lambda n, w, h=360: page.evaluate("([n, w, h]) => window.drishtiCharts.labelFit(Array.from({length: n}, () => ({text: '+12,345,678'})), {width: w, height: h}).mode", [n, w, h])  # noqa: E731
    assert fit(4, 900) == "flat"
    assert fit(9, 400) == "up"
    assert fit(40, 400) == "none"


@pytest.mark.parametrize("width", [260, 420, 900])
def test_waterfall_drawn_labels_never_overlap(page, tmp_path, width):
    div = f"<div class='xchart' data-kind='waterfall' style='width:{width}px;height:360px' data-xchart='{json.dumps({'steps': STEPS})}'></div>"
    page.set_viewport_size({"width": 1000, "height": 600})
    page.goto(_doc(tmp_path, div, scripts=True))
    page.wait_for_function("document.querySelectorAll('.xchart svg text').length > 0")
    page.wait_for_timeout(700)                                   # the entrance animation settles
    boxes = page.evaluate("""() => [...document.querySelectorAll('.xchart svg text')]
        .filter(t => /^\\+\\d/.test(t.textContent)).map(t => { const r = t.getBoundingClientRect(); return {l: r.left, r: r.right, t: r.top, b: r.bottom}; })""")
    for i, a in enumerate(boxes):
        for c in boxes[i + 1:]:
            apart = a["r"] <= c["l"] + 0.5 or c["r"] <= a["l"] + 0.5 or a["b"] <= c["t"] + 0.5 or c["b"] <= a["t"] + 0.5
            assert apart, f"value labels overlap at {width}px: {a} {c}"
