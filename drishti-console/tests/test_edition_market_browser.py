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

"""Drishti Market's three Home landings in a browser (EDITIONS.md section 13): the console with the stand-in server holding market data
with known stale, gap, forward and surface problems (market_fake.py; port 17976). Each landing fills its panels from those answers, fits a
phone without sideways scrolling, passes the page-width audit, and draws in every theme with its violet accent; the Quality landing lists
each known problem by severity and opens its command; skipped when Playwright or its Chromium is missing."""
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from market_fake import MarketBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from test_page_widths_browser import AUDIT  # noqa: E402
from scratch_ports import worker_port  # noqa: E402

PORT = worker_port(17976)
THEMES = ["terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"]
VIOLETS = ("rgb(167, 139, 250)", "rgb(109, 40, 217)")      # the accent on dark and on light themes


@pytest.fixture(scope="module")
def market_console():
    import uvicorn

    from core import market_landing
    from core.app import create_app
    from core.config import load_settings

    market_landing.forget()
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = MarketBackend()
    app.state.editions.learn({"product": "Drishti Market", "edition": {"name": "market", "title": "Drishti Market"}})
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=PORT, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{PORT}"
    server.should_exit = True
    thread.join(10)
    market_landing.forget()


@pytest.mark.parametrize("landing", ["desk", "explorer", "quality"])
@pytest.mark.parametrize("width", [390, 1280])
def test_each_market_landing_fills_from_the_pack_and_fits(browser, market_console, landing, width):
    ctx = browser.new_context(viewport={"width": width, "height": 900}, is_mobile=width < 600, has_touch=width < 600)
    page = ctx.new_page()
    try:
        page.goto(f"{market_console}/?landing={landing}", wait_until="load")
        page.locator(f"[data-market-landing='{landing}']").wait_for()
        if landing == "desk":
            page.wait_for_function("() => document.querySelector('[data-ml-date]').textContent === '2026-09-30'")
            page.wait_for_function("() => document.querySelectorAll('[data-ml-moves] tr').length >= 3")
            page.wait_for_function("() => document.querySelector('[data-ml-tile=exceptions] [data-ml-v]').textContent === '7'")
            assert page.locator("[data-ml-tile=ois10y] [data-ml-v]").inner_text().replace("\n", " ") == "3.93% −4.0bp"
            assert page.locator("[data-ml-tile=slope] [data-ml-v]").inner_text().replace("\n", " ") == "46.0bp +1.0bp"
            fx = page.locator("[data-ml-tile=fxvol]")
            assert fx.locator("[data-ml-v]").inner_text() == "7.80%"                       # no change: the day before holds no data
            assert "no history for the day before" in fx.locator("[data-ml-s]").inner_text()
            assert page.locator("[data-ml-tile=cds] [data-ml-v]").inner_text().replace("\n", " ") == "70bp +1.0bp"
            assert page.locator("[data-ml-curve] svg polyline").count() == 3                # OIS, a week ago, projection
            assert "2026-09-23" in page.locator("[data-ml-curve-how]").inner_text()
            assert "standard deviations" in page.locator("[data-ml-moves-how]").inner_text()
            first = page.locator("[data-ml-moves] tr").first
            assert "CRV-EUR-OIS" in first.inner_text() and first.locator(".ml-pill").inner_text() == "jump"
            assert page.locator("[data-home-card='FIX FIX-SOFR']").count() == 1
        elif landing == "explorer":
            assert page.locator("#mlCmd").is_visible()
            assert page.locator(".ml-chip", has_text="CRV CRV-CAD-GOVT").count() == 1
            page.wait_for_function("() => document.querySelector('[data-ml-count=BND]').textContent === '32'")
            page.wait_for_function("() => document.querySelector('[data-ml-group-count=credit]').textContent === '48'")   # 16 CDS + 32 bonds
            assert page.locator("[data-ml-count=CRV]").inner_text() == "28"
            recents = page.locator("[data-ml-recents]").inner_text()
            assert "CRV CRV-USD-OIS" in recents and "ECON" not in recents and "TRD" not in recents
        else:
            page.wait_for_function("() => document.querySelectorAll('[data-ml-exceptions] tr[data-ml-exception]').length === 7")
            assert page.locator("[data-ml-critical]").inner_text() == "3"
            assert page.locator("[data-ml-warnings]").inner_text() == "4"
            assert page.locator("[data-ml-clean]").inner_text() == "57.1%"
            assert page.locator("[data-ml-clean-s]").inner_text() == "of 14 items checked"
            rows = page.locator("[data-ml-exceptions] tr")
            assert rows.first.inner_text().strip().lower() == "critical"                     # worst first
            text = page.locator("[data-ml-exceptions]").inner_text()
            for item in ("CRV CRV-EUR-OIS", "FXV FXV-USDTRY", "EQV EQV-ZERO", "CRV CRV-GBP-OIS", "INF INF-UKRPI", "CRV CRV-AUD-PROJ"):
                assert item in text, item
            assert text.index("FXV FXV-USDTRY") < text.index("CRV CRV-GBP-OIS")
            gap = page.locator("[data-ml-exception=gap] a")
            assert "CRV%20CRV-AUD-PROJ" in gap.get_attribute("href")                          # each item opens its command
            assert "Impact on VaR and valuations comes in a later release" in page.locator("[data-ml-q=impact]").inner_text()
            page.wait_for_function("() => document.querySelectorAll('[data-ml-checks] tr').length === 4")
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        assert not page.evaluate(AUDIT)
    finally:
        ctx.close()


@pytest.mark.parametrize("landing", ["desk", "explorer", "quality"])
def test_each_market_landing_draws_in_every_theme_with_its_violet_accent(browser, market_console, landing):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    page = ctx.new_page()
    try:
        page.goto(f"{market_console}/?landing={landing}", wait_until="load")
        page.locator(f"[data-market-landing='{landing}']").wait_for()
        seen = set()
        box = ".ml-tile" if landing != "explorer" else ".ml-q"
        for theme in THEMES:
            page.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
            colours = page.evaluate("""(box) => { const t = getComputedStyle(document.querySelector(box));
                const on = getComputedStyle(document.querySelector('.ml-switch a.on'));
                return [t.backgroundColor, t.color, on.color]; }""", box)
            assert all(c and c != "rgba(0, 0, 0, 0)" for c in colours), (theme, colours)
            assert colours[2] in VIOLETS, (theme, colours)
            seen.add(colours[0])
            assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), theme
        assert len(seen) > 1                                                     # the tiles follow the theme
    finally:
        ctx.close()
