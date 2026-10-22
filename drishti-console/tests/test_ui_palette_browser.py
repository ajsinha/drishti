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

"""The Chip Store palette in a real browser (Chromium): a search by the words of a purpose, title matches first, a category chip, "Fits this
data" narrowing to the chips a selected supplier can feed, and the keys (/, the arrows, Enter) with the chip added becoming recent."""
import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_ui_board_browser import browser, page  # noqa: E402,F401
from ui_board_backend import serve  # noqa: E402


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve()
    yield url, backend
    stop()


def top(page):
    return page.locator("[data-ub-palette] .ub-pal-item").first.get_attribute("data-chip-item")


def test_the_chip_store_palette_finds_chips(page, stack):
    url, be = stack
    page.goto(url + "/build/board/new?advanced=1")
    page.wait_for_selector('.ub-pal-add[data-chip="cashflow-ladder"]')
    page.fill("[data-ub-palette-q]", "running total per group")                   # words of the purpose
    page.wait_for_function("(document.querySelector('[data-ub-palette] .ub-pal-item') || {dataset: {}}).dataset.chipItem === 'cashflow-ladder'")
    assert page.locator("[data-ub-palette] .ub-pal-item").first.locator(".ub-pal-pins").text_content().startswith("in: ")
    page.fill("[data-ub-palette-q]", "date")                                       # a pin type finds chips; title and id matches first
    page.wait_for_function("document.querySelector('[data-ub-palette] .ub-pal-item').dataset.chipItem === 'date-range'")
    assert page.locator('[data-chip-item="cashflow-ladder"]').count() == 1         # its rows have a date column
    page.fill("[data-ub-palette-q]", "")
    page.locator("[data-ub-cat='ladder']").click()                                 # a category chip
    page.wait_for_function("Array.from(document.querySelectorAll('[data-ub-palette] .ub-pal-item')).every(li => li.dataset.category.split(' ').indexOf('ladder') >= 0)")
    assert page.locator('[data-chip-item="cashflow-ladder"]').count() == 1
    page.locator("[data-ub-cat='ladder']").click()

    async def form(pack, kind="", ident=None):                                    # a supplier of ids and text only
        return {"pack": pack, "form": {"kind": kind, "fields": [{"path": "TRADE_ID", "label": "Trade", "type": "id"}, {"path": "STATUS", "label": "Status", "type": "text"}]}}
    be.form = form
    page.evaluate("window.drishtiUiBoard.designer.addInstance('entity-card@1')")
    page.wait_for_function("window.drishtiUiBoard.designer.doc.chips['entity-card']")
    page.evaluate("const D = window.drishtiUiBoard.designer; D.doc.chips['entity-card'].supply = { connector: 'trading/trade' }; D.selected = null; D.select('entity-card');")
    before = page.locator("[data-ub-palette] .ub-pal-item").count()
    page.locator("[data-ub-fits]").check()
    page.wait_for_function("document.querySelector('[data-ub-fits-note]').textContent.indexOf('can feed') >= 0")
    after = page.locator("[data-ub-palette] .ub-pal-item").count()
    assert after < before and page.locator('[data-chip-item="cashflow-ladder"]').count() == 0 and page.locator('[data-chip-item="entity-card"]').count() == 1
    page.locator("[data-ub-fits]").uncheck()
    page.locator("h1").click()
    page.keyboard.press("/")
    assert page.evaluate("document.activeElement.hasAttribute('data-ub-palette-q')")
    page.keyboard.type("search box")
    page.wait_for_function("document.querySelector('[data-ub-palette] .ub-pal-item').dataset.chipItem === 'search-box'")
    page.keyboard.press("ArrowDown")
    assert page.evaluate("document.activeElement.dataset.chip") == "search-box"
    page.keyboard.press("Enter")
    page.wait_for_function("Object.values(window.drishtiUiBoard.designer.doc.chips).some(i => i.use === 'search-box@1')")
    page.fill("[data-ub-palette-q]", "")
    page.wait_for_function("document.querySelector('[data-ub-palette] .ub-pal-item').dataset.chipItem === 'search-box'")   # recent first
    assert page.errors == []
