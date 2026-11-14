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

"""The pivot screen (BI_GUIDE.md section 2; docs/lenses/mockups/pivot-fields) in a real browser against a stand-in engine, drawn by
drishti-grid: it opens in the default mode, the View picker in front of the compact line (C + B), on a source with a first arrangement;
the grid shows the engine's figures as they are (every total the server's); R opens the Rows picker, a hierarchy is one item with its
levels and Alt+↓ adds its next level; a value's ▾ offers the functions valid for its field and show-as; Shift+V lists the ready-made,
my and shared views and saves one; the backslash key opens the panes (A) where a field is dragged to a well; compact and panes open from the address;
an old saved view opens as before; the export panel opens; no script error is raised. The grid itself is tested once, against its
fixture harness (test_elements_grid_browser.py); this file tests the screen's wiring and its numbers."""
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


def opened(browser, url):
    ctx = browser.new_context(viewport={"width": 1440, "height": 900}, bypass_csp=True)
    pg = ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(url, wait_until="load")
    pg.wait_for_selector("[data-pv-grid] .dg-cell[data-cell]")
    return pg


@pytest.fixture(scope="module")
def page(stack, browser):
    pg = opened(browser, stack[0] + "/pivot?source=trade")
    yield pg
    pg.context.close()


def wells(pg):
    return pg.evaluate("() => window.Drishti.PivotScreen.gridApi().model.wells.getState()")


def asked_view(backend):
    """The last view the screen asked the engine for."""
    for what, body in reversed(backend.asked):
        if what in ("query", "progressive") and body:
            return body.get("view") or body
    return {}


def test_the_screen_opens_on_the_source_and_draws_the_engines_figures(page):
    assert page.locator("[data-pv-title]").text_content() == "Trades"
    heads = page.locator("[data-pv-grid] .dg-row [role=rowheader] .dg-text").all_text_contents()
    assert heads[:4] == ["Credit", "Equities", "FX", "Rates"] and heads[-1] == "Total"
    rows = page.locator("[data-pv-grid] .dg-row")
    total = rows.last.locator(".dg-cell[data-cell] .dg-text").first.text_content().replace(",", "")
    assert total == "400"                                                   # count() of the grand total, as the engine answered it
    assert not page.errors, page.errors


def test_it_opens_as_the_view_picker_in_front_of_the_compact_line_with_no_field_list(page):
    assert page.evaluate("window.Drishti.PivotScreen.mode()") == "views"
    assert page.locator("[data-pv-grid] .dg-wells-ribbon .dg-viewbtn").is_visible()                 # C: the View picker first
    assert page.locator("[data-pv-grid] .dg-viewbtn").text_content().startswith("ViewDefault layout")
    assert page.locator("[data-pv-grid] .dg-fields, [data-pv-grid] .dg-field-list").count() == 0       # B: no field list on screen
    hier = page.locator(".dg-zone[data-well='rows'] .dg-hchip[data-hier='ORG']")                   # the hierarchy is one chip
    assert hier.locator(".dg-chip-label").text_content() == "Desk"
    assert page.locator(".dg-zone[data-well='values'] .dg-vchip").count() == 2                        # the lit toggles are the values
    assert page.locator(".dg-zone[data-well='values'] .dg-vtoggle[data-field='PNL']").is_visible()     # an unlit one, a click away
    assert page.locator(".dg-customize").is_visible()
    # the cleanups: the scan estimate behind the i, the full label in a row's tooltip
    assert page.locator("[data-pv-estimate]").is_hidden()
    page.click("[data-pv-info]")
    assert page.locator("[data-pv-infobox]").is_visible()
    page.click("[data-pv-info]")
    assert page.locator("[data-pv-grid] .dg-row [role=rowheader]").first.get_attribute("title") == "Credit"
    assert not page.errors, page.errors


def test_r_opens_the_rows_picker_a_level_and_a_field_join_rows_and_alt_down_steps_the_hierarchy(stack, page):
    page.locator("[data-pv-title]").click()
    page.keyboard.press("r")
    picker = page.locator(".di-fp-pop")
    picker.wait_for()
    assert picker.locator(".di-fp-group").first.text_content().startswith("▾Hierarchies")
    org = picker.locator(".di-fp-item[data-key='h:ORG']")
    assert "Desk › Product" in org.text_content() and org.get_attribute("aria-checked") == "true"
    assert picker.locator(".di-fp-item[data-key='h:ORG/PRODUCT']").get_attribute("aria-checked") == "false"
    assert picker.locator(".di-fp-item[data-key='NOTIONAL']").count() == 1                 # a number may group rows (buckets), a measure may not
    page.keyboard.type("curr")                                                             # the search
    assert picker.locator(".di-fp-item").count() == 1
    page.keyboard.press("Enter")                                                           # Enter uses it and closes
    page.wait_for_function("() => window.Drishti.PivotScreen.gridApi().model.wells.state.rows.join() === 'DESK,CURRENCY'")
    assert picker.count() == 0
    page.locator("[data-pv-title]").click()
    page.keyboard.press("Alt+ArrowDown")                                                   # the hierarchy's next level, right after its own
    page.wait_for_function("() => window.Drishti.PivotScreen.gridApi().model.wells.state.rows.join() === 'DESK,PRODUCT,CURRENCY'")
    assert page.locator(".dg-hchip[data-hier='ORG'] .dg-chip-label").text_content() == "Desk › Product"
    page.click(".dg-hchip[data-hier='ORG'] [data-step='-1']")                            # ▲ up a level
    page.wait_for_function("() => window.Drishti.PivotScreen.gridApi().model.wells.state.rows.join() === 'DESK,CURRENCY'")
    page.click(".dg-zone[data-well='rows'] .dg-chip[data-field='CURRENCY'] .dg-chip-x")
    page.wait_for_function("() => window.Drishti.PivotScreen.gridApi().model.wells.state.rows.join() === 'DESK'")
    assert not page.errors, page.errors


def test_a_field_moved_to_columns_from_the_columns_picker_asks_the_engine_again(page):
    before = page.locator("[data-pv-grid] .dg-hcell.dg-hgroup").count()
    page.click(".dg-zone[data-well='columns'] .dg-pick")
    page.locator(".di-fp-pop .di-fp-item[data-key='CURRENCY']").click()
    page.keyboard.press("Escape")
    page.wait_for_function(f"() => document.querySelectorAll('[data-pv-grid] .dg-hcell.dg-hgroup').length > {before}")
    cols = page.locator("[data-pv-grid] .dg-hcell.dg-hgroup.dg-num .dg-hlabel").all_text_contents()      # the column members, not the row heading's corner
    assert cols[:5] == ["CAD", "EUR", "GBP", "JPY", "USD"] and cols[-1] == "Grand total"
    page.click(".dg-zone[data-well='columns'] .dg-chip[data-field='CURRENCY'] .dg-chip-x")
    page.wait_for_function("() => !window.Drishti.PivotScreen.gridApi().model.wells.state.columns.length")
    assert not page.errors, page.errors


def test_a_values_menu_offers_the_functions_valid_for_its_field_and_show_as_and_another_value(stack, page):
    page.click(".dg-zone[data-well='values'] .dg-vchip[data-field='NOTIONAL'] .dg-chip-menu")
    menu = page.locator(".dg-menu")
    labels = menu.locator(".dg-menu-item .dg-menu-label").all_text_contents()
    for f in ("Sum", "Count", "Distinct count", "Average", "Weighted average…", "Min", "Max", "Median", "Percentile…", "Variance", "Std dev", "Custom ƒ…"):
        assert f in labels, (f, labels)
    assert "% of grand total" in labels and "Rank among siblings" in labels and "Difference from previous Desk" in labels
    assert "Running total" not in labels                                                 # not a form the engine computes: not offered
    menu.locator("[data-action='agg:percentile']").click()                               # a function with an argument: its quantile
    page.locator(".dg-menu [data-action='arg:0.99']").click()
    page.wait_for_function("() => window.Drishti.PivotScreen.view().values.some(v => v.measure === 'percentile(NOTIONAL, 0.99)')")
    page.click(".dg-zone[data-well='values'] .dg-vchip[data-field='NOTIONAL'] .dg-chip-menu")
    page.locator(".dg-menu [data-action='show:pct-grand']").click()
    page.wait_for_function("() => window.Drishti.PivotScreen.view().values.some(v => v.showAs === 'pct-grand')")
    for _ in range(50):                                                                  # asked of the server, never computed here
        if any(v.get("showAs") == "pct-grand" for v in asked_view(stack[1]).get("values", [])):
            break
        page.wait_for_timeout(100)
    assert any(v.get("showAs") == "pct-grand" for v in asked_view(stack[1]).get("values", [])), stack[1].asked[-2:]
    page.click(".dg-zone[data-well='values'] .dg-vchip[data-field='NOTIONAL'] .dg-chip-menu")
    page.locator(".dg-menu [data-action='again']").click()                               # Sum of PV and Count of PV side by side
    page.wait_for_function("() => window.Drishti.PivotScreen.view().values.filter(v => /NOTIONAL/.test(v.measure)).length === 2")
    # a rate is never summed and a text is counted: the functions valid per field come from the catalogue (the model's view of it)
    fns = page.evaluate("() => { const w = window.Drishti.PivotScreen.gridApi().model.wells; return w.funcsFor('CURRENCY').map(f => [f.name, f.disabled]); }")
    assert fns == [["sum", True], ["count", False], ["count_distinct", False]]
    page.evaluate("() => { const w = window.Drishti.PivotScreen.gridApi().model.wells; const st = w.getState(); st.values = st.values.slice(0, 2); w.setState(st); }")
    assert not page.errors, page.errors


def test_shift_v_lists_the_views_saves_one_and_opens_a_shared_one(stack, page):
    page.locator("[data-pv-title]").click()
    page.keyboard.press("Shift+V")
    pop = page.locator(".dg-views-pop")
    pop.wait_for()
    page.wait_for_selector(".dg-views-pop .di-option")
    groups = pop.locator(".di-option-group").all_text_contents()
    assert groups[0] == "Ready-made" and "Shared with me" in groups
    pop.locator(".dg-vp-save-name").fill("Desk morning")
    pop.locator("[data-act='save']").click()
    page.wait_for_function("() => /ash\\/desk-morning|\\/desk-morning/.test(document.querySelector('[data-pv-title]').textContent)")
    assert any(w == "views" and b["name"] == "desk-morning" for w, b in stack[1].asked)
    page.keyboard.press("Shift+V")
    page.wait_for_selector(".dg-views-pop .di-option")
    assert "My views" in page.locator(".dg-views-pop .di-option-group").all_text_contents()
    page.locator(".dg-views-pop .di-option", has_text="desk").filter(has_text="shared · u").click()
    page.wait_for_function("() => document.querySelector('[data-pv-title]').textContent.startsWith('u/desk')")
    assert not page.errors, page.errors


def test_backslash_opens_the_panes_where_a_field_is_dragged_to_a_well_and_closes_them(page):
    page.locator("[data-pv-title]").click()
    page.keyboard.press("\\")
    page.wait_for_selector("[data-pv-grid] .dg-wells-panel .dg-fieldpane")
    assert page.evaluate("window.Drishti.PivotScreen.mode()") == "panes"
    assert page.locator(".dg-fieldpane .dg-hfield[data-hier='ORG']").count() == 1
    page.drag_and_drop(".dg-fieldpane .dg-field[data-field='TRADE_DATE']", ".dg-wells-panel .dg-zone[data-well='columns']")
    page.wait_for_function("() => window.Drishti.PivotScreen.gridApi().model.wells.state.columns.indexOf('TRADE_DATE') >= 0")
    page.locator("[data-pv-title]").click()
    page.keyboard.press("\\")
    page.wait_for_selector("[data-pv-grid] .dg-wells-ribbon")
    assert page.evaluate("window.Drishti.PivotScreen.mode()") == "views"
    assert not page.errors, page.errors


def test_the_address_opens_compact_or_panes_and_an_old_saved_view_opens_as_before(stack, browser):
    pg = opened(browser, stack[0] + "/pivot?source=trade&mode=compact")
    try:
        assert pg.locator("[data-pv-grid] .dg-wells-ribbon").is_visible() and pg.locator("[data-pv-grid] .dg-viewbtn").count() == 0
        assert not pg.errors, pg.errors
    finally:
        pg.context.close()
    pg = opened(browser, stack[0] + "/pivot?source=trade&mode=panes")
    try:
        assert pg.locator("[data-pv-grid] .dg-wells-panel .dg-build").is_visible()
        assert not pg.errors, pg.errors
    finally:
        pg.context.close()
    pg = opened(browser, stack[0] + "/pivot?view=u/desk")                                # a view saved before the modes: its wells as they were
    try:
        assert pg.locator("[data-pv-title]").text_content().startswith("u/desk")
        assert wells(pg)["rows"] == ["DESK"]
        assert pg.locator(".dg-zone[data-well='values'] .dg-vchip .dg-chip-label").all_text_contents() == ["count()"]
        assert not pg.errors, pg.errors
    finally:
        pg.context.close()


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
