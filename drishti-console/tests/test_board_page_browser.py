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
"""Concept A, "Edit the live page" (UI_BOARD_GUIDE.md "The designer"), in a real browser: the designer opens on the live page (the stand-in's
render paints the blocks, no pin pads, no mapping sheet, no "Live data is off"); hovering a block shows the three pills; Swap chip changes the
block at once; Follows… writes a trace into the board document; Change fields writes the pin mapping; "+ Add a block here" adds one; a block
moves by drag and by the side panel; the Advanced drawer is closed by default and holds the old tools; the phone preview; two themes. Saves the
guide's concept-a screenshots (dark terminal and light). Against the stand-in server of ui_board_backend.py; skipped without Chromium."""
from pathlib import Path

import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_ui_board_browser import BOOK, browser, put_board  # noqa: E402,F401
from ui_board_backend import serve  # noqa: E402

IMG = CONSOLE.parent / "docs" / "guides" / "img" / "ui-board"
DOC = "window.drishtiUiBoard.designer.doc"


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve()
    put_board(backend, "book-trades", BOOK)
    yield url, backend
    stop()


def open_page(browser, url, width=1280, height=800, theme=None, board="book-trades"):
    c = browser.new_context(viewport={"width": width, "height": height}, bypass_csp=True)
    p = c.new_page()
    p.errors = []
    p.on("pageerror", lambda e: p.errors.append(str(e)))
    p.goto(f"{url}/build/board/{board}")
    p.wait_for_selector('.ub-block[data-block="trades"] .ub-body table tbody tr')
    if theme:
        p.evaluate("n => { document.documentElement.setAttribute('data-theme', n); document.dispatchEvent(new CustomEvent('drishti:theme', { detail: n })); }", theme)
    return c, p


def test_the_designer_opens_on_the_live_page(browser, stack):
    url, _ = stack
    c, p = open_page(browser, url)
    try:
        assert p.locator("[data-ub-advanced]").get_attribute("open") is None          # Advanced is closed by default
        assert not p.locator(".ub-pin").first.is_visible() and not p.locator("[data-ub-sheet]").is_visible()
        assert p.locator(".ub-block", has_text="Live data is off").count() == 0
        assert p.locator(".ub-bar [data-ub-save]").is_visible() and p.locator(".ub-bar [data-ub-preview-as]").is_visible()
        assert not p.locator("[data-ub-share]").is_visible()                          # Share and the rest wait in the ⋯ menu
        p.locator("[data-ub-more]").click()
        assert p.locator("[data-ub-menu] [data-ub-share]").is_visible() and p.locator("[data-ub-menu] [data-ub-export]").is_visible()
        p.keyboard.press("Escape")
        assert p.locator(".ub-block").count() == 3 and p.locator("[data-ub-add]").count() >= 2
        assert p.errors == []
    finally:
        c.close()


def test_hover_shows_three_pills_and_swap_chip_changes_the_block_live(browser, stack):
    url, _ = stack
    c, p = open_page(browser, url)
    try:
        flows = p.locator('.ub-block[data-block="flows"]')
        assert not flows.locator(".ub-pills").is_visible()
        flows.hover()
        assert flows.locator(".ub-pill").all_text_contents() == ["Swap chip", "Its data", "Follows…"]
        flows.locator('[data-ub-pill="swap"]').click()
        p.wait_for_selector("[data-ub-pop] [data-ub-pick]")
        assert p.locator("[data-ub-pop] .ub-pick-group").first.text_content() == "Fits its data"
        p.fill("[data-ub-pick-q]", "filterable table")
        p.locator('[data-ub-pop] [data-ub-pick^="filterable-table@"]').click()
        p.wait_for_function(DOC + ".chips.flows.use.startsWith('filterable-table@')")
        p.keyboard.press("Escape")
        p.wait_for_selector('.ub-block[data-block="flows"] .ub-body table')
        assert p.errors == []
    finally:
        c.close()


def test_follows_writes_a_trace_and_change_fields_writes_the_mapping(browser, stack):
    url, _ = stack
    c, p = open_page(browser, url)
    try:
        ticket = p.locator('.ub-block[data-block="ticket"]')
        ticket.click()
        assert "Trade ticket" in p.input_value("[data-ub-side-title]")
        assert p.locator("[data-ub-side-follows]").text_content().startswith("The trade you click in Trades")
        ticket.locator('[data-ub-pill="follows"]').click()
        p.locator('[data-ub-pop] [data-ub-follow=""]').click()                          # nothing
        p.wait_for_function(DOC + ".traces.every(t => t.to.split('.')[0] !== 'ticket')")
        ticket.hover()
        ticket.locator('[data-ub-pill="follows"]').click()
        p.locator('[data-ub-pop] [data-ub-follow="trades.selected"]').click()
        p.wait_for_function(DOC + ".traces.some(t => t.from === 'trades.selected' && t.to === 'ticket.id')")
        assert "Trades" in p.locator("[data-ub-side-follows]").text_content()
        # Change fields: a checklist of the supplier's fields, written into doc.fields
        p.locator("[data-ub-change-fields]").click()
        p.wait_for_selector('[data-ub-pop] [data-ub-field-col="doc.fields"] [data-ub-field="COUNTERPARTY_NAME"]')
        p.locator('[data-ub-pop] [data-ub-field="COUNTERPARTY_NAME"]').check()
        p.locator('[data-ub-pop] [data-ub-field="STATUS"]').uncheck()
        p.wait_for_function(DOC + ".chips.ticket.map['doc.fields'].includes('COUNTERPARTY_NAME') && !" + DOC + ".chips.ticket.map['doc.fields'].includes('STATUS')")
        p.locator("[data-ub-pop-done]").click()
        assert "Counterparty" in p.locator("[data-ub-side-fields]").text_content()
        assert p.errors == []
    finally:
        c.close()


def test_add_a_block_here_and_reorder(browser, stack):
    url, _ = stack
    c, p = open_page(browser, url, height=1600)
    try:
        p.locator(".ub-add-big").click()
        p.wait_for_selector("[data-ub-pop] [data-ub-pick]")
        assert p.locator("[data-ub-pop] .ub-pick-group").first.text_content().startswith("For this page's data")
        p.fill("[data-ub-pick-q]", "kpi")
        p.locator("[data-ub-pop] [data-ub-pick]").first.click()
        p.wait_for_function("Object.keys(" + DOC + ".chips).length === 4")
        new = p.evaluate("Object.keys(" + DOC + ".chips).find(n => !['trades', 'ticket', 'flows'].includes(n))")
        assert p.evaluate("window.drishtiUiBoard.designer.selected") == new
        order = lambda: p.evaluate("window.drishtiUiBoard.words.order(window.drishtiUiBoard.designer)")  # noqa: E731
        assert order()[-1] == new
        # the side panel moves it earlier; a drag by its head puts it back after the flows
        p.locator('[data-ub-move="up"]').click()
        p.wait_for_function(f"window.drishtiUiBoard.words.order(window.drishtiUiBoard.designer).indexOf('{new}') === 2")
        head = p.locator(f'.ub-block[data-block="{new}"] .ub-block-head').bounding_box()
        target = p.locator('.ub-block[data-block="trades"]').bounding_box()
        p.mouse.move(head["x"] + head["width"] / 2, head["y"] + head["height"] / 2)
        p.mouse.down()
        p.mouse.move(target["x"] + 20, target["y"] + 30, steps=8)
        p.mouse.up()
        p.wait_for_function(f"window.drishtiUiBoard.words.order(window.drishtiUiBoard.designer)[0] === '{new}'")
        # half or full width from the side panel; the layout is written back
        p.locator('[data-ub-width="full"]').click()
        p.wait_for_function(DOC + f".layout.desktop.areas.find(a => a.chip === '{new}').span[0] === 12")
        assert p.errors == []
    finally:
        c.close()


def test_advanced_holds_the_old_tools(browser, stack):
    url, _ = stack
    c, p = open_page(browser, url)
    try:
        p.locator("[data-ub-adv-summary]").click()
        p.wait_for_selector('.ub-box[data-instance="trades"] .ub-pin')
        assert p.locator("[data-ub-sheet]").is_visible() and p.locator('[data-ub-tab="yaml"]').is_visible()
        assert p.locator("[data-ub-problems]").count() == 1
        assert p.errors == []
    finally:
        c.close()


def test_phone_preview_and_a_phone(browser, stack):
    url, _ = stack
    c, p = open_page(browser, url)
    try:
        p.locator(".ub-bar [data-ub-device]").click()
        p.wait_for_selector(".ub-page.ub-page-phone")
        assert p.locator(".ub-page").bounding_box()["width"] <= 392
        assert p.locator(".ub-block").count() == 3
    finally:
        c.close()
    c, p = open_page(browser, url, 390, 844)
    try:
        assert p.evaluate("() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")
        assert p.errors == []
    finally:
        c.close()


@pytest.mark.parametrize("theme,shot", [("terminal", "concept-a.png"), ("light", "concept-a-light.png")])
def test_two_themes_and_the_guide_screenshots(browser, stack, theme, shot):
    url, _ = stack
    c, p = open_page(browser, url, theme=theme)
    try:
        bg = p.evaluate("getComputedStyle(document.querySelector('.ub-block')).backgroundColor")
        assert bg not in ("", "rgba(0, 0, 0, 0)")                                      # painted from the theme's tokens
        p.locator('.ub-block[data-block="trades"] tbody tr').first.click()
        p.wait_for_selector('.ub-block[data-block="ticket"] .ub-body dl, .ub-block[data-block="ticket"] .ub-body table')
        p.locator('.ub-block[data-block="ticket"] .ub-block-head').click()
        p.locator('.ub-block[data-block="ticket"]').hover()
        IMG.mkdir(parents=True, exist_ok=True)
        p.screenshot(path=str(IMG / shot))
        assert Path(IMG / shot).stat().st_size > 10_000
        assert p.errors == []
    finally:
        c.close()
