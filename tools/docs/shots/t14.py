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

"""Pictures of tutorial 14, Python and Analyse (docs/tutorials/14-python-and-analyse.md), written to docs/tutorials/img/t14/.

A Python measure and a Python visual in a report (the shipped ones, then one of your own); then Analyse: an extract of a dataset, a slice, a SQL cell, a Python cell
that draws a chart, a saved notebook, and the promotion of a cell to a visual and of a slice to a report draft."""
from __future__ import annotations

from _bi import bi, clean_items, quiet_inbox
from _tut import OUT, ROOT, SERVER_ENV, elem_shot, go, login, page_shot  # noqa: F401
from shotlib import Ctx, register

shot = register("t14", OUT / "t14")
DATA = ROOT / "docs" / "tutorials" / "data"
ITEM, REPORT = "Notional by desk chart", "Desks in Python"
WORKSPACE = "economics-desk"
CHART = ("g = df.groupby('desk', as_index=False)['notional'].sum()\n"
         "result = {'title': {'text': 'Notional by desk'}, 'xAxis': {'type': 'category', 'data': list(g['desk'])}, 'yAxis': {'type': 'value'}, "
         "'series': [{'type': 'bar', 'data': [float(v) for v in g['notional']]}]}")


def open_py(c: Ctx, item: str):
    """Opens a report with Python visuals: they run when the page opens (a worker per visual), so give them a few seconds."""
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(f"{c.base}/bi/r/{item}")
    c.page.wait_for_selector("[data-ready=yes]", timeout=30000)
    c.page.wait_for_timeout(12000)


def python_drawn(c: Ctx):
    c.page.wait_for_timeout(500)


@shot("01-python-report.jpg")
def shipped_report(c: Ctx):
    login(c, height=900)
    quiet_inbox(c)
    open_py(c, "file:report:python-desk-risk")
    python_drawn(c)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("01-python-report.jpg")


@shot("02-python-item.jpg", also=("03-check-python.jpg", "04-desks-in-python.jpg"))
def own_python(c: Ctx):
    login(c, height=900)
    quiet_inbox(c)
    clean_items(c, ITEM, REPORT)
    cat = bi(c, "GET", "/catalogue")
    for it in (cat.get("items", []) if isinstance(cat, dict) else cat):
        if it["title"] == ITEM and not it.get("shipped"):
            bi(c, "DELETE", "/items/" + it["id"])
    if not any(w["id"] == WORKSPACE for w in bi(c, "GET", "/workspaces")):
        bi(c, "POST", "/workspaces", {"name": "Economics desk", "description": "Reports on the world economy"})
    made = bi(c, "POST", f"/workspaces/{WORKSPACE}/items", {"kind": "python", "title": ITEM, "body": (DATA / "notional-by-desk.python.yaml").read_text()})
    go(c, "/bi/i/" + made["id"], wait="[data-meta]", settle=1500)
    c.save("02-python-item.jpg")
    c.page.get_by_role("button", name="Check the Python").click()
    c.page.wait_for_timeout(3500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("03-check-python.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 420})
    bi(c, "POST", f"/items/{made['id']}/publish", {"note": "a Python visual"})
    report = bi(c, "POST", "/workspaces/me/items", {"kind": "report", "title": REPORT, "body": (DATA / "desk-python.report.yaml").read_text()})
    open_py(c, report["id"])
    python_drawn(c)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("04-desks-in-python.jpg")


def open_extract(c: Ctx):
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(c.base + "/bi/explore")
    c.page.wait_for_function("() => document.querySelectorAll('[data-dataset] option').length > 1", timeout=30000)
    c.page.select_option("[data-dataset]", "file:dataset:trades-basic")
    c.page.wait_for_selector("[data-fields] input[name=f]", timeout=30000)


@shot("05-analyse-start.jpg", also=("06-slice.jpg", "07-sql.jpg", "08-python-cell.jpg", "09-saved.jpg", "10-promote-visual.jpg", "11-promote-slice.jpg"))
def analyse(c: Ctx):
    login(c, height=900)
    quiet_inbox(c)
    listing = bi(c, "GET", "/workspaces/me/items")
    for it in (listing if isinstance(listing, list) else listing.get("items", [])):
        if it["title"].startswith(("Slice of ", "Analyse cell", "Desk notional, first look")):
            bi(c, "DELETE", "/items/" + it["id"])
    open_extract(c)
    c.save("05-analyse-start.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
    c.page.click("[data-open-go]")
    c.page.wait_for_function("() => /rows,/.test(document.querySelector('[data-info]').textContent)", timeout=60000)
    c.page.click('.bx-chip[data-field="desk"] [data-to="rows"]')
    c.page.click('.bx-chip[data-field="currency"] [data-to="columns"]') if c.page.locator('.bx-chip[data-field="currency"]').count() else c.page.click('.bx-chip[data-field="book"] [data-to="columns"]')
    c.page.click('.bx-chip[data-field="notional"] [data-to="values"]')
    c.page.wait_for_selector("[data-pivot] table")
    c.page.wait_for_timeout(500)
    elem_shot(c, "06-slice.jpg", "#bx", pad=6)
    c.page.click("[data-promote-slice]")
    c.page.wait_for_timeout(2500)
    c.save("11-promote-slice.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
    c.page.click("#bxT-sql")
    c.page.fill("[data-cells=sql] textarea", "SELECT desk, COUNT(*) AS trades, SUM(notional) AS notional\nFROM extract\nGROUP BY desk\nORDER BY notional DESC")
    c.page.click("[data-cells=sql] [data-run]")
    c.page.wait_for_selector("[data-cells=sql] table")
    c.page.wait_for_timeout(500)
    elem_shot(c, "07-sql.jpg", "[data-pane=sql]", pad=6)
    c.page.click("#bxT-python")
    c.page.fill("[data-cells=python] textarea", CHART)
    c.page.click("[data-cells=python] [data-run]")
    c.page.wait_for_selector("[data-cells=python] [data-ready]", timeout=60000)
    c.page.wait_for_timeout(800)
    elem_shot(c, "08-python-cell.jpg", "[data-pane=python]", pad=6)
    c.page.click('[data-cells=python] [data-promote="visual"]')
    c.page.wait_for_timeout(2500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("10-promote-visual.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
    c.page.click("[data-save]")
    c.page.fill("input[name=title], [data-save-title]", "Desk notional, first look") if c.page.locator("input[name=title], [data-save-title]").count() else None
    c.page.click("[data-save-go]")
    c.page.wait_for_selector("[data-saved]:not([hidden])", timeout=30000)
    c.page.wait_for_timeout(600)
    c.save("09-saved.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})


DERIVED = "file:dataset:desk-pnl-vs-exposure-py"
NOTEBOOK = "file:notebook:pnl-vs-exposure-pandas"


@shot("12-derived-dataset.jpg")
def derived_dataset(c: Ctx):
    """The derived sample dataset: its sources, the Python Transform that reads them, and Run the Transform now with the columns it returned."""
    login(c, height=900)
    quiet_inbox(c)
    go(c, "/bi/d/" + DERIVED, wait="[data-built-sec]:not([hidden])", settle=1500)
    c.page.click("[data-run-transform]")
    c.page.wait_for_function("() => /rows/.test(document.querySelector('[data-run-result]').textContent)", timeout=90000)
    c.page.wait_for_timeout(600)
    elem_shot(c, "12-derived-dataset.jpg", "[data-built-sec]", pad=10)


@shot("13-analyse-extracts.jpg", also=("14-analyse-join.jpg", "15-promote-transform.jpg"))
def analyse_extracts(c: Ctx):
    """The sample notebook: two extracts of two datasets, joined in pandas in one cell, and the cell promoted to a Python Transform draft."""
    login(c, height=900)
    quiet_inbox(c)
    listing = bi(c, "GET", "/workspaces/me/items")
    for it in (listing if isinstance(listing, list) else listing.get("items", [])):
        if it["title"].startswith("Join of P&L and exposure"):
            bi(c, "DELETE", "/items/" + it["id"])
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(c.base + "/bi/explore?item=" + NOTEBOOK)
    c.page.wait_for_selector("[data-extra='exposure']", timeout=60000)
    c.page.wait_for_function("() => /rows/.test(document.querySelector('[data-info]').textContent)", timeout=60000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("13-analyse-extracts.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
    c.page.click("#bxT-python")
    c.page.click("[data-cells=python] [data-run]")
    c.page.wait_for_selector("[data-cells=python] .bx-out table", timeout=90000)
    c.page.wait_for_timeout(800)
    elem_shot(c, "14-analyse-join.jpg", "[data-pane=python]", pad=6)
    c.page.fill("input[name=title]", "Join of P&L and exposure")
    c.page.click('[data-cells=python] [data-promote="transform"]')
    c.page.wait_for_function("() => /Python transform draft saved/.test(document.querySelector('[data-msg]').textContent)", timeout=30000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("15-promote-transform.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 300})


PY_REPORT = "file:report:python-desk-risk"


@shot("16-vega-lite.jpg", also=("17-python-vega.jpg",))
def vega_visuals(c: Ctx):
    """A vega-lite visual written in the report (its spec in the YAML, its data the rows of the wells), and a Python visual that returns a Vega-Lite specification."""
    login(c, height=900)
    quiet_inbox(c)
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(f"{c.base}/bi/r/{PY_REPORT}/vega")
    c.page.wait_for_selector('[data-visual="mtm-by-desk"] .bi-vega[data-ready="1"] canvas', timeout=90000)
    c.page.wait_for_timeout(800)
    elem_shot(c, "16-vega-lite.jpg", '[data-visual="mtm-by-desk"]', pad=8)
    c.page.goto(f"{c.base}/bi/r/{PY_REPORT}/risk")
    c.page.wait_for_selector('[data-visual="strip"] .bi-vega[data-ready="1"] canvas', timeout=120000)
    c.page.wait_for_timeout(800)
    elem_shot(c, "17-python-vega.jpg", '[data-visual="strip"]', pad=8)


@shot("18-python-tests.jpg")
def python_tests(c: Ctx):
    """Check on a Python item that ships tests: each is run in the sandbox and shown as passed, failed or could not run."""
    login(c, height=900)
    quiet_inbox(c)
    go(c, "/bi/i/file:python:desk-pnl-volatility", wait="[data-meta]", settle=1500)
    c.page.get_by_role("button", name="Check the Python").click()
    c.page.wait_for_selector("[data-py-tests]", timeout=60000)
    c.page.wait_for_timeout(600)
    elem_shot(c, "18-python-tests.jpg", "[data-warnings]", pad=12)


REPORT_DRAFT = "Desk views"
SPAN = """([a, b]) => { const r = (s) => { const e = document.querySelector(s); const x = e.getBoundingClientRect(); return {l: x.left, t: x.top + window.scrollY, r: x.right, b: x.bottom + window.scrollY}; };
  const p = r(a), q = r(b); return {x: Math.min(p.l, q.l), y: Math.min(p.t, q.t), width: Math.max(p.r, q.r) - Math.min(p.l, q.l), height: Math.max(p.b, q.b) - Math.min(p.t, q.t)}; }"""


def span_shot(c: Ctx, name: str, top: str, bottom: str, pad: int = 10):
    """A picture of the page from the top of one element to the bottom of another (from the top of the page, so the sticky bar is not in the middle of it)."""
    c.page.evaluate("window.scrollTo(0, 0)")
    c.page.wait_for_timeout(300)
    page_shot(c, name, c.page.evaluate(SPAN, [top, bottom]), pad=pad)


@shot("19-analyse-browser.jpg", also=("20-promote-existing.jpg",))
def analyse_in_the_browser(c: Ctx):
    """Slice and SQL worked out by DuckDB in the browser (the line under each says so), then a SQL cell promoted into an existing report draft."""
    login(c, height=900)
    quiet_inbox(c)
    clean_items(c, REPORT_DRAFT, "Slice of file:dataset:trades-basic", "SQL of file:dataset:trades-basic")
    bi(c, "POST", "/workspaces/me/items", {"kind": "report", "title": REPORT_DRAFT, "body": (DATA / "desk-python.report.yaml").read_text()})
    open_extract(c)
    c.page.click("[data-open-go]")
    c.page.wait_for_function("() => /rows,/.test(document.querySelector('[data-info]').textContent)", timeout=60000)
    c.page.click('.bx-chip[data-field="desk"] [data-to="rows"]')
    c.page.click('.bx-chip[data-field="notional"] [data-to="values"]')
    c.page.wait_for_function("() => document.querySelector('[data-pivot-meta]').dataset.engine === 'duckdb-wasm'", timeout=120000)
    c.page.wait_for_timeout(600)
    span_shot(c, "19-analyse-browser.jpg", ".bx-engine", "[data-pane=slice]")
    c.page.wait_for_function("() => document.querySelectorAll('[data-promote-target] option').length > 1", timeout=30000)
    c.page.locator("[data-promote-target]").select_option(label=f"the report: {REPORT_DRAFT}")
    c.page.click("#bxT-sql")
    c.page.fill("[data-cells=sql] textarea", "SELECT desk, COUNT(*) AS trades, SUM(notional) AS notional\nFROM extract\nGROUP BY desk\nORDER BY notional DESC")
    c.page.click("[data-cells=sql] [data-run]")
    c.page.wait_for_selector('[data-cells=sql] [data-engine="duckdb-wasm"]', timeout=60000)
    c.page.click("[data-promote-report]")
    c.page.wait_for_function("() => /added to the report draft/.test(document.querySelector('[data-msg]').textContent)", timeout=30000)
    c.page.wait_for_timeout(600)
    span_shot(c, "20-promote-existing.jpg", "[data-msg]", "[data-pane=sql]")
    clean_items(c, REPORT_DRAFT)


CALC_VIEW = "/v/trade/MX-20000001"


def open_calc(c: Ctx, code: str):
    """Opens Calc on a trade's view, types `code` and runs it (Ctrl+Enter); waits for the run to finish."""
    go(c, CALC_VIEW, height=900, wait="[data-panel]", settle=1500)
    c.page.keyboard.press("Alt+c")
    c.page.wait_for_selector("#calcDrawer:not([hidden])", timeout=10000)
    c.page.evaluate("(code) => document.querySelector('#calcDrawer .CodeMirror').CodeMirror.setValue(code)", code)         # the editor is CodeMirror's
    c.page.click("[data-calc-run]")
    c.page.wait_for_function("() => /^(Done|Failed)/.test(document.querySelector('[data-calc-status]').textContent)", timeout=180000)


@shot("21-calc-bi.jpg", also=("22-calc-walker.jpg",))
def calc_bi(c: Ctx):
    """Calc's BI client (drishti_bi.query: the engine's answer as you, a DataFrame) and Graphic Walker over that frame (drishti_bi.walk)."""
    login(c, height=900)
    quiet_inbox(c)
    open_calc(c, "import drishti_bi\ndf = await drishti_bi.query_async('file:dataset:trades-basic', measures=['MTM', 'Trades'], by=['trade.desk'])\nshow(df, 'MTM by desk')")
    assert "Done" in c.page.inner_text("[data-calc-status]"), c.page.inner_text("[data-calc-status]")
    c.page.wait_for_timeout(500)
    c.save("21-calc-bi.jpg", "#calcDrawer")
    open_calc(c, "import drishti_bi\ndf = await drishti_bi.query_async('file:dataset:trades-basic', measures=['MTM'], by=['trade.desk', 'trade.currency'])\ndrishti_bi.walk(df, 'MTM by desk and currency')")
    c.page.wait_for_selector("[data-calc-walker]", timeout=30000)
    frame = c.page.frame_locator("[data-calc-walker]")
    frame.locator("#walker[data-ready='1']").wait_for(timeout=60000)
    c.page.wait_for_timeout(2500)
    c.save("22-calc-walker.jpg", "#calcDrawer")


STARTER = "Mean notional (starter)"


@shot("23-python-starter.jpg", also=("24-python-starter-editor.jpg",))
def python_starter(c: Ctx):
    """New item, kind python: a form that asks what it makes (a measure, a visual or a table) and opens the new item in its editor with a valid, commented body."""
    login(c, height=900)
    clean_items(c, STARTER)
    p = c.page
    go(c, "/bi/w/me", wait="[data-new-item]:not([hidden])", settle=800)
    p.select_option('[data-new-item] [name="kind"]', "python")
    p.select_option('[data-new-item] [name="pyinterface"]', "measure")
    p.fill('[data-new-item] [name="title"]', STARTER)
    p.fill('[data-new-item] [name="pyinputs"]', "notional")
    p.evaluate("document.querySelector('[data-new-item]').scrollIntoView({block: 'center'})")
    p.wait_for_timeout(400)
    c.save("23-python-starter.jpg", locator="[data-new-item]")
    p.click("[data-create]")
    p.wait_for_selector("[data-edit]:not([hidden])", timeout=30000)
    p.get_by_role("button", name="Check the Python").click()
    p.wait_for_selector("[data-py-check]", timeout=30000)
    p.evaluate("document.querySelector('[data-edit] textarea').scrollIntoView({block: 'center'})")
    p.wait_for_timeout(500)
    c.save("24-python-starter-editor.jpg", locator="[data-edit] label:has(textarea)")
    clean_items(c, STARTER)
