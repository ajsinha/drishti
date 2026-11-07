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

"""The scenario matrix, SMAT, in a real browser (VAR_GUIDE.md section 3.9; the approved mock-up scenario-matrix-B), over a stand-in
server whose VaR engine answers a position's 509 scenarios on its last 20 business dates (the oldest refused, DRS-5203): the matrix comes
as Arrow, only the rows in view are in the table (virtualised), each date's VaR scenario and ES tail are outlined and marked V / E, the
gap's column says its code and its cells show no number; the chart shows the selected date's scenarios in day order (a click selects
one) or the selected row across the dates, in Line, Dots, Area or Bars (remembered), against -VaR or the PV at VaR with a legend that
matches; one date open says so; the selected row stays visible; the details give the cell's rank, PV, FX, days in the tail and source; PV, the change and Only tail scenarios redraw; By trade asks for the trades; the CSV and Arrow exports download.
Every theme draws the heat and the outline; a phone gets the glance form without sideways scrolling; no request leaves the console's host.
Skipped when Playwright or its Chromium is missing."""
import random
import socket
import threading
import time
from datetime import date, timedelta

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from risk_elements import chart, chart_ready  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from test_page_widths_browser import AUDIT  # noqa: E402

THEMES = ["terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"]
N, DAYS = 509, 20


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _business(start: date, n: int) -> list[str]:
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d.isoformat())
        d += timedelta(days=1)
    return out


def _matrix() -> dict:
    """509 scenario days by 20 business dates; the oldest date refused; the newest date's worst scenario is the 300th day."""
    rnd = random.Random(7)
    scen = _business(date(2024, 9, 2), N)
    dates = _business(date(2026, 9, 3), DAYS)
    base = [rnd.gauss(0, 1000) for _ in range(N)]
    base[300] = -9000.0
    rows, cols = [], []
    for i, d in enumerate(dates):
        if i == 0:
            cols.append({"date": d, "source": "engine", "gap": "DRS-5203 the VaR inputs of " + d + " are not loaded: 2 batch ids", "code": "DRS-5203",
                         "scenarios": 0})
            continue
        v = [round(x * (1 + i / 40), 2) for x in base]
        order = sorted(range(N), key=lambda j: v[j])
        cols.append({"date": d, "source": "open" if i == DAYS - 1 else "lake", "sourceNote": "read", "scenarioSet": "HS-509", "gap": None,
                     "scenarios": N, "var": -v[order[5]], "es": -sum(v[j] for j in order[:13]) / 13, "k": 6, "esCount": 13,
                     "varScenario": scen[order[5]], "basePv": -141_000_000.0 - i * 100_000, "valueType": "PV", "fx": "USD (no conversion)",
                     "batch": f"B-{d}-01", "dataVersion": "gen 4", "link": "/v/scenario-pv/SPV-POS-X-HS-509",
                     "tail": [{"scenario": scen[j], "rank": r + 1, "pnl": v[j]} for r, j in enumerate(order[:13])], "values": v})
    for j, s in enumerate(scen):
        r = {"scenario": s}
        for c in cols:
            r[c["date"]] = None if c["gap"] else c["values"][j]
        rows.append(r)
    for c in cols:
        c.pop("values", None)
    return {"id": "POS-X", "scope": "POS:POS-X", "kind": "position", "label": "POS-X", "entity": "POS-X", "supplied": False, "currency": "USD",
            "days": DAYS, "asked": DAYS, "maxDays": 60, "confidence": 0.99, "esConfidence": 0.975, "horizon": 1, "window": "all", "by": "scenario",
            "labels": {"var": "VaR 99% 1-day", "es": "ES 97.5% 1-day", "basePv": "Base PV"}, "dates": cols, "rows": rows}


MATRIX = _matrix()


class SmatBackend(FakeBackend):
    """The stand-in server with a VaR engine that answers the scenario matrix: JSON, or the Arrow stream when asked (as the server)."""

    async def var(self, method, path, ident, body=None, raw=False, accept="", **params):
        import json
        if path != "/matrix":
            return {}
        if params.get("by") == "trade":
            dates = [c["date"] for c in MATRIX["dates"]]
            ans = {**MATRIX, "by": "trade", "scenario": params.get("scenario"),
                   "dates": [{"date": d, "source": "open", "gap": None if i else "DRS-5203 refused", "total": -100.0 * i, "rank": 3}
                             for i, d in enumerate(dates)],
                   "rows": [{"trade": t, "label": t, "counterparty": "CP-1", **{d: (None if i == 0 else -50.0 * i) for i, d in enumerate(dates)}}
                            for t in ("T-1", "T-2")]}
        elif params.get("id") == "POS-ONE":                       # one date open of the 20 asked
            ans = {**MATRIX, "id": "POS-ONE", "scope": "POS:POS-ONE", "days": 1, "dates": MATRIX["dates"][-1:],
                   "rows": [{"scenario": r["scenario"], MATRIX["dates"][-1]["date"]: r[MATRIX["dates"][-1]["date"]]} for r in MATRIX["rows"]]}
        else:
            ans = MATRIX
        if raw and "arrow" in (accept or ""):            # asked as Arrow: the stream the server's ArrowAnswers writes (the console passes it on)
            from arrow_server_stream import server_stream
            content = server_stream(ans, "records", "rows")
            if content is not None:
                return content, {"content-type": "application/vnd.apache.arrow.stream"}
        return (json.dumps(ans).encode(), {"content-type": "application/json"}) if raw else ans


@pytest.fixture(scope="module")
def smat_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = SmatBackend()
    app.state.editions.learn({"product": "Drishti Risk", "edition": {"name": "risk", "title": "Drishti Risk"}})
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


def _page(ctx, url):
    page = ctx.new_page()
    page.outside = []
    page.on("request", lambda r: page.outside.append(r.url) if not r.url.startswith((url, "data:", "blob:")) else None)
    return page


#: a row of the matrix as the grid holds it (it draws only the rows and columns in view): its key, its row class, and each column's
#: id, text, classes (the tail outline, the selection) and the drawn cell's text (the value with its V or E)
ROW_JS = """(i) => {
  const g = document.querySelector('[data-sm-scroll] drishti-grid').grid, m = g.model, cols = m.shownColumns(), x = m.rowAt(i);
  if (!x || !x.row) { return null; }
  return { key: g.o.getRowData(x.row).key, rowClass: g.o.getRowClass(x.row) || '', cells: cols.map((c, j) => ({ id: c.id, text: m.cellText(i, j),
    cls: c.cellClass ? (c.cellClass(m.cellValue(i, j), x.row) || '') : '',
    drawn: c.cellRenderer ? ((c.cellRenderer({ value: m.cellValue(i, j), text: '', row: x.row, doc: document }) || {}).textContent || '') : '' })) };
}"""
#: the matrix's rows: how many, their keys in order, the selected one's index (-1: none)
ROWS_JS = """() => {
  const g = document.querySelector('[data-sm-scroll] drishti-grid').grid, m = g.model, keys = [];
  let sel = -1;
  for (let i = 0; i < m.rowCount(); i++) { const x = m.rowAt(i); keys.push(g.o.getRowData(x.row).key); if (g.o.getRowClass(x.row)) { sel = i; } }
  return { n: keys.length, keys: keys, sel: sel };
}"""


def _row(page, i):
    return page.evaluate(ROW_JS, i)


def _rows(page):
    return page.evaluate(ROWS_JS)


def _drawn_rows(page):
    """The matrix rows in the DOM (the grid draws those in view)."""
    return page.locator("[data-sm-scroll] .dg-row[data-key]")


def _cell(page, key, col):
    """The drawn cell of a row (by key) and a date column (by its index in the matrix's dates)."""
    return page.locator(f"[data-sm-scroll] .dg-row[data-key='{key}'] .dg-cell[data-c='{col + 1}']")


def _open(page, url, ident="POS-X"):
    page.goto(url + "/smat?id=" + ident + "&days=20", wait_until="load")
    page.wait_for_function("() => document.querySelectorAll('[data-sm-scroll] .dg-row[data-key]').length > 10 || "
                           "getComputedStyle(document.querySelector('.sm-matrix-card')).display === 'none' && "
                           "document.querySelector('[data-sm-kv] dt') !== null", timeout=20000)
    page.wait_for_function(chart_ready("[data-sm-chart]") + " || !!document.querySelector('[data-sm-chart] .sm-note')", timeout=20000)


def _series(page, name):
    """A series of the chart by name (None when it is not drawn)."""
    return next((s for s in chart(page, "[data-sm-chart]")["series"] if s["name"] == name), None)


def _click_point(page, category, value):
    """A real click on the chart's point at (category, value): the engine says where it is drawn."""
    at = page.evaluate("""([c, v]) => { const e = document.querySelector('[data-sm-chart] drishti-chart').chart.engine;
        return e.convertToPixel({ seriesIndex: 0 }, [c, v]); }""", [category, value])
    box = page.locator("[data-sm-chart] .dc-canvas").bounding_box()
    page.mouse.click(box["x"] + at[0], box["y"] + at[1])


def test_the_matrix_arrives_as_arrow_virtualised_with_its_tail_and_gap(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000})
    page = _page(ctx, smat_console)
    try:
        _open(page, smat_console)
        assert "509 scenarios × 20 dates · read as Arrow" in page.locator("[data-sm-size]").inner_text()
        rendered = _drawn_rows(page).count()
        assert 10 < rendered < 120, rendered                                           # only the rows in view: virtualised
        assert _rows(page)["n"] == N
        first = _row(page, 0)
        gap = first["cells"][1]
        assert "DRS-5203" in page.locator("[data-sm-scroll] .dg-hcell[data-c='1']").inner_text()   # the refused date's heading says its code
        assert gap["text"] == "—" and gap["drawn"] == "—"                               # the refused date: no number, never zero
        # sorted worst on the newest date: the first row is its worst scenario, outlined and marked on every date it is in the tail
        assert first["key"] == MATRIX["rows"][300]["scenario"]
        dates = first["cells"][1:]
        assert sum("sm-tail" in c["cls"] for c in dates) == DAYS - 1 and sum(c["drawn"][-1:] in ("V", "E") for c in dates) == DAYS - 1
        assert any("sm-var" in c["cls"] for i in range(20) for c in _row(page, i)["cells"])   # the VaR scenario of some date: V
        foot = page.locator("[data-sm-tfoot]").inner_text()
        assert "VaR 99% 1-day (rank)" in foot and "ES 97.5% 1-day" in foot and "Base PV" in foot and "#6" in foot
        # the newest date's VaR scenario is selected and charted against -VaR
        assert _series(page, "scenario P&L")["type"] == "line" and _series(page, "−VaR") is not None
        kv = page.locator("[data-sm-kv]").inner_text()
        for words in ("Selected", "P&L", "6th worst of 509", "Scenario PV", "base", "FX used", "USD (no conversion)", "Days in tail", "Source", "B-"):
            assert words in kv, words
        assert page.locator("[data-sm-kv] a").get_attribute("href").startswith("/asof?d=")
        # scroll to the bottom: the last rows are drawn, the first ones are gone
        page.locator("[data-sm-scroll] .dg-scroll").evaluate("e => { e.scrollTop = e.scrollHeight; e.dispatchEvent(new Event('scroll')); }")
        page.wait_for_function("() => !document.querySelector(\"[data-sm-scroll] .dg-row[data-key='" + MATRIX["rows"][300]["scenario"] + "']\")")
        assert _drawn_rows(page).count() < 120
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_select_a_row_switch_the_values_filter_and_export(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000}, accept_downloads=True)
    page = _page(ctx, smat_console)
    try:
        _open(page, smat_console)
        key = _row(page, 3)["key"]
        _cell(page, key, 4).click()                                                            # the 5th date's cell (the matrix's 6th column)
        assert _rows(page)["sel"] == 3 and page.locator(f"[data-sm-scroll] .dg-row[data-key='{key}']").is_visible()   # the selected row stays in the matrix
        assert page.locator("[data-sm-chart-title]").inner_text() == "POS-X on " + MATRIX["dates"][4]["date"] + ":"   # that date's scenarios
        assert key in page.locator("[data-sm-kv]").inner_text()
        assert sum("sm-sel" in c["cls"] for c in _row(page, 3)["cells"]) == 1
        assert MATRIX["dates"][4]["date"] in page.locator("[data-sm-kv]").inner_text()
        before = _row(page, 0)["cells"][5]["text"]
        page.locator("[data-sm-mode='pv']").click()
        assert page.locator("[data-sm-mode='pv']").get_attribute("aria-pressed") == "true"
        pv = _row(page, 0)["cells"][5]["text"]
        assert pv != before and pv.endswith("m")                                                 # base PV (-141m) plus the P&L
        page.locator("[data-sm-mode='change']").click()
        assert _row(page, 0)["cells"][2]["text"] == "—"                                         # the day before was refused
        page.locator("[data-sm-mode='pnl']").click()
        page.locator("[data-sm-tailonly]").click()
        n = _rows(page)["n"]
        assert 13 <= n < 100 and "scenarios" in page.locator("[data-sm-size]").inner_text()
        page.locator("[data-sm-sort]").select_option("day")
        keys = _rows(page)["keys"]
        assert keys == sorted(keys, reverse=True)
        _drawn_rows(page).first.locator(".dg-cell").nth(1).click()
        page.keyboard.press("ArrowDown")                                                         # the grid's keys move the selection
        page.wait_for_function("(" + ROWS_JS + ")().sel === 1")
        assert _drawn_rows(page).nth(1).is_visible()
        csv = page.locator("[data-sm-csv]").get_attribute("href")
        assert csv.startswith("/api/smat/matrix.csv?") and "id=POS-X" in csv
        with page.expect_download() as d:
            page.locator("[data-sm-csv]").click()
        assert d.value.suggested_filename.endswith(".csv")
        with page.expect_download() as d2:
            page.locator("[data-sm-arrow]").click()
        assert d2.value.suggested_filename.endswith(".arrow")
        page.locator("[data-sm-by='trade']").click()
        page.wait_for_function("() => { const r = document.querySelector('[data-sm-scroll] .dg-row[data-key]'); return r && r.dataset.key.startsWith('T-'); }")
        assert _rows(page)["n"] == 2
        assert "Position (this scenario)" in page.locator("[data-sm-tfoot]").inner_text()
        assert page.locator("[data-sm-chart-title]").inner_text().startswith("Trade T-")
        page.locator("[data-sm-by='scenario']").click()
        assert _rows(page)["n"] > 10
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_every_theme_draws_the_heat_and_the_outline(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    page = _page(ctx, smat_console)
    try:
        _open(page, smat_console)
        seen = set()
        key = _row(page, 0)["key"]
        for theme in THEMES:
            page.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
            cell = _cell(page, key, 1)                                                           # the worst row on the first open date: in its tail
            bg = cell.locator(".sm-v").evaluate("e => getComputedStyle(e).backgroundColor")
            ring = cell.evaluate("e => getComputedStyle(e).boxShadow")
            assert bg and bg != "rgba(0, 0, 0, 0)", (theme, bg)
            assert ring and ring != "none", (theme, ring)
            page.wait_for_function(chart_ready("[data-sm-chart]"))                              # the chart draws again in the theme
            seen.add(bg)
            assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), theme
        assert len(seen) > 1                                                           # the heat follows the theme
        assert not page.evaluate(AUDIT)
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_a_phone_gets_the_glance_form_without_sideways_scrolling(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    page = _page(ctx, smat_console)
    try:
        _open(page, smat_console)
        assert page.locator(".sm-matrix-card").is_hidden()
        assert page.locator("[data-sm-chart] drishti-chart").is_visible() and page.locator("[data-sm-pick]").is_visible()
        assert "in the tail on" in page.locator("[data-sm-glance]").inner_text()
        assert page.locator(".sm-phone-note").is_visible()
        page.locator("[data-sm-pick]").select_option(index=2)
        assert page.locator("[data-sm-pick]").input_value() in page.locator("[data-sm-kv]").inner_text()
        page.wait_for_timeout(150)
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        assert not page.outside, page.outside
    finally:
        ctx.close()


def _shot(page, name):
    """A picture of the chart card, only where a scratch/ folder sits beside the console (a working copy); never part of the repository."""
    out = CONSOLE.parent / "scratch"
    if out.is_dir():
        page.locator(".sm-side").screenshot(path=str(out / f"smat-{name}.png"))


def test_the_selected_var_row_stays_visible_and_the_legend_follows_the_mode(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000})
    page = _page(ctx, smat_console)
    try:
        _open(page, smat_console)
        newest = MATRIX["dates"][-1]
        sel = _rows(page)["sel"]
        row = _row(page, sel)
        assert row["key"] == newest["varScenario"] and row["rowClass"] == "sm-selrow"
        assert page.locator(f"[data-sm-scroll] .dg-row[data-key='{row['key']}']").is_visible()   # in view
        assert "sm-var" in row["cells"][-1]["cls"] and row["cells"][-1]["drawn"].endswith("V")      # the VaR row and its V
        ref, base = page.locator("[data-sm-key-ref]"), page.locator("[data-sm-key-base]")
        assert ref.is_visible() and page.locator("[data-sm-ref-label]").inner_text() == "−VaR" and base.is_hidden()
        assert _series(page, "−VaR") is not None and _series(page, "base PV") is None
        page.locator("[data-sm-mode='pv']").click()
        assert page.locator("[data-sm-ref-label]").inner_text() == "PV at VaR" and ref.is_visible() and base.is_visible()
        assert _series(page, "PV at VaR") is not None and _series(page, "base PV") is not None
        assert page.locator("[data-sm-chart-sub]").inner_text() == "scenario PV by scenario day"
        page.locator("[data-sm-mode='change']").click()
        assert ref.is_hidden() and base.is_hidden() and _series(page, "−VaR") is None and _series(page, "PV at VaR") is None
        assert _series(page, "change vs day before")["type"] == "line"
        page.locator("[data-sm-chart-view='dates']").click()                                     # the row across the dates
        assert ref.is_hidden() and _series(page, "−VaR") is None
        page.locator("[data-sm-mode='pv']").click()
        assert page.locator("[data-sm-ref-label]").inner_text() == "PV at VaR" and base.is_hidden()
        assert _series(page, "PV at VaR")["n"] == DAYS - 1
        page.locator("[data-sm-mode='pnl']").click()
        assert ref.is_visible() and page.locator("[data-sm-ref-label]").inner_text() == "−VaR"
        assert _series(page, "beyond VaR")["n"] >= 1                                             # its own date is at VaR: marked
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_the_scenarios_chart_is_chronological_and_a_click_selects(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000})
    page = _page(ctx, smat_console)
    try:
        _open(page, smat_console)
        newest = MATRIX["dates"][-1]
        assert page.locator("[data-sm-chart-view='scenarios']").get_attribute("aria-pressed") == "true"
        assert page.locator("[data-sm-chart-title]").inner_text() == "POS-X on " + newest["date"] + ":"
        assert page.locator("[data-sm-chart-sub]").inner_text() == "scenario P&L by scenario day"
        c = chart(page, "[data-sm-chart]")
        days = sorted(r["scenario"] for r in MATRIX["rows"])
        assert c["x"] == days and c["series"][0]["n"] == N                                       # every scenario day, in order
        assert _series(page, "in the tail")["n"] == 13 and _series(page, "the VaR scenario")["n"] == 1   # the ES tail, the VaR scenario
        tail = sorted(newest["tail"], key=lambda t: t["scenario"])
        target = next(t for t in tail if t["scenario"] != newest["varScenario"])
        _click_point(page, target["scenario"], target["pnl"])
        page.wait_for_function("(() => { const r = (" + ROWS_JS + ")(); return r.sel >= 0 && r.keys[r.sel] === %r; })()" % target["scenario"])
        assert page.locator(f"[data-sm-scroll] .dg-row[data-key='{target['scenario']}']").is_visible()   # selected and scrolled to
        assert target["scenario"] in page.locator("[data-sm-kv]").inner_text()
        page.locator("[data-sm-by='trade']").click()
        page.locator("[data-sm-scroll] .dg-row[data-key^='T-']").first.wait_for()
        assert page.locator("[data-sm-chart-view='scenarios']").is_disabled()
        assert page.locator("[data-sm-chart-title]").inner_text().startswith("Trade T-")
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_each_chart_type_draws_in_both_views_and_is_remembered(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000})
    page = _page(ctx, smat_console)
    try:
        _open(page, smat_console)
        assert page.locator("[data-sm-chart-type='line']").get_attribute("aria-pressed") == "true"
        for view, n, value, marked in (("scenarios", N, "scenario P&L", "in the tail"), ("dates", DAYS - 1, "this scenario", "beyond VaR")):
            page.locator(f"[data-sm-chart-view='{view}']").click()
            expect = {"line": ("line", "line"), "dots": ("line", "scatter"), "area": ("area", "line"), "bars": ("bar", "bar")}
            for kind, (chart_kind, series_type) in expect.items():
                button = page.locator(f"[data-sm-chart-type='{kind}']")
                button.focus()
                page.keyboard.press("Enter")                                                   # a button: the keyboard switches too
                assert button.get_attribute("aria-pressed") == "true", kind
                c = chart(page, "[data-sm-chart]")
                assert c["kind"] == chart_kind and c["series"][0]["type"] == series_type and c["series"][0]["n"] == n, (view, kind, c["series"][0])
                assert _series(page, "−VaR")["type"] == "line", (view, kind)                  # -VaR on every type, as a line
                assert _series(page, marked)["n"] >= 1, (view, kind)                           # still marked, by shape too
                assert "sm-key-" + kind in page.locator("[data-sm-key-row]").get_attribute("class")
                _shot(page, f"{view}-{kind}")
        page.reload(wait_until="load")
        page.wait_for_function(chart_ready("[data-sm-chart]"))
        assert page.locator("[data-sm-chart-type='bars']").get_attribute("aria-pressed") == "true"   # remembered in this browser
        assert page.locator("[data-sm-chart-view='dates']").get_attribute("aria-pressed") == "true"
        c = chart(page, "[data-sm-chart]")
        assert c["kind"] == "bar" and c["series"][0]["n"] == DAYS - 1 and c["x"][-1] == MATRIX["dates"][-1]["date"]
        page.locator("[data-sm-chart-view='scenarios']").click()
        page.locator("[data-sm-mode='pv']").click()
        assert chart(page, "[data-sm-chart]")["series"][0]["n"] == N and _series(page, "PV at VaR") is not None
        _shot(page, "scenarios-bars-pv")
        page.locator("[data-sm-chart-type='area']").click()
        page.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
        assert chart(page, "[data-sm-chart]")["kind"] == "area"
        _shot(page, "scenarios-area-pv-light")
        page.locator("[data-sm-chart-type='line']").click()
        _shot(page, "scenarios-line-pv-light")
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_one_date_open_shows_the_value_and_says_so(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000})
    page = _page(ctx, smat_console)
    try:
        page.goto(smat_console + "/smat?id=POS-ONE&days=20", wait_until="load")
        page.wait_for_function("(" + ROWS_JS + ")().sel >= 0")
        page.wait_for_function(chart_ready("[data-sm-chart]"))
        assert chart(page, "[data-sm-chart]")["series"][0]["n"] == N and page.locator("[data-sm-one]").count() == 0   # its scenarios: a full chart
        page.locator("[data-sm-chart-view='dates']").click()
        assert page.locator("[data-sm-one]").inner_text() == "Only 1 date is open; the chart needs two or more (20 asked)."
        assert chart(page, "[data-sm-chart]")["series"][0]["n"] == 1                             # the one value drawn
        page.locator("[data-sm-mode='pv']").click()
        c = chart(page, "[data-sm-chart]")
        assert c["series"][0]["n"] == 1 and _series(page, "PV at VaR")["n"] == 1 and c["yScale"]   # PV: no axis from zero
        _shot(page, "dates-one-date-pv")
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_the_matrix_and_the_chart_fill_the_window(browser, smat_console):
    for height in (900, 1400):
        ctx = browser.new_context(viewport={"width": 1440, "height": height})
        page = _page(ctx, smat_console)
        try:
            _open(page, smat_console)
            page.wait_for_timeout(200)
            box = page.locator("[data-sm-scroll]").bounding_box()
            legend = page.locator(".sm-legend").bounding_box()
            assert legend["y"] + legend["height"] <= height                 # the legend stays on screen
            assert height - (legend["y"] + legend["height"]) < 80           # and little room is left under it
            if height == 1400:
                assert box["height"] > 700                                  # a tall window: a tall matrix
                assert page.locator("[data-sm-chart] drishti-chart").bounding_box()["height"] > 300
        finally:
            ctx.close()
