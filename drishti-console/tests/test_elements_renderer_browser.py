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

"""drishti-renderer 2.0.0 in a real browser against its static fixture harness (static/elements/drishti-renderer/drishti_harness.html):
no Drishti server, the files served from the static folder under a strict CSP, the per-worker browser only. Fast: the components it
draws with (drishti-grid's document table and tree grid, drishti-chart's panel charts) are tested in their own harnesses; this file tests
the renderer's wiring of them. The parity with the console's macros is test_render_parity_browser.py.

* every kind of the showcase drawn, every chart an engine instance made by drishti-chart, every table drishti-grid's with its sort;
* a streamed ViewModel ends equal to the one-shot render;
* behaviours 'console': the nested pivot drawn by drishti-grid's tree grid (it opens and closes), the console's column funnels on top;
* a theme change redraws the charts; a phone's width never scrolls sideways."""
import pytest

pytestmark = pytest.mark.spread

from test_elements_grid_browser import ORIGIN, route  # noqa: E402 - the same static origin and strict CSP

PAGE = "/static/elements/drishti-renderer/drishti_harness.html"
ROOT = "document.getElementById('host').shadowRoot"
READY = "() => window.__rx && window.__rx.ready === true"


async def opened(pg, query=""):
    await pg.goto(ORIGIN + PAGE + ("?" + query if query else ""))
    await pg.wait_for_function(READY, timeout=30000)


def run(browsers, body, width=1360, height=900):
    async def go():
        async with browsers.page(width, height, route=route) as pg:
            await body(pg)
            assert pg.errors == [], pg.errors
            assert await pg.evaluate("window.__csp") == []
            assert await pg.evaluate("window.__rx.events.error || 0") == 0
    browsers.run(go(), timeout=120)


SNAPSHOT = f"""() => {{
  const v = {ROOT}.querySelector('.view');
  return {{ text: v.innerText, cells: [...v.querySelectorAll('td, th')].map((c) => c.textContent.trim()),
    links: [...v.querySelectorAll('a[href]')].map((a) => a.getAttribute('href')), tones: [...v.querySelectorAll('[class*="t-"]')].length }};
}}"""


def test_the_showcase_draws_every_kind_with_the_libraries(browsers):
    async def body(pg):
        await opened(pg, "case=showcase")
        got = await pg.evaluate(f"""() => {{
          const r = {ROOT}, E = window.echarts;
          const charts = [...r.querySelectorAll('[data-r-chart]')];
          return {{ kinds: [...new Set([...r.querySelectorAll('.pnl')].map((p) => p.getAttribute('data-kind')))].length,
            unknown: r.querySelectorAll('[data-r-unknown]').length, charts: charts.length, drawn: charts.filter((c) => E && E.getInstanceByDom(c)).length,
            tables: r.querySelectorAll('table.tbl').length, sorts: r.querySelectorAll('.tbl-sort').length, version: window.__rx.version }};
        }}""")
        assert got["version"] == "2.0.0" and got["unknown"] == 0 and got["kinds"] >= 20
        assert got["charts"] >= 8 and got["drawn"] == got["charts"], got
        assert got["tables"] > 5 and got["sorts"] > 0
        first = await pg.evaluate(f"() => {ROOT}.querySelector('table.tbl:not([data-plain]) .tbl-sort').closest('table').id || ''")
        await pg.locator("#host .tbl-sort").first.click()
        assert await pg.evaluate(f"() => {ROOT}.querySelector('th[aria-sort=\"ascending\"]') !== null"), first
    run(browsers, body)


def test_a_streamed_view_model_ends_equal_to_the_one_shot_render(browsers):
    async def body(pg):
        await opened(pg, "case=more-shapes")
        one = await pg.evaluate(SNAPSHOT)
        await opened(pg, "case=more-shapes&stream=1")
        streamed = await pg.evaluate(SNAPSHOT)
        assert streamed == one
        assert await pg.evaluate(f"() => {ROOT}.querySelector('.view').hasAttribute('aria-busy')") is False
        assert await pg.evaluate("window.__rx.events.ready") == 1
    run(browsers, body)


def test_console_behaviours_draw_the_nested_pivot_with_drishti_grid(browsers):
    async def body(pg):
        await opened(pg, "case=showcase&behaviours=console")
        await pg.wait_for_function(f"() => !!{ROOT}.querySelector('.pv-tree-host [role=treegrid] [aria-expanded]')", timeout=15000)
        before = await pg.evaluate(f"() => {ROOT}.querySelectorAll('.pv-tree-host .dg-row').length")
        await pg.locator("#host .pv-tree-host [aria-expanded='true']").first.click()
        await pg.wait_for_function(f"(n) => {ROOT}.querySelectorAll('.pv-tree-host .dg-row').length < n", arg=before, timeout=5000)
        await pg.wait_for_function(f"() => {ROOT}.querySelectorAll('th .cf-btn').length > 0", timeout=15000)     # the console's funnels
        assert await pg.evaluate(f"() => {ROOT}.querySelectorAll('.tbl-sort').length") == 0                       # the funnels sort, not the grid
    run(browsers, body)


def test_a_theme_change_redraws_and_a_phone_never_scrolls_sideways(browsers):
    async def body(pg):
        await opened(pg, "case=grid-areas")
        await pg.select_option("#rx-theme", "crimson-dark")
        await pg.wait_for_function(READY)
        assert await pg.get_attribute("#host", "data-theme") == "crimson-dark"
        await pg.set_viewport_size({"width": 390, "height": 844})
        await opened(pg, "case=showcase")
        assert await pg.evaluate("document.scrollingElement.scrollWidth - window.innerWidth") <= 1
    run(browsers, body)
