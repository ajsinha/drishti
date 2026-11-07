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
"""The Elements gallery in a real browser (static/gallery/drishti_gallery.html; ELEMENTS.md section 10, mock-up g4): no Drishti server,
the static files served under a strict CSP, the per-worker browser only. Each gallery example is a test:

* the playground (mode A, the default): every component of every library in the manifest drawn with no page error and a clean
  keyboard/ARIA smoke check, its code tabs written from the object it was given, its status line, its event log; options from the JSDoc
  change the component and its code together; the grid in both themes and three densities; the AG Grid tab (planned) with its mapping;
  the search; the address keeps the state;
* the documentation page (mode B): every example of every component runs, "Open in playground", the API generated from the JSDoc, the
  accessibility contract with the keys, the changelog;
* the keys: Tab into the grid and the arrows move its focus; the chart's marks walked by the arrows; the code tabs one Tab stop.

DRISHTI_GALLERY_SHOTS names a folder for screenshots (none are taken otherwise)."""
import json
import os
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.spread

STATIC = Path(__file__).resolve().parents[1] / "web" / "static"
ORIGIN = "http://render.test"
PAGE = "/static/gallery/drishti_gallery.html"
HEADERS = {"content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:"}
TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "application/javascript", ".json": "application/json"}
SHOTS = os.environ.get("DRISHTI_GALLERY_SHOTS")
READY = "() => window.__gallery && document.querySelector('.gx-stage') && !document.querySelector('.dc-loading:not([hidden])')"


def router(fetched):
    async def route(r):
        path = r.request.url[len(ORIGIN):].split("?")[0].split("#")[0]
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


async def frames(pg, n=2):
    await pg.evaluate("(n) => new Promise((r) => { const f = (k) => (k ? requestAnimationFrame(() => f(k - 1)) : r()); f(n); })", n)


async def audited(pg, cid):
    """The smoke check of the component on the stage (the gallery runs it once the component is ready)."""
    await pg.wait_for_function("(c) => window.__gallery.audits[c] && !(document.querySelector('.gx-status') || {textContent: ''}).textContent.includes('checking')",
                               arg=cid, timeout=15000)
    return await pg.evaluate("(c) => { const a = window.__gallery.audits[c]; delete window.__gallery.audits[c]; return a; }", cid)


async def shot(pg, name, full=False):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        await pg.screenshot(path=str(Path(SHOTS) / name), full_page=full)


def run(browsers, body, address="", width=1500, height=1000, fetched=None):
    fetched = fetched if fetched is not None else []

    async def go():
        async with browsers.page(width, height, route=router(fetched)) as pg:
            await pg.goto(ORIGIN + PAGE + address)
            await pg.wait_for_function(READY, timeout=30000)
            assert await pg.evaluate("window.__gallery.loadProblems") == []
            await body(pg)
            assert pg.errors == [], pg.errors
            assert await pg.evaluate("window.__csp") == []            # nothing inline: the strict CSP holds
            assert not [f for f in fetched if f.endswith(" 404")], [f for f in fetched if f.endswith(" 404")]
    browsers.run(go(), timeout=150)


def test_the_playground_draws_every_component_with_clean_aria_and_its_code(browsers):
    async def body(pg):
        assert await pg.get_attribute(".gx", "data-mode") == "playground"                  # A is the default
        demos = await pg.evaluate("() => window.__gallery.demos.map((d) => ({ id: d.id, library: d.library, kind: d.kind, ag: !!d.ag }))")
        libs = await pg.evaluate("() => window.__gallery.cat.libraries.map((l) => l.name)")
        assert {d["library"] for d in demos} == set(libs)                                   # every library of the manifest has its pages
        assert len(demos) >= 20
        nav = await pg.eval_on_selector_all(".gx-nav-item[data-c]", "els => els.map((e) => e.dataset.c)")
        assert [d["id"] for d in demos] == [n for n in nav if n in {d["id"] for d in demos}]
        await shot(pg, "drishti-gallery-playground.png")
        for d in demos:
            if await pg.get_attribute(f".gx-nav-item[data-c='{d['id']}']", "aria-current") != "page":
                await pg.click(f".gx-nav-item[data-c='{d['id']}']")
            assert await pg.get_attribute(f".gx-nav-item[data-c='{d['id']}']", "aria-current") == "page"
            a = await audited(pg, d["id"])
            assert a["problems"] == [], (d["id"], a["problems"])
            assert await pg.locator(".gx-error").count() == 0, d["id"]
            status = await pg.text_content(".gx-status")
            assert re.search(r"bundle [\d.]+ KB, [\d,]+ lines", status) and "tests: " in status and "no problems" in status, (d["id"], status)
            tabs = await pg.eval_on_selector_all(".gx-codebox [role=tab]", "els => els.map((e) => e.textContent)")
            assert tabs[:3] == ["Web component", "JavaScript", "Sutra"], d["id"]
            assert ("AG Grid adapter (planned)" in tabs) == d["ag"], d["id"]
            await pg.click(".gx-codebox [data-tab=js]")
            js = await pg.text_content("#gx-code-panel")
            assert "import {" in js or "Drishti.elements" in js, (d["id"], js[:200])
            tag = {"drishti-grid": "drishti-grid", "drishti-chart": "drishti-chart", "drishti-risk": "drishti-chart"}.get(d["library"])
            if tag and d["kind"] == "element":
                assert await pg.locator(".gx-stage " + tag).count() >= 1, d["id"]
            await pg.click(".gx-codebox [data-tab=element]")
    run(browsers, body)


def test_options_from_the_jsdoc_change_the_component_and_its_code_and_the_address_keeps_them(browsers):
    async def body(pg):
        names = await pg.eval_on_selector_all(".gx-options [data-opt]", "els => els.map((e) => e.dataset.opt)")
        for n in ("rows", "layout", "density", "title", "pinTotals", "checkboxes", "phoneWidth", "clientAggregation", "rowSelection"):
            assert n in names, n
        assert await pg.locator(".gx-options .gx-opt-code").count() >= 5                   # objects and functions: set in code
        await pg.select_option("[data-opt=layout]", "terminal")
        await pg.wait_for_selector(".gx-stage drishti-grid[layout=terminal].dg")
        assert 'layout="terminal"' in await pg.text_content("#gx-code-panel")
        assert "layout" in await pg.evaluate("decodeURIComponent(location.hash)")
        log = await pg.text_content(".gx-evlog")
        assert "option" in log and "terminal" in log
        for density, row in (("comfortable", 36), ("compact", 28), ("terminal", 22)):
            await pg.select_option("[data-opt=density]", density)
            await pg.wait_for_selector(f".gx-stage drishti-grid[density={density}].dg")
            assert await pg.evaluate("() => document.querySelector('.gx-stage drishti-grid').grid.model.viewport.rowHeight") == row
            assert (await audited(pg, "grid"))["problems"] == []
        await pg.click("[data-action=theme]")                                               # light, then the same checks
        await frames(pg)
        assert await pg.evaluate("document.documentElement.getAttribute('data-theme')") == "light"
        await pg.select_option("[data-opt=layout]", "panel")
        assert (await audited(pg, "grid"))["problems"] == []
        await shot(pg, "drishti-gallery-grid-light.png")
        await pg.click("[data-opt=checkboxes]")                                             # a switch: off
        await pg.wait_for_function("() => !document.querySelector('.gx-stage drishti-grid').grid.checkboxes()")
        assert "checkboxes: false" in await pg.text_content("#gx-code-panel")
        await pg.fill("[data-opt=title]", "My trades")
        await pg.press("[data-opt=title]", "Tab")
        await pg.wait_for_function("() => document.querySelector('.gx-stage drishti-grid').textContent.includes('My trades')")
        # the address restores it
        await pg.reload()
        await pg.wait_for_function(READY)
        assert await pg.get_attribute(".gx-stage drishti-grid", "layout") == "panel"
        assert await pg.input_value("[data-opt=title]") == "My trades"
        # events: a cell clicked is in the log
        await pg.click(".gx-stage [role=gridcell] >> nth=3")
        await pg.wait_for_function("() => document.querySelector('.gx-evlog').textContent.includes('cellClicked')")
        # the search filters the list
        await pg.fill(".gx-search", "pivot")
        items = await pg.eval_on_selector_all(".gx-nav-item[data-c]", "els => els.map((e) => e.dataset.c)")
        assert "pivot" in items and "chart" not in items
        await pg.fill(".gx-search", "zzz-nothing")
        assert "Nothing matches" in await pg.text_content(".gx-nav-list")
    run(browsers, body, "#c=grid")


def test_the_pivot_ag_grid_tab_sutra_and_live_feed(browsers):
    async def body(pg):
        await pg.click("[data-tab=ag]")
        ag = await pg.text_content("#gx-code-panel")
        assert "pivotMode: true" in ag and "rowGroup: true" in ag and "aggFunc: 'drishti:server'" in ag and "planned" in ag
        rows = await pg.locator(".gx-ag tbody tr").count()
        assert rows == await pg.evaluate("window.__gallery.cat.agGrid.mapping.length") and rows >= 15
        assert "Planned" in await pg.text_content(".gx-ag summary")
        await shot(pg, "drishti-gallery-pivot-ag.png")
        await pg.click("[data-tab=sutra]")
        assert "kind: pivot" in await pg.text_content("#gx-code-panel")
        await pg.click("[data-tab=ag]")
        await pg.keyboard.press("ArrowLeft")                                                # the tabs: one Tab stop, arrows inside
        assert await pg.evaluate("document.activeElement.dataset.tab") == "sutra"
        # live: a manual feed's ticks flash and log
        await pg.click(".gx-nav-item[data-c=live]")
        await audited(pg, "live")
        await pg.click(".gx-stage [data-action=tick]")
        await pg.wait_for_function("() => document.querySelector('.gx-evlog').textContent.includes('feed.push')")
        await pg.wait_for_function("() => document.querySelector('.gx-evlog').textContent.includes('rowDataUpdated')")
        # editing through the API: a focused editable cell set
        await pg.click(".gx-nav-item[data-c=editing]")
        await audited(pg, "editing")
        col = await pg.evaluate("() => document.querySelector('.gx-stage drishti-grid').grid.model.shownColumns().findIndex((c) => c.field === 'MTM')")
        await pg.evaluate("(c) => { const g = document.querySelector('.gx-stage drishti-grid').grid; g.focusCell(0, c); }", col)
        await pg.click(".gx-stage [data-action=set]")
        await pg.wait_for_function("() => document.querySelector('.gx-evlog').textContent.includes('cellValueChanged')")
        # export: a real file handed to the download hook
        await pg.click(".gx-nav-item[data-c=export]")
        await audited(pg, "export")
        await pg.click(".gx-stage [data-action=export-xlsx]")
        await pg.wait_for_function("() => window.__gallery.downloads.some((d) => /\\.xlsx$/.test(d.name) && d.size > 1000)")
    run(browsers, body, "#c=pivot")


def test_the_keys_reach_the_grid_and_the_chart(browsers):
    async def body(pg):
        await pg.focus(".gx-stage [role=grid], .gx-stage [role=treegrid]")
        await pg.keyboard.press("ArrowDown")
        await pg.keyboard.press("ArrowRight")
        f = await pg.evaluate("() => document.querySelector('.gx-stage drishti-grid').grid.model.navigator.focus")
        assert f and f["row"] >= 0 and f["col"] >= 1
        await pg.click(".gx-nav-item[data-c=chart]")
        await audited(pg, "chart")
        await pg.focus(".gx-stage .dc-canvas")
        await pg.keyboard.press("ArrowRight")
        await pg.keyboard.press("t")
        await pg.wait_for_selector(".gx-stage .dc-table:not([hidden])")
        await pg.select_option("[data-opt=sample]", "heatmap")
        await pg.wait_for_function("() => document.querySelector('.gx-stage drishti-chart').chart.spec.kind === 'heatmap'")
        assert "kind: 'heatmap'" in await pg.text_content("#gx-code-panel")
        await pg.click(".gx-nav-item[data-c=map]")
        await audited(pg, "map")
        await pg.wait_for_function("() => document.querySelector('.gx-stage drishti-chart').textContent.includes('United States')")
        await shot(pg, "drishti-gallery-map.png")
    run(browsers, body, "#c=grid")


def test_the_documentation_pages_run_every_example(browsers):
    async def body(pg):
        assert await pg.get_attribute(".gx", "data-mode") == "docs"
        demos = await pg.evaluate("() => window.__gallery.demos.map((d) => ({ id: d.id, n: (d.examples || [1]).length }))")
        total = 0
        for d in demos:
            if await pg.get_attribute(f".gx-nav-item[data-c='{d['id']}']", "aria-current") != "page":
                await pg.click(f".gx-nav-item[data-c='{d['id']}']")
            await pg.wait_for_selector(".gx-example")
            assert await pg.locator(".gx-example").count() == d["n"], d["id"]
            assert await pg.locator(".gx-example .gx-stage").count() == d["n"]
            assert await pg.locator(".gx-error").count() == 0, d["id"]
            total += d["n"]
        assert total >= 35
        await pg.click(".gx-nav-item[data-c=pivot]")
        await shot(pg, "drishti-gallery-docs.png")
        # API: the options tables are the catalogue's typedefs, and the events
        await pg.click("[role=tab][data-tab=api]")
        captions = await pg.eval_on_selector_all(".gx-api caption", "els => els.map((e) => e.textContent)")
        assert any(c.startswith("GridViewOptions") for c in captions) and any(c.startswith("GridOptions") for c in captions)
        want = await pg.evaluate("() => window.__gallery.cat.libraries.find((l) => l.name === 'drishti-grid').types.find((t) => t.name === 'GridViewOptions').props.length")
        assert await pg.locator(".gx-api:has(caption:text-matches('^GridViewOptions')) tbody tr").count() == want
        assert "drishti:cell-clicked" in await pg.text_content(".gx-docpanel")
        assert "class Grid" in await pg.text_content(".gx-docpanel")
        # accessibility: the keys from the module, the function keys, the live check
        await pg.click("[role=tab][data-tab=a11y]")
        assert "Ctrl+Home" in await pg.text_content(".gx-keys")
        assert await pg.locator(".gx-docpanel kbd:text('F2')").count() == 1
        await pg.wait_for_function("() => /No problems in \\d+ elements/.test(document.querySelector('.gx-audit').textContent)")
        # changelog: the library's lines of the product CHANGELOG
        await pg.click("[role=tab][data-tab=changelog]")
        assert "0.2.0" in await pg.text_content(".gx-changelog")
        # Open in playground takes the example's options
        await pg.click("[role=tab][data-tab=examples]")
        await pg.click(".gx-example:nth-of-type(2) [data-action=open-playground]")
        await pg.wait_for_selector(".gx[data-mode=playground] .gx-stage drishti-grid[layout=terminal]")
        h = json.loads(await pg.evaluate("() => JSON.stringify(Object.fromEntries(new URLSearchParams(location.hash.slice(1))))"))
        assert h["mode"] == "playground" and h["c"] == "pivot" and "terminal" in h["o"]
        # a library that is not built yet has its page
        planned = await pg.evaluate("() => window.__gallery.cat.planned.map((p) => p.name)")
        if planned:
            await pg.click(f".gx-nav-item[data-c='{planned[0]}']")
            assert "Not in this build" in await pg.text_content(".gx-main")
    run(browsers, body, "#mode=docs&c=pivot")
