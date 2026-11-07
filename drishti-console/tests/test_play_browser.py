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

"""The VaR Playground in a real browser (VAR_GUIDE.md section 3.10; the approved mock-up A), over a stand-in server answering a desk of
500 positions shown of 560 (Rest of scope) by 509 scenarios: only the cells in view are in the page (windowed), the header rows stay while
scrolling, the VaR scenario is selected first with its formula in the bar and the explanation's steps with the real numbers; a click on a
cell moves both; the arrow keys move the selection; the export's choices state their size, time and applications, a refused one
disabled; a picker asks again with the lens in the address. At 1280 px the grid and the panel side by side; at 390 px the glance form (the
headline and the explanation), no sideways scroll. No request leaves the console's host. Skipped without Playwright or its Chromium."""
import random
import socket
import threading
import time
from datetime import date, timedelta

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
import engine_fakes  # noqa: E402

N, SHOWN, TOTAL = 509, 500, 560


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _grid() -> dict:
    rnd = random.Random(5)
    days, d = [], date(2024, 9, 2)
    while len(days) < N:
        if d.weekday() < 5:
            days.append(d.isoformat())
        d += timedelta(days=1)
    f = [rnd.gauss(0, 1) for _ in range(N)]
    positions = []
    port = [0.0] * N
    for i in range(SHOWN):
        b = rnd.gauss(0, 1)
        pnl = [round(1000 * (b * f[j] + 0.3 * rnd.gauss(0, 1)), 2) for j in range(N)]
        port = [a + x for a, x in zip(port, pnl)]
        positions.append({"id": f"POS-{10000 + i}", "kind": "position", "label": f"POS-{10000 + i}", "currency": ["USD", "EUR"][i % 2],
                          "basePv": 1_000_000.0, "baseFx": [1, 1.0835][i % 2], "form": "PV" if i % 3 else "PnL", "source": "scenario-pv",
                          "todayUsd": 1_000_000.0, "standaloneVar": 2000.0, "standaloneEs": 2300.0, "componentVar": 900.0 - i, "componentEs": 950.0,
                          "pct": 0.01, "incrementalVar": 880.0 if i < 50 else None, "component10": 2800.0, "engineComponentVar": 900.0 - i,
                          "engineComponentEs": 950.0, "pnl": pnl, "pnlRange": f"PnL!$B$4:$B${3 + N}", "helperRange": "PnL!$X$4:$X$512" if i < 50 else None})
    rest = [round(rnd.gauss(0, 500), 2) for _ in range(N)]
    port = [round(a + r, 2) for a, r in zip(port, rest)]
    order = sorted(range(N), key=lambda j: (port[j], j))
    rank = [0] * N
    for r, j in enumerate(order):
        rank[j] = r + 1
    mark = ["V" if rank[j] == 6 else "E" if rank[j] <= 13 else "" for j in range(N)]
    var = -port[order[5]]
    choice = lambda c, label, size, refused=None: {"count": c, "label": label, "default": c == 500, "positions": min(c or TOTAL, TOTAL),
                                                    "layout": "positions as columns", "size": size, "recalc": "10 s",
                                                    "apps": ["Excel", "LibreOffice Calc 7.4 or later", "Google Sheets"], "refused": refused}
    return {"canonical": "NODE:MGMT~DESK-RATES-NY", "label": "DESK-RATES-NY", "businessDate": "2026-09-30", "currency": "USD",
            "lens": {"horizon": "1D", "method": None, "confidence": 0.99, "esConfidence": 0.975, "set": "NORMAL", "confidences": [0.95, 0.975, 0.99, 0.995],
                     "esConfidences": [0.95, 0.975, 0.99], "rankRule": "kth-worst", "componentWindow": 5},
            "view": "pnl", "total": TOTAL, "shown": SHOWN, "truncated": True, "maxPositions": 500, "incrementalPositions": 50, "scenarios": days,
            "positions": positions, "rest": {"id": "Rest of scope", "count": TOTAL - SHOWN, "currency": "USD", "form": "Rest", "pnl": rest,
                                             "standaloneVar": 800.0, "standaloneEs": 900.0, "componentVar": 100.0, "componentEs": 120.0, "pct": 0.001,
                                             "incrementalVar": None, "component10": 300.0, "pnlRange": "PnL!$SG$4:$SG$512"},
            "portfolio": port, "rank": rank, "mark": mark,
            "fx": {"USD": {"base": 1, "source": "base", "rates": [1] * N}, "EUR": {"base": 1.0835, "source": "supplied", "rates": [1.08] * N}},
            "figures": {"scenarioCount": N, "k": 6, "kEs": 13, "lo": 4, "hi": 8, "smoothed": var, "scale": 1.0, "var": var, "es": var * 1.1,
                        "headlineVar": var, "headlineEs": var * 1.1, "var10sqrt": var * 3.1623, "es10sqrt": var * 3.4, "var10sum": var * 3, "es10sum": var * 3.2,
                        "windows": N - 9, "k10": 5, "diversification": 12345.0, "varScenario": order[5], "varScenarioDate": days[order[5]],
                        "confidences": [{"confidence": 0.99, "k": 6, "var": var, "var10sqrt": var * 3.16, "var10sum": var * 3}],
                        "esConfidences": [{"confidence": 0.975, "k": 13, "es": var * 1.1, "es10sqrt": var * 3.4, "es10sum": var * 3.2}],
                        "sumComponentVar": var},
            "engine": {"var": var}, "checks": [{"name": "VaR: sheet = engine to the cent", "ok": True}],
            "formulas": {"pnl": "(Config!$H${C}+IF(Config!$F${C}=\"PV\",{D}*INDEX(FX!$B${F}:$C${F},Config!$I${C})-Config!$H${C},{D}))/FX!$D${F}-Config!$H${C}/FX!$D$2",
                         "componentVar": "-Scale*SUMPRODUCT(PnL!$SK$4:$SK$512,{P})/(VarHi-VarLo+1)"},
            "export": {"choices": [choice(100, "100", "2.4 MB"), choice(500, "500", "12 MB"), choice(1000, "1,000", "13 MB"),
                                   choice(10000, "10,000", "14 MB"), choice(0, "All (560)", "14 MB", None)], "default": 500},
            "provenance": {}}


GRID = _grid()


class PlayBackend(FakeBackend):
    asked: list = []

    engine = engine_fakes.method

    async def var(self, verb, path, ident, /, body=None, raw=False, accept="", **params):
        PlayBackend.asked.append((path, dict(params)))
        if path == "/playground":
            return GRID
        if path == "/currencies":
            return {"listed": ["USD", "EUR"], "available": ["USD", "EUR", "GBP"]}
        return {}


@pytest.fixture(scope="module")
def play_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = PlayBackend()
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


def _open(ctx, url, query="scope=DESK:DESK-RATES-NY"):
    page = ctx.new_page()
    page.outside = []
    page.on("request", lambda r: page.outside.append(r.url) if not r.url.startswith((url, "data:", "blob:")) else None)
    page.goto(url + "/play?" + query, wait_until="load")
    page.wait_for_function("() => document.querySelector('[data-pg-explain] .pg-step') !== null", timeout=20000)
    return page


def test_the_grid_is_windowed_and_a_cell_explains_itself(browser, play_console):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    page = _open(ctx, play_console)
    try:
        cells = page.locator("[data-pg-canvas] .pg-c").count()
        assert 50 < cells < 2500, cells                                               # only the cells in view: 501 × 515 would be 258,000
        assert "500 of 560 positions" in page.locator("[data-pg-meta]").inner_text()
        assert "Rest of scope" in page.locator("[data-pg-notice]").inner_text()
        # the VaR scenario is selected first: its formula in the bar, the explanation's steps with the real numbers, the check line
        assert page.locator("[data-pg-ref]").inner_text().startswith("PnL!B")
        formula = page.locator("[data-pg-formula]").inner_text()
        assert formula.startswith("=(Config!$H$2+") and "INDEX(FX!$B$" in formula and "{" not in formula
        text = page.locator("[data-pg-explain]").inner_text()
        for words in ("VaR 99%, 1-day", "1. The selected cell: POS-10000", "Input form PnL", "Ranked worst first",
                      "k = ⌈509 × (1 − 0.99)⌉ = 6", "Component VaR of POS-10000", "10-day", "Every confidence", "✓"):
            assert words in text, words
        # a click on another position's cell moves the bar and the explanation
        row = page.locator("[data-pg-canvas] .pg-sel").first.get_attribute("data-r")
        page.locator(f'[data-pg-canvas] .pg-c[data-r="{row}"][data-c="3"]').first.dispatch_event("click")
        assert "POS-10002" in page.locator("[data-pg-explain] h3").first.inner_text()
        assert "P&L(s) = PV(s) × FX(s) − PV(base) × FX(base)" in page.locator("[data-pg-explain]").inner_text()
        assert "$H$4+" in page.locator("[data-pg-formula]").inner_text()
        page.locator("[data-pg-scroll]").press("ArrowRight")
        assert "POS-10003" in page.locator("[data-pg-explain] h3").first.inner_text()
        # scrolling far: the header rows stay, other cells are drawn
        page.locator("[data-pg-scroll]").evaluate("e => { e.scrollTop = e.scrollHeight; e.scrollLeft = e.scrollWidth; e.dispatchEvent(new Event('scroll')); }")
        page.wait_for_timeout(200)
        assert page.locator('[data-pg-canvas] .pg-ft[data-r="0"]').count() > 0
        assert "Rest of scope" in page.locator("[data-pg-canvas]").inner_text() or "Portfolio" in page.locator("[data-pg-canvas]").inner_text()
        # the export: five choices, the default 500, the estimate stated before the download
        opts = page.locator("[data-pg-count] option")
        assert opts.count() == 5 and page.locator("[data-pg-count]").input_value() == "500"
        est = page.locator("[data-pg-estimate]").inner_text()
        assert "Workbook of 500 positions" in est and "Rest of scope" in est and "12 MB" in est and "Excel" in est and "Google Sheets" in est
        page.locator("[data-pg-count]").select_option("all")
        assert "Workbook of 560 positions" in page.locator("[data-pg-estimate]").inner_text()
        # a picker asks again, the lens in the address
        page.locator("[data-pg-horizon]").select_option("10d-sum")
        page.wait_for_function("() => location.search.indexOf('m=sum') > 0")
        assert PlayBackend.asked[-1][1].get("method") == "sum" or any(p.get("method") == "sum" for _, p in PlayBackend.asked[-3:])
        width = page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth")
        assert width <= 1, width
        assert not page.outside, page.outside
    finally:
        ctx.close()


def test_a_phone_gets_the_glance_form(browser, play_console):
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True)
    page = _open(ctx, play_console)
    try:
        assert not page.locator(".pg-gridcard").is_visible()
        assert page.locator("[data-pg-explain]").is_visible()
        assert "VaR 99%" in page.locator(".pg-hl").inner_text()
        width = page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth")
        assert width <= 1, width
    finally:
        ctx.close()
