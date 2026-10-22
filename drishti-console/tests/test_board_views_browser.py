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

"""The Board designer's two plain-word views in a real browser (Chromium), against the stand-in server of ui_board_backend.py:
B, the Wiring view (data and chip boxes, wires as circuit traces labelled in words, a wire's popover with Remove, connecting two boxes) and
C, the guided New board (what data, pick what to show, arrange, save, a live preview as you, the result opened in the designer), on a phone
and in two themes. With UI_BOARD_SHOTS=1 the guide's pictures concept-b.png and concept-c.png are written too. Skipped without Chromium."""
import os

import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_ui_board_browser import BOOK, browser, put_board  # noqa: E402,F401
from ui_board_backend import TRADES, UiBoardBackend, serve  # noqa: E402

IMG = CONSOLE.parent / "docs" / "guides" / "img" / "ui-board"
SHOTS = os.environ.get("UI_BOARD_SHOTS") == "1"


class GuidedBackend(UiBoardBackend):
    """The stand-in with one active pack whose kind is the trade (its overview names BOOK as a key column)."""

    async def packs(self, ident=None):
        return [{"name": "trading", "active": True}]

    async def pack_overview(self, name, ident=None):
        return {"name": "trading", "kinds": [{"kind": "trade", "label": "Trade", "example": TRADES[2]["TRADE_ID"], "columns": ["BOOK"]}]}


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve(GuidedBackend())
    yield url, backend
    stop()


def new_page(browser, width=1440, height=900, theme=None):
    c = browser.new_context(viewport={"width": width, "height": height}, bypass_csp=True)
    p = c.new_page()
    p.errors = []
    p.on("pageerror", lambda e: p.errors.append(str(e)))
    if theme:
        p.add_init_script(f"document.addEventListener('DOMContentLoaded', () => document.documentElement.setAttribute('data-theme', '{theme}'))")
    return c, p


def open_wiring(p, url):
    p.goto(f"{url}/build/board/book-trades")
    p.wait_for_function("window.drishtiUiBoard.designer.doc.chips && Object.keys(window.drishtiUiBoard.designer.doc.chips).length === 3"
                        " && window.drishtiUiBoard.designer.chipDoc('cashflow-ladder@1')")
    p.locator("[data-ub-view-to='wiring']").click()
    p.wait_for_selector(".ubw-box[data-box='flows']")


def test_the_wiring_view_shows_boxes_and_wires_in_words_and_edits_the_board(browser, stack):
    url, backend = stack
    put_board(backend, "book-trades", BOOK)
    c, p = new_page(browser)
    try:
        open_wiring(p, url)
        assert p.locator(".ub-canvas-wrap").is_hidden() and p.locator("[data-ubw]").is_visible()
        data = p.locator(".ubw-box[data-kind='data']")
        assert data.count() == 4                                   # trade search, trade, the trading-risk model, the board's settings
        assert "Trades (trading)" in data.all_text_contents()[0] and "connector · trade-search" in data.all_text_contents()[0]
        flows = p.locator(".ubw-box[data-box='flows']").text_content()
        assert "CHIP" in flows and "follows: chosen trade" in flows
        assert "gives: chosen trade" in p.locator(".ubw-box[data-box='trades']").text_content()
        labels = p.locator(".ubw-label").all_text_contents()
        assert labels.count("chosen trade") == 2 and "trades" in labels and "the trade" in labels and "as of" in labels
        assert p.locator(".ubw-key").text_content().count("data from a connector") == 1 and "a selection passed on" in p.locator(".ubw-key").text_content()
        # every segment of every wire is horizontal or vertical
        for pts in p.locator(".ubw-wire").evaluate_all("gs => gs.map(g => g.getAttribute('data-points'))"):
            xy = [tuple(map(float, q.split(","))) for q in pts.split()]
            assert all(a[0] == b[0] or a[1] == b[1] for a, b in zip(xy, xy[1:])), pts
        # no words of the engine here
        text = p.locator("[data-ubw]").text_content()
        assert "rowset" not in text and "filter-on" not in text and "@" not in text
        # click the wire into the ladder: a sentence, then Remove; the board document follows
        k = [i for i, w in enumerate(p.evaluate("() => window.drishtiUiBoard.wiring.model().wires")) if w["to"] == "flows" and w["kind"] == "sel"][0]
        p.locator(".ubw-wire").nth(k).locator(".ubw-label").click()
        pop = p.locator("[data-ubw-pop]")
        assert "Cash flows follows the trade chosen in Trades." in pop.text_content()
        pop.locator("[data-ubw-remove]").click()
        assert p.evaluate("window.drishtiUiBoard.designer.doc.traces.filter(t => t.to === 'flows.filter').length") == 0
        p.wait_for_function("document.querySelectorAll('.ubw-wire').length === 5")
        # connect the table to the ladder again: two things it could pass, asked in words
        grip = p.locator("[data-ubw-grip='trades']").bounding_box()
        box = p.locator(".ubw-box[data-box='flows']").bounding_box()
        p.mouse.move(grip["x"] + 22, grip["y"] + 22)
        p.mouse.down()
        p.mouse.move(grip["x"] + 80, grip["y"] + 60, steps=4)
        assert "ubw-ok" in p.locator(".ubw-box[data-box='flows']").get_attribute("class")
        assert "ubw-dim" in p.locator(".ubw-box[data-box='ticket']").get_attribute("class")      # the ticket already follows it
        p.mouse.move(box["x"] + 60, box["y"] + 50, steps=6)
        p.mouse.up()
        ask = p.locator("[data-ubw-ask]")
        assert "What should Trades pass to Cash flows?" in ask.text_content()
        ask.locator(".ubw-choice", has_text="the chosen trade, to filter by it").click()
        t = p.evaluate("window.drishtiUiBoard.designer.doc.traces.find(t => t.to === 'flows.filter')")
        assert t["from"] == "trades.selected" and t["transform"] == {"filter-on": "rows.trade"}
        p.wait_for_function("document.querySelectorAll('.ubw-wire').length === 6")
        if SHOTS:
            p.add_style_tag(content=".toasts { display: none !important; }")
            p.mouse.click(5, 300)
            p.evaluate("document.querySelector('.ubw-scroll').scrollLeft = 0; window.scrollTo(0, 0)")
            p.screenshot(path=str(IMG / "concept-b.png"))
        # a box dragged keeps its place in the board's layout
        b = p.locator(".ubw-box[data-box='ticket']").bounding_box()
        p.mouse.move(b["x"] + 100, b["y"] + 20)
        p.mouse.down()
        p.mouse.move(b["x"] + 160, b["y"] + 120, steps=5)
        p.mouse.up()
        assert p.evaluate("window.drishtiUiBoard.designer.doc.layout.wiring.ticket") is not None
        p.locator("[data-ub-view-to='page']").click()
        assert p.locator(".ub-canvas-wrap").is_visible() and p.locator("[data-ubw]").is_hidden()
        assert p.errors == []
    finally:
        c.close()


def guided(p, url, shot=None):
    p.goto(f"{url}/build/board/start")
    p.locator("[data-ubg-source]", has_text="Trades of one book").click()
    p.locator("[data-ubg-param='book']").fill("FX-LDN-01")
    p.locator("[data-ubg-param='book']").dispatch_event("change")
    p.wait_for_function("document.querySelector('[data-ubg-next]').disabled === false")
    p.locator("[data-ubg-next]").click()
    p.wait_for_selector("[data-ubg-pick]")
    assert p.locator("[data-ubg-pick]:checked").count() == 2
    p.wait_for_function("document.querySelectorAll('[data-ubg-preview] .ub-state[data-state]').length > 0 || document.querySelectorAll('[data-ubg-preview] .bi-vis').length > 0")
    if shot:
        p.add_style_tag(content=".toasts { display: none !important; }")
        p.wait_for_timeout(400)
        p.screenshot(path=str(IMG / shot))


def test_the_guided_new_board_goes_from_data_to_a_saved_board_in_the_designer(browser, stack):
    url, backend = stack
    c, p = new_page(browser, 1280, 800)
    try:
        assert p.goto(f"{url}/build/new").ok and p.locator("[data-new-board]").get_attribute("href") == "/build/board/start"
        guided(p, url, "concept-c.png" if SHOTS else None)
        assert "Data: Trades of one book · suggested for this data" in p.locator("[data-ubg-data-note]").text_content()
        cards = p.locator("[data-ubg-suggest] li")
        assert cards.count() >= 3 and "Filterable table" in cards.first.text_content()
        assert p.locator(".ubg-follows", has_text="Follows the trade you click in Filterable table").count() >= 1
        # the preview paints the table with the stand-in's trades, as you
        p.wait_for_function("document.querySelector('[data-ubg-preview]').textContent.includes('" + TRADES[0]["COUNTERPARTY_NAME"] + "')")
        doc = p.evaluate("window.drishtiUiBoard.guided.board()")
        assert doc["params"]["book"]["default"] == "FX-LDN-01" and doc["chips"]["filterable-table"]["supply"]["query"] == {"BOOK": "%{board.params.book}"}
        detail = [n for n, i in doc["chips"].items() if n != "filterable-table"][0]
        assert {"from": "filterable-table.selected", "to": detail + ".id"} in doc["traces"]
        # browse all chips: the Chip Store, and one more chip
        p.locator("[data-ubg-browse-open]").click()
        p.locator("[data-ubg-browse-q]").fill("cash-flow ladder")
        p.locator("[data-ubg-add='cashflow-ladder@1']").click()
        assert p.locator("[data-ubg-pick='cashflow-ladder@1']").is_checked()
        p.locator("[data-ubg-next]").click()
        # arrange: the ladder first, half width
        items = p.locator("[data-ubg-arrange] [data-ubg-item]")
        assert items.count() == 3
        p.locator("[data-ubg-up='cashflow-ladder@1']").click()
        p.locator("[data-ubg-up='cashflow-ladder@1']").click()
        p.locator("[data-ubg-item='cashflow-ladder@1'] [data-ubg-span='half']").click()
        assert p.locator("[data-ubg-arrange] [data-ubg-item]").first.get_attribute("data-ubg-item") == "cashflow-ladder@1"
        doc = p.evaluate("window.drishtiUiBoard.guided.board()")
        assert doc["layout"]["desktop"]["areas"][0] == {"chip": "cashflow-ladder", "at": [1, 1], "span": [6, 6]}
        assert {"from": "filterable-table.selected", "to": "cashflow-ladder.filter", "transform": {"filter-on": "rows.trade"}} in doc["traces"]
        for b in p.locator("[data-ubg-arrange] button").all():
            bb = b.bounding_box()
            assert bb["height"] >= 44 and bb["width"] >= 44
        p.locator("[data-ubg-next]").click()
        # save in a workspace; the designer opens on it
        p.locator("[data-ubg-name]").fill("london-fx-trades")
        p.locator("[name='ubg-where'][value='workspace']").check()
        p.locator("[data-ubg-ws]").fill("fx-desk")
        p.locator("[data-ubg-next]").click()
        p.wait_for_url("**/build/board/london-fx-trades?workspace=fx-desk")
        assert "london-fx-trades" in backend.boards
        p.wait_for_function("window.drishtiUiBoard.designer && Object.keys(window.drishtiUiBoard.designer.doc.chips).length === 3")
        assert p.errors == []
    finally:
        c.close()


@pytest.mark.parametrize("theme", ["light", "terminal-crimson"])
def test_on_a_phone_the_steps_stack_with_the_preview_below_in_two_themes(browser, stack, theme):
    url, _ = stack
    c, p = new_page(browser, 390, 844, theme)
    try:
        guided(p, url)
        left, pv = p.locator(".ubg-left").bounding_box(), p.locator(".ubg-preview").bounding_box()
        assert pv["y"] >= left["y"] + left["height"] - 1 and abs(left["x"] - 16) <= 1
        assert p.evaluate("() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")
        # the theme's tokens: the chosen card's border is the theme's accent
        acc = p.evaluate("getComputedStyle(document.documentElement).getPropertyValue('--d-accent').trim()")
        assert acc and p.locator(".ubg-check.ubg-on").first.evaluate("e => getComputedStyle(e).borderTopColor") != "rgb(0, 0, 0)"
        assert p.evaluate("document.documentElement.getAttribute('data-theme')") == theme
        assert p.errors == []
    finally:
        c.close()
