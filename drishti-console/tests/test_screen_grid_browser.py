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

"""The layout grid (SUTRA_NEXT.md 13) and the designer's Outline mode (SCREEN_DESIGNER.md 31) in a real browser, a real server behind:

* a book screen with areas, a split (dragged, kept for the reader after a reload), tabs, a sticky header and user-can-arrange (a panel
  dragged to another area and resized, kept; Reset layout gives the author's); a flow screen with collapsible sections (kept);
* Outline on the book page: reorder, nest (it becomes a child that opens on a click), un-nest, add a section, the live preview in place;
* a phone (one column, the phone order, no sideways scroll) and two themes; the screenshots (docs/guides/img/designer/)."""
import json
from pathlib import Path

import pytest

from test_designer_browser import BOOK, page_in, stack  # noqa: F401 - the stack (ui.designer on) and pages
from test_workbench_browser import browser, wait  # noqa: F401
from wb_live import post
from docshots import docs_img

IMG = docs_img("guides/img/designer")                 # docs/guides/img/designer only when DRISHTI_SHOTS is set (docshots.py)
TABLE = """  - id: trades
    kind: table
    title: Trades
    rows: $.TRADES
    columns:
      - { label: Trade, bind: "@.TRADE_ID" }
      - { label: Product, bind: "@.PRODUCT_TYPE" }
      - { label: Notional, bind: "@.NOTIONAL", fmt: amount0 }
"""
AREAS = """rachana: 1
sutra: qa-book-grid
version: 1
match: { kind: portfolio-trades }
title: { pill: Book, id: $.PORTFOLIO }
layout:
  areas: ["head head", "trades detail"]
  columns: [7, 5]
  split: { between: [trades, detail] }
  tabs: [detail]
  sticky: head
  user-can-arrange: true
  phone: [head, detail, trades]
panels:
  - { id: pay7, kind: metric, title: "Payments, next 7 days", value: $.PAY_7D, fmt: signed0, area: head, span: 4 }
  - { id: pay30, kind: metric, title: "Payments, next 30 days", value: $.PAY_30D, fmt: signed0, area: head, span: 4 }
  - { id: pay90, kind: metric, title: "Payments, next 90 days", value: $.PAY_90D, fmt: signed0, area: head, span: 4 }
""" + TABLE.replace("    rows:", "    area: trades\n    rows:") + """  - { id: book, kind: kv, title: The book, area: detail, columns: [ { label: Trades, bind: $.TRADE_COUNT }, { label: Notional, bind: $.NOTIONAL, fmt: compact } ] }
  - { id: spread, kind: kv, title: Its spread, area: detail, collapsible: true, columns: [ { label: Currencies, bind: "size($.CURRENCIES)" }, { label: Products, bind: "size($.PRODUCT_TYPES)" } ] }
"""
SECTIONS = """rachana: 1
sutra: qa-book-sections
version: 1
match: { kind: portfolio-trades }
title: { pill: Book, id: $.PORTFOLIO }
layout:
  sections:
    - { id: payments, title: Payments }
    - { id: details, title: Book details, collapsed: true }
panels:
  - { id: pay7, kind: metric, title: "Payments, next 7 days", value: $.PAY_7D, fmt: signed0, section: payments, span: 6 }
  - { id: pay30, kind: metric, title: "Payments, next 30 days", value: $.PAY_30D, fmt: signed0, section: payments, span: 6 }
  - { id: book, kind: kv, title: The book, section: details, collapsible: true, columns: [ { label: Trades, bind: $.TRADE_COUNT } ] }
""" + TABLE

# The book page as the Outline test edits it: three metrics and a table written out (the pack's book page is now made of fragments,
# trading/payments-metrics@1 and trading/trade-table@1, which Outline lists as one item each and does not nest under)
OUTLINE = """rachana: 1
sutra: qa-book-outline
version: 1
match: { kind: portfolio-trades }
title: { pill: Book, id: $.PORTFOLIO }
panels:
  - { id: pay7, kind: metric, title: "Payments, next 7 days", value: $.PAY_7D, fmt: signed0, span: 4 }
  - { id: pay30, kind: metric, title: "Payments, next 30 days", value: $.PAY_30D, fmt: signed0, span: 4 }
  - { id: pay90, kind: metric, title: "Payments, next 90 days", value: $.PAY_90D, fmt: signed0, span: 4 }
""" + TABLE + """  - { id: by-product, kind: hbar, title: Notional by product type, rows: "group($.TRADES, 'PRODUCT_TYPE', 'NOTIONAL')", fmt: compact }
"""


def design(pg, base, text):
    r = post(pg, base + "/build/designs", {"name": "", "kind": "portfolio-trades", "sutra": text})
    assert r["http"] == 201, r
    return base + BOOK + "?design=" + r["id"]


def no_sideways(pg):
    return pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")


@pytest.mark.parametrize("theme", ["dark", "light"])
def test_areas_split_tabs_and_arrange(browser, stack, theme):
    pg = page_in(browser, theme, {"width": 1440, "height": 950})
    url = design(pg, stack, AREAS)
    pg.request.delete(stack + "/me/arrange/qa-book-grid", headers={"Origin": stack})      # each theme starts from the author's layout
    pg.goto(url)
    wait(pg, "document.querySelector('[data-sgrid][data-sg-ready] .sg-split')")
    # the picture: head across, trades left of detail; detail shows tabs
    box = {n: pg.locator(f'[data-sg-area="{n}"]').bounding_box() for n in ("head", "trades", "detail")}
    assert box["head"]["width"] > box["trades"]["width"] + box["detail"]["width"] - 40 and box["trades"]["x"] < box["detail"]["x"]
    assert pg.locator("#p-book").is_visible() and not pg.locator("#p-spread").is_visible()
    pg.click('[data-sg-tab="spread"]')
    assert pg.locator("#p-spread").is_visible() and not pg.locator("#p-book").is_visible()
    pg.click('[data-sg-fold-panel="spread"]')
    wait(pg, "document.querySelector('#p-spread.sg-folded')")
    # the split: drag the divider to the right, the trades area grows, kept after a reload
    before = box["trades"]["width"]
    pg.evaluate("document.querySelector('[data-sgrid]').removeAttribute('data-sg-saved')")
    s = pg.locator("[data-sg-split]").bounding_box()
    pg.mouse.move(s["x"] + s["width"] / 2, s["y"] + 20)
    pg.mouse.down()
    pg.mouse.move(s["x"] + 160, s["y"] + 20, steps=8)
    pg.mouse.up()
    wait(pg, "document.querySelector('[data-sgrid][data-sg-saved]')")
    grown = pg.locator('[data-sg-area="trades"]').bounding_box()["width"]
    assert grown > before + 80
    if theme == "dark":
        pg.screenshot(path=str(IMG / "layout-grid.jpg"), type="jpeg", quality=80, full_page=False)
    pg.reload()
    wait(pg, "document.querySelector('[data-sgrid][data-sg-ready]')")
    assert abs(pg.locator('[data-sg-area="trades"]').bounding_box()["width"] - grown) < 30
    assert pg.locator("#p-spread.sg-folded").count() == 1
    # user-can-arrange: drag the book panel into the trades area, make it narrower; kept; Reset gives the author's layout
    pg.evaluate("document.querySelector('[data-sgrid]').removeAttribute('data-sg-saved')")
    pg.locator('#p-book [data-sg-handle]').drag_to(pg.locator("#p-trades"), target_position={"x": 80, "y": 14})
    wait(pg, "document.querySelector('[data-sg-area=\"trades\"] #p-book')")
    g = pg.locator("#p-book [data-sg-grip]")
    g.focus()
    pg.wait_for_timeout(300)
    pg.evaluate("document.querySelector('[data-sgrid]').removeAttribute('data-sg-saved')")
    for _ in range(4):
        pg.keyboard.press("ArrowLeft")
    wait(pg, "document.querySelector('#p-book.c-span-8') && document.querySelector('[data-sgrid][data-sg-saved]')")
    pg.reload()
    wait(pg, "document.querySelector('[data-sgrid][data-sg-ready]')")
    assert pg.locator('[data-sg-area="trades"] #p-book.c-span-8').count() == 1
    with pg.expect_navigation():
        pg.click("[data-sg-reset]")
    wait(pg, "document.querySelector('[data-sgrid][data-sg-ready]')")
    assert pg.locator('[data-sg-area="detail"] #p-book').count() == 1 and pg.locator("#p-spread.sg-folded").count() == 0
    pg.close()


def test_sections_and_a_phone(browser, stack):
    pg = page_in(browser, "light", {"width": 1280, "height": 900})
    url = design(pg, stack, SECTIONS)
    pg.goto(url)
    wait(pg, "document.querySelector('[data-sgrid][data-sg-ready]')")
    assert pg.locator('[data-sg-in="payments"]').is_visible() and not pg.locator('[data-sg-in="details"]').is_visible()
    assert pg.locator(".sg-top #p-trades").count() == 1
    pg.evaluate("document.querySelector('[data-sgrid]').removeAttribute('data-sg-saved')")
    pg.click('[data-sg-fold="details"]')
    wait(pg, "document.querySelector('[data-sg-in=\"details\"]:not([hidden])') && document.querySelector('[data-sgrid][data-sg-saved]')")
    pg.reload()
    wait(pg, "document.querySelector('[data-sgrid][data-sg-ready]')")
    assert pg.locator('[data-sg-in="details"]').is_visible()
    pg.close()
    # a phone: one column in the phone order, 16 px gutters, no sideways scroll
    ph = page_in(browser, "dark", {"width": 390, "height": 844})
    ph.goto(design(ph, stack, AREAS))
    wait(ph, "document.querySelector('[data-sgrid][data-sg-ready]')")
    assert no_sideways(ph)
    y = {pid: ph.locator(f"#p-{pid}").bounding_box()["y"] for pid in ("pay7", "book", "trades")}
    assert y["pay7"] < y["book"] < y["trades"]                          # head, detail, trades: the phone order
    left = ph.locator("#p-trades").bounding_box()["x"]
    assert 12 <= left <= 20
    ph.close()


def test_outline_reorder_nest_unnest_and_add_a_section(browser, stack):
    pg = page_in(browser, "dark", {"width": 1500, "height": 950})
    made = post(pg, stack + "/build/designs", {"name": "", "kind": "portfolio-trades", "sutra": OUTLINE})
    assert made["http"] == 201, made
    pg.goto(stack + "/designer/outline?kind=portfolio-trades&id=BOOK-RATES-2&design=" + made["id"])
    wait(pg, "document.querySelector('[data-ol-rev]')")
    titles = lambda: pg.evaluate("[...document.querySelectorAll('[data-ol-panel] > .ol-row .ol-title')].map(t => t.textContent)")  # noqa: E731
    first = titles()
    assert first[:4] == ["Payments, next 7 days", "Payments, next 30 days", "Payments, next 90 days", "Trades"]
    assert pg.locator('[data-ol-panel="trades"] .ol-desc').first.inner_text().startswith("A table of trades")
    # reorder: drag the third metric above the first
    rev = pg.get_attribute("[data-outline]", "data-ol-rev")
    pg.locator('[data-ol-panel="pay90"]').drag_to(pg.locator('[data-ol-panel="pay7"] > .ol-row'))
    wait(pg, f"document.querySelector('[data-outline]').getAttribute('data-ol-rev') !== '{rev}'")
    assert titles()[:3] == ["Payments, next 90 days", "Payments, next 7 days", "Payments, next 30 days"]
    # nest: drop the 7-day metric on the trades table's "opens on a click" line, say where, Make it so
    rev = pg.get_attribute("[data-outline]", "data-ol-rev")
    pg.locator('[data-ol-panel="pay7"]').drag_to(pg.locator('[data-ol-nest="trades"]'), force=True)
    pg.select_option("[data-ol-place]", "detail-below")
    pg.click("[data-ol-nest-apply]")
    wait(pg, f"document.querySelector('[data-outline]').getAttribute('data-ol-rev') !== '{rev}' && document.querySelector('[data-ol-child=\"pay7\"]')")
    assert "opens below the table" in pg.locator('[data-ol-child="pay7"] .ol-desc').inner_text()
    # the live preview: a click on a trade opens it below the table
    fr = pg.frame_locator("[data-ol-preview]")
    wait(pg, "!!(document.querySelector('[data-ol-preview]').contentWindow.drishtiWire && document.querySelector('[data-ol-preview]').contentDocument.querySelector('#p-trades tbody tr[data-ev]'))")
    fr.locator("#p-trades tbody tr[data-ev] td:nth-child(3)").first.click()
    wait(pg, "[...document.querySelector('[data-ol-preview]').contentDocument.querySelectorAll('[data-panel=\"pay7\"]')].some(e => e.offsetParent)")
    pg.evaluate("window.scrollTo(0, 0)")
    pg.screenshot(path=str(IMG / "outline.jpg"), type="jpeg", quality=80, full_page=False)
    # un-nest: pick the child, press Un-nest; it is back on the page
    rev = pg.get_attribute("[data-outline]", "data-ol-rev")
    pg.click('[data-ol-child="pay7"]')
    pg.click("[data-ol-unnest]")
    wait(pg, f"document.querySelector('[data-outline]').getAttribute('data-ol-rev') !== '{rev}' && document.querySelector('[data-ol-panel=\"pay7\"]')")
    assert pg.locator('[data-ol-child="pay7"]').count() == 0
    # add a section, then move a panel into it from its settings
    rev = pg.get_attribute("[data-outline]", "data-ol-rev")
    pg.once("dialog", lambda d: d.accept("Risk"))
    pg.click("[data-ol-add-section]")
    wait(pg, f"document.querySelector('[data-outline]').getAttribute('data-ol-rev') !== '{rev}' && document.querySelector('[data-ol-sec=\"risk\"]')")
    rev = pg.get_attribute("[data-outline]", "data-ol-rev")
    pg.locator('[data-ol-panel="pay30"] > .ol-row').click()
    pg.select_option("[data-ol-set-section]", "risk")
    wait(pg, f"document.querySelector('[data-outline]').getAttribute('data-ol-rev') !== '{rev}' && document.querySelector('[data-ol-sec=\"risk\"] [data-ol-panel=\"pay30\"]')")
    wait(pg, "!!document.querySelector('[data-ol-preview]').contentDocument.querySelector('[data-sg-section=\"risk\"] #p-pay30')")
    doc = pg.locator("[data-ol-sutra]").text_content()
    assert "sections:" in doc and "section: risk" in doc
    assert json.loads(json.dumps(pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")))
    pg.click("[data-ol-cancel]")
    pg.wait_for_load_state()
    pg.close()
