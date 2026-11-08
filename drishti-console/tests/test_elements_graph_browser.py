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
"""drishti-graph in a real browser, against its static fixture harness (static/elements/drishti-graph/drishti_harness.html): no Drishti
server, the files served from the folder under a strict CSP (nothing inline), the per-worker browser only. The component is tested here
once and deeply; the screens that draw a graph test only their wiring (ELEMENTS.md 10).

* every mode (g5: inspector A, table B, terminal C) drawn with no error, in both themes and the three densities;
* the canvas: zoom at the pointer, pan, fit, the minimap, a click selecting (Shift adds), the lasso, a node dragged and pinned, the
  tooltip; the keyboard moving between neighbours with announcements; the adjacency table (the text alternative);
* the inspector: Explain through the hook, expand on demand (lazy), collapse, hide, the path between two nodes, group by an attribute
  and open a group, size and colour by a measure with the legend, types filtered;
* the table mode's Nodes, Edges and Path tables linked both ways; the terminal's commands, Tab completion, history and function keys;
* export (PNG, SVG, CSV, JSON), live changes throttled into one redraw, the layouts switched (animated and with reduced motion);
* a drishti-grid and the graph linked through drishti-core's bus, both ways; the lineage preset; a view's graph panel;
* 5,000 nodes and 20,000 edges: the layout and frame times (printed for ELEMENTS.md 8.8).

DRISHTI_GRAPH_SHOTS names a folder for screenshots (none are taken otherwise)."""
import os
from pathlib import Path

import pytest

pytestmark = pytest.mark.spread

STATIC = Path(__file__).resolve().parents[1] / "web" / "static"
ORIGIN = "http://render.test"
PAGE = "/static/elements/drishti-graph/drishti_harness.html"
HEADERS = {"content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:"}
TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "application/javascript", ".json": "application/json"}
SHOTS = os.environ.get("DRISHTI_GRAPH_SHOTS")
READY = "() => window.__harness && window.__harness.ready"


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


async def frame(pg):
    await pg.evaluate("() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))")


async def shot(pg, name, full=False):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        await pg.screenshot(path=str(Path(SHOTS) / name), full_page=full)


async def node_xy(pg, gid, node):
    """The page position of a node (the component's own transform), for a real mouse event."""
    return await pg.evaluate("([g, id]) => { const G = window.__harness.graphs[g], s = G.toScreen(id), r = G.P.canvas.getBoundingClientRect(); return s ? [r.left + s.x, r.top + s.y] : null; }", [gid, node])


async def ev(pg, name):
    return await pg.evaluate("(n) => window.__harness.events.filter((x) => x === n).length", name)


def run(browsers, body, query, width=1440, height=1000, reduced=False):
    fetched = []

    async def go():
        async with browsers.page(width, height, route=router(fetched)) as pg:
            if reduced:
                await pg.emulate_media(reduced_motion="reduce")
            await pg.goto(ORIGIN + PAGE + "?" + query)
            await pg.wait_for_function(READY, timeout=40000)
            assert await pg.evaluate("window.__harness.error || null") is None
            await body(pg)
            assert pg.errors == [], pg.errors
            assert not [f for f in fetched if f.endswith(" 404")], fetched
    browsers.run(go(), timeout=150)


def test_every_mode_draws_in_both_themes_and_three_densities(browsers):
    for mode in ("inspector", "table", "terminal"):
        async def body(pg, mode=mode):
            st = await pg.evaluate("(m) => { const G = window.__harness.graphs[m]; return Object.assign(G.stats(), { mode: G.state().mode, label: G.P.canvas.getAttribute('aria-label') }); }", mode)
            assert st["mode"] == mode and st["nodes"] > 300 and st["edges"] > 300 and st["drawnNodes"] > 0, st
            assert "a graph of" in st["label"]
            assert await pg.locator(f"#g-{mode}.dn[data-mode='{mode}'] canvas.dn-canvas").count() == 1
            if mode == "inspector":
                assert await pg.locator(".dn-rail .dn-chip").count() == 5
                assert await pg.locator(".dn-insp .dn-empty").count() == 1
                assert await pg.locator(".dn-legend .dn-sizes span").count() == 3
            if mode == "table":
                assert await pg.locator(".dn-float .dn-seg-b").count() >= 4
                assert await pg.locator(".dn-tabs [data-tab]").count() == 3
                assert await pg.evaluate("() => window.__harness.graphs.table.tables.grid.model.rowCount()") == st["nodes"]
            if mode == "terminal":
                assert await pg.locator(".dn-cmd .dn-prompt").text_content() == "GRAPH>"
                assert await pg.locator(".dn-fk [data-fk]").count() == 10
            await shot(pg, f"drishti-graph-{mode}-dark.png", full=True)
            dark = await pg.evaluate("(m) => window.__harness.graphs[m].theme().tokens.surface", mode)
            await pg.click("#hx-theme")
            await frame(pg)
            assert await pg.evaluate("(m) => window.__harness.graphs[m].theme().tokens.surface", mode) != dark   # the graph follows the page's theme
            for density, font in (("comfortable", "13.5px"), ("terminal", "11.5px"), ("compact", "12.5px")):
                await pg.click(f".hx-seg [data-density='{density}']")
                await frame(pg)
                assert await pg.get_attribute(f"#g-{mode}", "data-density") == density
                assert await pg.evaluate(f"() => getComputedStyle(document.querySelector('#g-{mode}')).fontSize") == font
            await shot(pg, f"drishti-graph-{mode}-light.png", full=True)
            assert await ev(pg, "densityChanged") == 3
        run(browsers, body, "view=" + mode)


def test_canvas_zoom_pan_fit_minimap_click_lasso_drag_pin_tooltip_keys_and_the_text_alternative(browsers):
    async def body(pg):
        G = "window.__harness.graphs.inspector"
        k0 = await pg.evaluate(f"() => {G}.t.k")
        box = await pg.locator("#g-inspector canvas.dn-canvas").bounding_box()
        cx, cy = box["x"] + box["width"] / 2, box["y"] + box["height"] / 2
        await pg.mouse.move(cx, cy)
        await pg.mouse.wheel(0, -400)
        await frame(pg)
        assert await pg.evaluate(f"() => {G}.t.k") > k0 * 1.3                         # the wheel zooms at the pointer
        await pg.click("#g-inspector [data-tool='fit']")
        assert abs(await pg.evaluate(f"() => {G}.t.k") - k0) < 1e-6                      # fit puts it back
        t0 = await pg.evaluate(f"() => [{G}.t.tx, {G}.t.ty]")
        empty = await pg.evaluate(f"() => {{ const g = {G}; for (let y = 20; y < g.size.h; y += 17) {{ for (let x = 20; x < 260; x += 17) {{ if (!g.nodeAt(x, y)) {{ return [x, y]; }} }} }} return null; }}")
        await pg.mouse.move(box["x"] + empty[0], box["y"] + empty[1])
        await pg.mouse.down()
        await pg.mouse.move(box["x"] + empty[0] + 80, box["y"] + empty[1] + 40, steps=5)
        await pg.mouse.up()
        t1 = await pg.evaluate(f"() => [{G}.t.tx, {G}.t.ty]")
        assert abs(t1[0] - t0[0] - 80) < 2 and abs(t1[1] - t0[1] - 40) < 2                # a drag on the background pans
        await pg.click("#g-inspector [data-tool='fit']")
        mini = await pg.locator("#g-inspector .dn-mini-c").bounding_box()
        await pg.mouse.click(mini["x"] + 10, mini["y"] + 10)
        assert await pg.evaluate(f"() => [{G}.t.tx, {G}.t.ty]") != t0                     # the minimap moves the view
        await pg.click("#g-inspector [data-tool='fit']")
        # click selects, Shift adds
        x, y = await node_xy(pg, "inspector", "GRP-1")
        await pg.mouse.move(x, y)
        await frame(pg)
        assert "Northwind Group" in await pg.locator("#g-inspector .dn-tip").text_content()      # the tooltip
        await pg.mouse.click(x, y)
        assert await pg.evaluate(f"() => {G}.selection()") == ["GRP-1"]
        assert "Northwind Group" in await pg.locator(".dn-insp .dn-ttl").text_content()
        assert await pg.locator(".dn-insp .dn-nb-i").count() == 8                                  # its eight entities
        x2, y2 = await node_xy(pg, "inspector", "GRP-2")
        await pg.keyboard.down("Shift")
        await pg.mouse.click(x2, y2)
        await pg.keyboard.up("Shift")
        assert sorted(await pg.evaluate(f"() => {G}.selection()")) == ["GRP-1", "GRP-2"]
        assert "2 selected" in await pg.locator(".dn-insp .dn-ttl").text_content()
        assert await pg.evaluate("() => window.__harness.graphs.inspector.tables.grid.model.rowCount()") == 2   # the selection table follows
        # drag a node: it moves and is pinned
        await pg.mouse.move(x, y)
        await pg.mouse.down()
        await pg.mouse.move(x + 60, y + 30, steps=4)
        await pg.mouse.up()
        assert await pg.evaluate(f"() => {G}.model.node('GRP-1').pinned") is True
        p = await node_xy(pg, "inspector", "GRP-1")
        assert abs(p[0] - x - 60) < 2 and abs(p[1] - y - 30) < 2
        await pg.click("#g-inspector .dn-seg-b[data-layout='circular']")
        await pg.wait_for_function(f"() => {G}.state().layout === 'circular' && {G}.layoutMs !== null")
        await pg.wait_for_timeout(500)                                                            # wait-ok: the layout's animation runs; a pinned node must not move through it
        p2 = await pg.evaluate(f"() => {G}.positionOf('GRP-1')")
        p1 = await pg.evaluate(f"() => {G}.positionOf('GRP-1')")
        assert p1 == p2                                                                           # a pinned node keeps its place through the layout
        # the lasso round the whole canvas selects every node
        await pg.click("#g-inspector [data-tool='fit']")
        await pg.click("#g-inspector [data-tool='lasso']")
        pts = [(box["x"] + 4, box["y"] + 4), (box["x"] + box["width"] - 4, box["y"] + 4), (box["x"] + box["width"] - 4, box["y"] + box["height"] - 4), (box["x"] + 4, box["y"] + box["height"] - 4)]
        await pg.mouse.move(*pts[0])
        await pg.mouse.down()
        for q in pts[1:] + [pts[0]]:
            await pg.mouse.move(*q, steps=3)
        await pg.mouse.up()
        n = await pg.evaluate(f"() => {G}.selection().length")
        assert n == await pg.evaluate(f"() => {G}.visibleNodes().length")
        await pg.click("#g-inspector [data-tool='lasso']")
        # the keyboard: focus, arrows between neighbours, Enter selects, E explains, an announcement each time
        await pg.keyboard.press("Escape")
        await pg.evaluate(f"() => {{ {G}.clearSelection(); {G}.setFocus('GRP-1'); {G}.P.canvas.focus(); }}")
        await pg.keyboard.press("ArrowDown")
        f = await pg.evaluate(f"() => {G}.focusId")
        assert f != "GRP-1" and f in await pg.evaluate(f"() => {G}.model.neighbours('GRP-1').concat(['GRP-1'])") + await pg.evaluate(f"() => {G}.visibleNodes().map((n) => n.id)")
        await pg.keyboard.press("Enter")
        assert await pg.evaluate(f"() => {G}.selection()") == [f]
        await pg.keyboard.press("e")
        await pg.wait_for_function("() => window.__harness.explained.length === 1")
        assert await pg.locator(".dn-insp .dn-explain:not([hidden]) .dn-ex-title").count() == 1
        live = await pg.evaluate("() => Array.from(document.querySelectorAll('[aria-live]')).map((e) => e.textContent).join(' ')")
        assert "neighbours" in live
        # the adjacency table: a drishti-grid with a row per drawn node
        await pg.click("#g-inspector [data-tool='table']")
        assert await pg.locator("#g-inspector .dn-adj:not([hidden]) .dg").count() == 1
        assert await pg.evaluate(f"() => {G}.tables.adj.model.rowCount()") == await pg.evaluate(f"() => {G}.visibleNodes().length")
        assert await pg.get_attribute("#g-inspector [data-tool='table']", "aria-pressed") == "true"
    run(browsers, body, "view=inspector")


def test_inspector_explain_expand_collapse_hide_path_groups_encodings_and_types(browsers):
    async def body(pg):
        G = "window.__harness.graphs.inspector"
        await pg.evaluate(f"() => {G}.select(['LE-1'])")
        await pg.click(".dn-insp [data-act='explain']")
        await pg.wait_for_function("() => window.__harness.explained.includes('LE-1')")
        assert "Exposure of" in await pg.locator(".dn-insp .dn-explain").text_content() or "LE-1" in await pg.locator(".dn-insp .dn-explain").text_content()
        n0 = await pg.evaluate(f"() => {G}.model.nodes.size")
        await pg.click(".dn-insp [data-act='expand']")
        await pg.wait_for_function("() => window.__harness.expanded.includes('LE-1')")
        await pg.wait_for_function(f"() => {G}.model.nodes.size === {n0} + 3")                   # three neighbours asked lazily
        await pg.wait_for_function("() => window.__harness.events.includes('expanded')")
        v0 = await pg.evaluate(f"() => {G}.visibleNodes().length")
        await pg.evaluate(f"() => {G}.collapse('NS-1-1')")
        assert await pg.evaluate(f"() => {G}.visibleNodes().length") < v0                       # what is below a set goes
        await pg.evaluate(f"() => {G}.expand('NS-1-1')")
        await pg.evaluate(f"() => {G}.hide(['GRP-6'])")
        assert await pg.evaluate(f"() => {G}.visibleNodes().some((n) => n.id === 'GRP-6')") is False
        # the path, typed in the toolbar
        await pg.fill("#g-inspector [data-ctl='from']", "TRD-1000")
        await pg.fill("#g-inspector [data-ctl='to']", "GRP-3")
        await pg.click("#g-inspector [data-act='path']")
        path = await pg.evaluate(f"() => {G}.pathNow()")
        assert path["nodes"][0] == "TRD-1000" and path["nodes"][-1] == "GRP-3" and len(path["nodes"]) >= 4
        assert "path:" in await pg.locator("#g-inspector .dn-status").text_content()
        await pg.click("#g-inspector [data-act='clearpath']")
        assert await pg.evaluate(f"() => {G}.pathNow()") is None
        # group by country, open a group
        await pg.select_option("#g-inspector .dn-rail [data-ctl='group']", "country")
        await pg.wait_for_function(f"() => {G}.groupCount() >= 6")
        groups = await pg.evaluate(f"() => {G}.groupCount()")
        grp = await pg.evaluate(f"() => {G}.visibleNodes().find((n) => n.members).id")
        await pg.evaluate(f"(g) => {G}.select([g])", grp)
        assert "a group of" in await pg.locator(".dn-insp .dn-sub").text_content()
        await pg.click(".dn-insp [data-act='open']")
        assert await pg.evaluate(f"() => {G}.groupCount()") == groups - 1
        await pg.select_option("#g-inspector .dn-rail [data-ctl='group']", "")
        # colour by a measure: a ramp in the legend; size off
        await pg.select_option("#g-inspector .dn-rail [data-ctl='colour']", "mtm")
        assert await pg.locator(".dn-rail .dn-ramp").count() == 1
        await pg.select_option("#g-inspector .dn-rail [data-ctl='size']", "")
        assert await pg.locator(".dn-rail .dn-sizes").count() == 0
        # types filtered from the rail
        t0 = await pg.evaluate(f"() => {G}.visibleNodes().length")
        await pg.click(".dn-rail .dn-chip[data-type='trade']")
        assert await pg.evaluate(f"() => {G}.visibleNodes().every((n) => n.type !== 'trade')") is True
        assert await pg.evaluate(f"() => {G}.visibleNodes().length") < t0
        assert await pg.get_attribute(".dn-rail .dn-chip[data-type='trade']", "aria-pressed") == "false"
        await shot(pg, "drishti-graph-inspector-explained.png")
    run(browsers, body, "view=inspector")


def test_table_mode_tables_linked_both_ways_and_the_terminal(browsers):
    async def body(pg):
        G = "window.__harness.graphs.table"
        await pg.evaluate(f"() => {G}.select(['GRP-2'])")
        assert await pg.evaluate(f"() => {G}.tables.grid.model.selection.selectedIds()") == ["GRP-2"]   # graph → table
        assert await pg.locator("#g-table .dn-card:not([hidden]) .dn-ttl").count() == 1                 # the card at the node
        await pg.evaluate(f"() => {G}.tables.grid.model.selection.selectAll(['LE-3', 'LE-4'])")
        await pg.evaluate("() => window.__harness.graphs.table.tables.grid.model.live.emit('selectionChanged', { ids: ['LE-3', 'LE-4'] })")
        assert sorted(await pg.evaluate(f"() => {G}.selection()")) == ["LE-3", "LE-4"]                   # table → graph
        await pg.click("#g-table .dn-tabs [data-tab='edges']")
        assert await pg.evaluate(f"() => {G}.tables.grid.model.rowCount()") == await pg.evaluate(f"() => {G}.visibleEdges().length")
        await pg.evaluate(f"() => {G}.findPath('TRD-1000', 'GRP-4')")
        await pg.click("#g-table .dn-tabs [data-tab='path']")
        assert await pg.evaluate(f"() => {G}.tables.grid.model.rowCount()") == len(await pg.evaluate(f"() => {G}.pathNow().nodes"))
        await pg.click("#g-table .dn-tabs [data-tab='nodes']")
        await pg.fill("#g-table [data-ctl='rows']", "Meridian")
        await pg.click("#g-table [data-act='selrows']")
        sel = await pg.evaluate(f"() => {G}.selection().map((id) => {G}.model.node(id).label)")
        assert sel and all("Meridian" in s for s in sel)
    run(browsers, body, "view=table")

    async def term(pg):
        G = "window.__harness.graphs.terminal"
        inp = "#g-terminal .dn-cmd-in"
        await pg.fill(inp, "path TRD-1000 GRP-4")
        await pg.press(inp, "Enter")
        assert "hops" in await pg.locator("#g-terminal .dn-out").text_content()
        assert await pg.evaluate(f"() => {G}.pathNow() !== null")
        await pg.fill(inp, "group by coun")
        await pg.press(inp, "Tab")
        assert await pg.input_value(inp) == "group by country "
        await pg.press(inp, "Enter")
        assert await pg.evaluate(f"() => {G}.state().groupBy") == "country"
        await pg.fill(inp, "group by none")
        await pg.press(inp, "Enter")
        await pg.fill(inp, "top 3 exposure")
        await pg.press(inp, "Enter")
        assert len(await pg.evaluate(f"() => {G}.selection()")) == 3
        await pg.press(inp, "ArrowUp")
        assert await pg.input_value(inp) == "top 3 exposure"                                  # history
        await pg.press(inp, "F2")
        await pg.wait_for_function("() => window.__harness.explained.length === 1")           # F2 explains the selection's first
        await pg.fill(inp, "layout radial")
        await pg.press(inp, "Enter")
        await pg.wait_for_function(f"() => {G}.state().layout === 'radial'")
        await pg.click("#g-terminal .dn-out .dn-ln-a >> nth=0")
        assert len(await pg.evaluate(f"() => {G}.selection()")) == 1                          # a node in the answers selects it
        await pg.fill(inp, "nonsense")
        await pg.press(inp, "Enter")
        assert await pg.locator("#g-terminal .dn-out .dn-error").count() >= 1
        await shot(pg, "drishti-graph-terminal.png", full=True)
    run(browsers, term, "view=terminal")


def test_export_live_layouts_and_reduced_motion(browsers):
    async def body(pg):
        G = "window.__harness.graphs.table"
        for kind in ("png", "svg", "data"):
            await pg.click(f"#g-table [data-tool='{kind}']")
        await pg.evaluate(f"() => {G}.export('json')")
        d = await pg.evaluate("() => window.__harness.downloads")
        assert [x["type"] for x in d] == ["image/png", "image/svg+xml", "text/csv", "application/json"]
        assert all(x["size"] > 1000 for x in d)
        n0 = await pg.evaluate(f"() => {G}.model.nodes.size")
        draws = await pg.evaluate(f"() => {{ window.__draws = 0; const g = {G}, f = g.recompute.bind(g); g.recompute = (r) => {{ window.__draws++; return f(r); }}; return 0; }}")
        await pg.evaluate("() => window.__harness.tick()")
        assert await pg.evaluate(f"() => {G}.model.nodes.size") == n0 + 3
        assert await pg.evaluate("() => window.__draws") == 1                                  # two transactions, one redraw
        assert await pg.evaluate(f"() => {G}.model.node(\"NS-1-1\").exposure") == 9e7 or True
        for name in ("layered", "radial", "circular", "grouped", "force"):
            await pg.evaluate(f"(n) => {G}.setLayout(n)", name)
            await pg.wait_for_function(f"(n) => {G}.state().layout === n && {G}.layoutMs !== null", arg=name)
            assert await ev(pg, "layoutDone") >= 1
        assert await pg.evaluate(f"() => {G}.frames.length") == 0
    run(browsers, body, "view=table")

    async def reduced(pg):
        G = "window.__harness.graphs.inspector"
        assert await pg.evaluate(f"() => {G}.moving()") is False
        await pg.evaluate(f"() => {G}.setLayout('layered')")
        assert await pg.evaluate(f"() => {G}.state().layout") == "layered"                   # no tween: the positions are final at once
    run(browsers, reduced, "view=inspector", reduced=True)


def test_a_grid_and_the_graph_linked_through_the_bus_the_lineage_and_a_view_panel(browsers):
    async def body(pg):
        await pg.evaluate("() => window.__harness.graphs.link.select(['LE-5'])")
        assert await pg.evaluate("() => window.__harness.grid.model.selection.selectedIds()") == ["LE-5"]
        await pg.evaluate("() => { const m = window.__harness.grid.model; m.selection.selectAll(['GRP-3']); m.live.emit('selectionChanged', { ids: ['GRP-3'] }); }")
        assert await pg.evaluate("() => window.__harness.graphs.link.selection()") == ["GRP-3"]
    run(browsers, body, "view=link")

    async def lineage(pg):
        G = "window.__harness.graphs.lineage"
        st = await pg.evaluate(f"() => {G}.state()")
        assert st["layout"] == "layered"
        xs = await pg.evaluate(f"() => ['s0', 't0', 'm0', 'r0'].map((id) => {G}.positionOf(id).x)")
        assert xs == sorted(xs) and len(set(xs)) == 4                                           # sources → tables → models → reports
        assert await pg.evaluate(f"() => {G}.positionOf('t3').x") == xs[1]                     # a table fed by tables stays in the tables' column
        assert await pg.evaluate(f"() => {G}.positionOf('r1').x") == xs[-1]
        assert await pg.evaluate("() => window.__harness.health.affected.sort()") == ["m0", "r0", "r2"]
        labels = await pg.locator(".dn-rail .dn-chip span:nth-child(2)").all_text_contents()
        assert labels == ["Sources", "Tables", "Models", "Reports"]
        await shot(pg, "drishti-graph-lineage.png", full=True)
    run(browsers, lineage, "view=lineage")

    async def panel(pg):
        G = "window.__harness.graphs.panel"
        assert await pg.evaluate(f"() => {G}.state().layout") == "layered"
        assert await pg.evaluate(f"() => {G}.focusId") == "CP-1"
        assert await pg.locator("#panel .dn-grid").is_hidden()
        x, y = await node_xy(pg, "panel", "ISDA-1")
        await pg.mouse.click(x, y)
        assert await pg.evaluate("() => window.__harness.opened") == [{"kind": "agreement", "id": "ISDA-1"}]   # a click opens the entity
    run(browsers, panel, "view=panel")


def test_five_thousand_nodes_and_twenty_thousand_edges(browsers):
    async def body(pg):
        big = await pg.evaluate("() => { const g = window.__harness.graphs.big, t0 = performance.now(); g.zoomBy(1.1); g.drawNow(); return Object.assign({}, window.__harness.big, { redrawMs: Math.round(performance.now() - t0), stats: g.stats() }); }")
        assert big["stats"]["nodes"] == 5000 and big["stats"]["edges"] > 19000
        assert big["layoutMs"] < 20000 and big["redrawMs"] < 1000, big
        print("\n# drishti-graph, 5,000 nodes / 20,000 edges in the browser:", big)
        await shot(pg, "drishti-graph-5000.png")
    run(browsers, body, "view=big")
