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

"""drishti-chart (with its maps) and drishti-risk's kinds in a real browser, against the chart's static fixture harness
(static/elements/drishti-chart/drishti_harness.html): no Drishti server, the files served from the folder under a strict CSP (nothing
inline), the per-worker browser only. The component is tested here once and deeply; screens test only their wiring (ELEMENTS.md 10).

* the board (g2 A): a card of every kind, general and risk, drawn with no error, in both themes and the three densities;
* the card's behaviour: crosshair tooltips, legend toggles, drag-to-zoom with the overview window and its reset, the keyboard, a click
  on a mark explaining it and outlining its rows, the data table, export (PNG, SVG, CSV, the spec), live updates by id;
* the studio (g2 B): the kind rail with the risk kinds, the spec beside the chart, options changing both;
* the map (g3): the world in ranking mode (A) with its quantile legend, counts and top ten, a drill into the United States (Alaska and
  Hawaii insets) and Canada by double-click, the breadcrumb back, the metric picker, another country's regions loaded once on demand,
  and the table mode (B) with its sortable table, floating legend and inset;
* a drishti-grid and a chart linked through drishti-core's bus, both ways.

DRISHTI_CHART_SHOTS names a folder for screenshots (none are taken otherwise)."""
import os
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.spread

STATIC = Path(__file__).resolve().parents[1] / "web" / "static"
ORIGIN = "http://render.test"
PAGE = "/static/elements/drishti-chart/drishti_harness.html"
HEADERS = {"content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:"}
TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "application/javascript", ".json": "application/json"}
SHOTS = os.environ.get("DRISHTI_CHART_SHOTS")


def router(fetched):
    async def route(r):
        path = r.request.url[len(ORIGIN):].split("?")[0]
        f = STATIC / path[len("/static/"):] if path.startswith("/static/") else None
        if f and f.is_file():
            fetched.append(path)
            headers = {"content-type": TYPES.get(f.suffix, "application/octet-stream")}
            if f.suffix == ".html":
                headers.update(HEADERS)
            return await r.fulfill(status=200, body=f.read_bytes(), headers=headers)
        fetched.append(path + " 404")
        return await r.fulfill(status=404, body="")
    return route


READY = "() => window.__harness && window.__harness.ready && !document.querySelector('.dc-loading:not([hidden])')"


async def ev(pg, name):
    return await pg.evaluate("(n) => window.__harness.events.filter((x) => x === n).length", name)


async def frame(pg):
    await pg.evaluate("() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))")


async def at(pg, chart, finder, value):
    """The page position of a value on a chart (the engine's own conversion), for a real mouse event."""
    return await pg.evaluate("([id, f, v]) => { const c = window.__harness.charts[id]; c.canvas.scrollIntoView({ block: 'center' }); const p = c.engine.convertToPixel(f, v), r = c.canvas.getBoundingClientRect(); return [r.left + p[0], r.top + p[1]]; }",
                             [chart, finder, value])


async def shot(pg, name, full=False):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        await pg.screenshot(path=str(Path(SHOTS) / name), full_page=full)


def run(browsers, body, query, width=1440, height=950, fetched=None):
    fetched = fetched if fetched is not None else []

    async def go():
        async with browsers.page(width, height, route=router(fetched)) as pg:
            await pg.goto(ORIGIN + PAGE + "?" + query)
            await pg.wait_for_function(READY, timeout=30000)
            assert await pg.evaluate("window.__harness.error || null") is None
            await body(pg)
            assert pg.errors == [], pg.errors
            assert await pg.evaluate("window.__csp") == []            # nothing inline: the strict CSP holds
    browsers.run(go(), timeout=120)


def test_the_board_draws_every_kind_in_both_themes_and_three_densities(browsers):
    async def body(pg):
        kinds = await pg.evaluate("() => Object.keys(window.__harness.charts).map((k) => window.__harness.charts[k].spec.kind)")
        for k in ("line", "area", "bar", "scatter", "bubble", "pie", "donut", "heatmap", "waterfall", "histogram", "boxplot", "gauge", "treemap", "sankey",
                  "candlestick", "layer", "map", "risk.pnl-distribution", "risk.backtest", "risk.exposure-profile", "risk.var-change"):
            assert k in kinds, k
        n = len(kinds)
        assert await pg.locator("drishti-chart .dc-canvas:has(canvas)").count() == n
        assert await pg.locator(".dc-error").count() == 0
        labels = await pg.eval_on_selector_all(".dc-canvas", "els => els.map((e) => e.getAttribute('aria-label'))")
        assert all(labels) and len(set(labels)) == n                                  # every chart described for a screen reader
        assert re.search(r"(green|amber|red) · \d+ exceptions · plus \d\.\d\d", await pg.text_content("#c-risk-backtest .dc-badge"))
        assert "computed here" in await pg.text_content("#c-risk-pnl-distribution .dc-badge")
        assert "PFE over the limit" in await pg.text_content("#c-risk-exposure-profile .dc-badge")
        assert await pg.text_content("#c-vega-bar .dc-status b") == "Vega-Lite, translated"
        assert await pg.locator(".dc-kind.dc-kind-risk").count() == 4
        await shot(pg, "drishti-chart-board-dark.png", full=True)
        for density, font in (("comfortable", "13.5px"), ("terminal", "11.5px"), ("compact", "12.5px")):
            await pg.click(f".hx-seg [data-density='{density}']")
            await frame(pg)
            assert await pg.get_attribute("#c-line", "data-density") == density
            assert await pg.evaluate("() => getComputedStyle(document.querySelector('#c-line')).fontSize") == font
            assert await pg.evaluate(f"() => window.__harness.charts.line.envNow.theme.density") == density
        dark = await pg.evaluate("() => window.__harness.charts.bar.envNow.theme.tokens.surface")
        await pg.click("#hx-theme")
        await frame(pg)
        assert await pg.evaluate("() => window.__harness.charts.bar.envNow.theme.tokens.surface") != dark   # the charts follow the page's theme
        assert await pg.evaluate("() => window.__harness.charts.bar.envNow.theme.dark") is False
        await shot(pg, "drishti-chart-board-light.png", full=True)
        assert await ev(pg, "densityChanged") == 3 * n
    run(browsers, body, "view=board")


def test_tooltips_legend_zoom_keys_explain_selection_table_export_and_live(browsers):
    async def body(pg):
        # the crosshair tooltip, an element (no markup strings)
        x, y = await at(pg, "bar", {"xAxisIndex": 0, "yAxisIndex": 0}, ["Rates", 4e6])
        await pg.mouse.move(x, y)
        await pg.wait_for_selector(".dc-tip .dc-tip-r")
        assert "Rates" in await pg.text_content(".dc-tip-h") and "9.82m" in await pg.text_content(".dc-tip")
        # legend toggles
        await pg.click("#c-bar .dc-lg[data-name='2026-11-05']")
        assert await pg.get_attribute("#c-bar .dc-lg[data-name='2026-11-05']", "aria-pressed") == "false"
        assert await pg.evaluate("() => window.__harness.charts.bar.engine.getOption().legend[0].selected['2026-11-05']") is False
        await pg.click("#c-bar .dc-lg[data-name='2026-11-05']")
        assert await ev(pg, "legendToggled") == 2
        # drag-to-zoom inside the overview window, then the reset
        z0 = await pg.evaluate("() => window.__harness.charts.line.engine.getOption().dataZoom[1]")
        assert z0["startValue"] < z0["endValue"]
        await pg.evaluate("() => document.querySelector('#c-line .dc-canvas').scrollIntoView({ block: 'center' })")
        box = await pg.locator("#c-line .dc-canvas").bounding_box()
        await pg.mouse.move(box["x"] + box["width"] * 0.35, box["y"] + 60)
        await pg.mouse.down()
        await pg.mouse.move(box["x"] + box["width"] * 0.55, box["y"] + 120, steps=6)
        await pg.mouse.up()
        await pg.wait_for_function("() => !document.querySelector('#c-line [data-tool=\"zoom-reset\"]').hidden")
        assert await ev(pg, "zoomChanged") >= 1
        await pg.click("#c-line [data-tool='zoom-reset']")
        # the keyboard: arrows walk the marks, Enter explains
        await pg.focus("#c-bar .dc-canvas")
        for _ in range(3):
            await pg.keyboard.press("ArrowRight")
        await pg.wait_for_function("() => (document.querySelector('[data-drishti-live]') || {}).textContent.indexOf('FX') >= 0")
        await pg.keyboard.press("Enter")
        await pg.wait_for_selector("#c-bar .dc-explain:not([hidden]) .dc-ex-value")
        await pg.keyboard.press("Escape")
        # a click on a mark: Explain (the hook's answer, the grid's drawing) and its rows selected (outlined)
        x, y = await at(pg, "waterfall", {"xAxisIndex": 0, "yAxisIndex": 0}, ["Rates", 4.0e6])
        await pg.mouse.click(x, y)
        await pg.wait_for_selector("#c-waterfall .dc-explain:not([hidden]) .dc-ex-steps")
        assert "Rates" in await pg.text_content("#c-waterfall .dc-ex-title")
        assert await pg.evaluate("() => window.__harness.charts.waterfall.selection()") == ["2"]
        assert await pg.evaluate("() => window.__harness.charts.waterfall.engine.getOption().series.some((s) => s.name === '__selected')")
        await pg.click("#c-waterfall .dc-ex-close")
        assert await pg.locator("#c-waterfall .dc-explain").is_hidden()
        await pg.click("#c-donut [data-tool='explain']")
        await pg.wait_for_selector("#c-donut .dc-explain:not([hidden]) .dc-ex-value")
        assert await ev(pg, "explainShown") == 3
        # the data table, the accessible alternative
        await pg.click("#c-heatmap [data-tool='table']")
        assert await pg.locator("#c-heatmap .dc-dtable tbody tr").count() == 6
        assert await pg.locator("#c-heatmap .dc-dtable thead th").count() == 13
        assert await pg.locator("#c-heatmap .dc-canvas").is_hidden()
        assert (await pg.text_content("#c-heatmap .dc-dtable caption")).startswith("Heatmap")
        await pg.keyboard.press("Escape")
        await pg.click("#c-heatmap [data-tool='table']")
        assert await pg.locator("#c-heatmap .dc-canvas").is_visible()
        # export: PNG at 2x, SVG drawn off screen, CSV of the data, the spec
        for what in ("png", "svg", "csv"):
            await pg.click("#c-line [data-tool='export']")
            await pg.click(f"#c-line .dc-menu [data-export='{what}']")
        names = await pg.evaluate("() => window.__harness.downloads.map((d) => d.name)")
        assert names == ["daily-p-l-against-var.png", "daily-p-l-against-var.svg", "daily-p-l-against-var.csv"]
        assert (await pg.evaluate("() => window.__harness.charts.line.export('svg')")).startswith("<svg")
        assert (await pg.evaluate("() => window.__harness.charts.line.export('png')")).startswith("data:image/png")
        csv = await pg.evaluate("() => window.__harness.charts.line.export('csv')")
        assert csv.count("\r\n") == 251 and "Hypothetical P&L" in csv
        spec = await pg.evaluate("() => JSON.parse(window.__harness.charts.line.export('spec'))")
        assert spec["kind"] == "line" and len(spec["data"]["rows"]) == 250
        assert await ev(pg, "exported") == 7
        # live: a new day appended, a desk's VaR updated by id, drawn once per throttle window
        before = await pg.evaluate("() => window.__harness.charts.line.rows.length")
        got = await pg.evaluate("() => window.__harness.tick()")
        assert got["line"]["added"] == 1 and got["bar"]["updated"] == 1
        await pg.wait_for_function(f"() => window.__harness.charts.line.built.table.rows.length === {before + 1}")
        assert await ev(pg, "dataUpdated") == 2
        await shot(pg, "drishti-chart-explain.png")
    run(browsers, body, "view=board")


def test_the_studio_rail_spec_and_options(browsers):
    async def body(pg):
        rail = await pg.eval_on_selector_all(".dc-rail-k", "els => els.map((e) => e.dataset.kind)")
        assert "risk.backtest" in rail and "map" in rail and "sankey" in rail and len(rail) >= 21
        assert await pg.text_content(".dc-rail-k.dc-on") == "P&L distribution"
        await shot(pg, "drishti-chart-studio.png")
        await pg.click(".dc-rail-k[data-kind='bar']")
        await pg.wait_for_function("() => document.querySelector('.dc-spec').textContent.indexOf('\"kind\": \"bar\"') >= 0")
        assert await pg.locator(".dc-studio-table tbody tr").count() == 6
        await pg.click(".dc-tab[data-tab='options']")
        await pg.select_option("[data-opt='stack']", "percent")
        await pg.wait_for_function("() => document.querySelector('.dc-spec').textContent.indexOf('\"percent\"') >= 0")
        assert await pg.evaluate("() => window.__harness.charts.studio.engine.getOption().yAxis[0].max") == 100
        await pg.select_option("[data-opt='orient']", "horizontal")
        assert await pg.evaluate("() => window.__harness.charts.studio.engine.getOption().yAxis[0].type") == "category"
        await pg.select_option("[data-opt='density']", "terminal")
        assert await pg.get_attribute("#c-studio", "data-density") == "terminal"
        await pg.click(".dc-rail-k[data-kind='risk.exposure-profile']")
        await pg.select_option("[data-opt='log']", "off")
        assert await pg.evaluate("() => window.__harness.charts.studio.engine.getOption().xAxis[0].type") == "value"
        await pg.click(".dc-rail-k[data-kind='map']")
        await pg.wait_for_function("() => window.__harness.charts.studio.map && window.__harness.charts.studio.map.geo")
        assert await ev(pg, "kindChanged") == 6                                          # bar, stack, bars, exposure, log x, map
    run(browsers, body, "view=studio")


def test_the_world_map_ranking_drill_into_the_states_and_canada_and_back(browsers):
    fetched = []

    async def body(pg):
        assert await pg.text_content(".dc-crumb") == "World"
        classes = await pg.eval_on_selector_all(".dc-lgq[data-class] .dc-n", "els => els.map((e) => Number(e.textContent))")
        nodata = int(await pg.text_content(".dc-nodata-row .dc-n"))
        places = await pg.evaluate("() => window.__harness.charts.map.map.built.places.length")
        assert len(classes) == 5 and sum(classes) + nodata == places                      # the counts add up
        assert await pg.locator(".dc-rank-row").count() == 10
        assert "United States" in await pg.text_content(".dc-rank-row >> nth=0")
        await pg.click(".dc-lgq[data-class='4']")
        assert await pg.get_attribute(".dc-lgq[data-class='4']", "aria-pressed") == "true"
        await pg.click(".dc-lgq[data-class='4']")
        await shot(pg, "drishti-chart-map-world.png")
        # the selected place: Explain and the drill
        await pg.click(".dc-rank-row[data-pick='USA']")
        await pg.wait_for_selector(".dc-detail[data-place='USA']")
        await pg.click(".dc-detail [data-act='explain']")
        await pg.wait_for_selector(".dc-explain:not([hidden]) .dc-ex-value")
        await pg.click(".dc-ex-close")
        await pg.click(".dc-detail [data-drill='USA']")
        await pg.wait_for_function("() => window.__harness.charts.map.map.geo.level === 'USA'")
        assert "United States of America: 51 regions loaded" in await pg.text_content("#c-map .dc-toast")
        assert await pg.text_content(".dc-crumb") == "World › United States of America"
        assert "regions/USA.json" in await pg.text_content(".dc-status")
        assert await pg.evaluate("() => window.__harness.charts.map.map.geo.projection") == "albers-usa"
        assert await pg.evaluate("() => window.__harness.charts.map.engine.getOption().geo[0].regions.filter((r) => r.name.indexOf('__frame_') === 0).length") == 2
        await shot(pg, "drishti-chart-map-usa.png")
        # the metric picker
        await pg.select_option(".dc-metric", index=2)
        assert (await pg.get_attribute("#c-map .dc-canvas", "aria-label")).startswith("Map of Counterparties by region")
        assert await ev(pg, "metricChanged") == 1
        # back, then Canada by a double-click on the map
        await pg.click(".dc-crumb [data-go='world']")
        await pg.wait_for_function("() => window.__harness.charts.map.map.geo.level === 'world'")
        xy = await pg.evaluate("() => window.Drishti.elements.chart.equalEarth(-105, 58)")
        x, y = await at(pg, "map", {"geoIndex": 0}, xy)
        await pg.mouse.dblclick(x, y)
        await pg.wait_for_function("() => window.__harness.charts.map.map.geo.level === 'CAN'")
        assert await pg.evaluate("() => window.__harness.charts.map.map.geo.list.length") == 13
        await shot(pg, "drishti-chart-map-canada.png")
        # another country, loaded on demand once
        await pg.evaluate("() => window.__harness.charts.map.map.drill('JPN')")
        await pg.evaluate("() => window.__harness.charts.map.map.back()")
        await pg.evaluate("() => window.__harness.charts.map.map.drill('JPN')")
        assert await pg.evaluate("() => window.__harness.charts.map.map.geo.level") == "JPN"
        assert await ev(pg, "levelChanged") == 7
    run(browsers, body, "view=map", fetched=fetched)
    assert fetched.count("/static/vendor/echarts/maps/regions/JPN.json") == 1
    assert fetched.count("/static/vendor/echarts/maps/world-countries.json") == 1


def test_the_map_table_mode(browsers):
    async def body(pg):
        assert await pg.get_attribute("#c-map", "data-map-mode") == "table"
        assert await pg.locator(".dc-side").is_hidden()
        assert await pg.locator(".dc-float-legend").count() == 1
        assert await pg.text_content(".dc-crumb") == "World › France"
        assert await pg.locator(".dc-inset[data-go='world']").count() == 1
        rows = await pg.locator(".dc-sortable tbody tr").count()
        assert rows == await pg.evaluate("() => window.__harness.charts.map.map.geo.list.length")
        await pg.click(".dc-sortable [data-sort='c']")
        codes = await pg.eval_on_selector_all(".dc-sortable tbody tr td:first-child", "els => els.map((e) => e.textContent)")
        assert codes == sorted(codes, reverse=True)
        await pg.click(".dc-sortable [data-sort='c']")
        assert await pg.get_attribute(".dc-sortable [data-sort='c'] >> xpath=..", "aria-sort") == "ascending"
        await pg.click(".dc-sortable tbody tr >> nth=0")
        await pg.wait_for_selector(".dc-float-card")
        await shot(pg, "drishti-chart-map-table.png")
        await pg.click(".dc-inset")
        await pg.wait_for_function("() => window.__harness.charts.map.map.geo.level === 'world'")
        await pg.evaluate("() => window.__harness.charts.map.setMapMode('ranking')")
        assert await pg.locator(".dc-side").is_visible()
    run(browsers, body, "view=map&mapMode=table&level=FRA")


def test_a_grid_and_a_chart_linked_both_ways(browsers):
    async def body(pg):
        await pg.wait_for_selector("drishti-grid .dg-row")
        await pg.click(".dg-row .dg-cell >> nth=0")
        await pg.wait_for_function("() => window.__harness.charts.link.selection().length === 1")
        first = await pg.evaluate("() => window.__harness.grid.model.selection.selectedIds()")
        assert await pg.evaluate("() => window.__harness.charts.link.selection()") == first
        assert await pg.evaluate("() => window.__harness.charts.link.engine.getOption().series.find((s) => s.name === '__selected').data.length") == 1
        t = await pg.evaluate("() => window.__harness.data.trades.find((r) => r.ID === 'T110')")
        x, y = await at(pg, "link", {"xAxisIndex": 0, "yAxisIndex": 0}, [t["VAR"], t["MTM"]])
        await pg.mouse.click(x, y)
        await pg.wait_for_function("() => window.__harness.grid.model.selection.selectedIds().join() === 'T110'")
        assert await pg.locator(".dg-row[aria-selected='true']").count() == 1
        assert await ev(pg, "selectionChanged") >= 2
        await shot(pg, "drishti-chart-linked.png")
    run(browsers, body, "view=link", width=1500)
