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

"""The map, funnel, histogram, box plot and bubble, true totals and the dataset's refresh block, in Chromium over the real server and console
(tests/bi_report_stack.py on ports 18954 and 17954, security on, a small file lake: two business days of 60 trades). A dataset and a report made for this test sit on the
shipped trades model with a country and a position worked out from the desk; every figure is computed here from the same trades. The viewer on a phone (390 px), the
exports, and the designer's catalogue are checked too.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import json
import os
import re
import statistics

import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18954")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17954")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import card_value, clean, open_report, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, trade  # noqa: E402

DATASET = "nv-demo-trade"          # the dataset the model nv-demo reads (2.2: a dataset and a model)
MODEL = "nv-demo@1"
REPORT = "file:report:nv-demo"
DAYS = ("2026-10-07", "2026-10-08")
COUNTRY = {"DESK-FX": "US", "DESK-FI": "GB", "DESK-EQD": "JP"}

DATASET_YAML = """rachana: 2
expressions: 2
dataset: nv-demo-trade
version: 1.0.0
title: "New visuals demo: trade"
sample: true
source: { connector: trading-store, kind: trade }
refresh: { schedule: "daily 06:00", on-load: true }
columns:
  tradeId:     { type: string, role: key }
  desk:        { type: string }
  book:        { type: string }
  productType: { type: string }
  currency:    { type: string }
  status:      { type: string }
  notional:    { type: decimal }
  mtm:         { type: decimal }
  tradeDate:   { type: date }
  country:     { type: string, expr: "'US' if desk == 'DESK-FX' else 'GB' if desk == 'DESK-FI' else 'JP'" }
  lat:         { type: decimal, expr: "40.7 if desk == 'DESK-FX' else 51.5 if desk == 'DESK-FI' else 35.7" }
  lon:         { type: decimal, expr: "-74.0 if desk == 'DESK-FX' else -0.1 if desk == 'DESK-FI' else 139.7" }
"""

MODEL_YAML = """rachana: 2
expressions: 2
model: nv-demo
version: 1.0.0
title: "New visuals demo"
sample: true
datasets:
  trade: { dataset: nv-demo-trade, role: fact }
measures:
  Notional:         { expr: "sum(trade.notional)", format: amount0 }
  MTM:              { expr: "sum(trade.mtm)", format: signed0 }
  Trades:           { expr: "count(trade)" }
  Avg MTM:          { expr: "avg(trade.mtm)", format: signed0 }
  MTM per notional: { expr: "ratio(sum(trade.mtm), sum(trade.notional)) * 1000000", format: signed2 }
  Largest notional: { expr: "max(trade.notional)", format: amount0 }
  MTM ytd:          { expr: "ytd(sum(trade.mtm), trade.tradeDate)", format: signed0 }
"""

REPORT_YAML = """rachana: 2
report: nv-demo
version: 1
title: New visuals demo
sample: true
model: nv-demo@1
dates: { from: 2026-10-07, to: 2026-10-08 }
pages:
  - id: main
    title: Main
    visuals:
      - { id: mtm, type: card, title: MTM, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }
      - { id: ytd, type: card, title: MTM year to date, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [MTM ytd] } }
      - { id: world, type: map, title: Notional by country, at: { x: 0, y: 2, w: 6, h: 6 }, wells: { location: [trade.country], values: [Notional] }, alt: Notional by country }
      - { id: points, type: map, title: Trades by position, at: { x: 6, y: 2, w: 6, h: 6 }, wells: { latitude: [trade.lat], longitude: [trade.lon], details: [trade.desk], values: [Trades] }, alt: Trades by position }
      - { id: steps, type: funnel, title: Trades by product, at: { x: 0, y: 8, w: 4, h: 5 }, wells: { axis: [trade.productType], values: [Trades] }, alt: Trades by product }
      - { id: spread, type: histogram, title: Spread of trade MTM, at: { x: 4, y: 8, w: 4, h: 5 }, wells: { axis: [trade.tradeId], values: [MTM] }, format: { bins: 6 }, alt: Spread of MTM }
      - { id: boxes, type: box, title: MTM by desk, at: { x: 8, y: 8, w: 4, h: 5 }, wells: { axis: [trade.desk], details: [trade.tradeId], values: [MTM] }, alt: MTM by desk }
      - { id: bubbles, type: bubble, title: Average against total MTM, at: { x: 0, y: 13, w: 6, h: 5 }, wells: { details: [trade.desk], x: [Avg MTM], y: [MTM], size: [Notional] }, alt: Desks }
      - id: matrix
        type: matrix
        title: By desk and currency
        at: { x: 0, y: 18, w: 12, h: 6 }
        wells: { rows: [trade.desk], columns: [trade.currency], values: [Notional, Avg MTM, MTM per notional, Largest notional] }
        format: { subtotals: true }
      - { id: table, type: table, title: Desks, at: { x: 6, y: 13, w: 6, h: 5 }, wells: { columns: [trade.desk, Trades, Avg MTM, Largest notional] }, format: { totals: true } }
"""


class Stack(bi_report_stack.ReportStack):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.server_args += ["--drishti.bi.refresh.tick=2s"]               # the refresh scheduler looks every 2 s here (30 s as shipped)

    def prepare(self, work):
        (work / "config/bi/datasets" / "nv-demo-trade.dataset.yaml").write_text(DATASET_YAML)
        (work / "config/bi/models").mkdir(parents=True, exist_ok=True)
        (work / "config/bi/models" / "nv-demo.model.yaml").write_text(MODEL_YAML)
        (work / "config/bi/reports" / "nv-demo.report.yaml").write_text(REPORT_YAML)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = Stack(tmp_path_factory.mktemp("nvvisuals"), delta_root="").start()
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


def rows():
    return [dict(trade(day, i), country=COUNTRY[trade(day, i)["desk"]]) for day in DAYS for i in range(60)]


OPTION = """(vis) => { const el = document.querySelector('[data-visual="' + vis + '"] .bi-chart'); if (!el) return null;
  const o = echarts.getInstanceByDom(el).getOption();
  return { series: o.series.map(s => ({ type: s.type, data: (s.data || []).map(d => d && typeof d === 'object' && !Array.isArray(d) && 'value' in d ? { name: d.name, value: d.value } : d) })),
           x: o.xAxis && o.xAxis[0] ? o.xAxis[0].data : null, geo: !!(o.geo && o.geo.length) }; }"""


def option(page, vis):
    return page.evaluate(OPTION, vis)


def opened(browser, user="rita", **ctx):
    page = sign_in(browser, user, **ctx)
    open_report(page, REPORT)
    settled(page)
    page.wait_for_function("() => ![...document.querySelectorAll('.bi-vis-empty')].some(e => /Loading the map/.test(e.textContent))", timeout=20000)
    return page


def test_the_map_shades_the_countries_and_marks_the_points(stack, browser):
    page = opened(browser)
    by_country = {}
    for t in rows():
        by_country[t["country"]] = by_country.get(t["country"], 0) + t["notional"]
    shade = option(page, "world")["series"][0]
    assert shade["type"] == "map"
    assert shade["data"]
    got = {d["name"]: d["value"] for d in shade["data"] if "value" in d}                          # (ECharts adds the other regions with no figure)
    assert got == {"USA": by_country["US"], "GBR": by_country["GB"], "JPN": by_country["JP"]}               # codes in, countries out
    assert page.evaluate("echarts.getMap('drishti-world') !== undefined")
    assert page.evaluate("[...document.querySelectorAll('[data-visual=world] canvas')].every(c => c.width > 0 && c.height > 0)")
    pts = option(page, "points")
    assert pts["geo"] and pts["series"][0]["type"] == "scatter"
    assert sorted(d["name"] for d in pts["series"][0]["data"]) == ["DESK-EQD", "DESK-FI", "DESK-FX"]
    assert {tuple(d["value"][:2]) for d in pts["series"][0]["data"]} == {(-74.0, 40.7), (-0.1, 51.5), (139.7, 35.7)}
    clean(page)                                                                                    # the map data came from our own origin: no policy violation
    page.context.close()


def test_a_country_the_map_does_not_hold_is_named_not_dropped(stack, browser):
    page = opened(browser)
    # the same visual with a code the map does not hold, drawn by the code that draws every map
    note = page.evaluate("""() => { const host = document.createElement('div'); document.body.appendChild(host); host.style.width = '400px'; host.style.height = '300px';
      const B = window.drishtiBi, spec = { id: 'x', type: 'map', title: 'x', wells: { location: ['trade.country'], values: ['Notional'] }, format: {} };
      const res = { columns: [{ name: 'trade.country' }, { name: 'Notional' }], rows: [['US', 5], ['ZZ', 7]], meta: { masked: [] } };
      const ctx = { index: window.drishtiBi.indexOf ? null : { measures: { Notional: {} }, columns: {} }, palette: ['#336', '#693'], t: { ink: '#000', muted: '#666', surface: '#fff', border: '#ccc', mono: 'monospace', link: '#33a', pos: '#3a3' }, interactive: false };
      B.visuals.drawChartOrCard(host, spec, ctx, res);
      return new Promise((resolve) => setTimeout(() => resolve(host.querySelector('.bi-map-note') ? host.querySelector('.bi-map-note').textContent : ''), 800)); }""")
    assert "Not on the map: ZZ" in note
    page.context.close()


def test_funnel_histogram_box_and_bubble_draw_what_the_data_says(stack, browser):
    page = opened(browser)
    data = rows()
    by_product = {}
    for t in data:
        by_product[t["productType"]] = by_product.get(t["productType"], 0) + 1
    funnel = option(page, "steps")["series"][0]
    assert funnel["type"] == "funnel" and {d["name"]: d["value"] for d in funnel["data"]} == by_product
    mtm = [t["mtm"] for t in data]
    hist = option(page, "spread")
    counts = hist["series"][0]["data"]
    assert len(counts) == 6 and sum(counts) == len(mtm) == 120
    lo, hi = min(mtm), max(mtm)
    width = (hi - lo) / 6
    edges = [lo + k * width for k in range(6)]                                                     # the same edges the labels show: a value on an edge is in the bar that starts there
    assert counts == [sum(1 for v in mtm if max(k for k in range(6) if v >= edges[k]) == b) for b in range(6)]
    box = option(page, "boxes")["series"][0]
    assert box["type"] == "boxplot"
    names = option(page, "boxes")["x"]
    assert names == ["DESK-EQD", "DESK-FI", "DESK-FX"]
    for name, b in zip(names, box["data"]):
        v = sorted(t["mtm"] for t in data if t["desk"] == name)
        assert b["value"][2] == statistics.median(v)                                                   # the median is the median
        assert b["value"][0] == min(v) and b["value"][4] == max(v)
    bubbles = option(page, "bubbles")["series"][0]
    assert bubbles["type"] == "scatter" and len(bubbles["data"]) == 3
    clean(page)
    page.context.close()


def test_totals_of_measures_that_do_not_add_up_are_the_engines(stack, browser):
    page = opened(browser)
    data = rows()
    overall_avg = sum(t["mtm"] for t in data) / len(data)
    ratio = sum(t["mtm"] for t in data) / sum(t["notional"] for t in data) * 1_000_000
    largest = max(t["notional"] for t in data)
    grand = page.locator('[data-visual="matrix"] tr[data-grandtotal]')
    expect(grand).to_have_count(1)
    cells = grand.locator("td[data-true-total]")
    assert cells.count() >= 3                                                                      # average, ratio and largest are the engine's
    texts = {m: grand.locator(f'td[data-measure="{m}"]').last.inner_text() for m in ("Avg MTM", "MTM per notional", "Largest notional", "Notional")}
    assert texts["Largest notional"].replace(",", "") == f"{largest:.0f}"                          # a maximum is not a sum
    assert texts["Notional"].replace(",", "") == f"{sum(t['notional'] for t in data):.0f}"        # a sum is
    assert abs(float(texts["MTM per notional"].replace(",", "").replace("+", "").replace("\u2212", "-")) - ratio) < 0.01
    assert abs(float(texts["Avg MTM"].replace(",", "").replace("+", "").replace("\u2212", "-")) - overall_avg) < 1
    assert "–" not in grand.inner_text()
    foot = page.locator('[data-visual="table"] tfoot tr[data-total-row]')
    expect(foot).to_have_count(1)
    assert foot.locator('td[data-measure="Largest notional"]').inner_text().replace(",", "") == f"{largest:.0f}"
    assert foot.locator('td[data-measure="Trades"]').inner_text().replace(",", "") == str(len(data))
    clean(page)
    page.context.close()


def test_the_year_to_date_card_is_the_time_intelligence_measure(stack, browser):
    page = opened(browser)
    assert card_value(page, "ytd") == card_value(page, "mtm") == sum(t["mtm"] for t in rows())      # every trade is dated this year, up to the as-of date
    page.context.close()


def test_the_dataset_page_shows_the_refresh_schedule_and_the_state(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/d/file:dataset:" + DATASET)                                       # the old link opens the dataset's page (2.2)
    page.wait_for_url("**/bi/ds/**")
    page.wait_for_selector("[data-refresh] tbody tr", timeout=30000)
    row = page.locator('[data-refresh] tr[data-refresh-row^="table"]')                            # a model-layer dataset has one table: the schedule is on it
    expect(row).to_have_count(1)
    assert "daily 06:00" in row.inner_text()                                                       # "daily at 06:00" is read as daily 06:00
    assert row.get_attribute("data-status")
    assert re.search(r"\d", row.locator("td").nth(3).inner_text()) or "–" in row.locator("td").nth(3).inner_text()
    assert "New York" in page.locator("[data-refresh-note]").inner_text() or "business days" in page.locator("[data-refresh-note]").inner_text()
    clean(page)
    page.context.close()


def test_the_designer_offers_the_new_visuals_and_marks_the_time_measure(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/designer")
    page.wait_for_selector("[data-new-dataset] option[value='mq:" + MODEL + "']", state="attached")
    page.select_option("[data-new-dataset]", "mq:" + MODEL)
    page.fill("[data-new-title]", "New visuals draft")
    page.click("[data-new] button[type=submit]")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    for t in ("map", "funnel", "histogram", "box", "bubble"):
        expect(page.locator(f'.bi-vtype[data-type="{t}"]')).to_have_count(1)
    badge = page.locator('[data-field="MTM ytd"] [data-time]')
    expect(badge).to_have_count(1)
    assert badge.inner_text() == "ytd"
    page.click('.bi-vtype[data-type="map"]')                                                       # a new visual of that type, with its wells
    page.wait_for_selector('[data-well="location"]')
    for w in ("location", "latitude", "longitude", "values"):
        expect(page.locator(f'[data-well="{w}"]')).to_have_count(1)
    clean(page)
    page.context.close()


def test_the_new_visuals_export_as_excel_pdf_png_and_csv(stack, browser):
    page = sign_in(browser, "rita")
    base = CONSOLE_URL + "/dist/bi/" + REPORT
    xlsx = page.request.get(base + ".xlsx?page=main")
    assert xlsx.status == 200 and xlsx.body()[:2] == b"PK"
    assert page.request.get(base + ".pdf?page=main").body()[:4] == b"%PDF"
    assert page.request.get(base + ".png?page=main").body()[:4] == b"\x89PNG"
    csv = page.request.get(base + ".csv?page=main")
    text = csv.text()
    assert csv.status == 200
    assert "bin,count,from,to" in text.replace("\r", "") and "median" in text and "outliers" in text      # the histogram's bins and the box plot's boxes are the figures exported
    assert "Not on the map" not in text and "US" in text and "Notional by country" in text
    page.context.close()


def test_a_phone_reads_the_new_visuals_in_one_column_without_sideways_scroll(stack, browser):
    page = opened(browser, "rita", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), page.evaluate("document.documentElement.scrollWidth")
    xs = page.evaluate("[...document.querySelectorAll('.bi-vis')].map(v => Math.round(v.getBoundingClientRect().left))")
    assert len(set(xs)) == 1, xs
    for vis in ("world", "points", "steps", "spread", "boxes", "bubbles"):
        box = page.locator(f'[data-visual="{vis}"]').bounding_box()
        assert 150 <= box["height"] <= 400 and box["width"] <= 390, (vis, box)
    for sel in ("[data-reset]:not([hidden])", "[data-pages] a", '[data-visual="matrix"] .bi-twist', '[data-visual="table"] .bi-sorthead'):
        for el in page.locator(sel).all():
            if el.is_visible():
                assert el.bounding_box()["height"] >= 43, (sel, el.bounding_box())
    clean(page)
    page.context.close()
    ds = sign_in(browser, "wes", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    ds.goto(CONSOLE_URL + "/bi/ds/file:dataset:" + DATASET)
    ds.wait_for_selector("[data-refresh] tbody tr", timeout=30000)
    assert ds.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")            # the refresh table scrolls inside its own box
    ds.context.close()
