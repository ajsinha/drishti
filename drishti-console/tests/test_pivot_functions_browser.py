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

"""The pivot screen's Why, What-if and Functions cards (PIVOT_GUIDE.md) in a real browser against a stand-in engine: each button (and its
key, W, I, U) opens its card, and the card fills with the engine's answer: the waterfall of steps, the cells a what-if changes, the list of
functions. The cards' script finds the screen by its root attribute; a script that misses it exits silently and the buttons do nothing, so
every test here clicks and asserts the card's content, never only that the page loaded."""
import pytest

pytest.importorskip("playwright")
from playwright import sync_api  # noqa: E402

from pivot_screen_backend import PivotBackend  # noqa: E402
from table_chart_backend import serve  # noqa: E402


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve(PivotBackend())
    yield url, backend
    stop()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture
def page(stack, browser):
    ctx = browser.new_context(viewport={"width": 1440, "height": 900}, bypass_csp=True)
    pg = ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(stack[0] + "/pivot?source=trade", wait_until="load")
    pg.wait_for_selector("[data-pv-grid] table.pv-table td.pv-c")
    yield pg
    ctx.close()


def _card(page, which):
    return page.locator(f"section[data-pf-card='{which}']")


def test_why_opens_its_card_and_draws_the_engines_waterfall(page):
    page.click("[data-pf-open='explain']")
    card = _card(page, "explain")
    card.wait_for(state="visible")
    assert page.locator("[data-pf-by] option").count() > 0                 # the split-by list filled from the view and the catalogue
    page.click("[data-pf-explain]")
    page.wait_for_selector("[data-pf-waterfall] .pf-bar")
    assert "Rates minus FX is 30" in page.locator("[data-pf-words]").text_content()
    assert page.locator("[data-pf-waterfall] .pf-bar").count() == 2
    assert not page.errors, page.errors


def test_what_if_opens_its_card_and_lists_the_cells_it_changes(page):
    page.click("[data-pf-open='whatif']")
    _card(page, "whatif").wait_for(state="visible")
    page.fill("[data-pf-exclude]", "DESK=Rates")
    page.click("[data-pf-whatif]")
    page.wait_for_selector("[data-pf-whatif-result] table")
    text = page.locator("[data-pf-whatif-result]").text_content()
    assert "2 cell(s) change" in text and "DESK=Rates" in text
    assert not page.errors, page.errors


def test_functions_opens_its_card_and_lists_the_functions(page):
    page.click("[data-pf-open='functions']")
    _card(page, "functions").wait_for(state="visible")
    page.wait_for_selector("[data-pf-list] table")
    assert "u.wret" in page.locator("[data-pf-list]").text_content()
    assert "formula" in page.locator("[data-pf-langs]").text_content()
    assert not page.errors, page.errors


def test_the_keys_open_the_cards_one_at_a_time(page):
    for key, which in (("w", "explain"), ("i", "whatif"), ("u", "functions")):
        page.evaluate("() => document.activeElement && document.activeElement.blur()")  # an open card focuses its first field; keys type there
        page.keyboard.press(key)
        _card(page, which).wait_for(state="visible")
        others = [w for w in ("explain", "whatif", "functions") if w != which]
        assert all(_card(page, w).is_hidden() for w in others)
    assert not page.errors, page.errors


def test_why_splits_by_any_field_of_the_source(stack, page):
    """Split by offers every field of the source (the catalogue the field picker reads), the view's own rows first, not only the view's
    rows and columns; the field chosen is the one sent (the CLI and the API accept any field too)."""
    page.click("[data-pf-open='explain']")
    _card(page, "explain").wait_for(state="visible")
    options = page.locator("[data-pf-by] option").all_text_contents()
    assert options[0] == "DESK", options                                  # the view's row first
    assert {"CURRENCY", "PRODUCT", "TRADE_DATE", "NOTIONAL", "PNL"} <= set(options), options
    page.select_option("[data-pf-by]", "PRODUCT")
    page.click("[data-pf-explain]")
    page.wait_for_selector("[data-pf-waterfall] .pf-bar")
    sent = [b for k, b in stack[1].asked if k == "explain"][-1]
    assert sent["by"] == "PRODUCT"
    assert not page.errors, page.errors


def test_the_playground_shows_the_states_and_small_figures_unrounded(page):
    """Tutorial 37 step 2: each state as the engine words it ([Σ=… n=…, Σ=… n=…]), and a figure such as 0.007 or 0.006429 as it is,
    never rounded to 0.01."""
    page.click("[data-pf-open='functions']")
    _card(page, "functions").wait_for(state="visible")
    page.click("[data-pf-check]")
    page.wait_for_selector("[data-pf-check-result] table")
    text = page.locator("[data-pf-check-result]").text_content()
    assert "[Σ=0.036 n=2, Σ=4.0 n=2]" in text and "@" not in text
    assert "0.006429" in text and "0.007" in text and "0.01 " not in text
    assert not page.errors, page.errors
