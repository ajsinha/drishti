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

"""Sutra Next events and children in a real browser (RACHANA_REFERENCE.md 7, 8), on the book desk (/v/portfolio-trades/<book>?sutra=book-desk):
before a click the right column says what to do; clicking a trade fills the counterparty and its credit limit (other packs) and opens the
trade's cash flows under the row, and only those panels move (the trades table is the same element); a new click replaces the children;
Enter on a focused row does what a click does; on a phone the children open under the row without the page scrolling sideways. Two themes.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py). Every wait is wb_live.BROWSER_WAIT_MS."""
import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

DESK = "/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from _stack(tmp_path_factory, {})


def open_desk(browser, base, theme, size):
    pg = browser.new_page(viewport=size)
    pg.add_init_script("document.addEventListener('DOMContentLoaded', () => { document.documentElement.setAttribute('data-theme', '%s');"
                       " window.__wire = []; document.addEventListener('drishti:wire', e => window.__wire.push(e.detail)); })" % theme)
    pg.goto(base + DESK)
    wait(pg, "document.querySelectorAll('#p-trades tr[data-ev]').length > 1")
    return pg


def cell(pg, row):
    """The second cell of a trade row: the first is the trade's link, which opens the trade instead of picking it."""
    return pg.locator("#p-trades tbody tr[data-ev]").nth(row).locator("td").nth(1)


def trade(pg, row):
    return pg.evaluate("i => document.querySelectorAll('#p-trades tbody tr[data-ev]')[i].cells[0].textContent.trim()", row)


def test_a_click_fills_the_listeners_and_the_row_and_a_new_click_replaces_them(stack, browser):
    pg = open_desk(browser, str(stack), "terminal", {"width": 1500, "height": 950})
    for p in ("counterparty", "limit", "buckets"):
        assert pg.locator(f"#p-{p} .pnl-wait").count() == 1, p
    assert pg.locator("#p-flows").count() == 0                                # children are lazy
    pg.evaluate("document.getElementById('p-trades').__mark = 1")
    first = trade(pg, 0)
    cell(pg, 0).click()
    wait(pg, "document.querySelector('tr.wire-kids #p-flows') && window.__wire.length === 1")
    assert pg.evaluate("document.getElementById('p-trades').__mark") == 1     # the clicked table was not redrawn
    assert sorted(pg.evaluate("window.__wire[0].panels")) == ["buckets", "counterparty", "limit"]
    assert pg.locator("#p-limit .pnl-wait").count() == 0
    assert pg.locator("#p-counterparty dd").first.inner_text().strip()
    assert pg.locator("#p-buckets .bars li").count() == 5
    assert pg.locator("#p-flows h3").inner_text() == f"Cash flows of {first}"
    assert pg.locator("tr.wire-kids #p-flows tbody tr").count() > 0
    assert pg.locator("#p-trades tbody tr[aria-selected='true']").count() == 1

    second = trade(pg, 1)
    pg.locator("#p-trades tbody tr[data-ev]").nth(1).focus()
    pg.keyboard.press("Enter")
    wait(pg, "window.__wire.length === 2")
    wait(pg, f"(document.querySelector('#p-flows h3') || {{}}).textContent === 'Cash flows of {second}'")
    assert pg.locator("tr.wire-kids").count() == 1                            # replaced, not added
    assert pg.evaluate("document.querySelectorAll('#p-trades tbody tr[data-ev]')[1].getAttribute('aria-selected')") == "true"
    pg.close()


PLACE = """place => {
  const sec = document.getElementById('p-trades'), html = document.getElementById('p-limit').outerHTML.replace(/p-limit/g, 'p-kid1').replace(/h-limit/g, 'h-kid1');
  window.drishtiWire.close('trades');
  window.drishtiWire.open(sec, null, { parent: 'trades', group: 0, place, show: 'on-select', hidden: false,
    panels: [{ id: 'kid1', title: 'One', html }, { id: 'kid2', title: 'Two', html: html.replace(/kid1/g, 'kid2') }] });
  const box = document.querySelector('[data-wire-kids="trades"]'), r = box.getBoundingClientRect(), s = sec.getBoundingClientRect();
  return { place: box.dataset.place, right: r.left >= s.right - 1, below: r.top >= s.bottom - 1, tabs: box.querySelectorAll('[role=tab]').length,
           shown: [...box.querySelectorAll('[role=tabpanel]')].filter(p => !p.hidden).length, sheet: getComputedStyle(box).position,
           wide: document.documentElement.scrollWidth <= window.innerWidth + 1 };
}"""


def test_children_open_beside_below_and_as_tabs_and_as_a_sheet_on_a_phone(stack, browser):
    pg = open_desk(browser, str(stack), "blue", {"width": 1500, "height": 950})
    right = pg.evaluate(PLACE, "detail-right")
    assert right["place"] == "detail-right" and right["right"] and pg.locator(".wire-split #p-trades").count() == 1
    below = pg.evaluate(PLACE, "detail-below")
    assert below["below"] and pg.locator(".wire-split").count() == 0          # closing the beside pane unwraps the split
    tabs = pg.evaluate(PLACE, "tabs")
    assert tabs["tabs"] == 2 and tabs["shown"] == 1
    pg.locator("[data-wire-kids='trades'] [role=tab]").nth(1).click()
    assert pg.evaluate("[...document.querySelectorAll('[data-wire-kids=trades] [role=tabpanel]')].map(p => p.hidden)") == [True, False]
    pg.set_viewport_size({"width": 390, "height": 844})
    sheet = pg.evaluate(PLACE, "detail-right")
    assert sheet["sheet"] == "fixed" and sheet["wide"]
    pg.locator("[data-wire-kids='trades'] .wire-back").click()
    assert pg.locator("[data-wire-kids='trades']").count() == 0
    pg.close()


def test_on_a_phone_in_the_light_theme_the_cash_flows_open_under_the_row(stack, browser):
    pg = open_desk(browser, str(stack), "light", {"width": 390, "height": 844})
    assert pg.evaluate("document.documentElement.getAttribute('data-theme')") == "light"
    cell(pg, 0).click()
    wait(pg, "document.querySelector('tr.wire-kids #p-flows tbody tr')")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), "the page scrolls sideways"
    accent = pg.evaluate("getComputedStyle(document.querySelector('tr.wire-kids > td')).borderLeftColor")
    assert accent and accent != "rgba(0, 0, 0, 0)"
    assert pg.locator("#p-limit .pnl-wait").count() == 0
    pg.close()
