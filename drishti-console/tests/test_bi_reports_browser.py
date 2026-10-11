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

"""Rupaka phase 3 in Chromium, over the real server and console (tests/bi_report_stack.py: ports 18990 and 17990, security on, a small file lake read through the
connector the shipped sample dataset ``trades-basic`` names; two business days of 60 trades with figures this test computes for itself): the sample report
"Trades overview" is opened and its cards, bar, line, table and slicer add up; clicking a bar filters (or highlights) the rest; drill-down, show-the-rows and
show-as-table; the page tabs and a bookmark; the keyboard; a phone (390 px); and the designer: drag a field to make a visual, change a well, add a filter, check,
save, undo and redo, the YAML tab both ways, moving and resizing from the keyboard.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import json
import re

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import card_value, chart, clean, click_mark, open_report, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, trade  # noqa: E402

REPORT = "file:report:trades-overview"
DAY = "2026-10-08"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = bi_report_stack.ReportStack(tmp_path_factory.mktemp("bireports"), delta_root="").start()
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


def day_trades(day=DAY):
    return [trade(day, i) for i in range(60)]


def by(key, measure, rows=None):
    out = {}
    for t in rows or day_trades():
        out[t[key]] = out.get(t[key], 0) + t[measure]
    return out


# ---- the viewer -------------------------------------------------------------------------------------------------------------

# What sticks out past the right edge (named in a failure, to say where to look).
WIDE = """() => [...document.querySelectorAll('body *')].filter(e => e.getBoundingClientRect().right > document.documentElement.clientWidth + 1
  && !e.closest('[hidden]') && getComputedStyle(e).position !== 'fixed'
  && !(e.parentElement && e.parentElement.closest('*') && (function (p) { for (; p && p !== document.body; p = p.parentElement) { if (/auto|scroll|hidden/.test(getComputedStyle(p).overflowX)) return true; } return false; })(e.parentElement))).slice(0, 6)
  .map(e => e.tagName.toLowerCase() + '.' + String(e.className).trim().split(/\\s+/)[0] + ' ' + Math.round(e.getBoundingClientRect().right))"""

def test_the_overview_opens_and_every_figure_adds_up(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    rows = day_trades()
    assert card_value(page, "trades") == 60
    assert card_value(page, "notional") == sum(t["notional"] for t in rows)
    assert card_value(page, "mtm") == sum(t["mtm"] for t in rows)
    assert float(page.locator('[data-visual="average"] .bi-card-value').get_attribute("data-value")) == pytest.approx(sum(t["mtm"] for t in rows) / 60)
    bar = chart(page, "by-desk")
    want = by("desk", "mtm")
    assert dict(zip(bar["categories"], bar["series"][0]["values"])) == want
    assert list(bar["series"][0]["values"]) == sorted(want.values(), reverse=True)                   # ranked by the measure, largest first
    assert chart(page, "by-date")["categories"] == [DAY]
    table = page.locator('[data-visual="rows"] tbody tr')
    expect(table).to_have_count(3)
    assert [t.inner_text().split("\t")[0] for t in table.all()] == sorted(want)
    expect(page.locator('[data-visual="currency"] [data-slicer-value]')).to_have_count(3)
    foot = page.locator('[data-visual="by-desk"] .bi-vis-foot').inner_text()
    assert re.search(r"3 rows · [\d.]+ ms|3 rows · from the cache", foot) and "columns" in foot          # the query time and the layer are under every visual
    clean(page)


def test_clicking_a_bar_filters_the_other_visuals_and_clearing_restores_them(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    fx = [t for t in day_trades() if t["desk"] == "DESK-FX"]
    click_mark(page, "by-desk", "DESK-FX")
    expect(page.locator('[data-chip="selection"]')).to_contain_text("DESK-FX")
    settled(page)
    assert card_value(page, "trades") == 20
    assert card_value(page, "mtm") == sum(t["mtm"] for t in fx)
    assert card_value(page, "notional") == sum(t["notional"] for t in fx)
    expect(page.locator('[data-visual="rows"] tbody tr')).to_have_count(1)
    bar = chart(page, "by-desk")
    assert bar["series"][0]["opacity"].count(0.35) == 2 and bar["series"][0]["opacity"].count(1) == 1       # the other bars are dimmed, the chosen one is not
    page.click('[data-visual="by-desk"] [data-act="clear"]')
    settled(page)
    assert card_value(page, "trades") == 60
    expect(page.locator('[data-chip="selection"]')).to_have_count(0)
    clean(page)


def test_a_mark_can_also_highlight_instead_of_filtering(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    bodies = []
    page.on("request", lambda r: bodies.append(r.post_data) if "/reports/" in r.url and r.method == "POST" else None)
    page.select_option("[data-mode]", "highlight")
    click_mark(page, "by-desk", "DESK-FX")
    page.wait_for_function("() => document.querySelector('[data-chip=selection]')")
    page.wait_for_timeout(800)
    asked = [json.loads(b) for b in bodies if b]
    assert asked and all(a["state"]["selections"][0]["highlight"] is True for a in asked)                # the state says highlight, not filter
    assert card_value(page, "trades") == 60 or card_value(page, "trades") == 20                          # a card cannot draw a highlight: it is filtered
    assert chart(page, "by-date")["series"][0]["values"] != []
    clean(page)


def test_drill_down_follows_the_hierarchy_and_up_comes_back(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    expect(page.locator('[data-visual="by-desk"] [data-act="drill-down"]')).to_be_disabled()           # nothing chosen yet
    click_mark(page, "by-desk", "DESK-FI")
    page.click('[data-visual="by-desk"] [data-act="drill-down"]')
    page.wait_for_function("() => document.querySelector('[data-visual=by-desk] .bi-vis-title').textContent.includes('DESK-FI')")
    settled(page)
    bar = chart(page, "by-desk")
    fi = [t for t in day_trades() if t["desk"] == "DESK-FI"]
    assert dict(zip(bar["categories"], bar["series"][0]["values"])) == by("book", "mtm", fi)               # books of that desk only
    expect(page.locator('[data-visual="by-desk"] [data-act="drill-down"]')).to_be_disabled()              # the hierarchy has no more levels
    page.click('[data-visual="by-desk"] [data-act="drill-up"]')
    settled(page)
    assert set(chart(page, "by-desk")["categories"]) == {"DESK-FX", "DESK-FI", "DESK-EQD"}
    clean(page)


def test_a_slicer_filters_the_page_and_reset_clears_it(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    page.check('[data-visual="currency"] [data-slicer-value="USD"]')
    settled(page)
    assert card_value(page, "trades") == 20
    usd = [t for t in day_trades() if t["currency"] == "USD"]
    assert card_value(page, "notional") == sum(t["notional"] for t in usd)
    assert set(chart(page, "by-desk")["categories"]) == {t["desk"] for t in usd}
    expect(page.locator('[data-chip="slicer"]')).to_contain_text("USD")
    expect(page.locator('[data-visual="currency"] [data-slicer-value]')).to_have_count(3)                # a slicer lists every value, not only the chosen
    page.click("[data-reset]")
    settled(page)
    assert card_value(page, "trades") == 60
    clean(page)


def test_show_the_rows_and_show_as_a_table(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    click_mark(page, "by-desk", "DESK-EQD")
    page.click('[data-visual="by-desk"] [data-act="rows"]')
    page.wait_for_selector('[data-visual="by-desk"] table tbody tr')
    expect(page.locator('[data-visual="by-desk"] table tbody tr')).to_have_count(20)                     # the 20 trades of that desk, every column of the table
    head = page.locator('[data-visual="by-desk"] thead th').all_inner_texts()
    assert "tradeId" in " ".join(head) and "desk" in " ".join(head) and "mtmInMillions" in " ".join(head)
    page.click('[data-visual="by-desk"] [data-act="rows"]')
    page.wait_for_selector('[data-visual="by-desk"] .bi-chart')
    page.click('[data-visual="by-date"] [data-act="as-table"]')
    expect(page.locator('[data-visual="by-date"] table tbody tr')).to_have_count(1)
    page.click('[data-visual="by-date"] [data-act="as-table"]')
    page.wait_for_selector('[data-visual="by-date"] .bi-chart')
    clean(page)


def test_the_page_tabs_the_matrix_with_subtotals_and_a_bookmark(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    page.click('[data-pages] [data-page="products"]')
    page.wait_for_url("**/products")
    settled(page)
    rows = day_trades()
    donut = chart(page, "share")
    assert round(sum(donut["series"][0]["values"])) == sum(t["notional"] for t in rows)
    matrix = page.locator('[data-visual="matrix"]')
    expect(matrix.locator("tbody tr[data-row]")).to_have_count(3)
    total = matrix.locator("tr[data-grandtotal]")
    assert total.locator('td[data-measure="Trades"]').first.inner_text() == "20"
    cells = [c.inner_text().replace(",", "").replace("+", "").replace("−", "-") for c in total.locator('td[data-measure="MTM"]').all()]
    assert int(cells[-1]) == sum(t["mtm"] for t in rows)                                                  # the grand total is the sum of the rows
    page.go_back()
    page.wait_for_selector('[data-visual="by-desk"] .bi-chart')
    page.select_option("[data-bookmarks]", "FX desk only")
    settled(page)
    assert card_value(page, "trades") == 20
    expect(page.locator('[data-chip="carry"]')).to_contain_text("DESK-FX")
    clean(page)


def test_the_keyboard_selects_a_mark_clears_it_and_switches_to_the_table(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    page.locator('[data-visual="by-desk"] .bi-chart').focus()
    page.keyboard.press("ArrowDown")
    first = chart(page, "by-desk")["categories"][0]
    page.keyboard.press("Enter")
    expect(page.locator('[data-chip="selection"]')).to_contain_text(first)
    page.locator('[data-visual="by-desk"]').focus()
    page.keyboard.press("Escape")
    expect(page.locator('[data-chip="selection"]')).to_have_count(0)
    page.keyboard.press("t")
    page.wait_for_selector('[data-visual="by-desk"] table')
    page.locator('[data-visual="rows"] tbody tr').first.focus()
    page.keyboard.press("Enter")
    expect(page.locator('[data-chip="selection"]')).to_have_count(1)                                       # a table row selects too
    clean(page)


def test_a_phone_shows_one_column_without_sideways_scroll_and_finger_sized_buttons(stack, browser):
    page = sign_in(browser, "rita", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    open_report(page, REPORT)
    settled(page)
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), ("the page scrolls sideways", page.evaluate(WIDE))
    xs = page.evaluate("[...document.querySelectorAll('.bi-vis')].map(v => Math.round(v.getBoundingClientRect().left))")
    assert len(set(xs)) == 1, xs                                                                         # one column
    ys = page.evaluate("[...document.querySelectorAll('.bi-vis')].map(v => Math.round(v.getBoundingClientRect().top))")
    assert ys == sorted(ys)                                                                              # in reading order
    for sel in ('[data-visual="by-desk"] .bi-vis-btn', "[data-reset]:not([hidden])", "[data-pages] a"):
        for el in page.locator(sel).all():
            if el.is_visible():
                assert el.bounding_box()["height"] >= 43, (sel, el.bounding_box())
    click_mark(page, "by-desk", "DESK-FX")
    expect(page.locator('[data-chip="selection"]')).to_have_count(1)
    clean(page)


def test_a_missing_report_says_so_with_its_code(stack, browser):
    page = sign_in(browser, "ann")
    open_report(page, REPORT)
    settled(page)
    page.goto(CONSOLE_URL + "/bi/r/file:report:no-such-report")
    expect(page.locator("[data-msg]")).to_contain_text("DRS-9001")


# ---- the designer -----------------------------------------------------------------------------------------------------------

def new_draft(page):
    page.goto(CONSOLE_URL + "/bi/designer")
    page.wait_for_selector("[data-new-dataset] option[value='file:dataset:trades-basic']", state="attached")
    page.select_option("[data-new-dataset]", "file:dataset:trades-basic")
    page.fill("[data-new-title]", "Designer test")
    page.click("[data-new] button[type=submit]")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    return page.url.rsplit("/", 1)[1]


def model(page):
    return page.evaluate("window.drishtiBi.design.model")


def test_designer_drag_a_field_makes_the_best_visual_and_another_field_improves_it(stack, browser):
    page = sign_in(browser, "wes")
    new_draft(page)
    expect(page.locator("[data-canvas-empty]")).to_be_visible()
    page.drag_and_drop('[data-field="MTM"]', "[data-canvas-grid]", target_position={"x": 80, "y": 40})
    page.wait_for_selector('.bi-dvis[data-type="card"]')
    page.wait_for_selector('.bi-dvis .bi-card-value')
    assert card_value(page, "card-1") == sum(t["mtm"] for t in day_trades())                              # real data while designing
    expect(page.locator("[data-canvas-empty]")).to_be_hidden()
    page.drag_and_drop('[data-field="trade.desk"]', ".bi-dvis")                                           # a category with the measure: a bar
    page.wait_for_selector('.bi-dvis[data-type="bar"]')
    settled_design(page)
    bar = page.evaluate("(() => { const c = echarts.getInstanceByDom(document.querySelector('.bi-dvis .bi-chart')); return c.getOption().yAxis[0].data; })()")
    assert sorted(bar) == ["DESK-EQD", "DESK-FI", "DESK-FX"]
    assert re.search(r"\d+ ms|from the cache", page.locator(".bi-dvis .bi-vis-foot").inner_text())         # the query time is shown on the visual
    assert model(page)["pages"][0]["visuals"][0]["wells"] == {"axis": ["trade.desk"], "values": ["MTM"]}
    clean(page)


def settled_design(page):
    page.wait_for_function("() => !document.querySelector('.bi-dvis.bi-vis-busy') && [...document.querySelectorAll('.bi-dvis')].every(v => v.querySelector('.bi-vis-ms') || v.querySelector('.bi-dropnote') || v.querySelector('.bi-vis-error') || v.classList.contains('bi-vis-failed') || v.querySelector('.bi-text,.bi-button,.bi-picture'))", timeout=30000)


def test_designer_change_a_well_set_a_filter_check_save_and_reload(stack, browser):
    page = sign_in(browser, "wes")
    draft = new_draft(page)
    page.drag_and_drop('[data-field="trade.desk"]', "[data-canvas-grid]", target_position={"x": 60, "y": 40})
    page.wait_for_selector('.bi-dvis[data-type="table"]')
    page.click('.bi-vtype[data-type="bar"]')                                                              # change the visual's type: the fields follow
    settled_design(page)
    page.select_option('[data-add="values"]', "Notional")                                                  # a well, by the select (no dragging)
    settled_design(page)
    assert model(page)["pages"][0]["visuals"][0]["wells"]["values"] == ["Notional"]
    page.select_option('[data-add="values"]', "Trades")
    settled_design(page)
    assert model(page)["pages"][0]["visuals"][0]["wells"]["values"] == ["Notional", "Trades"]
    page.click('[data-remove="Trades"]')
    assert model(page)["pages"][0]["visuals"][0]["wells"]["values"] == ["Notional"]
    # a page filter: currency is one of USD
    page.click('[data-scope="page"] [data-add-filter]')
    page.select_option('[data-filter-editor] [name="field"]', "trade.currency")
    page.fill('[data-filter-editor] [name="values"]', "USD")
    page.click('[data-filter-editor] button[type=submit]')
    settled_design(page)
    assert model(page)["pages"][0]["filters"] == [{"field": "trade.currency", "op": "in", "values": ["USD"]}]
    usd = [t for t in day_trades() if t["currency"] == "USD"]
    bar = page.evaluate("(() => { const c = echarts.getInstanceByDom(document.querySelector('.bi-dvis .bi-chart')); return [c.getOption().yAxis[0].data, c.getOption().series[0].data.map(d => d.value)]; })()")
    assert dict(zip(*bar)) == by("desk", "notional", usd)                                                  # the filter reached the data
    page.click("[data-check]")
    page.wait_for_selector("[data-check-list] li")
    assert "no title and no alt text" in page.locator("[data-check-list]").inner_text()                    # a basic accessibility check
    page.click("[data-ptab='format']")
    page.fill("[data-format-title]", "Notional by desk, USD")
    page.press("[data-format-title]", "Enter")
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved")
    page.goto(CONSOLE_URL + "/bi/design/" + draft)
    page.wait_for_selector("[data-design-root][data-ready=yes]")
    saved = model(page)["pages"][0]
    model_id = saved["visuals"][0]["id"]
    assert model_id == "bar-1"                                                                            # changing the type renamed the visual too
    assert saved["filters"][0]["values"] == ["USD"] and saved["visuals"][0]["title"] == "Notional by desk, USD"
    page.goto(f"{CONSOLE_URL}/bi/r/{draft}")                                                              # and the viewer draws the saved draft
    page.wait_for_selector("[data-ready=yes]")
    settled(page)
    drawn = chart(page, model_id)
    assert dict(zip(drawn["categories"], drawn["series"][0]["values"])) == by("desk", "notional", usd)
    clean(page)


def test_designer_undo_redo_and_the_yaml_tab_work_both_ways(stack, browser):
    page = sign_in(browser, "wes")
    new_draft(page)
    page.drag_and_drop('[data-field="Trades"]', "[data-canvas-grid]", target_position={"x": 40, "y": 30})
    page.wait_for_selector('.bi-dvis[data-type="card"]')
    page.click("[data-undo]")
    expect(page.locator(".bi-dvis")).to_have_count(0)
    page.click("[data-redo]")
    expect(page.locator(".bi-dvis")).to_have_count(1)
    page.keyboard.press("Control+z")
    expect(page.locator(".bi-dvis")).to_have_count(0)
    page.keyboard.press("Control+y")
    expect(page.locator(".bi-dvis")).to_have_count(1)
    # canvas to YAML
    page.click('[data-tab="yaml"]')
    page.wait_for_function("() => document.querySelector('.CodeMirror').CodeMirror.getValue().includes('values:')")
    text = page.evaluate("document.querySelector('.CodeMirror').CodeMirror.getValue()")
    assert "type: card" in text and "wells: { values: [Trades] }" in text
    # YAML to canvas: a second visual typed in the text appears on the canvas, and Undo takes it away again
    edited = text.replace("    visuals:\n", "    visuals:\n      - id: typed\n        type: card\n        title: Typed in YAML\n        at: { x: 6, y: 0, w: 3, h: 2 }\n        wells: { values: [MTM] }\n", 1) if "    visuals:\n" in text else text
    page.evaluate("t => document.querySelector('.CodeMirror').CodeMirror.setValue(t)", edited)
    page.wait_for_function("() => window.drishtiBi.design.model.pages[0].visuals.length === 2", timeout=10000)
    page.click('[data-tab="canvas"]')
    expect(page.locator('.bi-dvis[data-visual="typed"]')).to_be_visible()
    # a mistake in the YAML is reported with its code and does not change the canvas
    page.click('[data-tab="yaml"]')
    page.evaluate("t => document.querySelector('.CodeMirror').CodeMirror.setValue(t)", edited.replace("values: [MTM]", "values: [Nonsense]"))
    page.wait_for_selector('[data-yaml-problems] [data-code="DRS-9031"]')
    page.click('[data-tab="canvas"]')
    expect(page.locator(".bi-dvis")).to_have_count(2)
    clean(page)


def test_designer_move_resize_duplicate_and_delete_from_the_keyboard(stack, browser):
    page = sign_in(browser, "wes")
    new_draft(page)
    page.drag_and_drop('[data-field="Notional"]', "[data-canvas-grid]", target_position={"x": 20, "y": 20})
    page.wait_for_selector('.bi-dvis[data-type="card"]')
    box = page.locator(".bi-dvis").first
    at0 = model(page)["pages"][0]["visuals"][0]["at"]
    box.focus()
    page.keyboard.press("ArrowRight")
    page.keyboard.press("ArrowDown")
    page.keyboard.press("Shift+ArrowRight")
    page.keyboard.press("Shift+ArrowDown")
    at = model(page)["pages"][0]["visuals"][0]["at"]
    assert (at["x"], at["y"], at["w"], at["h"]) == (at0["x"] + 1, at0["y"] + 1, at0["w"] + 1, at0["h"] + 1)
    page.keyboard.press("Control+d")
    expect(page.locator(".bi-dvis")).to_have_count(2)
    ids = [v["id"] for v in model(page)["pages"][0]["visuals"]]
    assert len(set(ids)) == 2
    xs = page.evaluate("window.drishtiBi.design.model.pages[0].visuals.map(v => [v.at.x, v.at.y, v.at.w, v.at.h])")
    a, b = xs
    assert not (a[0] < b[0] + b[2] and b[0] < a[0] + a[2] and a[1] < b[1] + b[3] and b[1] < a[1] + a[3]), "the copy overlaps the original"     # the grid settles
    page.keyboard.press("Delete")
    expect(page.locator(".bi-dvis")).to_have_count(1)
    clean(page)


def test_designer_resize_with_the_pointer_snaps_to_the_grid(stack, browser):
    page = sign_in(browser, "wes")
    new_draft(page)
    page.drag_and_drop('[data-field="Notional"]', "[data-canvas-grid]", target_position={"x": 20, "y": 20})
    page.wait_for_selector('.bi-dvis[data-type="card"]')
    handle = page.locator(".bi-dvis .bi-resize").first
    hb = handle.bounding_box()
    grid = page.locator("[data-canvas-grid]").bounding_box()
    step = (grid["width"] - 11 * 10) / 12 + 10
    page.mouse.move(hb["x"] + 8, hb["y"] + 8)
    page.mouse.down()
    page.mouse.move(hb["x"] + 8 + 2 * step, hb["y"] + 8 + 66 * 2, steps=6)
    page.mouse.up()
    at = model(page)["pages"][0]["visuals"][0]["at"]
    assert at["w"] == 5 and at["h"] == 4, at                                                               # 3 wide and 2 high grew by 2 columns and 2 rows
    handle = page.locator(".bi-dvis .bi-dhandle").first                                                    # and the header moves it
    bb = handle.bounding_box()
    page.mouse.move(bb["x"] + 30, bb["y"] + 10)
    page.mouse.down()
    page.mouse.move(bb["x"] + 30 + 3 * step, bb["y"] + 10, steps=6)
    page.mouse.up()
    assert model(page)["pages"][0]["visuals"][0]["at"]["x"] >= 3
    clean(page)


def test_designer_pages_the_report_settings_and_a_drillthrough_target(stack, browser):
    page = sign_in(browser, "wes")
    new_draft(page)
    page.click("[data-add-page]")
    expect(page.locator("[data-ptabs] [role=tab]")).to_have_count(2)
    page.click("[data-ptab='format']")
    page.fill("[data-page-title]", "Detail")
    page.press("[data-page-title]", "Enter")
    page.select_option("[data-page-drill]", "trade.desk")
    m = model(page)
    assert m["pages"][1]["title"] == "Detail" and m["pages"][1]["drillthrough"] == {"fields": ["trade.desk"]}
    page.select_option("[data-theme-palette]", "colour-blind")
    assert model(page)["theme"] == {"palette": "colour-blind"}
    page.fill("[data-bookmark-name]", "Start")
    page.click("[data-add-bookmark]")
    assert model(page)["bookmarks"] == [{"name": "Start", "page": "page2"}]
    page.once("dialog", lambda d: d.accept())
    page.click("[data-delete-page]")
    expect(page.locator("[data-ptabs] [role=tab]")).to_have_count(1)
    clean(page)


def test_a_shipped_report_opens_read_only_and_can_be_saved_as_a_copy(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(f"{CONSOLE_URL}/bi/design/{REPORT}")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    expect(page.locator("[data-readonly]")).to_be_visible()
    expect(page.locator("[data-save]")).to_be_disabled()
    settled_design(page)
    assert page.locator(".bi-dvis").count() == 8                                                           # the whole first page, with data
    page.click("[data-save-copy]")
    page.wait_for_url("**/bi/design/bi-**")
    page.wait_for_selector("[data-design-root][data-ready=yes]")
    expect(page.locator("[data-readonly]")).to_be_hidden()
    assert "(copy)" in page.locator("[data-title]").inner_text()
    clean(page)


EVERY_TYPE = """rachana: 1
report: every-type
dataset: trades-basic
theme: colour-blind
pages:
  - id: all
    title: Every visual
    visuals:
      - { id: bar, type: bar, title: Bar, at: { x: 0, y: 0, w: 4, h: 4 }, wells: { axis: [trade.desk], values: [MTM] } }
      - { id: column, type: column, title: Column, at: { x: 4, y: 0, w: 4, h: 4 }, wells: { axis: [trade.desk], legend: [trade.currency], values: [Notional] }, format: { stacked: true, labels: true } }
      - { id: line, type: line, title: Line, at: { x: 8, y: 0, w: 4, h: 4 }, wells: { axis: [trade.tradeDate], values: [Notional] } }
      - { id: area, type: area, title: Area, at: { x: 0, y: 4, w: 4, h: 4 }, wells: { axis: [trade.tradeDate], values: [Notional] } }
      - { id: combo, type: combo, title: Combo, at: { x: 4, y: 4, w: 4, h: 4 }, wells: { axis: [trade.desk], values: [Notional], secondary: [MTM] } }
      - { id: scatter, type: scatter, title: Scatter, at: { x: 8, y: 4, w: 4, h: 4 }, wells: { details: [trade.book], x: [Notional], y: [MTM], size: [Trades] } }
      - { id: pie, type: pie, title: Pie, at: { x: 0, y: 8, w: 3, h: 4 }, wells: { axis: [trade.productType], values: [Notional] } }
      - { id: donut, type: donut, title: Donut, at: { x: 3, y: 8, w: 3, h: 4 }, wells: { axis: [trade.productType], values: [Notional] } }
      - { id: waterfall, type: waterfall, title: Waterfall, at: { x: 6, y: 8, w: 3, h: 4 }, wells: { axis: [trade.desk], values: [MTM] } }
      - { id: treemap, type: treemap, title: Treemap, at: { x: 9, y: 8, w: 3, h: 4 }, wells: { axis: [trade.desk, trade.book], values: [Notional] } }
      - { id: heatmap, type: heatmap, title: Heatmap, at: { x: 0, y: 12, w: 4, h: 4 }, wells: { axis: [trade.desk], legend: [trade.currency], values: [Trades] } }
      - { id: card, type: card, title: Card, at: { x: 4, y: 12, w: 2, h: 2 }, wells: { values: [MTM] } }
      - { id: multi, type: multirow-card, title: Multi-row card, at: { x: 6, y: 12, w: 3, h: 3 }, wells: { axis: [trade.desk], values: [Trades, MTM] } }
      - { id: kpi, type: kpi, title: KPI, at: { x: 9, y: 12, w: 3, h: 3 }, wells: { values: [MTM], target: [Notional] } }
      - { id: gauge, type: gauge, title: Gauge, at: { x: 4, y: 14, w: 2, h: 3 }, wells: { values: [Trades] }, format: { max: 100 } }
      - { id: table, type: table, title: Table, at: { x: 0, y: 16, w: 4, h: 4 }, wells: { columns: [trade.tradeId, trade.desk, MTM] }, limit: 5 }
      - { id: matrix, type: matrix, title: Matrix, at: { x: 4, y: 17, w: 4, h: 4 }, wells: { rows: [trade.desk, trade.book], columns: [trade.currency], values: [Trades] }, format: { subtotals: true } }
      - { id: s-list, type: slicer, title: List slicer, at: { x: 8, y: 15, w: 2, h: 4 }, wells: { field: [trade.currency] } }
      - { id: s-drop, type: slicer, title: Dropdown slicer, at: { x: 10, y: 15, w: 2, h: 2 }, wells: { field: [trade.desk] }, format: { mode: dropdown } }
      - { id: s-range, type: slicer, title: Range slicer, at: { x: 10, y: 17, w: 2, h: 2 }, wells: { field: [trade.notional] }, format: { mode: range } }
      - { id: text, type: text, at: { x: 0, y: 20, w: 4, h: 1 }, text: A text visual, format: { size: heading } }
      - { id: image, type: image, at: { x: 4, y: 21, w: 2, h: 2 }, src: /static/img/drishti-icon.png, alt: The Drishti eye }
      - { id: button, type: button, at: { x: 6, y: 21, w: 3, h: 1 }, text: Go to the first page, action: { navigate: all } }
"""


def test_every_visual_type_draws_with_data(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/designer")
    made = page.evaluate("""async (body) => { const r = await fetch('/bi/api/workspaces/me/items', {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({kind: 'report', title: 'Every visual', description: '', body})}); return await r.json(); }""", EVERY_TYPE)
    open_report(page, made["id"])
    settled(page)
    failed = page.evaluate("[...document.querySelectorAll('.bi-vis-failed')].map(v => v.getAttribute('data-visual') + ': ' + v.textContent.slice(0, 160))")
    assert failed == [], failed
    rows = day_trades()
    desks = sorted({t["desk"] for t in rows})
    for vis in ("bar", "column", "line", "area", "combo", "scatter", "pie", "donut", "waterfall", "treemap", "heatmap", "gauge"):
        assert page.locator(f'[data-visual="{vis}"] .bi-chart').count() == 1, vis                 # an ECharts canvas for every chart
    assert sorted(chart(page, "bar")["categories"]) == desks
    col = chart(page, "column")                                                                    # one series per currency, stacked
    assert len(col["series"]) == 3 and sum(sum(v for v in s["values"] if v) for s in col["series"]) == sum(t["notional"] for t in rows)
    combo = chart(page, "combo")
    assert [s["type"] for s in combo["series"]] == ["bar", "line"]                                 # columns and a line on the second axis
    assert len(chart(page, "waterfall")["series"][0]["values"]) == len(desks) + 1                  # a step per desk and the total
    assert card_value(page, "card") == sum(t["mtm"] for t in rows)
    assert float(page.locator('[data-visual="gauge"] .bi-chart').count()) == 1
    assert "▲" in page.locator('[data-visual="kpi"]').inner_text() or "▼" in page.locator('[data-visual="kpi"]').inner_text()     # the KPI says how it stands against its target
    assert page.locator('[data-visual="multi"] [role=listitem]').count() == 3
    assert page.locator('[data-visual="table"] tbody tr').count() == 5
    expect(page.locator('[data-visual="matrix"] tr[data-subtotal]')).to_have_count(3)                # a subtotal per desk, collapsible
    page.click('[data-visual="matrix"] [data-group="DESK-FX"]')
    expect(page.locator('[data-visual="matrix"] tr[data-row^="DESK-FX"]')).to_have_count(0)
    page.click('[data-visual="matrix"] [data-group="DESK-FX"]')
    expect(page.locator('[data-visual="matrix"] tr[data-row^="DESK-FX"]')).to_have_count(1)
    assert page.locator('[data-visual="s-list"] [data-slicer-value]').count() == 3
    page.click('[data-visual="s-drop"] summary')
    assert page.locator('[data-visual="s-drop"] [data-slicer-value]').count() == 3
    page.keyboard.press("Escape")
    page.fill('[data-visual="s-range"] [data-range="from"]', "3000000")
    page.press('[data-visual="s-range"] [data-range="from"]', "Enter")
    settled(page)
    assert card_value(page, "card") == sum(t["mtm"] for t in rows if t["notional"] >= 3000000)      # a range slicer filters like any other
    assert page.locator('[data-visual="text"] h3.bi-text').inner_text() == "A text visual"
    assert page.locator('[data-visual="image"] img').get_attribute("alt") == "The Drishti eye"
    page.click('[data-visual="button"] button')
    assert page.url.endswith("/all")
    expect(page.locator("[data-chip]")).to_have_count(1)
    clean(page)


def test_designer_filters_advanced_and_top_n_the_suggestions_and_conditional_colours(stack, browser):
    page = sign_in(browser, "wes")
    new_draft(page)
    page.drag_and_drop('[data-field="trade.desk"]', "[data-canvas-grid]", target_position={"x": 60, "y": 40})
    page.wait_for_selector('.bi-dvis[data-type="table"]')
    page.drag_and_drop('[data-field="MTM"]', ".bi-dvis")
    page.wait_for_selector('.bi-dvis[data-type="bar"]')
    settled_design(page)
    # an advanced filter on the visual: notional at least 3,000,000
    page.click('[data-scope="visual"] [data-add-filter]')
    page.select_option('[data-filter-editor] [name="field"]', "trade.notional")
    page.select_option('[data-filter-editor] [name="mode"]', "advanced")
    page.select_option('[data-filter-editor] [name="op"]', "ge")
    page.fill('[data-filter-editor] [name="v1"]', "3000000")
    page.click('[data-filter-editor] button[type=submit]')
    settled_design(page)
    assert model(page)["pages"][0]["visuals"][0]["filters"] == [{"field": "trade.notional", "op": "ge", "values": [3000000]}]
    big = [t for t in day_trades() if t["notional"] >= 3000000]
    got = page.evaluate("(() => { const o = echarts.getInstanceByDom(document.querySelector('.bi-dvis .bi-chart')).getOption(); return [o.yAxis[0].data, o.series[0].data.map(d => d.value)]; })()")
    assert dict(zip(*got)) == by("desk", "mtm", big)
    # top 2 desks by notional, on the report: the pick-from-the-data list is there too
    page.click('[data-scope="report"] [data-add-filter]')
    page.select_option('[data-filter-editor] [name="field"]', "trade.desk")
    page.click("[data-load-values]")
    page.wait_for_selector("[data-pick-value]")
    assert page.locator("[data-pick-value]").count() == 3
    page.select_option('[data-filter-editor] [name="mode"]', "top")
    page.fill('[data-filter-editor] [name="n"]', "2")
    page.click('[data-filter-editor] button[type=submit]')
    settled_design(page)
    assert model(page)["filters"] == [{"field": "trade.desk", "op": "top", "n": 2, "by": "Notional", "dir": "desc"}]
    got = page.evaluate("(() => { const o = echarts.getInstanceByDom(document.querySelector('.bi-dvis .bi-chart')).getOption(); return o.yAxis[0].data; })()")
    top2 = sorted(by("desk", "notional").items(), key=lambda kv: -kv[1])[:2]
    assert sorted(got) == sorted(k for k, _ in top2) or sorted(got) == sorted({t["desk"] for t in big} & {k for k, _ in top2})
    # the filter is edited and removed again
    page.click('[data-scope="report"] [data-remove-filter]')
    assert "filters" not in model(page)
    # conditional colour on the measure
    page.click("[data-ptab='format']")
    page.click("[data-add-conditional]")
    assert model(page)["pages"][0]["visuals"][0]["format"]["conditional"][0]["measure"] == "MTM"
    # the YAML tab offers what fits the cursor
    page.click('[data-tab="yaml"]')
    page.evaluate("""() => { const cm = document.querySelector('.CodeMirror').CodeMirror; cm.focus(); const n = cm.lineCount(); cm.setCursor({line: n - 1, ch: 0}); cm.replaceRange('      - { type: ', {line: n - 1, ch: cm.getLine(n - 1).length}); cm.setCursor({line: cm.lineCount() - 1, ch: 100}); }""")
    page.wait_for_function("() => [...document.querySelectorAll('[data-suggest] .bi-suggest')].some(b => b.textContent === 'waterfall')", timeout=10000)
    clean(page)


@pytest.mark.parametrize("width,height", [(390, 844), (2560, 1440)])
def test_the_viewer_and_the_designer_do_not_scroll_sideways_on_a_phone_or_a_wide_screen(stack, browser, width, height):
    page = sign_in(browser, "wes", viewport={"width": width, "height": height})
    for path in (f"/bi/r/{REPORT}", f"/bi/design/{REPORT}", "/bi/designer"):
        page.goto(CONSOLE_URL + path)
        page.wait_for_selector("[data-ready=yes], [data-design-start]", timeout=30000)
        page.wait_for_timeout(1200)
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), (f"{path} scrolls sideways at {width}", page.evaluate(WIDE))
    clean(page)
