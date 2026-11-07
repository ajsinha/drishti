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

"""The pivot screen (PIVOT_GUIDE.md) in a real browser against a stand-in engine, drawn by drishti-grid in its wells layout: it opens on a
source with a first arrangement, the grid shows the engine's figures as they are (every total the server's), a field moved to Columns
asks again, the export panel opens, and no script error is raised. The grid itself is tested once, against its fixture harness
(test_elements_grid_browser.py); this file tests the screen's wiring and its numbers."""
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
        pg.wait_for_selector("[data-pv-grid] .dg-cell[data-cell]")
        yield pg
        b.close()


def test_the_screen_opens_on_the_source_and_draws_the_engines_figures(page):
    assert page.locator("[data-pv-title]").text_content() == "Trades"
    heads = page.locator("[data-pv-grid] .dg-row [role=rowheader] .dg-text").all_text_contents()
    assert heads[:4] == ["Credit", "Equities", "FX", "Rates"] and heads[-1] == "Total"
    rows = page.locator("[data-pv-grid] .dg-row")
    total = rows.last.locator(".dg-cell[data-cell] .dg-text").first.text_content().replace(",", "")
    assert total == "400"                                                   # count() of the grand total, as the engine answered it
    assert not page.errors, page.errors


def test_a_field_moved_to_columns_with_the_keyboard_asks_the_engine_again(page):
    before = page.locator("[data-pv-grid] .dg-hcell.dg-hgroup").count()
    page.click(".dg-fields .dg-chip[data-field='CURRENCY'] .dg-chip-menu")   # the field's menu: the keyboard way of dragging
    page.click(".dg-menu [data-action='to:columns']")
    page.wait_for_function(f"() => document.querySelectorAll('[data-pv-grid] .dg-hcell.dg-hgroup').length > {before}")
    cols = page.locator("[data-pv-grid] .dg-hcell.dg-hgroup.dg-num .dg-hlabel").all_text_contents()      # the column members, not the row heading's corner
    assert cols[:5] == ["CAD", "EUR", "GBP", "JPY", "USD"] and cols[-1] == "Grand total"
    assert not page.errors, page.errors


def test_the_export_panel_opens(page):
    page.click("[data-pv-export-open]")
    assert page.locator("section[data-pv-export]").is_visible()
    assert not page.errors, page.errors


def _holistic_answer():
    """An engine answer with a median by desk: parts 100 and 50, the total 120 and the engine's gap (Σ parts − total) set to 31 on purpose,
    not the 30 the parts would give: the chart must draw the engine's figure, never one it adds up itself."""
    node = {"kind": "node", "depth": 1, "expandable": False, "expanded": False, "by": None, "dim": "DESK"}
    return {"source": "trade", "title": "Trades", "date": "2026-10-30", "provenance": "stand-in-2", "exactness": "exact", "cellCount": 3,
            "view": {"source": "trade", "rows": ["DESK"], "columns": [], "values": [{"measure": "median(NOTIONAL)", "as": "Median"}], "filters": {},
                     "expanded": []},                                    # the view the answer is of (every engine answer carries it)
            "rowDimensions": ["DESK"], "columnDimensions": [],
            "measures": [{"index": 0, "id": "median(NOTIONAL)", "label": "Median", "kind": "holistic", "plainSum": False, "exact": True}],
            "rows": [{**node, "key": "DESK=A", "value": "A", "label": "A", "path": [["DESK", "A"]], "coords": {"DESK": "A"}},
                     {**node, "key": "DESK=B", "value": "B", "label": "B", "path": [["DESK", "B"]], "coords": {"DESK": "B"}},
                     {"key": "*", "kind": "grand", "depth": 0, "label": "Grand total", "value": None, "path": [], "coords": {}, "expandable": False,
                      "expanded": False, "diversification": {"Median": 31}, "diversificationByColumn": {"*": {"Median": 31}}}],
            "columns": [{"key": "*", "kind": "grand", "depth": 0, "label": "Grand total", "value": None, "path": [], "coords": {}}],
            "cells": {"DESK=A|*": {"values": {"Median": 100}, "members": 3}, "DESK=B|*": {"values": {"Median": 50}, "members": 2},
                      "*|*": {"values": {"Median": 120}, "members": 5}}}


def test_the_chart_draws_the_engines_diversification_never_its_own(stack, page):
    """A non-additive measure's chart: the parts beside the total and the diversification bar, the engine's gap of the charted column
    (``diversificationByColumn``, PivotAnswer) shown as total − Σ parts; the browser adds up nothing (2.9.11.b). A page of its own in the
    module's browser (a second Playwright inside the module's would fail)."""
    import json
    ctx = page.context.browser.new_context(viewport={"width": 1440, "height": 900}, bypass_csp=True)
    try:
        pg = ctx.new_page()
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.route("**/api/pivot/query", lambda route: route.fulfill(status=200, content_type="application/json", body=json.dumps(_holistic_answer())))
        pg.goto(stack[0] + "/pivot?source=trade", wait_until="load")
        pg.wait_for_selector("[data-pv-grid] .dg-cell[data-cell]")
        # Progressive (on as shipped) streams from /api/pivot/query/progressive: off, the screen asks /api/pivot/query, answered here
        pg.uncheck("[data-pv-progressive]")
        pg.wait_for_function("() => (document.querySelector('[data-pv-grid-title]') || {}).textContent.startsWith('Median')")
        pg.click("[data-pv-chart]")
        pg.wait_for_selector("[data-pv-chart-note]:not(:empty)")
        note = pg.locator("[data-pv-chart-note]").text_content()
        assert "the engine's" in note and ("31" in note) and "30" not in note
        assert not errors, errors
    finally:
        ctx.close()
