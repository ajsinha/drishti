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

"""The pivot screen (PIVOT_GUIDE.md) in a real browser against a stand-in engine: it opens on a source with a first arrangement, the grid
shows the engine's figures as they are (every total the server's), a field moved to Columns asks again, the export panel opens, and no
script error is raised."""
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
def page(stack):
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        ctx = b.new_context(viewport={"width": 1440, "height": 900}, bypass_csp=True)
        pg = ctx.new_page()
        pg.errors = []
        pg.on("pageerror", lambda e: pg.errors.append(str(e)))
        pg.goto(stack[0] + "/pivot?source=trade", wait_until="load")
        pg.wait_for_selector("[data-pv-grid] table.pv-table td.pv-c")
        yield pg
        b.close()


def test_the_screen_opens_on_the_source_and_draws_the_engines_figures(page):
    assert page.locator("[data-pv-title]").text_content() == "Trades"
    heads = page.locator("[data-pv-grid] tbody th.pv-rowhead .pv-lab").all_text_contents()
    assert heads[:4] == ["Credit", "Equities", "FX", "Rates"] and heads[-1] == "Total"
    rows = page.locator("[data-pv-grid] tbody tr")
    total = rows.last.locator("td.pv-c").first.text_content().replace(",", "")
    assert total == "400"                                                   # count() of the grand total, as the engine answered it
    assert not page.errors, page.errors


def test_a_field_moved_to_columns_with_the_keyboard_asks_the_engine_again(page):
    before = page.locator("[data-pv-grid] thead th.pv-colhead").count()
    page.click('[data-pv-fields] .pv-field[data-field="CURRENCY"]')
    page.keyboard.press("c")                                                # C: to Columns (the keyboard way of dragging)
    page.wait_for_function(f"() => document.querySelectorAll('[data-pv-grid] thead th.pv-colhead').length > {before}")
    cols = page.locator("[data-pv-grid] thead th.pv-colhead").all_text_contents()
    assert cols[:5] == ["CAD", "EUR", "GBP", "JPY", "USD"] and cols[-1] == "Total"
    assert not page.errors, page.errors


def test_the_export_panel_opens(page):
    page.click("[data-pv-export-open]")
    assert page.locator("section[data-pv-export]").is_visible()
    assert not page.errors, page.errors
