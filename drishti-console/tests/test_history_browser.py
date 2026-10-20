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

"""History queries in a real browser (Chromium), HISTORY_QUERIES.md section 19.3: TRD 1000015 30 typed in the command line opens the
trade's history; the header names the dates; ↓ moves the selection; C hides the days on which nothing moved and the header counts them;
Enter opens the day pinned; Esc goes back to today's view. EQ EQ-CSCA 3M draws the chart and the headline numbers, and 1 switches to one
month with the address following. In the light and terminal themes. The console runs in-process against a stand-in server answering
the contract's fixtures; every wait is on the page's own marker (data-history-ready) or the address, never a fixed sleep."""
import threading
import time

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _free_port  # noqa: E402


class HistoryBackend(FakeBackend):
    """The server's answer to a history command (CommandHistoryTest checks the real one)."""

    async def command(self, text, ident=None):
        words = text.replace("<GO>", "").split()
        if len(words) >= 3 and words[1] in ("1000015", "EQ-CSCA"):
            kind = "trade" if words[1] == "1000015" else "equity"
            rng = words[2].upper()
            query = f"days={rng}" if rng.isdigit() else f"range={rng}"
            return {"ref": {"kind": kind, "id": words[1]}, "mnemonic": words[0].upper(), "history": {"range": rng, "query": query}}
        return await super().command(text, ident)


@pytest.fixture(scope="module")
def url():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = HistoryBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed
            pytest.skip(f"Chromium is not installed: {e}")
        yield b
        b.close()


def _page(browser, theme):
    ctx = browser.new_context(viewport={"width": 1400, "height": 900})
    pg = ctx.new_page()
    errors = []
    pg.on("pageerror", lambda e: errors.append(str(e)))
    pg.add_init_script(f"document.addEventListener('DOMContentLoaded', () => document.documentElement.setAttribute('data-theme', '{theme}'))")
    return ctx, pg, errors


def _ready(pg):
    pg.wait_for_selector('[data-history-ready="1"]', timeout=15000)


@pytest.mark.parametrize("theme", ["light", "terminal"])
def test_a_trade_over_thirty_days(url, browser, theme):
    ctx, pg, errors = _page(browser, theme)
    try:
        pg.goto(url + "/t")
        pg.fill(".cmd-input", "TRD 1000015 30")
        pg.press(".cmd-input", "Enter")
        pg.wait_for_url("**/v/trade/1000015/history?days=30")
        _ready(pg)
        assert pg.get_attribute("html", "data-theme") == theme
        dates = pg.inner_text("[data-hx-dates]")
        assert "2026-09-07" in dates and "2026-10-19" in dates
        assert pg.locator("tr[data-row]").count() == 30
        assert pg.get_attribute('tr[data-row][aria-selected="true"]', "data-date") == "2026-10-19"
        pg.keyboard.press("ArrowDown")
        pg.keyboard.press("ArrowDown")
        assert pg.get_attribute('tr[data-row][aria-selected="true"]', "data-date") == "2026-10-15"
        assert pg.locator('tr[data-row][data-date="2026-10-15"] td[data-changed]').count() >= 3
        pg.keyboard.press("c")
        pg.wait_for_url("**only=1")
        assert pg.inner_text("[data-hx-shown]") == "7 of 30"
        assert pg.locator("tr[data-row]:visible").count() == 7
        assert pg.get_attribute('tr[data-row][aria-selected="true"]', "data-date") == "2026-10-15"   # still selected
        pg.keyboard.press("Enter")
        pg.wait_for_url("**/v/trade/1000015?asOf=2026-10-15&gen=*")
        pg.go_back()
        _ready(pg)
        pg.keyboard.press("Escape")
        pg.wait_for_url("**/v/trade/1000015")
        assert not errors, errors
    finally:
        ctx.close()


@pytest.mark.parametrize("theme", ["light", "terminal"])
def test_an_equity_over_three_months(url, browser, theme):
    ctx, pg, errors = _page(browser, theme)
    try:
        pg.goto(url + "/t")
        pg.fill(".cmd-input", "EQ EQ-CSCA 3M")
        pg.press(".cmd-input", "Enter")
        pg.wait_for_url("**/v/equity/EQ-CSCA/history?range=3M")
        _ready(pg)
        pg.wait_for_selector("[data-hx-chart] canvas", timeout=10000)          # ECharts drew
        assert pg.inner_text(".hx-kpi-last .hx-v") == "19.54"
        assert "20.16" in pg.inner_text(".hx-kpis") and "2026-08-24" in pg.inner_text(".hx-kpis")
        pg.keyboard.press("ArrowDown")
        pg.wait_for_function("document.querySelector('[data-hx-cross]').textContent.includes('2026-10-16')")   # the crosshair follows
        pg.keyboard.press("1")
        pg.wait_for_url("**range=1M")
        _ready(pg)
        assert pg.get_attribute('[data-hx-range-btn="1M"]', "aria-current") == "true"
        assert not errors, errors
    finally:
        ctx.close()


def test_link_copies_on_a_plain_http_lan_address_and_keeps_changes_only(url, browser):
    """QA1-07: on a console's LAN address over plain http the page is not a secure context and has no navigator.clipboard. Link (and
    Alt+S) still shows the link, selected in a field under the toolbar, copies it with execCommand('copy') and says so; the link keeps
    &only=1 when changes only is on, and its end date is pinned."""
    ctx, pg, errors = _page(browser, "terminal")
    pg.add_init_script("Object.defineProperty(window, 'isSecureContext', {get: () => false});"
                       "Object.defineProperty(navigator, 'clipboard', {get: () => undefined});"
                       "window.__copied = [];"
                       "document.execCommand = function (c) { if (c === 'copy') { window.__copied.push(document.activeElement.value); return true; } return false; };")
    try:
        pg.goto(url + "/v/trade/1000015/history?days=30&only=1")
        _ready(pg)
        assert pg.is_hidden("[data-hx-linkbox]")
        pg.keyboard.press("Alt+s")
        pg.wait_for_selector("[data-hx-linkbox]:not([hidden])")
        link = pg.input_value("[data-hx-link-text]")
        assert "/v/trade/1000015/history?" in link and "days=30" in link and "only=1" in link and "to=2026-10-19" in link, link
        assert pg.evaluate("window.__copied") == [link]
        assert "Copied" in pg.inner_text("[data-hx-link-msg]")
        pg.click("[data-hx-key='c']")                                         # changes only off: the next link drops only=1
        pg.click("[data-hx-key='s']")
        assert "only=1" not in pg.input_value("[data-hx-link-text]")
        assert not errors, errors
    finally:
        ctx.close()
