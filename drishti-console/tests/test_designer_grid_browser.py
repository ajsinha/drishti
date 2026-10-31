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

"""The designer's Grid mode in a real browser (RACHANA_REFERENCE.md › Screen designer and the Build workbench, section 31), ui.designer on, a real server behind the console: a cell
picked shows its formula, a changed parameter redraws the preview in place (no reload); a cell reading another's selection fills on a
click in the preview; merge, split, insert a row, drag a cell; a bad formula says its red part and changes nothing; a column's width
dragged at its header's edge, typed on the header and moved with the arrow keys (layout.columns); the panel a click opens as a cell of its
own under its parent (A2a ... opens from A2), edited in place, and a panel a click in IT opens (A2a1 ... opens from A2a) that fills
on a click in its parent's opened panel; Outline and back with nothing lost; two themes and a phone; the screenshot
docs/guides/img/designer/grid-mode.jpg. Skipped as the other browser tests are."""
from pathlib import Path

import pytest
import yaml

from test_designer_browser import BOOK, page_in, stack  # noqa: F401 - the stack (ui.designer on) and pages
from test_workbench_browser import browser, wait  # noqa: F401
from wb_live import post
from docshots import docs_img

IMG = docs_img("guides/img/designer")                 # docs/guides/img/designer only when DRISHTI_SHOTS is set (docshots.py)
SUTRA = """rachana: 1
sutra: qa-book-sheet
version: 1
match: { kind: portfolio-trades }
title: { pill: Book, id: $.PORTFOLIO }
layout:
  areas: ["head head", "trades detail"]
  columns: [7, 5]
panels:
  - { id: pay7, kind: metric, title: "Payments, next 7 days", value: $.PAY_7D, fmt: signed0, area: head }
  - use: row-picker@1
    with: { id: trades, title: Trades of the book, rows: $.TRADES, event: open, key: TRADE_ID, key-label: Trade, ref: COUNTERPARTY,
            a: PRODUCT_TYPE, a-label: Product, b: COUNTERPARTY, b-label: Counterparty, amount: NOTIONAL, amount-label: Notional }
    area: trades
    children:
      - place: expand-row
        source: { pack: trading, kind: trade, id: "@parent.open.id" }
        panels:
          - use: cashflow-ladder@1
            with: { id: flows, title: Cash flows, rows: $src.EVENTS, date: EVENT_DATE, type: PAYMENT_TYPE, amount: AMOUNT }
  - { id: book, kind: kv, title: The book, area: detail, columns: [ { label: Trades, bind: $.TRADE_COUNT } ] }
"""
WHO = "field-list@1(id: who, title: Counterparty, rows: $src; source: {pack: core, kind: counterparty, id: @A2.open.ref})"


def sheet(browser, base, theme="dark", size=None, sutra=SUTRA):
    pg = page_in(browser, theme, size or {"width": 1500, "height": 950})
    pg.goto(base + "/")
    r = post(pg, base + "/build/designs", {"name": "", "kind": "portfolio-trades", "sutra": sutra})
    assert r["http"] == 201, r
    pg.goto(f"{base}/designer/grid?kind=portfolio-trades&id=BOOK-RATES-2&design={r['id']}")
    pg.draft = r["id"]
    if (size or {}).get("width", 1500) > 700:
        wait(pg, "document.querySelector('[data-grid][data-gd-rev]')")
    return pg


def rev(pg):
    return pg.get_attribute("[data-grid]", "data-gd-rev")


def stepped(pg, before):
    wait(pg, f"document.querySelector('[data-grid]').getAttribute('data-gd-rev') !== '{before}'")


def formula_of(pg, cell):
    pg.click(f'[data-gd-cell="{cell}"]')
    return pg.input_value("[data-gd-formula]")


def typed(pg, cell, text):
    pg.click(f'[data-gd-cell="{cell}"]')
    pg.fill("[data-gd-formula]", text)
    r = rev(pg)
    pg.evaluate("document.querySelector('[data-gd-suggest]').hidden = true")
    pg.press("[data-gd-formula]", "Enter")
    return r


def preview(pg):
    return pg.frame_locator("[data-gd-preview]")


def test_a_formula_changes_the_preview_in_place_and_a_cell_reads_another_cells_selection(browser, stack):
    pg = sheet(browser, stack)
    assert formula_of(pg, "A1") == 'metric(id: pay7, title: "Payments, next 7 days", value: $.PAY_7D, fmt: signed0)'
    assert pg.inner_text("[data-gd-name]") == "A1:B1"
    wait(pg, "!!document.querySelector('[data-gd-preview]').contentDocument.querySelector('#p-pay7')")
    pg.evaluate("document.querySelector('[data-gd-preview]').contentWindow.__kept = 1")
    stepped(pg, typed(pg, "A1", 'metric(id: pay7, title: "Payments this week", value: $.PAY_7D, fmt: signed0)'))
    wait(pg, "document.querySelector('[data-gd-preview]').contentDocument.querySelector('#p-pay7').textContent.includes('Payments this week')")
    assert pg.evaluate("document.querySelector('[data-gd-preview]').contentWindow.__kept") == 1, "the preview reloaded"
    # suggestions: the panels' events by cell
    pg.click('[data-gd-cell="B2"]')
    pg.fill("[data-gd-formula]", "field-list@1(id: who; source: {pack: core, kind: counterparty, id: @A2")
    pg.press("[data-gd-formula]", "End")
    pg.keyboard.type(".")
    wait(pg, "!document.querySelector('[data-gd-suggest]').hidden")
    assert "@A2.open.ref" in pg.locator("[data-gd-suggest]").inner_text()
    # B2 reads A2's selection: a click on a trade in the preview fills it
    stepped(pg, typed(pg, "B2", WHO))
    assert "@A2.open.ref" in pg.inner_text('[data-gd-cell="B2"] .gd-f')
    doc = yaml.safe_load(pg.locator("[data-gd-sutra]").text_content())
    assert doc["panels"][2]["source"]["id"] == "@trades.open.ref"
    wait(pg, "!!(document.querySelector('[data-gd-preview]').contentWindow.drishtiWire && document.querySelector('[data-gd-preview]').contentDocument.querySelector('#p-trades tbody tr[data-ev]'))")
    preview(pg).locator("#p-trades tbody tr[data-ev] td:nth-child(2)").first.click()
    wait(pg, "!!document.querySelector('[data-gd-preview]').contentDocument.querySelector('#p-who dd, #p-who td')")
    pg.close()


def test_merge_split_insert_drag_and_a_bad_formula(browser, stack):
    pg = sheet(browser, stack, theme="light")
    typed(pg, "A1", "metric(id: pay7")                                         # nothing changes: the part to fix is said
    wait(pg, "!document.querySelector('[data-gd-error]').hidden")
    assert pg.inner_text("[data-gd-red]").startswith("(id: pay7")
    assert "not closed" in pg.inner_text("[data-gd-why]")
    pg.press("[data-gd-formula]", "Escape")
    assert formula_of(pg, "A1").startswith('metric(id: pay7, title: "Payments')       # the cell kept its last good state
    # split the head, merge it back
    r = rev(pg)
    pg.click("[data-gd-split]")
    stepped(pg, r)
    assert pg.locator('[data-gd-cell="B1"]').count() == 1 and pg.get_attribute('[data-gd-cell="A1"]', "data-gd-range") == "A1"
    pg.click('[data-gd-cell="A1"]')
    pg.click('[data-gd-cell="B1"]', modifiers=["Shift"])
    assert pg.inner_text("[data-gd-name]") == "A1:B1"
    r = rev(pg)
    pg.click("[data-gd-merge]")
    stepped(pg, r)
    assert pg.get_attribute('[data-gd-cell="A1"]', "data-gd-range") == "A1:B1"
    # insert a row below the head; drag the trades (A3) onto the new empty A2
    pg.click('[data-gd-cell="A1"]')
    r = rev(pg)
    pg.click('[data-gd-insert="row-below"]')
    stepped(pg, r)
    assert pg.locator(".gd-rowh").count() == 3 and "empty" in pg.inner_text('[data-gd-cell="A2"]')
    r = rev(pg)
    pg.locator('[data-gd-cell="A3"]').drag_to(pg.locator('[data-gd-cell="A2"]'))
    stepped(pg, r)
    doc = yaml.safe_load(pg.locator("[data-gd-sutra]").text_content())
    assert doc["layout"]["areas"] == ["head head", "trades .", ". detail"]
    # the keyboard: arrows move, Enter edits, Esc goes back
    pg.click('[data-gd-cell="A2"]')
    pg.keyboard.press("ArrowRight")
    assert pg.inner_text("[data-gd-name]") == "B2"
    pg.keyboard.press("ArrowDown")
    pg.keyboard.press("Enter")
    assert pg.evaluate("document.activeElement.matches('[data-gd-formula]')") and pg.input_value("[data-gd-formula]").startswith("kv(id: book")
    pg.keyboard.press("Escape")
    assert pg.evaluate("document.activeElement.getAttribute('data-gd-cell')") == "B3"
    pg.close()


def sutra_of(pg):
    return yaml.safe_load(pg.locator("[data-gd-sutra]").text_content())


def test_column_widths_and_a_panel_a_click_opens_as_its_own_cell(browser, stack):
    pg = sheet(browser, stack)
    assert pg.inner_text('[data-gd-width="0"]') == "7/12" and pg.inner_text('[data-gd-width="1"]') == "5/12"
    edge = pg.locator('[data-gd-edge="0"]').bounding_box()
    per = pg.locator('[data-gd-col="0"]').bounding_box()["width"] / 7
    x, y = edge["x"] + edge["width"] / 2, edge["y"] + edge["height"] / 2
    r = rev(pg)
    pg.mouse.move(x, y)
    pg.mouse.down()
    pg.mouse.move(x + per * 2.1, y, steps=8)                                   # two units to the right
    assert pg.inner_text('[data-gd-width="0"]') == "9/12"                       # shown as it is dragged
    pg.mouse.up()
    stepped(pg, r)
    assert sutra_of(pg)["layout"]["columns"] == [9, 3]
    r = rev(pg)
    pg.click('[data-gd-width="1"]')                                            # a width typed: its neighbour gives the units
    pg.fill('[data-gd-width-in="1"]', "4")
    pg.press('[data-gd-width-in="1"]', "Enter")
    stepped(pg, r)
    assert sutra_of(pg)["layout"]["columns"] == [8, 4]
    r = rev(pg)
    pg.focus('[data-gd-edge="0"]')
    pg.keyboard.press("ArrowLeft")
    stepped(pg, r)
    assert sutra_of(pg)["layout"]["columns"] == [7, 5] and pg.inner_text('[data-gd-width="0"]') == "7/12"
    # the cash flows a click on a trade opens: a cell of their own, under the trades
    kid = pg.locator('[data-gd-kid="A2a"]')
    assert "↳ A2a" in kid.inner_text() and "opens from A2" in kid.inner_text()
    pg.click('[data-gd-cell="A2"] > .gd-ref')
    pg.keyboard.press("ArrowDown")
    assert pg.inner_text("[data-gd-name]") == "A2a"
    assert pg.input_value("[data-gd-formula]").startswith("cashflow-ladder@1(id: flows, title: Cash flows,")
    pg.keyboard.press("ArrowUp")
    assert pg.inner_text("[data-gd-name]") == "A2"
    text = formula_of(pg, "A2a").replace("title: Cash flows", "title: Flows of the trade")
    stepped(pg, typed(pg, "A2a", text))
    child = sutra_of(pg)["panels"][1]["children"][0]["panels"][0]
    assert child["with"]["title"] == "Flows of the trade" and child["with"]["amount"] == "AMOUNT"
    assert "Flows of the trade" in pg.inner_text('[data-gd-kid="A2a"] .gd-f')
    typed(pg, "A2a", text.replace("opens from A2", "opens from B2"))           # it stays under its table: said, nothing changes
    wait(pg, "!document.querySelector('[data-gd-error]').hidden")
    assert pg.inner_text("[data-gd-red]") == "B2" and "Outline" in pg.inner_text("[data-gd-why]")
    pg.close()


def test_outline_and_back_with_nothing_lost_the_screenshot_and_a_phone(browser, stack):
    pg = sheet(browser, stack)
    stepped(pg, typed(pg, "B2", WHO))
    pg.click('[data-gd-cell="A2a"]')
    pg.evaluate("window.scrollTo(0, 0)")
    IMG.mkdir(parents=True, exist_ok=True)
    pg.screenshot(path=str(IMG / "grid-mode.jpg"), type="jpeg", quality=82, full_page=False)
    with pg.expect_navigation():
        pg.click('[data-dz-mode="outline"]')
    wait(pg, "document.querySelector('[data-ol-rev]')")
    assert pg.locator('[data-ol-panel="who"]').count() == 1                    # the same draft: Grid's change is there
    with pg.expect_navigation():
        pg.click('[data-dz-mode="grid"]')
    wait(pg, "document.querySelector('[data-grid][data-gd-rev]')")
    assert formula_of(pg, "B2") == WHO
    url = pg.url
    pg.close()
    phone = page_in(browser, "light", {"width": 390, "height": 844})
    phone.goto(url)
    assert phone.locator("[data-gd-narrow]").is_visible() and not phone.locator("[data-gd-sheet]").is_visible()
    assert "/designer/outline" in phone.get_attribute("[data-gd-narrow] a", "href")
    assert phone.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    phone.close()


@pytest.mark.parametrize("theme", ["dark", "light"])
def test_the_sheet_in_two_themes_and_the_default_mode(browser, stack, theme):
    pg = sheet(browser, stack, theme=theme)
    colours = pg.evaluate("[getComputedStyle(document.querySelector('.gd-cell')).backgroundColor, getComputedStyle(document.querySelector('.gd-title')).color]")
    assert colours[0] != colours[1]
    pg.select_option("[data-dz-default]", "grid")
    wait(pg, "document.querySelector('[data-dz-default]').getAttribute('data-dz-saved') === 'grid'")
    pg.goto(stack + "/designer")
    assert "/designer/grid" in pg.url
    pg.select_option("[data-dz-default]", "customize")                          # as it was, for the other tests
    wait(pg, "document.querySelector('[data-dz-default]').getAttribute('data-dz-saved') === 'customize'")
    pg.close()


DEEP = """rachana: 1
sutra: qa-book-deep
version: 1
match: { kind: portfolio-trades }
title: { pill: Book, id: $.PORTFOLIO }
layout:
  areas: ["trades detail"]
  columns: [8, 4]
panels:
  - use: row-picker@1
    with: { id: trades, title: Trades of the book, rows: $.TRADES, event: open, key: TRADE_ID, key-label: Trade, ref: COUNTERPARTY,
            a: PRODUCT_TYPE, a-label: Product }
    area: trades
    children:
      - place: detail-below
        source: { pack: trading, kind: trade, id: "@parent.open.id" }
        panels:
          - id: flows
            kind: table
            title: Cash flows
            rows: $src.EVENTS
            columns: [ { label: Date, bind: "@.EVENT_DATE" }, { label: Amount, bind: "@.AMOUNT" } ]
            emits: [ { name: open, on: row-click, payload: { when: "@.EVENT_DATE" } } ]
            children:
              - place: detail-below
                panels:
                  - { id: event, kind: kv, title: "The flow of ${@parent.open.when}", columns: [ { label: Picked, bind: "@parent.open.when" } ] }
  - { id: book, kind: kv, title: The book, area: detail, columns: [ { label: Trades, bind: $.TRADE_COUNT } ] }
"""


def test_a_panel_a_click_in_an_opened_panel_opens_is_a_cell_one_step_further_in(browser, stack):
    pg = sheet(browser, stack, sutra=DEEP)
    kid = pg.locator('[data-gd-kid="A1a1"]')
    assert "↳ A1a1" in kid.inner_text() and "opens from A1a" in kid.inner_text() and "gd-kid2" in kid.get_attribute("class")
    assert "children" not in pg.inner_text('[data-gd-kid="A1a"]')              # its children show below it, as cells
    pg.click('[data-gd-cell="A1"] > .gd-ref')                                  # Down: parent, child, grandchild; Up: back
    for name in ("A1a", "A1a1"):
        pg.keyboard.press("ArrowDown")
        assert pg.inner_text("[data-gd-name]") == name
    f = pg.input_value("[data-gd-formula]")
    assert f.startswith("kv(id: event,") and f.endswith("opens from A1a"), f
    pg.keyboard.press("ArrowUp")
    pg.keyboard.press("ArrowUp")
    assert pg.inner_text("[data-gd-name]") == "A1"
    # suggestions inside a child cell: @parent. with what the parent emits, and the opened cells by their own names
    formula_of(pg, "A1a1")
    for word, want in (("@p", "@parent.open.when"), ("@A1", "@A1a.open.when")):
        pg.fill("[data-gd-formula]", "kv(id: event, title: " + word[:-1])
        pg.press("[data-gd-formula]", "End")
        pg.keyboard.type(word[-1])
        wait(pg, "!document.querySelector('[data-gd-suggest]').hidden")
        assert want in pg.locator("[data-gd-suggest]").inner_text(), want
    pg.press("[data-gd-formula]", "Escape")
    text = formula_of(pg, "A1a1").replace("The flow of", "Picked flow")
    stepped(pg, typed(pg, "A1a1", text))
    grand = sutra_of(pg)["panels"][0]["children"][0]["panels"][0]["children"][0]["panels"][0]
    assert grand["title"] == "Picked flow ${@parent.open.when}" and grand["columns"] == [{"label": "Picked", "bind": "@parent.open.when"}]
    typed(pg, "A1a1", text.replace("opens from A1a", "opens from A1"))         # it stays under its table: said, nothing changes
    wait(pg, "!document.querySelector('[data-gd-error]').hidden")
    assert pg.inner_text("[data-gd-red]") == "A1"
    pg.press("[data-gd-formula]", "Escape")
    # in the preview: a trade opens its cash flows, a click on a flow opens the grandchild, which fills
    wait(pg, "!!(document.querySelector('[data-gd-preview]').contentWindow.drishtiWire && document.querySelector('[data-gd-preview]').contentDocument.querySelector('#p-trades tbody tr[data-ev]'))")
    assert preview(pg).locator("#p-event").count() == 0
    preview(pg).locator("#p-trades tbody tr[data-ev] td:nth-child(2)").first.click()
    wait(pg, "!!document.querySelector('[data-gd-preview]').contentDocument.querySelector('#p-flows tbody tr[data-ev]')")
    preview(pg).locator("#p-flows tbody tr[data-ev] td").first.click()
    wait(pg, "/Picked flow \\d/.test((document.querySelector('[data-gd-preview]').contentDocument.querySelector('#p-event') || {}).textContent || '')")
    pg.close()
