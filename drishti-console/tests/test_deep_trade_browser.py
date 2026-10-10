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

"""A deep trade (trade, legs, each leg's cashflows, each floating cashflow's fixings) in a real browser against a real server: a
commodity swap with three legs shows three tabs, each with the leg's terms and its cashflow table; a floating cashflow opens to its
fixings (and the leg's tab keeps its place); a live tick moves a fixing deep in the document and the nested cell follows; and a phone
(390 px) never scrolls sideways. Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
import json

import pytest

from conftest import CONSOLE
from test_workbench_browser import browser, page, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack


@pytest.fixture(scope="module")
def live_console(tmp_path_factory):
    """A real server with the trading pack (its swaps are the deep trades), and a console in front of it."""
    yield from _stack(tmp_path_factory, {"DRISHTI_PACKS": "trading"})


SAMPLES = CONSOLE.parent / "config" / "packs" / "trading" / "samples" / "trade"


def commodity_swap(legs: int) -> str:
    """The id of a sample commodity swap with that many legs (the generator fixes them: see tools/packgen/banking/data_deep.py)."""
    for f in sorted(SAMPLES.glob("END-*.json")):
        d = json.loads(f.read_text())
        if d["productType"] == "CMD_SWAP" and len(d["legs"]) == legs:
            return d["tradeId"]
    raise AssertionError(f"no commodity swap sample with {legs} legs")


def open_trade(page, base, trade_id):
    page.goto(f"{base}/v/trade/{trade_id}")
    page.locator("#p-legs .tab-head").first.wait_for()
    return page.locator("#p-legs")


def test_a_three_leg_commodity_swap_shows_three_tabs_each_with_its_terms_and_cashflows(live_console, page):
    legs = open_trade(page, live_console, commodity_swap(3))
    heads = legs.locator(".tab-head")
    assert heads.count() == 3
    titles = [heads.nth(i).inner_text() for i in range(3)]
    assert titles[0].startswith("Leg 1 · ") and "fixed" in titles[0], titles
    assert titles[1].startswith("Leg 2 · ") and "settle" in titles[1], titles
    assert titles[2].startswith("Leg 3 · ") and "fee" in titles[2], titles
    for i in range(3):
        heads.nth(i).click()
        pane = legs.locator(".tab-pane:not([hidden])")
        assert pane.locator(".tab-part").count() == 2                                   # the leg's terms, then its cashflows
        assert pane.locator(".tab-part dl.kv dt", has_text="Pay/receive").count() == 1
        assert pane.locator("table.tbl-tree tbody tr").count() >= 6                     # a cashflow a month, at least half a year
    assert not page.errors


def test_a_four_leg_swap_has_four_tabs(live_console, page):
    legs = open_trade(page, live_console, commodity_swap(4))
    assert legs.locator(".tab-head").count() == 4
    assert "differential" in legs.locator(".tab-head").nth(3).inner_text()


def test_a_floating_cashflow_opens_to_its_fixings_and_a_fixed_one_has_none(live_console, page):
    legs = open_trade(page, live_console, commodity_swap(3))
    legs.locator(".tab-head").nth(1).click()                                          # the index leg: a fixing a business day
    pane = legs.locator(".tab-pane:not([hidden])")
    rows = pane.locator("table.tbl-tree tbody tr.tr-node")
    assert rows.filter(has=page.locator("button.tr-tog")).count() >= 6                  # every cashflow of the index leg has fixings
    first = rows.first
    assert first.locator("button.tr-tog").get_attribute("aria-expanded") == "false"
    assert pane.locator("tr[data-depth='1']:visible").count() == 0                       # the fixings are shut
    first.locator("button.tr-tog").click()
    wait(page, "document.querySelectorAll('#p-legs .tab-pane:not([hidden]) tr[data-depth=\"1\"]:not([hidden])').length >= 15")
    fixing = pane.locator("tr[data-depth='1']:not([hidden])").first
    assert fixing.locator("td").nth(0).inner_text()[:2] == "20"                         # a fixing date
    assert "settlement" in pane.locator("tr[data-depth='1']:not([hidden])").first.inner_text() or "Forward curve" in fixing.inner_text()
    legs.locator(".tab-head").nth(0).click()                                          # the fixed leg
    assert legs.locator(".tab-pane:not([hidden]) button.tr-tog").count() == 0
    assert not page.errors


def test_what_a_reader_opened_stays_open_when_a_live_tick_repaints_the_panel(live_console, page):
    legs = open_trade(page, live_console, commodity_swap(3))
    legs.locator(".tab-head").nth(1).click()
    pane = legs.locator(".tab-pane:not([hidden])")
    page.evaluate("() => { window.__repaints = 0; new MutationObserver(() => { window.__repaints++; }).observe(document.getElementById('p-legs').parentNode, { childList: true }); }")
    pane.locator("button.tr-tog").nth(1).click()                                          # the second cashflow of the index leg
    open_rows = "document.querySelectorAll('#legs-pane-1 tr[data-depth=\"1\"]:not([hidden])').length"
    wait(page, f"{open_rows} >= 15")
    wait(page, "window.__repaints >= 1")                                                  # the panel was replaced by a tick
    assert page.evaluate(open_rows) >= 15
    assert legs.locator(".tab-head").nth(1).get_attribute("aria-selected") == "true"


FIXING_RATE = """([leg, cf, fix]) => {
  const pane = document.getElementById('legs-pane-' + leg);
  let n = -1, k = -1;
  for (const r of pane.querySelectorAll('table.tbl-tree tbody tr.tr-node')) {
    if (r.dataset.depth === '0') { n++; k = -1; } else { k++; }
    if (n === cf && r.dataset.depth === '1' && k === fix) { return r.children[5].textContent.trim(); }
  }
  return null;
}"""


def test_a_live_tick_moves_a_fixing_deep_in_the_document(live_console, page):
    trade = commodity_swap(3)
    legs = open_trade(page, live_console, trade)
    walk = json.loads((SAMPLES / f"{trade}.json").read_text())["_meta"]["walk"]
    steps = next(k for k in walk if k.startswith("legs.")).split(".")                  # legs.1.cashflows.N.fixings.M.rate
    where = [int(steps[1]), int(steps[3]), int(steps[5])]
    legs.locator(".tab-head").nth(where[0]).click()
    before = page.evaluate(FIXING_RATE, where)
    assert before and before.endswith("%") or before, before                             # the nested cell is in the page
    wait(page, f"(() => {{ const f = {FIXING_RATE}; return f({json.dumps(where)}) !== {json.dumps(before)}; }})()")
    after = page.evaluate(FIXING_RATE, where)
    assert after != before
    assert legs.locator(".tab-head").nth(where[0]).get_attribute("aria-selected") == "true"        # the same leg is still open
    assert not page.errors


def test_a_phone_shows_a_deep_trade_without_sideways_scrolling_small_targets_or_clipping(live_console, browser):
    """The page-widths checks (test_page_widths_browser.py) on a deep trade at 390 px: every leg's tab opened, a cashflow opened to its fixings."""
    from test_page_widths_browser import AUDIT, CLIPPED, TARGETS
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    page = ctx.new_page()
    problems = []
    try:
        page.goto(f"{live_console}/v/trade/{commodity_swap(3)}")
        page.locator("#p-legs .tab-head").first.wait_for()
        for leg in range(3):
            page.locator("#p-legs .tab-head").nth(leg).tap()
            if leg == 1:
                page.locator("#p-legs .tab-pane:not([hidden]) button.tr-tog").first.tap()
                assert page.evaluate("(() => { const w = document.querySelector('#legs-pane-1 .tbl-wrap'); return w.scrollWidth > w.clientWidth; })()")   # the table scrolls inside its own box
            problems += [f"leg {leg + 1}: {t}" for t in page.evaluate(AUDIT)]
            problems += [f"leg {leg + 1}: {t}" for t in page.evaluate(TARGETS) if "tr-tog" in t or "tab-head" in t]
            problems += [f"leg {leg + 1}: {t}" for t in page.evaluate(CLIPPED)]
    finally:
        ctx.close()
    assert not problems, "\n".join(problems)
