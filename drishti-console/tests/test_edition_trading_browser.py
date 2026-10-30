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

"""Drishti Trading's three Home landings in a browser (EDITIONS.md section 14): the console with the stand-in server of trading_fake.py
(port 17980), the person mapped to A. Shah's books. Each landing fills its panels from those answers, fits a phone without sideways
scrolling, passes the page-width audit, asks no host but the console (every library is vendored), and draws in every theme with its green
accent; the new-trade check answers end to end; skipped when Playwright or its Chromium is missing."""
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from trading_fake import TradingBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from test_page_widths_browser import AUDIT  # noqa: E402
from scratch_ports import worker_port  # noqa: E402

PORT = worker_port(17980)
THEMES = ["terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"]
GREENS = ("rgb(79, 209, 139)", "rgb(26, 127, 75)")      # the accent on dark and on light themes


@pytest.fixture(scope="module")
def trading_console():
    import uvicorn

    from core import trading_landing
    from core.app import create_app
    from core.config import load_settings

    trading_landing.forget()
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = TradingBackend()
    app.state.editions.learn({"product": "Drishti Trading", "edition": {"name": "trading", "title": "Drishti Trading"}})
    user = app.state.auth.default.user
    app.state.editions.instance.spec["trading"]["people"][user] = {"trader": "TRDR-ASHAH"}
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
    trading_landing.forget()


def _page(ctx):
    page = ctx.new_page()
    page.outside = []
    page.on("request", lambda r: page.outside.append(r.url) if not r.url.startswith((f"http://127.0.0.1:{PORT}", "data:", "blob:")) else None)
    return page


def _text(page, sel):
    return page.locator(sel).inner_text().replace("\n", " ")


@pytest.mark.parametrize("landing", ["trader", "desk", "explorer"])
@pytest.mark.parametrize("width", [390, 1280])
def test_each_trading_landing_fills_from_the_servers_answers_and_fits(browser, trading_console, landing, width):
    ctx = browser.new_context(viewport={"width": width, "height": 900}, is_mobile=width < 600, has_touch=width < 600)
    page = _page(ctx)
    try:
        page.goto(f"{trading_console}/?landing={landing}", wait_until="load")
        page.locator(f"[data-trading-landing='{landing}']").wait_for()
        page.wait_for_function("() => document.querySelector('[data-tl-who]').textContent.includes('A. Shah')"
                               if landing != "desk" else "() => document.querySelector('[data-tl-who]').textContent.includes('DESK-RATES')")
        assert page.locator("[data-tl-viewas]").is_visible()                           # the default user is an administrator: may pick
        if landing == "trader":
            page.wait_for_function("() => document.querySelector('[data-tl-date]').textContent === '2026-09-30'")
            page.wait_for_function("() => document.querySelector('[data-tl-tile=limit] [data-tl-v]').textContent === '83%'")
            page.wait_for_function("() => document.querySelectorAll('[data-tl-blotter] tr[data-tl-event]').length === 3")
            assert _text(page, "[data-tl-tile=pnl] [data-tl-v]") == "+480k"
            assert "since 2026-09-29" in _text(page, "[data-tl-tile=pnl] [data-tl-s]")
            assert _text(page, "[data-tl-tile=var] [data-tl-v]") == "8.00m" and "intraday" in _text(page, "[data-tl-tile=var] [data-tl-s]")
            assert _text(page, "[data-tl-tile=dv01] [data-tl-v]") == "−48k"                # the data has DV01: the tile shows
            assert _text(page, "[data-tl-tile=trades] [data-tl-v]") == "3"
            assert "1 new · 1 amended · 1 cancelled" in _text(page, "[data-tl-tile=trades] [data-tl-s]")
            assert "BOOK-RATES-1" in _text(page, "[data-tl-tile=limit] [data-tl-s]")
            assert page.locator("[data-tl-blotter] tr[data-tl-event=cancelled]").count() == 1
            page.wait_for_function("() => document.querySelectorAll('[data-tl-positions] tr').length === 2")
            page.wait_for_function("() => document.querySelector(\"[data-tl-quick] [data-home-card='TRDR TRDR-ASHAH']\") !== null")
        elif landing == "desk":
            page.wait_for_function("() => document.querySelectorAll('[data-tl-heat] [data-tl-cell]').length === 15")
            assert _text(page, "[data-tl-cell='BOOK-RATES-3@2026-09-30']") == "110%"
            assert page.locator("[data-tl-cell='BOOK-RATES-3@2026-09-30']").get_attribute("class") == "c breach"
            assert _text(page, "[data-tl-cell='BOOK-RATES-1@2026-09-25']") == "—"           # no official record that day, said
            page.wait_for_function("() => document.querySelector('[data-tl-tile=breaches] [data-tl-v]').textContent === '1'")
            assert "BOOK-RATES-3" in _text(page, "[data-tl-tile=breaches] [data-tl-s]")
            assert _text(page, "[data-tl-tile=near] [data-tl-v]") == "1"
            page.wait_for_function("() => document.querySelector('[data-tl-tile=pnl] [data-tl-v]').textContent === '+780k'")
            assert "CP-" in _text(page, "[data-tl-tile=cpty] [data-tl-s]")
            page.wait_for_function("() => document.querySelectorAll('[data-tl-traders] tr').length === 2")
            assert "A. Shah" in _text(page, "[data-tl-traders]")
            page.wait_for_function("() => document.querySelectorAll('[data-tl-pnlvar] svg rect').length === 60")
            assert page.locator("[data-tl-pnlvar] svg circle").count() == 1
            assert "1 exception in 60 days" in _text(page, "[data-tl-pnlvar-note]")
            page.wait_for_function("() => document.querySelectorAll('[data-tl-movers] tr').length >= 3")
            assert "MX-20000012" in page.locator("[data-tl-movers] tr").first.inner_text()
        else:
            assert page.locator("#tlCmd").is_visible()
            assert page.locator(".tl-chip", has_text="CTRD CP-ALDERSHOT").count() == 1
            for gid in ("blotter", "books", "risk", "cpty", "market"):
                assert page.locator(f"[data-tl-group='{gid}']").count() == 1, gid
            recents = page.locator("[data-tl-recents]").inner_text()
            assert "TRD MX-20000001" in recents and "ECON" not in recents and "SCN" not in recents
        page.wait_for_timeout(200)
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        assert not page.evaluate(AUDIT)
        assert not page.outside, page.outside                                          # no host but the console
    finally:
        ctx.close()


def test_the_check_box_answers_end_to_end(browser, trading_console):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    page = _page(ctx)
    try:
        page.goto(f"{trading_console}/?landing=trader", wait_until="load")
        page.wait_for_function("() => document.querySelectorAll('[data-tl-product] option').length === 3")
        page.select_option("[data-tl-product]", "IR_SWAP")
        page.select_option("[data-tl-check] select[name=side]", "S")
        page.fill("[data-tl-check] input[name=notional]", "250m")
        page.fill("[data-tl-check] input[name=counterparty]", "CP-ALDERSHOT")
        page.click("[data-tl-check-go]")
        page.wait_for_function("() => document.querySelector('[data-trading-landing]').getAttribute('data-tl-checked') === 'near/near'")
        v = _text(page, "[data-tl-verdict-var]")
        assert "Near my VaR limit" in v and "6.12m → 6.30m" in v and "83% → 85%" in v
        x = _text(page, "[data-tl-verdict-exp]")
        assert "Counterparty near its limit" in x and "401.50m → 409.20m" in x and "97% of limit 420.00m" in x
        assert "MX-20000010" in _text(page, "[data-tl-check-note]")
        assert not page.outside, page.outside
    finally:
        ctx.close()


@pytest.mark.parametrize("landing", ["trader", "desk", "explorer"])
def test_each_trading_landing_draws_in_every_theme_with_its_green_accent(browser, trading_console, landing):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    page = ctx.new_page()
    try:
        page.goto(f"{trading_console}/?landing={landing}", wait_until="load")
        page.locator(f"[data-trading-landing='{landing}']").wait_for()
        seen = set()
        box = ".tl-tile" if landing != "explorer" else ".tl-q"
        for theme in THEMES:
            page.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
            colours = page.evaluate("""(box) => { const t = getComputedStyle(document.querySelector(box));
                const on = getComputedStyle(document.querySelector('.tl-switch a.on'));
                return [t.backgroundColor, t.color, on.color]; }""", box)
            assert all(c and c != "rgba(0, 0, 0, 0)" for c in colours), (theme, colours)
            assert colours[2] in GREENS, (theme, colours)
            seen.add(colours[0])
            assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), theme
        assert len(seen) > 1                                                     # the tiles follow the theme
    finally:
        ctx.close()
