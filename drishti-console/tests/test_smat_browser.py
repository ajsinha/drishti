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
gap's column says its code and its cells show no number; selecting a row charts it against -VaR with the cell's rank, PV, FX, days in
the tail and source; PV, the change and Only tail scenarios redraw; By trade asks for the trades; the CSV and Arrow exports download.
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
    """The stand-in server with a VaR engine that answers the scenario matrix (JSON; the console writes Arrow when asked)."""

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
        else:
            ans = MATRIX
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


def _open(page, url):
    page.goto(url + "/smat?id=POS-X&days=20", wait_until="load")
    page.wait_for_function("() => document.querySelectorAll('[data-sm-tbody] tr[data-key]').length > 10 || "
                           "getComputedStyle(document.querySelector('.sm-matrix-card')).display === 'none' && "
                           "document.querySelector('[data-sm-kv] dt') !== null", timeout=20000)


def test_the_matrix_arrives_as_arrow_virtualised_with_its_tail_and_gap(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000})
    page = _page(ctx, smat_console)
    try:
        _open(page, smat_console)
        assert "509 scenarios × 20 dates · read as Arrow" in page.locator("[data-sm-size]").inner_text()
        rendered = page.locator("[data-sm-tbody] tr[data-key]").count()
        assert 10 < rendered < 120, rendered                                           # only the rows in view: virtualised
        head = page.locator("[data-sm-thead] th").nth(1)
        assert "DRS-5203" in head.inner_text() and "no values" in head.get_attribute("title")
        first = page.locator("[data-sm-tbody] tr[data-key]").first
        assert first.locator("td").first.inner_text() == "—"                          # the refused date: no number, never zero
        # sorted worst on the newest date: the first row is its worst scenario, outlined and marked on every date it is in the tail
        assert first.get_attribute("data-key") == MATRIX["rows"][300]["scenario"]
        assert first.locator("td.sm-tail").count() == DAYS - 1 and first.locator("td .mk").count() == DAYS - 1
        assert page.locator("[data-sm-tbody] td.sm-var").count() >= 1                  # the VaR scenario of some date: V
        foot = page.locator("[data-sm-tfoot]").inner_text()
        assert "VaR 99% 1-day (rank)" in foot and "ES 97.5% 1-day" in foot and "Base PV" in foot and "#6" in foot
        # the newest date's VaR scenario is selected and charted against -VaR
        assert page.locator("[data-sm-chart] polyline.sm-line").count() >= 1 and page.locator("[data-sm-chart] polyline.sm-varline").count() >= 1
        kv = page.locator("[data-sm-kv]").inner_text()
        for words in ("Selected", "P&L", "6th worst of 509", "Scenario PV", "base", "FX used", "USD (no conversion)", "Days in tail", "Source", "B-"):
            assert words in kv, words
        assert page.locator("[data-sm-kv] a").get_attribute("href").startswith("/asof?d=")
        # scroll to the bottom: the last rows are drawn, the first ones are gone
        page.locator("[data-sm-scroll]").evaluate("e => { e.scrollTop = e.scrollHeight; e.dispatchEvent(new Event('scroll')); }")
        page.wait_for_function("() => !document.querySelector(\"[data-sm-tbody] tr[data-key='" + MATRIX["rows"][300]["scenario"] + "']\")")
        assert page.locator("[data-sm-tbody] tr[data-key]").count() < 120
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_select_a_row_switch_the_values_filter_and_export(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000}, accept_downloads=True)
    page = _page(ctx, smat_console)
    try:
        _open(page, smat_console)
        row = page.locator("[data-sm-tbody] tr[data-key]").nth(3)
        key = row.get_attribute("data-key")
        row.locator("td").nth(5).click()
        assert "sm-pick" in row.get_attribute("class")
        assert page.locator("[data-sm-chart-title]").inner_text() == "Scenario " + key
        assert page.locator("[data-sm-tbody] td.sm-sel").count() == 1
        assert MATRIX["dates"][5]["date"] in page.locator("[data-sm-kv]").inner_text()
        before = page.locator("[data-sm-tbody] tr[data-key]").first.locator("td").nth(5).inner_text()
        page.locator("[data-sm-mode='pv']").click()
        assert page.locator("[data-sm-mode='pv']").get_attribute("aria-pressed") == "true"
        pv = page.locator("[data-sm-tbody] tr[data-key]").first.locator("td").nth(5).inner_text()
        assert pv != before and pv.rstrip("VE").endswith("m")                                     # base PV (-141m) plus the P&L
        page.locator("[data-sm-mode='change']").click()
        assert page.locator("[data-sm-tbody] tr[data-key]").first.locator("td").nth(1).inner_text().rstrip("VE") == "—"   # the day before was refused
        page.locator("[data-sm-mode='pnl']").click()
        page.locator("[data-sm-tailonly]").click()
        n = page.locator("[data-sm-tbody] tr[data-key]").count()
        assert 13 <= n < 100 and "scenarios" in page.locator("[data-sm-size]").inner_text()
        page.locator("[data-sm-sort]").select_option("day")
        keys = page.locator("[data-sm-tbody] tr[data-key]").evaluate_all("rs => rs.map(r => r.dataset.key)")
        assert keys == sorted(keys, reverse=True)
        page.keyboard.press("Tab")
        page.locator("[data-sm-scroll]").focus()
        page.keyboard.press("ArrowDown")
        assert page.locator("[data-sm-tbody] tr.sm-pick").count() == 1
        csv = page.locator("[data-sm-csv]").get_attribute("href")
        assert csv.startswith("/api/smat/matrix.csv?") and "id=POS-X" in csv
        with page.expect_download() as d:
            page.locator("[data-sm-csv]").click()
        assert d.value.suggested_filename.endswith(".csv")
        with page.expect_download() as d2:
            page.locator("[data-sm-arrow]").click()
        assert d2.value.suggested_filename.endswith(".arrow")
        page.locator("[data-sm-by='trade']").click()
        page.wait_for_function("() => document.querySelector('[data-sm-tbody] tr[data-key]') && "
                               "document.querySelector('[data-sm-tbody] tr[data-key]').dataset.key.startsWith('T-')")
        assert page.locator("[data-sm-tbody] tr[data-key]").count() == 2
        assert "Position (this scenario)" in page.locator("[data-sm-tfoot]").inner_text()
        assert page.locator("[data-sm-chart-title]").inner_text().startswith("Trade T-")
        page.locator("[data-sm-by='scenario']").click()
        assert page.locator("[data-sm-tbody] tr[data-key]").count() > 10
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_every_theme_draws_the_heat_and_the_outline(browser, smat_console):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    page = _page(ctx, smat_console)
    try:
        _open(page, smat_console)
        seen = set()
        for theme in THEMES:
            page.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
            cell = page.locator("[data-sm-tbody] tr[data-key]").first.locator("td").nth(DAYS - 1)
            bg = cell.evaluate("e => getComputedStyle(e).backgroundColor")
            ring = cell.evaluate("e => getComputedStyle(e).boxShadow")
            line = page.locator("[data-sm-chart] polyline.sm-line").first.evaluate("e => getComputedStyle(e).stroke")
            assert bg and bg != "rgba(0, 0, 0, 0)", (theme, bg)
            assert ring and ring != "none", (theme, ring)
            assert line and line != "none", (theme, line)
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
        assert page.locator("[data-sm-chart] svg").is_visible() and page.locator("[data-sm-pick]").is_visible()
        assert "in the tail on" in page.locator("[data-sm-glance]").inner_text()
        assert page.locator(".sm-phone-note").is_visible()
        page.locator("[data-sm-pick]").select_option(index=2)
        assert page.locator("[data-sm-chart-title]").inner_text().startswith("Scenario ")
        page.wait_for_timeout(150)
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        assert not page.outside, page.outside
    finally:
        ctx.close()
