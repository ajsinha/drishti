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

"""The data map, DATAMAP, in a real browser (docs/guides/USER_GUIDE.md "The data map"): typing DATAMAP trade opens the Map with the trade
selected, its joins lit and the side panel listing its join fields with their coverage and the commands that open it; Cards shows the
domain's table, comes from and crosses into; the chips and Find narrow both views and everything is in the address; a phone keeps the
page from scrolling sideways; the page follows the server while it is still checking. Runs Chromium through Playwright; skipped when
Playwright or its Chromium is missing."""
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class MapBackend(FakeBackend):
    """The stand-in server with the shipped trading, core and counterparty-risk packs switched on (their guides and contracts resolve)."""

    async def packs(self, ident=None):
        return [{"name": n, "version": "1.0.0", "title": n.title(), "description": "", "assigned": True, "active": True}
                for n in ("trading", "core", "counterparty-risk")]


@pytest.fixture(scope="module")
def map_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = MapBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}", app
    server.should_exit = True
    thread.join(10)


def test_datamap_trade_selects_the_trade_and_lights_its_joins(browser, map_console):
    url, _ = map_console
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    page.goto(url + "/t")
    page.locator("#cmdInput").fill("DATAMAP trade")
    page.keyboard.press("Enter")
    page.wait_for_url("**/datamap?kind=trade")
    side = page.locator("[data-side-body]")
    text = side.inner_text()
    for words in ("Joins to", "COUNTERPARTY", "15,000 of 15,000 ids match (100%)", "PORTFOLIO", "1 of 4 ids match (25%)", "finding",
                  "Joined from", "Exposure", "Open it", "TRD T-1", "Data contract"):
        assert words in text, words
    assert "dm-sel" in page.locator(".dm-n[data-kind='trade']").get_attribute("class")
    assert page.locator(".dm-j.dm-on").count() == 3, "the trade's three lines are lit"
    assert "dm-dim" not in page.locator(".dm-n[data-kind='counterparty']").get_attribute("class")
    assert side.locator("a.dm-cmd").first.get_attribute("href") == "/go?q=TRD%20T-1"

    page.locator(".dm-n[data-kind='counterparty']").click()
    assert "kind=counterparty" in page.url
    assert "Joined from" in side.inner_text() and "Trade" in side.inner_text()
    page.close()


def test_cards_show_the_domain_table_and_the_state_is_in_the_address(browser, map_console):
    url, _ = map_console
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    page.goto(url + "/datamap?kind=trade")
    page.locator("[data-set-view='cards']").click()
    assert "view=cards" in page.url and "kind=trade" in page.url
    assert page.locator("[data-pane='map']").is_hidden() and page.locator("[data-pane='cards']").is_visible()
    side = page.locator("[data-side-body]").inner_text()
    for words in ("Trades", "KIND", "COUNT", "JOINS", "OPEN", "15,000", "COMES FROM", "CROSSES INTO", "Organisation"):
        assert words in side.upper() if words.isupper() else words in side, words
    page.locator("[data-card='organisation']").click()
    assert "domain=organisation" in page.url
    assert "Counterparty" in page.locator("[data-side-body]").inner_text()
    page.locator("[data-chip-set='organisation']").click()
    assert "chip=organisation" in page.url
    assert page.locator("[data-card='trades']").is_hidden() and page.locator("[data-card='organisation']").is_visible()
    page.locator("[data-set-view='map']").click()
    assert "dm-hidden" in page.locator(".dm-n[data-kind='trade']").get_attribute("class")
    page.locator("[data-chip-set='']").click()
    page.locator("[data-find]").fill("PORTFOLIO")
    assert "find=PORTFOLIO" in page.url
    assert "dm-dim" not in page.locator(".dm-n[data-kind='trade']").get_attribute("class")
    page.reload()
    assert page.locator("[data-find]").input_value() == "PORTFOLIO", "a reload opens the same picture"
    page.close()


def test_a_phone_never_scrolls_sideways(browser, map_console):
    url, _ = map_console
    page = browser.new_page(viewport={"width": 390, "height": 844})
    for view in ("map", "cards"):
        page.goto(url + f"/datamap?view={view}&kind=trade")
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), view
    page.close()


def test_the_page_follows_the_server_while_it_is_checking(browser, map_console):
    url, app = map_console
    app.state.backend.datamap_state = "checking"
    page = browser.new_page()
    try:
        page.goto(url + "/datamap")
        assert "Counting and checking" in page.locator("[data-state]").inner_text()
        app.state.backend.datamap_state = "ready"
        page.wait_for_function("() => document.querySelector('[data-state]').innerText === ''", timeout=10000)
        assert "1 finding" in page.locator("[data-findings-box]").inner_text()
    finally:
        app.state.backend.datamap_state = "ready"
        page.close()
