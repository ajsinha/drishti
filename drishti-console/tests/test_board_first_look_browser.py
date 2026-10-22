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

"""The Board designer's first look, in a real browser (Chromium): the all-panels showcase opened as a board (/build/board/new?design=...).
The converter's notes fold into one line with a Details table; the page has the console's gutters (desktop and phone, 16 px); live data is on
at once with the board parameter id prefilled from the design's sample; the board parameters are a form, not raw JSON; nothing scrolls
sideways at 1280 px or on a phone. A design with no sample says what to set and shows data once it is set. Against the stand-in server of
ui_chip_backend.py; skipped without Playwright's Chromium."""
import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_ui_board_browser import browser  # noqa: E402,F401
from ui_board_backend import TRADES, serve  # noqa: E402
from ui_chip_backend import UiChipBackend  # noqa: E402

SHOWCASE = (CONSOLE.parent / "docs" / "guides" / "examples" / "all-panels-showcase.sutra.yaml").read_text()
TRADE = TRADES[2]["TRADE_ID"]


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve(UiChipBackend())

    async def designs(method, path, ident, body=None, **params):
        if path == "/d-show":
            return {"id": "d-show", "name": "Showcase", "kind": "trade", "sutra": SHOWCASE,
                    "samples": [{"name": "trade " + TRADE, "type": "ref", "ref": {"kind": "trade", "id": TRADE}}]}
        return {"id": "d-bare", "name": "Bare", "kind": "trade", "sutra": SHOWCASE, "samples": []}
    backend.designs = designs
    yield url, backend
    stop()


def open_board(browser, url, design, width, height=900):
    c = browser.new_context(viewport={"width": width, "height": height}, bypass_csp=True)
    p = c.new_page()
    p.errors = []
    p.on("pageerror", lambda e: p.errors.append(str(e)))
    p.goto(f"{url}/build/board/new?design={design}")
    p.wait_for_function("window.drishtiUiBoard.designer.doc.chips && Object.keys(window.drishtiUiBoard.designer.doc.chips).length > 3"
                        " && document.querySelector('[data-ub-status]').textContent.startsWith('Opened')")
    return c, p


def no_sideways_scroll(p):
    return p.evaluate("() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")


def test_the_showcase_opens_as_a_board_with_a_short_note_gutters_live_data_and_a_parameter_form(browser, stack):
    url, _ = stack
    c, p = open_board(browser, url, "d-show", 1280)
    try:
        status = p.locator("[data-ub-status]").text_content()
        assert status.startswith("Opened as a board (not saved).") and "board-local: Chip Store candidates." in status and len(status) < 160
        notes = p.locator("[data-ub-notes]")
        assert notes.is_visible() and notes.get_attribute("open") is None          # the details stay folded
        p.locator("[data-ub-notes] > summary").click()
        rows = p.locator("[data-ub-notes] tbody tr")
        assert rows.count() >= 1 and p.locator("[data-ub-notes] thead th").all_text_contents() == ["Chip", "Nearest Chip Store chips", "Why it stays local"]
        p.locator("[data-ub-notes] > summary").click()
        # the console's gutters: the breadcrumb and the toolbar sit inside the page, not on its edge
        assert p.locator(".ub-design .crumbs").bounding_box()["x"] >= 16
        assert p.locator(".ub-toolbar[aria-label='Board']").bounding_box()["x"] >= 16
        # live data on at once: the id parameter is the design's sample, and the boxes paint (the stand-in's render)
        assert p.evaluate("window.drishtiUiBoard.designer.params.id") == TRADE
        assert p.locator("[data-ub-live]").is_checked()
        p.wait_for_function("document.querySelectorAll('.ub-box .ub-state[data-state]').length > 0")
        assert p.locator(".ub-box", has_text="Live data is off").count() == 0
        # the board parameters as a form: a field per parameter with its value, never the raw JSON
        p.locator(".ub-box").first.click()
        param = p.locator("[data-ub-param='id']")
        assert param.is_visible() and p.input_value("[data-ub-param-value='id']") == TRADE
        assert '{"type"' not in p.locator("[data-ub-sheet]").text_content()
        assert no_sideways_scroll(p)
        assert p.evaluate("() => { const s = document.querySelector('[data-ub-sheet]'); return s.scrollWidth <= s.clientWidth + 1; }")
        assert p.errors == []
    finally:
        c.close()


def test_on_a_phone_the_designer_has_16px_gutters_and_no_sideways_scroll(browser, stack):
    url, _ = stack
    c, p = open_board(browser, url, "d-show", 390, 844)
    try:
        assert abs(p.locator(".ub-design .crumbs").bounding_box()["x"] - 16) <= 1
        assert no_sideways_scroll(p)
        assert p.errors == []
    finally:
        c.close()


def test_a_design_with_no_sample_asks_for_the_id_and_shows_data_once_set(browser, stack):
    url, _ = stack
    c, p = open_board(browser, url, "d-bare", 1280)
    try:
        assert not p.locator("[data-ub-live]").is_checked()
        box = p.locator(".ub-box").nth(1)
        assert "Set board parameter id to see data" in " ".join(box.locator(".ub-empty-state").text_content().split())
        box.locator("[data-ub-param-quick='id']").fill(TRADE)
        box.locator("[data-ub-param-quick='id']").press("Enter")
        p.wait_for_function("window.drishtiUiBoard.designer.live === true")
        p.wait_for_function("document.querySelectorAll('.ub-box .ub-state[data-state]').length > 0")
        assert p.errors == []
    finally:
        c.close()
