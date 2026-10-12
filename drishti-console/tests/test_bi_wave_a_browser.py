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

"""The BI long tail (wave A) in Chromium over the real server and console (tests/bi_report_stack.py on ports 18954 and 17954 unless
DRISHTI_BI_REPORT_SERVER_PORT / _CONSOLE_PORT say otherwise; security on, a small file lake: two business days of 60 trades).

Checked: the dataset page's Refresh now button and its history (kept after a reload), the Expected values section (a test that passes and one that fails), a
relative-date slicer whose window is worked out on the server, a tablet layout (two columns, a wide visual) and the designer's Tablet tab, an entity view that
opens the Drishti view of the clicked trade in a frame, and the phone width without sideways scroll.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import os

import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18954")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17954")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import card_value, clean, open_report, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL  # noqa: E402

DATASET = "wa-demo"
REPORT = "file:report:wa-demo"
TRADES = 120                                                      # two days of 60

DATASET_YAML = """rachana: 1
dataset: wa-demo
version: 1
title: Wave A demo
sample: true
refresh: "daily at 06:00"
tables:
  trade:
    source: { connector: trading-store, kind: trade }
    mode: cached
    columns:
      tradeId: { type: string, key: true }
      desk: string
      notional: decimal
      mtm: decimal
      tradeDate: date
measures:
  Notional: { expr: "sum(trade.notional)", format: amount0 }
  MTM: { expr: "sum(trade.mtm)", format: signed0 }
  Trades: { expr: "count(trade)" }
  MTM rank: { expr: "rank(sum(trade.mtm))" }
  MTM 2-day average: { expr: "moving_avg(sum(trade.mtm), 2, trade.tradeDate)" }
tests:
  - { name: The latest day holds 60 trades, measure: Trades, date: 2026-10-08, expect: 60 }
  - { name: Not a hundred, measure: Trades, date: 2026-10-08, expect: 100, tolerance: 1% }
"""

REPORT_YAML = """rachana: 1
report: wa-demo
version: 1
title: Wave A demo
sample: true
dataset: wa-demo
dates: { from: 2026-10-07, to: 2026-10-08 }
pages:
  - id: main
    title: Main
    visuals:
      - { id: when, type: slicer, title: When, at: { x: 0, y: 0, w: 4, h: 3 }, wells: { field: [trade.tradeDate] }, format: { mode: relative-date, default: all, options: [last-1-days, last-2-days, this-month, all] } }
      - { id: count, type: card, title: Trades, at: { x: 4, y: 0, w: 4, h: 3 }, wells: { values: [Trades] } }
      - { id: mtm, type: card, title: MTM, at: { x: 8, y: 0, w: 4, h: 3 }, wells: { values: [MTM] } }
      - { id: days, type: column, title: MTM by day, at: { x: 0, y: 3, w: 8, h: 5 }, wells: { axis: [trade.tradeDate], values: [MTM, MTM 2-day average] }, alt: MTM by day }
      - { id: ids, type: bar, title: Trades by id, at: { x: 8, y: 3, w: 4, h: 5 }, wells: { axis: [trade.tradeId], values: [Notional] }, filters: [{ field: trade.tradeId, op: top, n: 5, by: Notional }], alt: Top five trades }
      - { id: one, type: entity-view, title: The trade, at: { x: 0, y: 8, w: 12, h: 6 }, wells: { key: [trade.tradeId] }, alt: The selected trade }
layouts:
  tablet:
    main:
      order: [days, count, mtm, when, ids, one]
      wide: [days, one]
      span: { days: 4 }
"""


class Stack(bi_report_stack.ReportStack):
    def prepare(self, work):
        (work / "config/bi/datasets" / f"{DATASET}.dataset.yaml").write_text(DATASET_YAML)
        (work / "config/bi/reports" / "wa-demo.report.yaml").write_text(REPORT_YAML)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = Stack(tmp_path_factory.mktemp("wavea"), delta_root="").start()
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


def opened(browser, user="rita", **ctx):
    page = sign_in(browser, user, **ctx)
    open_report(page, REPORT)
    settled(page)
    return page


def test_refresh_now_runs_in_the_background_and_its_history_survives_a_reload(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/d/file:dataset:" + DATASET)
    page.wait_for_selector("[data-refresh] tbody tr", timeout=30000)
    button = page.locator("[data-refresh-now]")
    expect(button).to_be_visible()
    button.click()
    page.wait_for_function("() => /Refreshed|Refresh /.test(document.querySelector('[data-refresh-msg]').textContent)", timeout=60000)
    assert "Refreshed" in page.locator("[data-refresh-msg]").inner_text()
    expect(page.locator('[data-refresh-history] tbody tr[data-history-row="ok"]')).to_have_count(1)
    assert "manual (wes)" in page.locator("[data-refresh-history] tbody tr").first.inner_text()
    assert "Last refresh:" in page.locator("[data-refresh-when]").inner_text()
    page.reload()
    page.wait_for_selector('[data-refresh-history] tbody tr[data-history-row="ok"]', timeout=30000)        # the server kept it
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    clean(page)
    page.context.close()


def test_a_reader_without_the_author_power_has_no_refresh_button_or_tests_section(stack, browser):
    page = sign_in(browser, "rita")
    page.goto(CONSOLE_URL + "/bi/d/file:dataset:" + DATASET)
    page.wait_for_selector("[data-refresh] tbody tr", timeout=30000)
    page.wait_for_timeout(800)
    expect(page.locator("[data-refresh-now]")).to_be_hidden()
    expect(page.locator("[data-tests-sec]")).to_be_hidden()
    page.context.close()


def test_the_dataset_page_runs_its_expected_values_and_says_which_fail(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/d/file:dataset:" + DATASET)
    expect(page.locator("[data-tests-sec]")).to_be_visible(timeout=30000)
    page.click("[data-tests-run]")
    page.wait_for_selector("[data-tests-table] tbody tr", timeout=60000)
    rows = {r.locator("th").inner_text(): r.get_attribute("data-test") for r in page.locator("[data-tests-table] tbody tr").all()}
    assert rows == {"The latest day holds 60 trades": "pass", "Not a hundred": "fail"}, (rows, page.locator("[data-tests-table]").inner_text())
    assert "got 60" in page.locator('[data-tests-table] tr[data-test="fail"]').inner_text()
    assert "1 passed, 1 failed" in page.locator("[data-tests-sum]").inner_text()
    clean(page)
    page.context.close()


def test_a_relative_date_slicer_narrows_every_visual_by_dates_worked_out_on_the_server(stack, browser):
    page = opened(browser)
    assert card_value(page, "count") == TRADES                                                       # the default is "all": no restriction
    page.select_option('[data-visual="when"] [data-relative]', "last-1-days")                        # the day the report ends on: 8 October, 60 trades
    page.wait_for_function("() => document.querySelector('[data-visual=\"count\"] .bi-card-value')?.textContent.trim() === '60'", timeout=30000)
    assert "Last 1 days" in page.locator("[data-chip=slicer]").inner_text() or "Last day" in page.locator("[data-chip=slicer]").inner_text()
    page.select_option('[data-visual="when"] [data-relative]', "last-2-days")                        # both days
    page.wait_for_function("() => document.querySelector('[data-visual=\"count\"] .bi-card-value')?.textContent.trim() === '120'", timeout=30000)
    page.select_option('[data-visual="when"] [data-relative]', "all")
    settled(page)
    assert card_value(page, "count") == TRADES
    clean(page)
    page.context.close()


def test_a_tablet_gets_two_columns_with_the_wide_visuals_across_and_a_desktop_the_grid(stack, browser):
    page = opened(browser, viewport={"width": 820, "height": 1000})
    assert page.get_attribute("[data-grid]", "data-device") == "tablet"
    grid = page.locator("[data-grid]").bounding_box()["width"]
    wide = page.locator('[data-visual="days"]').bounding_box()["width"]
    half = page.locator('[data-visual="count"]').bounding_box()["width"]
    assert wide >= grid - 2 and 0.4 * grid <= half <= 0.55 * grid, (grid, wide, half)
    order = page.evaluate("[...document.querySelectorAll('[data-grid] .bi-vis')].map(v => v.getAttribute('data-visual'))")
    assert order[:3] == ["days", "count", "mtm"], order                                              # the layout's order
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    clean(page)
    page.context.close()
    desk = opened(browser)
    assert desk.get_attribute("[data-grid]", "data-device") == "desktop"
    desk.context.close()
    phone = opened(browser, viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    assert phone.get_attribute("[data-grid]", "data-device") == "desktop"                           # a phone with only a tablet layout keeps the automatic column
    xs = phone.evaluate("[...document.querySelectorAll('.bi-vis')].map(v => Math.round(v.getBoundingClientRect().left))")
    assert len(set(xs)) == 1, xs
    assert phone.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    phone.context.close()


def test_the_designer_has_a_tablet_tab_that_writes_layouts_tablet(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/designer")
    page.wait_for_selector("[data-new-dataset] option[value='file:dataset:" + DATASET + "']", state="attached")
    page.select_option("[data-new-dataset]", "file:dataset:" + DATASET)
    page.fill("[data-new-title]", "Tablet draft")
    page.click("[data-new] button[type=submit]")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    expect(page.locator('[data-tab="tablet"]')).to_have_count(1)
    page.click('.bi-vtype[data-type="card"]')                                                         # one visual, so the tab has a row
    page.wait_for_selector(".bi-dvis")
    page.click('[data-tab="tablet"]')
    page.wait_for_selector("[data-tablet-list] li")
    page.locator("[data-tablet-wide]").first.check()
    page.click('[data-tab="yaml"]')
    page.wait_for_timeout(600)
    yaml = page.evaluate("() => { const e = document.querySelector('.CodeMirror'); return e && e.CodeMirror ? e.CodeMirror.getValue() : document.querySelector('[data-yaml]').value; }")
    assert "layouts:" in yaml and "tablet:" in yaml and "wide:" in yaml, yaml
    clean(page)
    page.context.close()


def test_an_entity_view_opens_the_drishti_view_of_the_clicked_trade_in_a_frame(stack, browser):
    page = opened(browser)
    expect(page.locator('[data-visual="one"] [data-entity="none"]')).to_have_count(1)                # nothing chosen yet: it says what to do
    first = page.evaluate("""() => { const o = echarts.getInstanceByDom(document.querySelector('[data-visual="ids"] .bi-chart')).getOption();
        return (o.yAxis[0].type === 'category' ? o.yAxis[0].data : o.xAxis[0].data)[0]; }""")
    from bi_report_helpers import click_mark
    click_mark(page, "ids", first)
    frame = page.locator('[data-visual="one"] iframe.bi-entity-frame')
    expect(frame).to_have_count(1, timeout=30000)
    assert frame.get_attribute("src") == f"/v/trade/{first}?embed=1", frame.get_attribute("src")
    assert first in page.frame_locator('[data-visual="one"] iframe').locator("body").inner_text(timeout=30000)
    clean(page)
    page.context.close()
