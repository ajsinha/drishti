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

"""A deep trade (the production-shaped ENDUR ComSwap: LEGS, each leg's CASH_FLOWS, each floating flow's RESETS) in a real browser
against a real server: a commodity swap with three legs shows them side by side, each with its terms, notional profile, fixings and
cash-flow table; a floating flow opens to its resets while a fixed leg has none; and a phone (390 px) never scrolls sideways. A
source system's trade does not tick (its valuation does, in market-risk), so the live-tick checks live with the valuations' tests.
Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
import json

import pytest

from conftest import CONSOLE
from test_workbench_browser import browser, page, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack


@pytest.fixture(scope="module")
def live_console(tmp_path_factory):
    """A real server with the trading pack (its ENDUR swaps are the deep trades), and a console in front of it."""
    yield from _stack(tmp_path_factory, {"DRISHTI_PACKS": "core,market-data,trading"})


SAMPLES = CONSOLE.parent / "config" / "packs" / "trading" / "samples" / "trade.jsonl"


def commodity_swap(legs: int) -> dict:
    """A sample ENDUR ComSwap with that many legs (the generator fixes them: see tools/packgen/trades/endur.py)."""
    for line in SAMPLES.read_text().splitlines():
        d = json.loads(line)
        if d["SOURCE_SYSTEM"] == "ENDUR" and d["PRODUCT_TYPE"] == "ComSwap" and len(d["LEGS"]) == legs:
            return d
    raise AssertionError(f"no ComSwap sample with {legs} legs")


def open_trade(page, base, trade_id):
    page.goto(f"{base}/v/trade/{trade_id}")
    page.locator("#p-legs .col-box").first.wait_for()
    return page.locator("#p-legs")


def flows(box):
    return box.locator("section[data-part='leg-flows'] table.tbl-tree tbody tr")


def test_a_three_leg_commodity_swap_shows_its_legs_side_by_side_each_with_terms_charts_and_flows(live_console, page):
    deal = commodity_swap(3)
    legs = open_trade(page, live_console, deal["TRADE_ID"])
    boxes = legs.locator(".cols .col-box")
    assert boxes.count() == 3
    for i, leg in enumerate(deal["LEGS"]):
        box = boxes.nth(i)
        title = box.locator("h4").first.inner_text()
        assert title.startswith(f"Leg {leg['LEG_ID']} · "), title
        if leg["INDEX"]["NAME"]:
            assert leg["INDEX"]["NAME"] in title, title
        parts = [box.locator("section.tab-part").nth(k).get_attribute("data-part") for k in range(box.locator("section.tab-part").count())]
        assert parts == ["leg-terms", "leg-notional", "leg-fixings", "leg-flows"], parts
        assert box.locator("section[data-part='leg-terms'] dt", has_text="Pay / receive").count() == 1
        assert flows(box).locator("xpath=self::tr[@data-depth='0']").count() == len(leg["CASH_FLOWS"])
    assert not page.errors


def test_a_four_leg_swap_has_four_legs(live_console, page):
    legs = open_trade(page, live_console, commodity_swap(4)["TRADE_ID"])
    assert legs.locator(".cols .col-box").count() == 4


def test_a_floating_flow_opens_to_its_resets_and_a_fixed_leg_has_none(live_console, page):
    deal = commodity_swap(3)
    legs = open_trade(page, live_console, deal["TRADE_ID"])
    floating = next(i for i, leg in enumerate(deal["LEGS"]) if any(c["RESETS"] for c in leg["CASH_FLOWS"]))
    box = legs.locator(".cols .col-box").nth(floating)
    first = flows(box).locator("xpath=self::tr[@data-kids]").first
    tog = first.locator("button.tr-tog")
    assert tog.get_attribute("aria-expanded") == "false"
    assert box.locator("tr[data-depth='1']:not([hidden])").count() == 0                   # the resets are shut
    tog.click()
    wait(page, f"document.querySelectorAll('#p-legs .col-box:nth-child({floating + 1}) tr[data-depth=\"1\"]:not([hidden])').length >= 1")
    reset = box.locator("tr[data-depth='1']:not([hidden])").first
    assert reset.locator("td").nth(0).inner_text().strip()                                # a reset id
    fixed = next(i for i, leg in enumerate(deal["LEGS"]) if leg["LEG_TYPE"] == "FIXED")
    assert legs.locator(".cols .col-box").nth(fixed).locator("button.tr-tog").count() == 0
    assert not page.errors


def test_a_phone_shows_a_deep_trade_without_sideways_scrolling_small_targets_or_clipping(live_console, browser):
    """The page-widths checks (test_page_widths_browser.py) on a deep trade at 390 px, a floating flow opened to its resets."""
    from test_page_widths_browser import AUDIT, CLIPPED, TARGETS
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    page = ctx.new_page()
    problems = []
    try:
        page.goto(f"{live_console}/v/trade/{commodity_swap(3)['TRADE_ID']}")
        page.locator("#p-legs .col-box").first.wait_for()
        tog = page.locator("#p-legs button.tr-tog").first
        tog.scroll_into_view_if_needed()
        tog.tap()
        problems += list(page.evaluate(AUDIT))
        problems += [t for t in page.evaluate(TARGETS) if "tr-tog" in t]
        problems += list(page.evaluate(CLIPPED))
    finally:
        ctx.close()
    assert not problems, "\n".join(problems)
