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

"""Chart any table in a real browser (Chromium; docs/architecture/TABLE_CHARTS.md section 19.3): the Chart button and K on an
entity page's table, a search's results and a pick list, Shift+K on the history page, the records of a table with a Pivot tab, the
type switcher, the pickers, Top N, a masked column never plotted, Chart all N through the search pivot, View as table, the CSV
export, point navigation by keyboard, Esc with the focus returned, empty, single-row and big tables, a phone, and every console
theme (colours from the theme's tokens only). The console runs in-process against a stand-in server with synthetic data
(table_chart_backend.py); skipped when Playwright's Chromium is not installed."""
import re

import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from table_chart_backend import serve  # noqa: E402

THEMES = ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue")
VIEW = "/v/trade/CHART-1"
DLG = "dialog.tc-dlg[open]"


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve()
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
    c = browser.new_context(viewport={"width": 1366, "height": 860}, accept_downloads=True, bypass_csp=True)
    yield c
    c.close()


def open_view(ctx, stack, path=VIEW, wait="#p-flows [data-table-chart]"):
    url, _ = stack
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(url + path, wait_until="load")
    page.wait_for_selector(wait)
    page.errors = errors
    return page


def drawn(page):
    page.wait_for_selector(DLG + " .tc-canvas svg path", state="attached", timeout=15000)
    return page.evaluate("""(() => { const c = window.drishtiTableChart.current();
        return { type: c.settings.type, x: c.settings.x.field, series: c.option.series.map(s => s.name), kinds: c.option.series.map(s => s.type),
                 sum: document.querySelector('.tc-sum').textContent, status: document.querySelector('[data-tc-status]').textContent,
                 label: document.querySelector('.tc-canvas').getAttribute('aria-label') }; })()""")


def test_the_button_opens_the_chart_drawn_with_the_defaults_and_esc_returns_the_focus(ctx, stack):
    page = open_view(ctx, stack)
    btn = page.locator("#p-flows [data-table-chart]")
    assert btn.get_attribute("aria-keyshortcuts") == "K" and "Chart" in btn.get_attribute("aria-label")
    btn.click()
    d = drawn(page)
    assert d["type"] == "line" and d["x"] == "Pay date"                       # a date X: a line by date
    assert d["series"] == ["Amount (sum)"] and d["sum"].startswith("Line chart of Amount (sum) by Pay date")
    assert d["label"] == d["sum"]                                               # the caption is the chart's accessible name
    assert "40 rows" in d["sum"] and "figures as displayed" in d["status"]
    page.keyboard.press("Escape")
    page.wait_for_selector("dialog.tc-dlg", state="detached")
    assert page.evaluate("document.activeElement === document.querySelector('#p-flows [data-table-chart]')")
    assert not page.errors, page.errors


def test_k_on_a_focused_table_opens_it_and_the_type_switcher_redraws(ctx, stack):
    page = open_view(ctx, stack)
    page.focus("#p-flows table.tbl")
    page.keyboard.press("k")
    drawn(page)
    page.locator(".tc-canvas").focus()
    page.keyboard.press("2")                                                    # 2: bar
    d = drawn(page)
    assert d["type"] == "bar" and d["kinds"] == ["bar"]
    assert page.locator('[data-tc-type="bar"]').get_attribute("aria-pressed") == "true"
    page.click('[data-tc-type="pie"]')
    d = drawn(page)
    assert d["type"] == "pie" and d["x"] in ("Currency", "Desk") and d["kinds"] == ["pie"]
    page.click('[data-tc-type="combo"]')
    d = drawn(page)
    assert d["type"] == "combo" and len(d["series"]) == 2 and set(d["kinds"]) == {"bar", "line"}
    page.click('[data-tc-type="scatter"]')
    d = drawn(page)
    assert d["type"] == "scatter" and d["kinds"] == ["scatter"]


def test_x_split_top_n_and_a_second_series_redraw_and_the_summary_follows(ctx, stack):
    page = open_view(ctx, stack)
    page.click("#p-flows [data-table-chart]")
    drawn(page)
    page.select_option('[data-tc-k="x"]', "Desk")
    d = drawn(page)
    assert d["x"] == "Desk" and d["type"] == "line"
    page.click('[data-tc-type="bar"]')
    page.select_option('[data-tc-k="split"]', "Currency")
    d = drawn(page)
    assert sorted(d["series"]) == ["EUR", "GBP", "JPY", "USD"] and "split by Currency (4)" in d["sum"]
    page.select_option('[data-tc-k="split"]', "")
    page.click('[data-tc-k="add"]')
    page.select_option('[data-tc-k="s1-axis"]', "right")
    d = drawn(page)
    assert len(d["series"]) == 2
    assert page.evaluate("Array.isArray(window.drishtiTableChart.current().option.yAxis) && window.drishtiTableChart.current().option.yAxis.length") == 2
    page.fill('[data-tc-k="top"]', "2")
    page.locator('[data-tc-k="top"]').dispatch_event("change")
    drawn(page)
    xs = page.evaluate("window.drishtiTableChart.current().last.x")
    assert len(xs) == 3 and xs[-1] == "Others"


def test_a_masked_column_is_offered_greyed_and_never_plotted(ctx, stack):
    page = open_view(ctx, stack)
    page.click("#p-flows [data-table-chart]")
    d = drawn(page)
    for sel in ('[data-tc-k="x"]', '[data-tc-k="s0-field"]'):
        opt = page.locator(sel + ' option[value="Limit"]')
        assert opt.count() == 1 and opt.is_disabled() and "hidden for your role" in opt.inner_text()
    assert "Limit hidden for your role, not charted" in d["status"]
    # a crafted choice of the masked column is refused by the engine's normalise
    page.evaluate("(() => { const c = window.drishtiTableChart.current(); c.change(n => { n.series[0].field = 'Limit'; n.x.field = 'Limit'; }); })()")
    d = drawn(page)
    assert all("Limit" not in s for s in d["series"]) and d["x"] != "Limit"
    assert "•••" not in page.evaluate("JSON.stringify(window.drishtiTableChart.current().gridData())")


def test_search_results_chart_the_raw_values_and_chart_all_asks_the_search_pivot(ctx, stack):
    url, backend = stack
    page = open_view(ctx, stack, "/s?q=TRD%20where%20notional%20%3E%200", "table[data-chart-rows] ~ * , [data-table-chart]")
    page.click("[data-table-chart]")
    d = drawn(page)
    assert d["type"] == "line" and d["x"] == "Trade date"                      # the first date column
    assert "MTM hidden for your role" in d["status"] and "figures as displayed" not in d["status"]   # raw values, not the rounded cells
    page.select_option('[data-tc-k="x"]', "Desk")
    page.click('[data-tc-type="bar"]')
    d = drawn(page)
    assert d["x"] == "Desk" and d["series"] == ["Notional (sum)"]
    total = page.evaluate("window.drishtiTableChart.current().last.series[0].data.reduce((a, b) => a + b, 0)")
    assert total == sum(1_000_000 * ((i * 37) % 50 + 1) for i in range(30))
    allb = page.locator(".tc-all")
    assert allb.is_visible() and allb.is_enabled() and "Chart all 500 rows" in allb.inner_text()
    before = len(backend.pivot_calls)
    allb.click()
    page.wait_for_function("document.querySelector('[data-tc-status]').textContent.includes('Every match')")
    assert len(backend.pivot_calls) == before + 1
    assert backend.pivot_calls[-1]["rows"] == ["desk"] and backend.pivot_calls[-1]["values"] == [{"field": "notional", "agg": "sum"}]
    assert page.evaluate("window.drishtiTableChart.current().last.series[0].data").count(None) == 0
    assert "Chart the shown rows" in allb.inner_text()
    page.select_option('[data-tc-k="split"]', "Currency")                     # a change while charting all asks the server again
    page.wait_for_function(f"document.querySelector('[data-tc-status]').textContent.includes('Every match')")
    assert len(backend.pivot_calls) == before + 2 and backend.pivot_calls[-1]["columns"] == ["currency"]
    page.click(".tc-all")                                                      # back to the shown rows
    page.select_option('[data-tc-k="x"]', "Trade date")                       # not a pivot field: Chart all stands down, with the reason
    assert allb.is_disabled() and "pivot" in allb.get_attribute("title")


def test_a_table_with_a_pivot_tab_charts_its_raw_records(ctx, stack):
    url, backend = stack
    page = open_view(ctx, stack, "/v/netting-set/NS-NORTH-01", "#p-trades [data-table-chart]")
    page.click("#p-trades [data-table-chart]")
    d = drawn(page)
    assert backend.record_calls and backend.record_calls[-1][2] == "trades"
    assert "figures as displayed" not in d["status"] and d["series"] == ["MTM (USD) (sum)"] and d["x"] == "Maturity"


def test_the_history_page_opens_with_shift_k_and_plain_k_stays_the_previous_day(ctx, stack):
    page = open_view(ctx, stack, "/v/trade/1000015/history?days=30", "[data-hx-chart-open]")
    page.keyboard.press("k")
    assert page.locator(DLG).count() == 0
    page.keyboard.press("Shift+K")
    d = drawn(page)
    assert d["x"] == "DATE" and d["type"] == "line"
    page.keyboard.press("Escape")
    page.wait_for_selector("dialog.tc-dlg", state="detached")
    assert "/history" in page.url                                               # Esc closed the chart, not the history page


def test_view_as_table_export_and_point_navigation_by_keyboard(ctx, stack):
    page = open_view(ctx, stack)
    page.click("#p-flows [data-table-chart]")
    drawn(page)
    page.locator(".tc-canvas").focus()
    page.keyboard.press("ArrowRight")
    page.wait_for_function("document.querySelector('[data-tc-live]').textContent.includes('(1 of')")
    page.keyboard.press("End")
    page.wait_for_function("/\\((\\d+) of \\1\\)/.test(document.querySelector('[data-tc-live]').textContent)")
    page.keyboard.press("t")
    page.wait_for_selector(".tc-table table tbody tr")
    assert page.locator('[data-tc-act="table"]').get_attribute("aria-pressed") == "true"
    rows = page.locator(".tc-table tbody tr").count()
    with page.expect_download() as dl:
        page.click('[data-tc-act="csv"]')
    text = open(dl.value.path(), encoding="utf-8-sig").read().strip().splitlines()
    assert len(text) == rows + 1 and text[0].startswith("Pay date")
    with page.expect_download() as dl:
        page.click('[data-tc-act="png"]')
    assert dl.value.suggested_filename.endswith(".png")
    page.keyboard.press("t")
    with page.expect_download() as dl:
        page.click('[data-tc-act="svg"]')
    assert open(dl.value.path(), encoding="utf-8").read().lstrip().startswith("<svg")


def test_empty_single_row_and_big_tables(ctx, stack):
    page = open_view(ctx, stack)
    page.fill("#p-flows .tbl-pg-q", "no-such-value")
    page.click("#p-flows [data-table-chart]")
    page.wait_for_selector(DLG + " .tc-msg:not([hidden])")
    assert "No rows to chart" in page.inner_text(".tc-msg") and "filters" in page.inner_text(".tc-msg")
    page.keyboard.press("Escape")
    page.click("#p-one [data-table-chart]")
    d = drawn(page)
    assert d["sum"].endswith("one row.")
    page.keyboard.press("Escape")
    page.click("#p-big [data-table-chart]")
    d = drawn(page)
    assert d["type"] == "bar" and "top 20 and Others" in d["status"]
    assert len(page.evaluate("window.drishtiTableChart.current().last.x")) == 21


def test_a_phone_gets_a_full_screen_sheet_with_no_sideways_scroll(browser, stack):
    c = browser.new_context(viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    try:
        page = open_view(c, stack)
        page.click("#p-flows [data-table-chart]")
        drawn(page)
        box = page.evaluate("(() => { const r = document.querySelector('dialog.tc-dlg').getBoundingClientRect(); return [r.width, r.height]; })()")
        assert box[0] >= 389 and box[1] >= 800
        assert page.evaluate("document.querySelector('dialog.tc-dlg').scrollWidth <= document.querySelector('dialog.tc-dlg').clientWidth + 1")
        assert page.locator(".tc-sheet > summary").is_visible()
        h = page.evaluate("document.querySelector('[data-tc-type=\"bar\"]').getBoundingClientRect().height")
        assert h >= 44
    finally:
        c.close()


def _hex(rgb):
    m = re.match(r"rgba?\((\d+),\s*(\d+),\s*(\d+)", rgb)
    return "#%02x%02x%02x" % tuple(int(x) for x in m.groups()) if m else rgb.lower()


def test_every_theme_draws_with_its_own_tokens_only(ctx, stack):
    page = open_view(ctx, stack)
    page.click("#p-flows [data-table-chart]")
    drawn(page)
    page.click('[data-tc-type="bar"]')
    page.select_option('[data-tc-k="split"]', "Currency")
    for name in THEMES:
        page.evaluate("n => { document.documentElement.setAttribute('data-theme', n); document.dispatchEvent(new CustomEvent('drishti:theme', { detail: n })); }", name)
        page.wait_for_timeout(50)
        got = page.evaluate("""(() => {
            const s = getComputedStyle(document.documentElement), tok = {};
            ['link','accent','ok','neg','warn','bad','muted','ink','faint','border','surface'].forEach(k => tok[k] = s.getPropertyValue('--d-' + k).trim().toLowerCase());
            const o = window.drishtiTableChart.current().option;
            return { tok, colours: o.series.map(x => String(x.itemStyle.color).toLowerCase()),
                     bg: getComputedStyle(document.querySelector('dialog.tc-dlg')).backgroundColor,
                     fills: Array.from(document.querySelectorAll('.tc-canvas svg path[fill]')).map(p => p.getAttribute('fill').toLowerCase()) };
        })()""")
        tokens = set(got["tok"].values())
        assert set(got["colours"]) <= tokens, (name, got["colours"])
        assert _hex(got["bg"]) == got["tok"]["surface"], (name, got["bg"])
        drawn_fills = {f for f in got["fills"] if f.startswith("#") or f.startswith("rgb")}
        assert {_hex(f) if f.startswith("rgb") else f for f in drawn_fills} <= tokens | {"#000000", "#ffffff", "none", "transparent"}, name
