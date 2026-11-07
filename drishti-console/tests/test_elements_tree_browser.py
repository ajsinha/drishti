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
"""drishti-tree in a real browser, against its static fixture harness (static/elements/drishti-tree/drishti_harness.html): no Drishti
server, the files served from the folder under a strict CSP (nothing inline), the per-worker browser only. The component is tested
here once and deeply; screens test only their wiring (ELEMENTS.md 10).

* the tree grid (g6 B, the default): drishti-grid's treegrid with a column per measure, ◆ on the non-additive ones, every parent's
  figure the stand-in server's (never the children's sum, which shows beside it as Σ children), the limit-use bar; open and close
  following the model; search with its breadcrumbs; a click selecting and sharing the selection on the bus; Explain in the side panel;
  the three densities and both themes; CSV and JSON exports;
* the org chart (A): boxes as treeitems with levels, the keyboard walking and opening them, a box dragged onto another in the edit mode
  making one proposal (a move under its own descendant refused), undo, discard and apply; SVG and PNG exports; the lineage's DAG with
  its dashed second parent;
* the sunburst and the icicle (C): sized by an additive measure, the text alternative's table, a zoom into a node.

DRISHTI_TREE_SHOTS names a folder for screenshots (none are taken otherwise)."""
import os
from pathlib import Path

import pytest

pytestmark = pytest.mark.spread

STATIC = Path(__file__).resolve().parents[1] / "web" / "static"
ORIGIN = "http://render.test"
PAGE = "/static/elements/drishti-tree/drishti_harness.html"
HEADERS = {"content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:"}
TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "application/javascript", ".json": "application/json"}
SHOTS = os.environ.get("DRISHTI_TREE_SHOTS")
READY = "() => window.__harness && window.__harness.ready"


def router():
    async def route(r):
        path = r.request.url[len(ORIGIN):].split("?")[0]
        f = STATIC / path[len("/static/"):] if path.startswith("/static/") else None
        if f and f.is_file():
            headers = {"content-type": TYPES.get(f.suffix, "application/octet-stream")}
            if f.suffix == ".html":
                headers.update(HEADERS)
            return await r.fulfill(status=200, body=f.read_bytes(), headers=headers)
        return await r.fulfill(status=404, body="")
    return route


async def shot(pg, name):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        await pg.screenshot(path=str(Path(SHOTS) / name), full_page=True)


async def settle(pg):
    await pg.evaluate("() => new Promise((r) => setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(r)), 120))")


def run(browsers, body, query, width=1440, height=950):
    async def go():
        async with browsers.page(width, height, route=router()) as pg:
            await pg.goto(ORIGIN + PAGE + "?" + query)
            await pg.wait_for_function(READY, timeout=30000)
            assert await pg.evaluate("window.__harness.error || null") is None
            await settle(pg)
            await body(pg)
            assert pg.errors == [], pg.errors
            assert await pg.evaluate("window.__csp") == []            # nothing inline: the strict CSP holds
    browsers.run(go(), timeout=120)


def test_the_tree_grid_shows_the_servers_figures_never_a_sum_with_search_selection_explain_densities_and_export(browsers):
    async def body(pg):
        assert await pg.get_attribute("#hx-tree", "data-mode") == "grid"
        assert await pg.locator("#hx-tree [role='treegrid']").count() == 1
        heads = await pg.eval_on_selector_all("#hx-tree .dg-hcell", "els => els.map((e) => e.textContent.trim())")
        assert any("VaR 99% 1d" in x and "◆" in x for x in heads) and any("Σ children" in x and "◆" not in x for x in heads) and any("Positions" in x for x in heads)
        # every parent's VaR is the stand-in server's: under its children's sum, never equal to it
        checks = await pg.evaluate("""() => { const m = window.__harness.tree.model; return ['BANK', 'MKT', 'EQD', 'MKT-1'].map((id) => [m.value(id, 'VAR'), m.childSum(id, 'VAR')]); }""")
        for own, kids in checks:
            assert own is not None and kids is not None and own < kids
        assert await pg.evaluate("window.__harness.asked.length") >= 1
        assert await pg.locator("#hx-tree .dt-barcell .dt-bar i").count() > 0
        # the open state follows the model both ways (desks open at the start: openDepth 2)
        n0 = await pg.evaluate("window.__harness.tree.grid.grid.model.rowCount()")
        await pg.click("#hx-tree [data-act='expand-all']")
        await settle(pg)
        n1 = await pg.evaluate("window.__harness.tree.grid.grid.model.rowCount()")
        assert n1 == 53 and n1 > n0
        await pg.click("#hx-tree [data-act='collapse-all']")
        await settle(pg)
        assert await pg.evaluate("window.__harness.tree.grid.grid.model.rowCount()") == 5
        # search: the branches open, the first match is selected, its breadcrumbs shown
        await pg.fill("#hx-tree .dt-search", "metals book 3")
        await settle(pg)
        assert (await pg.text_content("#hx-tree .dt-lbl")).strip() == "1 found"
        crumbs = await pg.eval_on_selector_all("#hx-tree .dt-crumb", "els => els.map((e) => e.textContent)")
        assert crumbs == ["Bank (synthetic)", "Commodities", "Metals", "Metals book 3"]
        assert await pg.evaluate("window.__harness.heard.length") == 0      # a search selects quietly
        assert "Metals book 3" in await pg.text_content("#hx-tree .dt-side .dt-ttl")
        # a click on a row selects it and shares it; Explain draws in the side panel
        await pg.click("#hx-tree .dg-row:has-text('Treasury') .dg-cell >> nth=0")
        await settle(pg)
        assert await pg.evaluate("window.__harness.tree.selected") == "TSY"
        assert await pg.evaluate("window.__harness.heard.slice(-1)[0]") == ["TSY"]
        assert "Σ children (not a VaR 99% 1d)" in await pg.text_content("#hx-tree .dt-side")
        await pg.click("#hx-tree .dt-side [data-act='explain']")
        await pg.wait_for_selector("#hx-tree .dt-explain:not([hidden]) >> text=diversification")
        assert await pg.evaluate("window.__harness.explains") == 1
        # a selection from the bus (a grid or a chart on the channel)
        await pg.evaluate("window.__harness.publish(['EQD-2'])")
        await settle(pg)
        assert await pg.evaluate("window.__harness.tree.selected") == "EQD-2"
        # three densities, both themes
        for d in ("comfortable", "terminal", "compact"):
            await pg.click(f".hx-seg [data-density='{d}']")
            await settle(pg)
            assert await pg.get_attribute("#hx-tree", "data-density") == d
            assert await pg.get_attribute("#hx-tree .dg", "data-density") == d
        dark = await pg.evaluate("window.__harness.tree.theme.surface")
        await pg.click("#hx-theme")
        await settle(pg)
        assert await pg.evaluate("window.__harness.tree.theme.surface") != dark
        await shot(pg, "drishti-tree-grid-light.png")
        # exports: the data (the drawing's buttons are off in the grid)
        assert await pg.is_disabled("#hx-tree [data-act='png']")
        await pg.click("#hx-tree [data-act='csv']")
        await pg.click("#hx-tree [data-act='json']")
        d = await pg.evaluate("window.__harness.downloads")
        assert [x["name"] for x in d] == ["drishti-tree-var-hierarchy.csv", "drishti-tree-var-hierarchy.json"]
        assert "Path,Id,Kind,VaR 99% 1d" in d[0]["head"]
        assert await pg.evaluate("window.__harness.events.includes('selectionChanged') && window.__harness.events.includes('explainShown')")
    run(browsers, body, "view=grid")


def test_the_org_chart_keys_edit_mode_proposal_and_export(browsers):
    async def body(pg):
        items = pg.locator("#hx-tree [role='tree'] [role='treeitem']")
        assert await items.count() == 17                                    # the bank, 4 divisions, 12 desks (openDepth 2)
        assert await pg.get_attribute("#hx-tree [data-id='BANK']", "aria-level") == "1"
        assert await pg.get_attribute("#hx-tree [data-id='MKT']", "aria-expanded") == "true"
        # keys: walk, close, open, explain
        await pg.click("#hx-tree [data-id='MKT'] .dt-b")
        await settle(pg)
        await pg.focus("#hx-tree [data-id='MKT']")
        await pg.keyboard.press("ArrowLeft")
        await settle(pg)
        assert await pg.evaluate("window.__harness.tree.model.isOpen('MKT')") is False
        await pg.keyboard.press("ArrowRight")
        await settle(pg)
        assert await pg.evaluate("window.__harness.tree.model.isOpen('MKT')") is True
        await pg.keyboard.press("ArrowRight")
        assert await pg.evaluate("document.activeElement.getAttribute('data-id')") == "MKT-1"
        await pg.keyboard.press("Enter")
        await pg.wait_for_selector("#hx-tree .dt-explain:not([hidden])")
        # the edit mode: drag a desk onto another division: one proposal, drawn as a draft; a move under its own descendant refused
        await pg.click("#hx-tree [data-act='edit']")
        await settle(pg)
        assert await pg.is_visible("#hx-tree .dt-editbar")

        async def drag(a, b):
            await pg.locator(f"#hx-tree [data-id='{a}'] .dt-b").scroll_into_view_if_needed()
            await pg.hover(f"#hx-tree [data-id='{a}'] .dt-b")
            await pg.mouse.down()
            await pg.hover(f"#hx-tree [data-id='{b}'] .dt-b")
            await pg.mouse.up()
            await settle(pg)
        await drag("MKT-3", "TSY")
        p = await pg.evaluate("window.__harness.last.proposal")
        assert p["moves"] == [{"id": "MKT-3", "label": "FX", "from": "MKT", "to": "TSY", "fromPath": ["Bank (synthetic)", "Markets"], "toPath": ["Bank (synthetic)", "Treasury"]}]
        assert "Proposal · 1 move · draft" in await pg.text_content("#hx-tree .dt-prop")
        assert await pg.locator("#hx-tree .dt-box.dt-moved").count() == 1
        await drag("MKT", "MKT-1")
        assert "own descendant" in await pg.evaluate("window.__harness.last.moveRefused.reason")
        await drag("COM-1", "TSY")
        assert len(await pg.evaluate("window.__harness.last.proposal.moves")) == 2
        await pg.click("#hx-tree .dt-prop [data-act='undo']")
        await settle(pg)
        assert len(await pg.evaluate("window.__harness.tree.proposal().moves")) == 1
        await pg.click("#hx-tree .dt-prop [data-act='submit']")
        assert await pg.evaluate("window.__harness.events.includes('proposalSubmitted')")
        await pg.evaluate("window.__harness.tree.applyProposal()")
        await settle(pg)
        assert await pg.evaluate("window.__harness.tree.model.node('MKT-3').parent.id") == "TSY"
        assert await pg.locator("#hx-tree .dt-box.dt-moved").count() == 0
        await shot(pg, "drishti-tree-org.png")
        await pg.click("#hx-tree [data-act='svg']")
        await pg.click("#hx-tree [data-act='png']")
        await pg.wait_for_function("window.__harness.downloads.length === 2")
        d = await pg.evaluate("window.__harness.downloads")
        assert d[0]["type"] == "image/svg+xml" and d[0]["head"].startswith("<svg")
        assert d[1]["type"] == "image/png" and d[1]["head"].startswith("data:image/png;base64,")
    run(browsers, body, "view=org")


def test_the_lineage_dag_in_the_org_chart(browsers):
    async def body(pg):
        assert await pg.locator("#hx-tree [role='treeitem']").count() == 9
        assert await pg.locator("#hx-tree .dt-lnk-shared").count() == 1    # curves also read from the trades feed
        assert "with two or more parents ⧉" in await pg.text_content("#hx-tree .dt-status")
    run(browsers, body, "view=org&data=lineage&density=terminal&theme=light")


@pytest.mark.parametrize("mode", ["sunburst", "icicle"])
def test_the_partitions_sized_by_an_additive_measure_with_their_table_and_zoom(browsers, mode):
    async def body(pg):
        slices = pg.locator("#hx-tree .dt-slice")
        assert await slices.count() == 53
        assert await pg.locator("#hx-tree .dt-sizebox select option").all_text_contents() == ["Positions", "Notional"]   # never VaR
        await pg.click("#hx-tree [data-act='alt']")
        await settle(pg)
        assert await pg.locator("#hx-tree table.dt-alt tbody tr").count() == 53
        await pg.click("#hx-tree .dt-slice[data-id='EQD']")
        await settle(pg)
        await pg.click("#hx-tree .dt-side [data-act='zoom-in']")
        await settle(pg)
        assert await slices.count() == 13
        assert (await pg.eval_on_selector_all("#hx-tree .dt-crumb", "els => els.map((e) => e.textContent)"))[-1] == "Equities"
        await pg.select_option("#hx-tree .dt-sizebox select", "NOTIONAL")
        await settle(pg)
        assert await slices.count() == 13
        await pg.focus("#hx-tree .dt-slice[data-id='EQD']")
        await pg.keyboard.press("ArrowDown")
        assert await pg.evaluate("document.activeElement.getAttribute('data-id')") == "EQD-1"
        await shot(pg, f"drishti-tree-{mode}.png")
    run(browsers, body, f"view={mode}")
