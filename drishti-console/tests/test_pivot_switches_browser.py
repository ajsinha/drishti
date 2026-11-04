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

"""The pivot screen's switches, live pivots and cell comments (PIVOT_GUIDE.md) in a real browser against the stand-in engine: Exact /
Auto / ≈, Progressive and Use rollups are sent as the request's options and the line beside them says how the answer was computed; L turns
Live on and a pushed patch changes only its cell, which flashes; on a saved view a comment added in a cell's popover marks the cell; on a
phone the switches wrap inside the screen and stay large enough to tap. The browser computes no figure: every number is the stand-in's."""
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
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


def _open(browser, url, width=1440, height=900):
    ctx = browser.new_context(viewport={"width": width, "height": height}, bypass_csp=True)
    pg = ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(url, wait_until="load")
    pg.wait_for_selector("[data-pv-grid] table.pv-table td.pv-c")
    return pg


def test_the_switches_are_sent_as_asked_and_the_line_says_how_the_answer_was_computed(browser, stack):
    url, backend = stack
    pg = _open(browser, url + "/pivot?source=trade")
    assert pg.locator("[data-pv-computed]").text_content().startswith("exact")
    backend.asked.clear()
    pg.uncheck("[data-pv-progressive]")
    pg.check('input[name="pvExact"][value="approximate"]')
    pg.wait_for_function("() => document.querySelector('[data-pv-computed]').textContent.includes('≈')")
    query = [b for k, b in backend.asked if k == "query"][-1]
    assert query["exact"] is False and query["progressive"] is False and query["rollups"] is True
    assert "approx" in pg.locator("[data-pv-computed]").get_attribute("class")
    pg.uncheck("[data-pv-rollups]")
    pg.wait_for_function("() => document.querySelector('[data-pv-computed]').textContent.includes('rollups off')")
    assert [b for k, b in backend.asked if k == "query"][-1]["rollups"] is False
    # progressive on: ≈ first from the stream, then the exact answer replaces it
    pg.check('input[name="pvExact"][value="exact"]')
    pg.check("[data-pv-progressive]")
    pg.wait_for_function("() => document.querySelector('[data-pv-computed]').textContent.startsWith('exact')")
    assert any(k == "progressive" for k, _ in backend.asked)
    assert not pg.errors, pg.errors


def test_live_on_with_the_keyboard_puts_a_pushed_patch_in_its_cell_only(browser, stack):
    url, backend = stack
    pg = _open(browser, url + "/pivot?source=trade")
    before = pg.locator('[data-pv-grid] td.pv-c[data-cell^="DESK=Rates"]').first.text_content()
    other = pg.locator('[data-pv-grid] td.pv-c[data-cell^="DESK=FX"]').first.text_content()
    pg.locator("[data-pv-grid]").focus()
    pg.keyboard.press("l")                                                   # L: Live
    pg.wait_for_selector('[data-pv-grid] td.pv-flash[data-cell^="DESK=Rates"]', timeout=5000)
    after = pg.locator('[data-pv-grid] td.pv-c[data-cell^="DESK=Rates"]').first.text_content()
    assert float(after.replace(",", "")) == 2 * float(before.replace(",", ""))   # the stand-in's patch, as pushed
    assert pg.locator('[data-pv-grid] td.pv-c[data-cell^="DESK=FX"]').first.text_content() == other
    assert pg.locator("[data-pv-live]").is_checked() and "live" in pg.locator("[data-pv-computed]").text_content()
    assert any(k == "live" for k, _ in backend.asked)
    pg.uncheck("[data-pv-live]")
    pg.wait_for_function("() => !document.querySelector('[data-pv-computed]').textContent.includes('live')")
    assert backend.ended and backend.ended[-1] == "/live/lp-1"
    assert not pg.errors, pg.errors


def test_a_comment_on_a_saved_views_cell_marks_it(browser, stack):
    url, backend = stack
    backend.comments.clear()
    pg = _open(browser, url + "/pivot?view=u/desk")
    cell = pg.locator('[data-pv-grid] td.pv-c[data-cell^="DESK=Rates"]').first
    cell.click()
    pg.wait_for_selector("[data-pv-pop] [data-cm-text]")
    pg.fill("[data-pv-pop] [data-cm-text]", "Rates count checked with the desk")
    pg.click("[data-pv-pop] [data-cm-add]")
    pg.wait_for_selector('[data-pv-grid] td.pv-commented[data-cell^="DESK=Rates"]')
    assert backend.comments[-1]["text"] == "Rates count checked with the desk"
    cell.click()
    pg.wait_for_selector("[data-pv-pop] .pv-cm")
    assert "Rates count checked with the desk" in pg.locator("[data-pv-pop] .pv-cms").text_content()
    assert not pg.errors, pg.errors


def test_on_a_phone_the_switches_wrap_inside_the_screen_and_are_large_enough_to_tap(browser, stack):
    url, _ = stack
    pg = _open(browser, url + "/pivot?source=trade", width=390, height=844)
    box = pg.locator("[data-pv-switches]").bounding_box()
    assert box["x"] >= 0 and box["x"] + box["width"] <= 390 + 1
    for sel in ('.pv-seg label:has(input[value="exact"])', ".pv-switches .pv-chip:has([data-pv-live])"):
        assert pg.locator(sel).bounding_box()["height"] >= 32, sel
    assert not pg.errors, pg.errors
