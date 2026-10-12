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
* Perspective chart: the d3fc chart plugin, the settings panel, and the view saved back into the report's draft as `format.view`; every <style> the viewer and the plugin write
  is covered by the page's hash list.
* Analyse: Slice and SQL run in the browser with DuckDB-Wasm and agree with the console's own engines; a runaway query is stopped; a slice and a SQL cell are promoted into
  a new and an existing report draft.
* Phone: the new controls are 44 px on touch.

Skipped when Playwright, Chromium, the built server jar or pandas and pyarrow are missing, or a scratch port is in use; the Analyse part also needs DuckDB-Wasm
(tools/fetch-duckdb-wasm.sh). Never run with pytest -n (the ports are fixed)."""
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
from core.perspective_csp import CHART_STYLE_HASHES, STYLE_HASHES  # noqa: E402

DUCK = list((ROOT / "drishti-console/web/static/vendor/duckdb-wasm").glob("*/duckdb-eh.wasm"))
CHART_REPORT = """rachana: 1
report: {name}
version: 1
title: Chart {name}
dataset: trades-basic
pages:
  - id: chart
    title: Chart
    visuals:
      - id: mtm-chart
        type: perspective-chart
        title: Net MTM by desk and currency
        at: {{ x: 0, y: 0, w: 12, h: 10 }}
        wells: {{ rows: [trade.desk], columns: [trade.currency], values: [trade.mtm] }}
        format: {{ plugin: Y Bar, aggregates: {{ trade.mtm: sum }} }}
        alt: Bars of net mark to market for each desk, split by currency
"""


class Stack(bi_report_stack.ReportStack):
    def prepare(self, work):
        python = work / "config/bi/python"
        python.mkdir(parents=True)
        for f in (ROOT / "config/bi/python").glob("*.python.yaml"):
            (python / f.name).write_text(f.read_text(encoding="utf-8"))
        reports = work / "config/bi/reports"
        reports.joinpath("python-desk-risk.report.yaml").write_text((ROOT / "config/bi/reports/python-desk-risk.report.yaml").read_text(encoding="utf-8"))
        boards = work / "config/bi/boards"
        boards.mkdir(parents=True)
        boards.joinpath("python-wall.board.yaml").write_text((ROOT / "config/bi/boards/python-wall.board.yaml").read_text(encoding="utf-8"))


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
    body = body.replace("        wells: { axis: [trade.desk], values: [MTM, Trades] }\n        format:\n          spec:\n", "        wells: { axis: [trade.desk], values: [MTM, Trades] }\n        format:\n          spec:\n            data: { url: 'http://127.0.0.1:1/x.json' }\n")
    s, item = api(page, "POST", "/workspaces/me/items", {"kind": "report", "title": "Vega url", "body": body})
    assert s in (200, 201), item
    s, checked = api(page, "POST", "/reports/validate", {"yaml": body})
    assert not checked["valid"] and any("loads nothing from a URL" in p["message"] for p in checked["problems"])
    clean(page)
    page.close()


# ---- boards ----------------------------------------------------------------------------------------------------------------------------
def test_a_board_of_python_tiles_runs_them_for_the_person_looking(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    page.goto(CONSOLE_URL + "/bi/b/file:board:python-wall")
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


def test_the_perspective_chart_its_settings_and_the_saved_view(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    s, item = api(page, "POST", "/workspaces/me/items", {"kind": "report", "title": "Chart one", "body": CHART_REPORT.format(name="chart-one")})
    assert s in (200, 201), item
    rid = item["id"]
    resp = page.goto(f"{CONSOLE_URL}/bi/r/{rid}")
    policy = resp.headers["content-security-policy"]
    assert "'wasm-unsafe-eval'" in policy and "unsafe-eval'" not in policy.replace("'wasm-unsafe-eval'", "") and "unsafe-inline" not in policy         # the narrow policy of a page that holds Perspective
    page.wait_for_selector("[data-psp=ready]", timeout=90000)
    page.wait_for_timeout(800)
    quiet(page)                                                                                     # the chart itself writes only the two styles of the chart list
    saved = page.evaluate("document.querySelector('perspective-viewer').save()")
    assert saved["plugin"] == "Y Bar" and saved["group_by"] == ["desk"] and saved["split_by"] == ["currency"]
    assert page.locator("perspective-viewer").evaluate("v => !!v.shadowRoot") is True
    # the settings panel: opened by the gear on the visual's frame
    page.click('[data-act="psp-settings"]')
    page.wait_for_selector('[data-psp-settings="open"]', timeout=30000)
    page.evaluate("document.querySelector('perspective-viewer').restore({ plugin: 'Y Line', group_by: ['desk'], split_by: [], columns: ['mtm'], settings: true })")
    page.wait_for_timeout(800)
    page.evaluate("document.querySelector('perspective-viewer').flush()")
    seen = page.evaluate(STYLES)
    missing = sorted(set(seen) - set(STYLE_HASHES + CHART_STYLE_HASHES))
    assert missing == [], "add these <style> hashes to core/perspective_csp.py: " + ", ".join(missing)
    # the settings panel sets a few style attributes (the viewer's own sizing); the policy refuses them and nothing else is refused (docs/guides/REPORTS.md, Perspective's settings panel)
    refused = page.evaluate("window.__csp")
    assert set(refused) <= {"style-src-attr inline"}, refused
    # for the report's editor the view can be saved into the draft
    page.click('[data-act="psp-save"]')
    page.wait_for_selector('#biCanvas[data-view-saved="mtm-chart"]', timeout=30000)
    s, again = api(page, "GET", "/items/" + rid)
    assert s == 200 and "view:" in again["body"] and "Y Line" in again["body"] and "group_by" in again["body"], again["body"]
    assert page.errors == []
    page.close()



# ---- Analyse ---------------------------------------------------------------------------------------------------------------------------
def open_extract(page):
    page.goto(CONSOLE_URL + "/bi/explore")
    page.wait_for_function("() => document.querySelectorAll('[data-dataset] option').length > 1", timeout=30000)
    page.select_option("[data-dataset]", "file:dataset:trades-basic")
    page.wait_for_selector("[data-fields] input[name=f]", timeout=30000)
    page.click("[data-open-go]")
    page.wait_for_function("() => /rows,/.test(document.querySelector('[data-info]').textContent)", timeout=60000)


def grid(page, selector):
    return page.eval_on_selector_all(selector + " tbody tr", "rows => rows.map(r => [...r.children].map(c => c.textContent))")


@pytest.mark.skipif(not DUCK, reason="needs DuckDB-Wasm: tools/fetch-duckdb-wasm.sh")
def test_slice_and_sql_run_in_the_browser_and_agree_with_the_console(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    resp = page.goto(CONSOLE_URL + "/bi/explore")
    csp = resp.headers["content-security-policy"]
    assert "unsafe-eval" not in csp and "unsafe-inline" not in csp
    open_extract(page)
    assert page.get_attribute("#bx", "data-duckdb").startswith("/static/vendor/duckdb-wasm/")
    page.click('.bx-chip[data-field="desk"] [data-to="rows"]')
    page.click('.bx-chip[data-field="currency"] [data-to="columns"]')
    page.click('.bx-chip[data-field="notional"] [data-to="values"]')
    page.click('.bx-chip[data-field="mtm"] [data-to="values"]')
    page.wait_for_function("() => document.querySelector('[data-pivot-meta]').dataset.engine === 'duckdb-wasm'", timeout=120000)
    duck_pivot = grid(page, "[data-pivot]")
    assert page.evaluate("document.body.dataset.duckReady") == "true" and len(duck_pivot) >= 2
    # the console's engine (the page's own JavaScript pivot) gives the same table
    page.select_option("[data-engine-pick]", "server")
    page.wait_for_function("() => document.querySelector('[data-pivot-meta]').dataset.engine === 'js'", timeout=30000)
    assert grid(page, "[data-pivot]") == duck_pivot
    page.select_option("[data-engine-pick]", "")
    page.wait_for_function("() => document.querySelector('[data-pivot-meta]').dataset.engine === 'duckdb-wasm'", timeout=60000)
    # a filter in the slice is a filter in both
    page.click('.bx-chip[data-field="desk"] [data-to="filters"]')
    page.select_option('[data-zone="filters"] select', "DESK-FX")
    page.wait_for_function("() => /rows used/.test(document.querySelector('[data-pivot-meta]').textContent) && document.querySelector('[data-pivot-meta]').dataset.engine === 'duckdb-wasm'")
    filtered = grid(page, "[data-pivot]")
    assert [r[0] for r in filtered] == ["DESK-FX"]
    page.click('[data-zone="filters"] .bx-mini[aria-label^="Remove"]')

    # SQL in the browser, then the same SQL on the console (SQLite)
    page.click("#bxT-sql")
    sql = "SELECT desk, SUM(notional) AS n, COUNT(*) AS c FROM extract GROUP BY desk ORDER BY desk"
    page.fill("[data-cells=sql] textarea", sql)
    page.click("[data-cells=sql] [data-run]")
    page.wait_for_selector('[data-cells=sql] [data-engine="duckdb-wasm"]', timeout=60000)
    in_browser = grid(page, "[data-cells=sql]")
    page.click("#bxT-slice")
    page.select_option("[data-engine-pick]", "server")
    page.click("#bxT-sql")
    page.click("[data-cells=sql] [data-run]")
    page.wait_for_selector('[data-cells=sql] [data-engine="server"]', timeout=60000)
    assert [[r[0], float(r[1]), float(r[2])] for r in grid(page, "[data-cells=sql]")] == [[r[0], float(r[1]), float(r[2])] for r in in_browser]
    page.select_option("[data-engine-pick]", "")

    # read-only: one statement, no file, no URL, no setting
    for bad in ("COPY extract TO '/tmp/x.csv'", "SELECT 1; SELECT 2", "ATTACH 'x.db'", "INSTALL httpfs"):
        page.fill("[data-cells=sql] textarea", bad)
        page.click("[data-cells=sql] [data-run]")
        page.wait_for_selector("[data-cells=sql] .bi-bad", timeout=30000)
        assert re.search(r"DRS-907[03]", page.inner_text("[data-cells=sql] .bi-bad"))
    page.fill("[data-cells=sql] textarea", "SELECT * FROM read_csv('http://127.0.0.1:1/x.csv')")
    page.click("[data-cells=sql] [data-run]")
    page.wait_for_selector("[data-cells=sql] .bi-bad", timeout=30000)
    assert re.search(r"DRS-907[03]", page.inner_text("[data-cells=sql] .bi-bad"))
    quiet(page)

    # a runaway query is stopped and the engine starts again
    page.fill("[data-cells=sql] textarea", "SELECT sum(a.i * b.i) FROM range(400000) a(i), range(400000) b(i)")
    page.click("[data-cells=sql] [data-run]")
    page.wait_for_function("() => /DRS-9072/.test(document.querySelector('[data-cells=sql]').textContent)", timeout=60000)
    page.fill("[data-cells=sql] textarea", "SELECT count(*) AS n FROM extract")
    page.click("[data-cells=sql] [data-run]")
    page.wait_for_selector('[data-cells=sql] [data-engine="duckdb-wasm"]', timeout=90000)
    assert int(grid(page, "[data-cells=sql]")[0][0]) > 0
    clean(page)
    page.close()


@pytest.mark.skipif(not DUCK, reason="needs DuckDB-Wasm: tools/fetch-duckdb-wasm.sh")
def test_when_duckdb_cannot_start_the_console_does_it(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    page.route("**/duckdb-browser.mjs", lambda route: route.abort())                              # the engine cannot start in this tab
    open_extract(page)
    page.click('.bx-chip[data-field="desk"] [data-to="rows"]')
    page.click('.bx-chip[data-field="notional"] [data-to="values"]')
    page.wait_for_function("() => document.querySelector('[data-pivot-meta]').dataset.engine === 'js'", timeout=120000)
    assert page.evaluate("document.body.dataset.duckFailed") is not None
    page.click("#bxT-sql")
    page.fill("[data-cells=sql] textarea", "SELECT desk, SUM(notional) AS n FROM extract GROUP BY desk ORDER BY desk")
    page.click("[data-cells=sql] [data-run]")
    page.wait_for_selector('[data-cells=sql] [data-engine="server"]', timeout=60000)
    assert len(grid(page, "[data-cells=sql]")) >= 2
    page.close()


@pytest.mark.skipif(not DUCK, reason="needs DuckDB-Wasm: tools/fetch-duckdb-wasm.sh")
def test_a_slice_and_a_sql_cell_become_visuals_in_a_new_and_an_existing_report(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    s, mine = api(page, "POST", "/workspaces/me/items", {"kind": "report", "title": "Mine", "body": CHART_REPORT.format(name="mine")})
    assert s in (200, 201), mine
    open_extract(page)
    page.click('.bx-chip[data-field="desk"] [data-to="rows"]')
    page.click('.bx-chip[data-field="mtm"] [data-to="values"]')
    page.click('.bx-chip[data-field="currency"] [data-to="filters"]')
    page.select_option('[data-zone="filters"] select', "USD")
    page.wait_for_selector("[data-pivot] table")
    # a new report draft
    page.click("[data-promote-slice]")
    page.wait_for_selector("[data-promote-slice] + .bi-near [data-promoted-link]", timeout=30000)
    page.wait_for_function("() => /Report draft saved/.test(document.querySelector('[data-promote-slice] + .bi-near').textContent)")
    href = page.get_attribute("[data-promoted-link]", "href")
    s, made = api(page, "GET", "/items/" + href.split("/bi/i/")[1].replace("%3A", ":"))
    assert "type: matrix" in made["body"] and "trade.currency" in made["body"] and "USD" in made["body"] and "MTM" in made["body"]
    # an existing one, from a SQL cell
    page.reload()
    open_extract(page)
    page.wait_for_function("() => document.querySelectorAll('[data-promote-target] option').length > 1", timeout=30000)
    page.select_option("[data-promote-target]", mine["id"])
    page.click("#bxT-sql")
    page.fill("[data-cells=sql] textarea", "SELECT desk, SUM(mtm) FROM extract WHERE currency = 'EUR' GROUP BY desk")
    page.click("[data-promote-report]")
    page.wait_for_function("() => /added to the report draft/.test(document.querySelector('[data-promote-report] + .bi-near').textContent)", timeout=30000)
    s, edited = api(page, "GET", "/items/" + mine["id"])
    assert s == 200 and edited["body"].count("- id:") >= 3 and "type: matrix" in edited["body"] and "EUR" in edited["body"] and "mtm-chart" in edited["body"], edited["body"]
    clean(page)
    page.close()


# ---- phone ------------------------------------------------------------------------------------------------------------------------------
@pytest.mark.skipif(not DUCK, reason="needs DuckDB-Wasm: tools/fetch-duckdb-wasm.sh")
def test_the_new_controls_are_finger_sized_on_a_phone(stack, browser):
    page = sign_in(browser, "wes", "/bi", viewport={"width": 390, "height": 800}, has_touch=True, is_mobile=True)
    open_extract(page)
    page.click("#bxT-sql")
    small = page.evaluate("""() => [...document.querySelectorAll('[data-engine-pick], [data-promote-target], [data-promote-page], [data-promote-slice], [data-promote-report], [data-run]')]
      .filter(e => e.offsetParent !== null).map(e => [e.getAttribute('data-engine-pick') !== null ? 'engine' : e.className.slice(0, 20) + (e.dataset.promoteReport !== undefined ? ' promote-report' : ''), Math.round(e.getBoundingClientRect().height)]).filter(x => x[1] < 44)""")
    assert small == [], small
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    page.close()
