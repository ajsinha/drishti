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

"""Wave C in Chromium over the real server and console (tests/bi_report_stack.py on ports 18948 and 17948, security on, a small file lake: two business days of 60 trades).

* Vega-Lite: a `vega-lite` report visual and a Python visual that returns a Vega-Lite specification are drawn under the strict policy (no violation, no eval, no
  WebAssembly), with a real canvas and the expression interpreter walking `datum.MTM < 0`.
* Boards: a board of Python tiles runs them for the person looking.
* Analyse (wave B: nothing computes in the browser): the Slice is worked out by the console and a SQL cell over the extract runs on the console, read-only; a slice and
  a SQL cell are promoted into a new and an existing report draft.
* Phone: the new controls are 44 px on touch.

Skipped when Playwright, Chromium, the built server jar or pandas and pyarrow are missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import json
import os
import re
import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18948")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17948")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
pytest.importorskip("pandas")
pytest.importorskip("pyarrow")
from playwright.sync_api import sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import clean, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, ROOT  # noqa: E402

CHART_REPORT = """rachana: 1
report: {name}
version: 1
title: Chart {name}
model: trades-basic@1
pages:
  - id: chart
    title: Chart
    visuals:
      - id: mtm-chart
        type: perspective-chart
        title: Net MTM by desk and currency
        at: {{ x: 0, y: 0, w: 12, h: 10 }}
        wells: {{ rows: [trade.DESK], columns: [trade.CURRENCY], values: [trade.MTM] }}
        format: {{ plugin: Y Bar, aggregates: {{ trade.MTM: sum }} }}
        alt: Bars of net mark to market for each desk, split by currency
"""


class Stack(bi_report_stack.ReportStack):
    def prepare(self, work):
        python = work / "config/bi/python"
        python.mkdir(parents=True, exist_ok=True)
        for f in (ROOT / "config/bi/python").glob("*.python.yaml"):
            (python / f.name).write_text(f.read_text(encoding="utf-8"))
        reports = work / "config/bi/reports"
        reports.joinpath("python-desk-risk.report.yaml").write_text((ROOT / "config/bi/reports/python-desk-risk.report.yaml").read_text(encoding="utf-8"))
        reports.joinpath("python-wall.report.yaml").write_text((ROOT / "config/bi/reports/python-wall.report.yaml").read_text(encoding="utf-8"))     # a board: a report with board pages (2.4.0)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    if os.environ.get("DRISHTI_WAVEC_REUSE") and bi_report_stack.listening(bi_report_stack.CONSOLE_PORT):          # a development aid: a stack that is already up (made by the same Stack class)
        yield None
        return
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = Stack(tmp_path_factory.mktemp("wavec"), delta_root="").start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    yield s
    s.close()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


def api(page, method, path, body=None):
    r = page.request.fetch(CONSOLE_URL + "/bi/api" + path, method=method, data=json.dumps(body) if body is not None else None, headers={"Content-Type": "application/json"})
    return r.status, (r.json() if r.body() else {})


def quiet(page):
    """No policy violation was reported by the page."""
    assert page.evaluate("window.__csp") == []


# ---- Vega-Lite -------------------------------------------------------------------------------------------------------------------------
INK = """(sel) => { const c = document.querySelector(sel); if (!c) return -1; const g = c.getContext('2d'); const d = g.getImageData(0, 0, c.width, c.height).data;
  let n = 0; for (let i = 3; i < d.length; i += 4) { if (d[i] > 0) n++; } return n; }"""


def test_vega_lite_is_drawn_under_the_strict_policy_by_the_interpreter(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    resp = page.goto(CONSOLE_URL + "/bi/r/file:report:python-desk-risk/vega")
    csp = resp.headers["content-security-policy"]
    assert "unsafe-eval" not in csp and "wasm-unsafe-eval" not in csp and "unsafe-inline" not in csp
    page.wait_for_selector('[data-visual="mtm-by-desk"] .bi-vega[data-ready="1"] canvas', timeout=60000)
    page.wait_for_timeout(500)
    assert page.evaluate(INK, '[data-visual="mtm-by-desk"] canvas') > 2000                      # bars were really drawn
    assert page.evaluate("typeof window.vegaInterpreter") == "object" and page.evaluate("typeof window.vegaEmbed") == "function"
    assert page.locator('[data-visual="mtm-by-desk"] [data-vega-error]').count() == 0
    quiet(page)
    assert page.errors == []
    # the same page, a Python visual that returns a specification
    page.goto(CONSOLE_URL + "/bi/r/file:report:python-desk-risk/risk")
    page.wait_for_selector('[data-visual="strip"] .bi-vega[data-ready="1"] canvas', timeout=90000)
    assert page.evaluate(INK, '[data-visual="strip"] canvas') > 1000
    assert "Python" in page.locator('[data-visual="strip"] .bi-py-foot').inner_text() and "pnl-vega" in page.locator('[data-visual="strip"] .bi-py-foot').inner_text()
    quiet(page)
    clean(page)
    page.close()


def test_a_specification_that_loads_a_url_is_not_drawn(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    body = open(ROOT / "config/bi/reports/python-desk-risk.report.yaml", encoding="utf-8").read().replace("report: python-desk-risk", "report: vega-url").replace("sample: true", "sample: false")
    body = body.replace("        wells: { axis: [trade.DESK], values: [MTM, Trades] }\n        format:\n          spec:\n", "        wells: { axis: [trade.DESK], values: [MTM, Trades] }\n        format:\n          spec:\n            data: { url: 'http://127.0.0.1:1/x.json' }\n")
    s, item = api(page, "POST", "/workspaces/me/items", {"kind": "report", "title": "Vega url", "body": body})
    assert s in (200, 201), item
    s, checked = api(page, "POST", "/reports/validate", {"yaml": body})
    assert not checked["valid"] and any("loads nothing from a URL" in p["message"] for p in checked["problems"])
    clean(page)
    page.close()


# ---- boards ----------------------------------------------------------------------------------------------------------------------------
def test_a_board_of_python_tiles_runs_them_for_the_person_looking(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    page.goto(CONSOLE_URL + "/bi/r/file:report:python-wall")
    page.wait_for_function("() => document.querySelectorAll('[data-tile] .bi-py-foot').length === 3", timeout=120000)
    assert page.locator('[data-tile="vol"] .bi-py-chart').count() == 1 and page.locator('[data-tile="hist"] .bi-py-chart').count() == 1
    page.wait_for_selector('[data-tile="strip"] .bi-vega[data-ready="1"] canvas', timeout=30000)                   # Vega-Lite draws after the footer shows: wait for its ready mark
    assert page.locator('[data-tile="strip"] .bi-vega[data-ready="1"] canvas').count() == 1
    assert "desk-pnl-volatility" in page.locator('[data-tile="vol"] .bi-py-foot').inner_text()
    quiet(page)
    assert page.errors == []
    clean(page)
    page.close()


# ---- Perspective chart -----------------------------------------------------------------------------------------------------------------
STYLES = """async () => {
  const seen = new Set(); const walk = (root) => { root.querySelectorAll('style').forEach(s => seen.add(s.textContent)); root.querySelectorAll('*').forEach(e => { if (e.shadowRoot) walk(e.shadowRoot); }); };
  walk(document);
  const out = [];
  for (const t of seen) { const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(t)); out.push('sha256-' + btoa(String.fromCharCode(...new Uint8Array(d)))); }
  return out;
}"""


# ---- Analyse ---------------------------------------------------------------------------------------------------------------------------
def open_extract(page):
    page.goto(CONSOLE_URL + "/bi/explore")
    page.wait_for_function("() => document.querySelectorAll('[data-dataset] option').length > 1", timeout=30000)
    page.select_option("[data-dataset]", "mq:trades-basic@1")
    page.wait_for_selector("[data-fields] input[name=f]", timeout=30000)
    page.click("[data-open-go]")
    page.wait_for_function("() => /rows,/.test((document.querySelector('[data-info]') || {}).textContent || '')", timeout=60000)


def grid(page, selector):
    return page.eval_on_selector_all(selector + " tbody tr", "rows => rows.map(r => [...r.children].map(c => c.textContent))")


def test_the_slice_and_sql_over_the_extract_run_on_the_console(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    wasm = []
    page.on("request", lambda r: wasm.append(r.url) if r.url.endswith(".wasm") or "pyodide" in r.url or "duckdb" in r.url else None)
    resp = page.goto(CONSOLE_URL + "/bi/explore")
    csp = resp.headers["content-security-policy"]
    assert "unsafe-eval" not in csp and "unsafe-inline" not in csp and "wasm" not in csp
    open_extract(page)
    page.click('.bx-chip[data-field="DESK"] [data-to="rows"]')
    page.click('.bx-chip[data-field="CURRENCY"] [data-to="columns"]')
    page.click('.bx-chip[data-field="NOTIONAL"] [data-to="values"]')
    page.wait_for_function("() => document.querySelector('[data-pivot-meta]').dataset.engine === 'console' && /rows used/.test(document.querySelector('[data-pivot-meta]').textContent)", timeout=60000)
    assert len(grid(page, "[data-pivot]")) >= 2
    page.click('.bx-chip[data-field="DESK"] [data-to="filters"]')
    page.wait_for_function("() => document.querySelectorAll('[data-zone=\"filters\"] select option').length > 1", timeout=30000)
    page.select_option('[data-zone="filters"] select', "DESK-FX")
    page.wait_for_function("() => /rows used/.test(document.querySelector('[data-pivot-meta]').textContent)")
    page.click("#bxT-sql")
    page.fill("[data-cells=sql] textarea", "SELECT DESK, SUM(NOTIONAL) AS n FROM extract GROUP BY DESK ORDER BY DESK")
    page.click("[data-cells=sql] [data-run]")
    page.wait_for_selector('[data-cells=sql] [data-engine="server"]', timeout=60000)
    assert len(grid(page, "[data-cells=sql]")) >= 2
    for bad in ("SELECT 1; SELECT 2", "ATTACH 'x.db'"):
        page.fill("[data-cells=sql] textarea", bad)
        page.click("[data-cells=sql] [data-run]")
        page.wait_for_selector("[data-cells=sql] .bi-bad", timeout=30000)
        assert re.search(r"DRS-907[03]", page.inner_text("[data-cells=sql] .bi-bad"))
    assert wasm == [], wasm
    quiet(page)
    clean(page)
    page.close()


def test_a_slice_and_a_sql_cell_become_visuals_in_a_new_and_an_existing_report(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    s, mine = api(page, "POST", "/workspaces/me/items", {"kind": "report", "title": "Mine", "body": CHART_REPORT.format(name="mine")})
    assert s in (200, 201), mine
    open_extract(page)
    page.click('.bx-chip[data-field="DESK"] [data-to="rows"]')
    page.click('.bx-chip[data-field="MTM"] [data-to="values"]')
    page.click('.bx-chip[data-field="CURRENCY"] [data-to="filters"]')
    page.select_option('[data-zone="filters"] select', "USD")
    page.wait_for_selector("[data-pivot] table")
    # a new report draft
    page.click("[data-promote-slice]")
    page.wait_for_selector("[data-promote-slice] + .bi-near [data-promoted-link]", timeout=30000)
    page.wait_for_function("() => /Report draft saved/.test(document.querySelector('[data-promote-slice] + .bi-near').textContent)")
    href = page.get_attribute("[data-promoted-link]", "href")
    s, made = api(page, "GET", "/items/" + href.split("/bi/i/")[1].replace("%3A", ":"))
    assert "type: matrix" in made["body"] and "trade.CURRENCY" in made["body"] and "USD" in made["body"] and "MTM" in made["body"]
    # an existing one, from a SQL cell
    page.reload()
    open_extract(page)
    page.wait_for_function("() => document.querySelectorAll('[data-promote-target] option').length > 1", timeout=30000)
    page.select_option("[data-promote-target]", mine["id"])
    page.click("#bxT-sql")
    page.fill("[data-cells=sql] textarea", "SELECT DESK, SUM(MTM) FROM extract WHERE CURRENCY = 'EUR' GROUP BY DESK")
    page.click("[data-promote-report]")
    page.wait_for_function("() => /added to the report draft/.test(document.querySelector('[data-promote-report] + .bi-near').textContent)", timeout=30000)
    s, edited = api(page, "GET", "/items/" + mine["id"])
    assert s == 200 and edited["body"].count("- id:") >= 3 and "type: matrix" in edited["body"] and "EUR" in edited["body"] and "mtm-chart" in edited["body"], edited["body"]
    clean(page)
    page.close()


# ---- phone ------------------------------------------------------------------------------------------------------------------------------
def test_the_new_controls_are_finger_sized_on_a_phone(stack, browser):
    page = sign_in(browser, "wes", "/bi", viewport={"width": 390, "height": 800}, has_touch=True, is_mobile=True)
    open_extract(page)
    page.click("#bxT-sql")
    small = page.evaluate("""() => [...document.querySelectorAll('[data-engine-pick], [data-promote-target], [data-promote-page], [data-promote-slice], [data-promote-report], [data-run]')]
      .filter(e => e.offsetParent !== null).map(e => [e.getAttribute('data-engine-pick') !== null ? 'engine' : e.className.slice(0, 20) + (e.dataset.promoteReport !== undefined ? ' promote-report' : ''), Math.round(e.getBoundingClientRect().height)]).filter(x => x[1] < 44)""")
    assert small == [], small
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    page.close()
