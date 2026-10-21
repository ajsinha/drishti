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

"""The BI catalogue by kind in a real browser (Chromium), against the stand-in catalogue of test_bi_catalogue_tabs.py: Reports is the first tab, each
tab shows its count, a tab switches in place and puts ?kind= in the address, back and forward walk the tabs, the search works inside the tab, the last
tab is remembered, All groups by kind under small headings, the arrow keys move along the tabs, every theme draws them, a phone scrolls the tabs sideways
with no page scroll and 16 px gutters. Skipped when Playwright's Chromium is not installed."""
import os

import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from bi_runs_backend import serve  # noqa: E402
from test_bi_catalogue_tabs import CatalogueBackend  # noqa: E402

THEMES = ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue")
SHOTS = os.environ.get("DRISHTI_SHOTS")          # a folder: the dark-theme screenshot is saved there


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve(CatalogueBackend(("bi", "bi-author"), True))
    yield url, backend
    stop()


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture
def ctx(browser):
    c = browser.new_context(viewport={"width": 1366, "height": 900}, bypass_csp=True)
    yield c
    c.close()


def open_page(ctx, stack, path):
    page = ctx.new_page()
    page.errors = []
    page.on("pageerror", lambda e: page.errors.append(str(e)))
    page.goto(stack[0] + path, wait_until="load")
    page.wait_for_selector("[data-cards] .bi-card, [data-groups] .bi-card", timeout=15000)
    return page


def titles(page, scope="[data-cards]"):
    return page.eval_on_selector_all(scope + " .bi-card h3", "els => els.map(e => e.textContent.trim())")


def shown(page, key):
    page.wait_for_selector(f'[data-cat-panel][data-cat-shown="{key}"]')
    page.wait_for_function(f"document.querySelector('[data-cat-tab=\"{key}\"]').getAttribute('aria-selected') === 'true'")


def test_tabs_switch_in_place_with_the_address_and_history(ctx, stack):
    page = open_page(ctx, stack, "/bi/catalogue?sort=use")
    shown(page, "reports")
    assert "kind=reports" in page.url and "sort=use" in page.url
    counts = page.eval_on_selector_all("[data-cat-tab]", "els => els.map(e => e.getAttribute('data-cat-tab') + ' ' + e.querySelector('[data-cat-count]').textContent)")
    assert counts == ["reports 3", "boards 2", "models 1", "datasets 2", "python 2", "all 10"]
    assert titles(page) == ["Desk P&L 0", "Desk P&L 1", "Desk P&L 2"]
    page.evaluate("window.marker = 1")                       # a full load would lose it: the tab switches in place
    page.click('[data-cat-tab="python"]')
    shown(page, "python")
    page.wait_for_function("document.querySelectorAll('[data-cards] .bi-card').length === 2")
    assert sorted(titles(page)) == ["Desk notebook", "VaR transform"] and "kind=python" in page.url and page.evaluate("window.marker") == 1
    page.click('[data-cat-tab="boards"]')
    shown(page, "boards")
    page.go_back()
    shown(page, "python")
    assert "kind=python" in page.url and page.evaluate("window.marker") == 1
    page.go_forward()
    shown(page, "boards")
    page.wait_for_function("document.querySelectorAll('[data-cards] .bi-card').length === 2")
    # the arrow keys move along the tabs and show each one
    page.focus('[data-cat-tab="boards"]')
    page.keyboard.press("ArrowRight")
    shown(page, "models")
    assert page.evaluate("document.activeElement.getAttribute('data-cat-tab')") == "models"
    # the last tab is remembered: the bare address opens it
    page.goto(stack[0] + "/bi/catalogue")
    shown(page, "models")
    assert not page.errors, page.errors


def test_search_works_inside_the_tab_and_all_groups_by_kind(ctx, stack):
    page = open_page(ctx, stack, "/bi/catalogue?kind=python")
    shown(page, "python")
    page.fill("[data-search]", "desk")
    page.wait_for_function("document.querySelectorAll('[data-cards] .bi-card').length === 1")
    assert titles(page) == ["Desk notebook"] and "q=desk" in page.url and "kind=python" in page.url
    assert page.text_content('[data-cat-tab="reports"] [data-cat-count]') == "3"
    page.click('[data-cat-tab="all"]')
    shown(page, "all")
    page.wait_for_selector('[data-cat-group="reports"]')
    heads = page.eval_on_selector_all(".bi-cat-group-h", "els => els.map(e => e.textContent.trim())")
    assert heads == ["Reports 3", "Python 1"]
    assert titles(page, '[data-cat-group="python"]') == ["Desk notebook"] and page.input_value("[data-search]") == "desk"
    assert not page.errors, page.errors


@pytest.mark.parametrize("theme", THEMES)
def test_every_theme_draws_the_tabs(ctx, stack, theme):
    page = open_page(ctx, stack, "/bi/catalogue?kind=all")
    page.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
    page.wait_for_selector(".bi-cat-group-h")
    c = page.evaluate("""(() => { const on = getComputedStyle(document.querySelector('[data-cat-tab="all"]')), off = getComputedStyle(document.querySelector('[data-cat-tab="reports"]'));
        const n = getComputedStyle(document.querySelector('[data-cat-tab="all"] [data-cat-count]'));
        return { on: on.color, off: off.color, border: on.borderBottomColor, count: n.color, countBg: n.backgroundColor }; })()""")
    assert c["on"] != c["off"] and c["border"] != "rgba(0, 0, 0, 0)" and c["count"] != c["countBg"]
    if SHOTS and theme == "terminal":
        page.screenshot(path=os.path.join(SHOTS, "bi-catalogue-tabs-dark.png"), full_page=False)
    assert not page.errors, page.errors


def test_a_phone_scrolls_the_tabs_sideways_with_16_px_gutters(browser, stack):
    c = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True, bypass_csp=True)
    try:
        page = c.new_page()
        page.goto(stack[0] + "/bi/catalogue?kind=reports")
        page.wait_for_selector("[data-cards] .bi-card")
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        tabs = page.evaluate("(() => { const t = document.querySelector('[data-cat-tabs]'); return { sw: t.scrollWidth, cw: t.clientWidth, ox: getComputedStyle(t).overflowX }; })()")
        assert tabs["sw"] > tabs["cw"] and tabs["ox"] == "auto"            # six tabs do not fit 390 px: they scroll, the page does not
        assert page.evaluate("parseFloat(getComputedStyle(document.querySelector('.bi-shell')).paddingLeft)") == 16
        box = page.locator("[data-cat-tabs]").bounding_box()
        assert box["x"] >= 15 and box["x"] + box["width"] <= 390 - 15
        page.locator('[data-cat-tab="all"]').scroll_into_view_if_needed()
        page.click('[data-cat-tab="all"]')
        page.wait_for_selector('[data-cat-panel][data-cat-shown="all"] [data-cat-group]')
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        if SHOTS:
            page.evaluate("document.documentElement.setAttribute('data-theme', 'terminal')")
            page.screenshot(path=os.path.join(SHOTS, "bi-catalogue-tabs-phone.png"))
    finally:
        c.close()
