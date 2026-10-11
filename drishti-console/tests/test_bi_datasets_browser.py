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

"""Rupaka phase 2 in Chromium, over the real server and console (tests/bi_data_stack.py: ports 18989 and 17989, security on, a small file lake read through
the connector the shipped sample ``trades-basic`` names): the datasets list shows the sample, its page shows the model, a query previewed on the page comes back
from the real engine with numbers that add up, the second run is a cache hit, a phone (390 px) has no sideways scroll, and no policy violation occurs.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_data_stack  # noqa: E402
from bi_data_stack import CONSOLE_URL, PASSWORD, trade  # noqa: E402

DATASET = "file:dataset:trades-basic"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_data_stack.SERVER_PORT, bi_data_stack.CONSOLE_PORT):
        if bi_data_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = bi_data_stack.DataStack(tmp_path_factory.mktemp("bidata"), delta_root="").start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    yield s
    s.close()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"


def sign_in(browser, user, **ctx):
    context = browser.new_context(**{"viewport": {"width": 1400, "height": 900}, **ctx})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(CONSOLE_URL + "/login?next=/bi/datasets")
    page.fill("#lu", user)
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/bi/datasets")
    page.errors = errors
    return page


def clean(page):
    assert page.evaluate("window.__csp") == [], "a policy violation"
    assert page.errors == [], page.errors


def expected_mtm_by_desk(day="2026-10-08"):
    out = {}
    for i in range(60):
        t = trade(day, i)
        out[t["desk"]] = out.get(t["desk"], 0) + t["mtm"]
    return out


def test_the_list_shows_the_sample_and_its_page_shows_the_model(stack, browser):
    page = sign_in(browser, "rita")
    card = page.locator(f'[data-datasets] [data-id="{DATASET}"]')
    expect(card).to_be_visible()
    expect(card).to_contain_text("sample")
    expect(card).to_contain_text("1 table")
    card.locator("h3 a").click()
    page.wait_for_url("**/bi/d/**")
    expect(page.locator("[data-title]")).to_have_text("Trades, the basics")
    expect(page.locator("[data-meta]")).to_contain_text("1 tables")
    expect(page.locator('[data-table="trade"]')).to_be_visible()
    expect(page.locator('[data-measure="Notional"] td').nth(0)).to_have_text("sum(trade.notional)")
    expect(page.locator("[data-empty-rel]")).to_be_visible()
    clean(page)


def test_a_previewed_query_runs_on_the_engine_and_adds_up(stack, browser):
    page = sign_in(browser, "rita")
    page.goto(CONSOLE_URL + "/bi/d/" + DATASET)
    expect(page.locator("[data-fields] option").first).to_be_attached()
    page.select_option("[data-fields]", "trade.desk")
    page.select_option("[data-measures]", ["MTM", "Trades"])
    page.click("[data-run]")
    rows = page.locator("[data-grid] tbody tr")
    expect(rows).to_have_count(3)
    expect(page.locator("[data-result-meta]")).to_contain_text("columns layer")
    want = expected_mtm_by_desk()
    got = {}
    for i in range(3):
        cells = rows.nth(i).locator("td")
        got[cells.nth(0).inner_text()] = int(cells.nth(1).inner_text().replace(",", ""))
    assert got == want
    page.click("[data-run]")                                      # the same query again: the server's cache answers
    expect(page.locator("[data-result-meta]")).to_contain_text("from the cache")
    clean(page)


def test_a_filter_and_a_refusal_are_shown_with_their_code(stack, browser):
    page = sign_in(browser, "rita")
    page.goto(CONSOLE_URL + "/bi/d/" + DATASET)
    expect(page.locator("[data-measures] option").first).to_be_attached()
    page.select_option("[data-fields]", "trade.currency")
    page.select_option("[data-measures]", "Trades")
    page.select_option("[data-ffield]", "trade.desk")
    page.fill('[name="fvalue"]', "DESK-FX")
    page.click("[data-run]")
    expect(page.locator("[data-grid] tbody tr")).to_have_count(1)
    page.select_option("[data-fields]", [])
    page.select_option("[data-measures]", [])
    page.click("[data-run]")
    expect(page.locator("[data-msg]")).to_contain_text("Choose at least one")
    clean(page)


def test_a_phone_has_no_sideways_scroll_and_finger_sized_targets(stack, browser):
    page = sign_in(browser, "rita", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    page.goto(CONSOLE_URL + "/bi/d/" + DATASET)
    expect(page.locator('[data-table="trade"]')).to_be_visible()
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), "the page scrolls sideways"
    for sel in ("[data-run]", "[data-table] summary"):
        box = page.locator(sel).first.bounding_box()
        assert box["height"] >= 43, (sel, box)
    clean(page)


def test_a_reader_without_the_bi_power_is_refused(stack, browser):
    context = browser.new_context()
    page = context.new_page()
    page.goto(CONSOLE_URL + "/login?next=/t")
    page.fill("#lu", "nobi")
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/t")
    assert page.goto(CONSOLE_URL + "/bi/datasets").status == 403
    assert page.goto(CONSOLE_URL + "/bi/d/" + DATASET).status == 403
    assert "BI is not available to you" in page.content()
