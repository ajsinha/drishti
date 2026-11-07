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

"""drishti-grid's visual layer in a real browser, against its static fixture harness (static/elements/drishti-grid/drishti_harness.html):
no Drishti server, the files served from the folder under a strict CSP (nothing inline), the per-worker browser only. The component is
tested here once and deeply; screens test only their wiring (ELEMENTS.md section 10).

* each layout (wells, panel, terminal) and density, light and dark, the nine at once, and the web component's attributes;
* the wells: a drag between wells, a refused drop and its reason, a field from the list, removal, the chip's menu by the keyboard;
* the menus: every column menu, context menu, chip menu and toolbar menu item clicked; every toolbar button and function key clicked;
* ranges by drag and Shift+click with the status bar (count, sum, average, min, max; no sum of VaR), the non-additive hover note;
* the keys (arrows, Enter, E and F2, Ctrl+C, Escape, Shift+F10), the grid's roles and announcements;
* live data: the flash up and down, reduced motion, server-side blocks with loading placeholders, virtual rows at 100,000 rows;
* a phone: the first column pinned, a pivot as one value column with its measure switcher;
* the AG Grid-style adapter (drishti_ag-harness.html): a pivot, server-side rows and tree data made purely from AG-shaped options.

DRISHTI_GRID_SHOTS names a folder for screenshots of the three layouts (none are taken otherwise)."""
import asyncio
import os
from pathlib import Path

import pytest

pytestmark = pytest.mark.spread

STATIC = Path(__file__).resolve().parents[1] / "web" / "static"
ORIGIN = "http://render.test"
PAGE = "/static/elements/drishti-grid/drishti_harness.html"
HEADERS = {"content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self'"}
TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "application/javascript", ".json": "application/json"}
SHOTS = os.environ.get("DRISHTI_GRID_SHOTS")


async def route(r):
    path = r.request.url[len(ORIGIN):].split("?")[0]
    f = STATIC / path[len("/static/"):] if path.startswith("/static/") else None
    if f and f.is_file():
        headers = {"content-type": TYPES.get(f.suffix, "application/octet-stream")}
        if f.suffix == ".html":
            headers.update(HEADERS)
        return await r.fulfill(status=200, body=f.read_bytes(), headers=headers)
    return await r.fulfill(status=404, body="")


READY = ("() => window.__harness && window.__harness.ready && document.querySelector('.dg-row') && !document.querySelector('.dg-pending')"
         " && !document.querySelector('.dg-loading')")


async def opened(pg, query=""):
    await pg.goto(ORIGIN + PAGE + ("?" + query if query else ""))
    await pg.wait_for_function(READY)


async def ev(pg, name):
    return await pg.evaluate("(n) => window.__harness.events.filter((x) => x === n).length", name)


async def wells(pg):
    return await pg.evaluate("() => window.__harness.grid.model.wells.getState()")


async def settle(pg):
    await pg.wait_for_function("() => !document.querySelector('.dg-pending') && !document.querySelector('.dg-loading')")
    await pg.evaluate("() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))")


async def menu_items(pg):
    found = await pg.eval_on_selector_all(".dg-menu [data-action]", "els => els.map((e) => [e.dataset.action, e.getAttribute('aria-disabled') === 'true'])")
    return [tuple(x) for x in found]


async def click_item(pg, action):
    await pg.click(f".dg-menu [data-action='{action}']")
    await settle(pg)


def cell(r, c):
    return f".dg-cell[data-r='{r}'][data-c='{c}']"


def run(browsers, body, width=1360, height=860, query=""):
    async def go():
        async with browsers.page(width, height, route=route) as pg:
            await opened(pg, query)
            await body(pg)
            assert pg.errors == [], pg.errors
            assert await pg.evaluate("window.__csp") == []            # nothing inline: the strict CSP holds
    browsers.run(go(), timeout=120)


def test_each_layout_and_density_draws_the_pivot_in_both_themes(browsers):
    async def body(pg):
        for layout, region in (("wells", ".dg-wells-ribbon"), ("panel", ".dg-side .dg-wells-panel"), ("terminal", ".dg-cmd")):
            await pg.click(f".hx-seg [data-layout='{layout}']")
            await settle(pg)
            assert await pg.get_attribute("drishti-grid", "data-layout") == layout
            assert await pg.locator(region).count() == 1
            assert await pg.get_attribute(".dg-scroll", "role") == "treegrid"
            assert int(await pg.get_attribute(".dg-scroll", "aria-rowcount")) > 10
            assert await pg.locator(".dg-hcell.dg-leaf .dg-na").count() > 0                 # ◆ on VaR's headings
            assert await pg.locator(".dg-hcell.dg-right").count() >= 2                    # the grand totals pinned right
            assert await pg.locator(".dg-cell.dg-nadd").count() > 0                       # non-additive totals, the server's
            assert await pg.locator(".dg-cf").count() > 0                                 # colour scale and data bars
            assert await pg.locator(".dg-ctoggle").count() >= 2                           # collapsible column groups
            if SHOTS:
                Path(SHOTS).mkdir(parents=True, exist_ok=True)
                await pg.screenshot(path=str(Path(SHOTS) / f"drishti-grid-{layout}.png"))
        assert await pg.locator(".dg-split").count() == 1 and await pg.locator(".dg-fkey").count() == 8
        for density, height in (("comfortable", 36), ("compact", 28), ("terminal", 22)):
            await pg.click(f".hx-seg [data-density='{density}']")
            await settle(pg)
            assert (await pg.locator(".dg-row").first.bounding_box())["height"] == pytest.approx(height, abs=1)
        dark = await pg.evaluate("() => getComputedStyle(document.querySelector('.dg-cell')).backgroundColor")
        await pg.click("#hx-theme")
        light = await pg.evaluate("() => getComputedStyle(document.querySelector('.dg-cell')).backgroundColor")
        assert dark != light
        if SHOTS:
            await pg.screenshot(path=str(Path(SHOTS) / "drishti-grid-terminal-light.png"))
        assert await ev(pg, "layoutChanged") == 2 and await ev(pg, "densityChanged") == 3       # wells was the layout already
    run(browsers, body)


def test_the_nine_layouts_and_densities_at_once(browsers):
    async def go():
        async with browsers.page(1500, 1400, route=route) as pg:
            await pg.goto(ORIGIN + PAGE + "?matrix=1")
            await pg.wait_for_function("() => document.querySelectorAll('drishti-grid').length === 9 && Array.from(document.querySelectorAll('drishti-grid')).every((g) => g.querySelector('.dg-row')) && !document.querySelector('.dg-pending')")
            assert await pg.locator("drishti-grid").count() == 9
            assert await pg.locator(".dg-layout-terminal").count() == 3 and await pg.locator(".dg-layout-panel").count() == 3
            heights = await pg.eval_on_selector_all("drishti-grid", "els => els.map((e) => e.querySelector('.dg-row').getBoundingClientRect().height)")
            assert [round(x) for x in heights] == [36, 28, 22] * 3
            if SHOTS:
                await pg.screenshot(path=str(Path(SHOTS) / "drishti-grid-matrix.png"), full_page=True)
            assert pg.errors == [] and await pg.evaluate("window.__csp") == []
    browsers.run(go(), timeout=120)


def test_drag_between_wells_refusals_the_field_list_and_the_keyboard(browsers):
    async def body(pg):
        ccy = ".dg-zone[data-well='columns'] .dg-chip[data-field='CCY']"
        rows = await pg.locator(".dg-zone[data-well='rows']").bounding_box()
        await pg.drag_and_drop(ccy, ".dg-zone[data-well='rows']", target_position={"x": rows["width"] - 6, "y": rows["height"] / 2})
        await settle(pg)
        w = await wells(pg)
        assert w["rows"] == ["DESK", "BOOK", "CCY"] and w["columns"] == ["REGION"]
        await pg.drag_and_drop(".dg-zone[data-well='values'] .dg-chip[data-field='VAR']", ".dg-zone[data-well='rows']")
        assert "cannot group rows" in await pg.text_content(".dg-toast")                 # refused, and why
        assert (await wells(pg))["rows"] == ["DESK", "BOOK", "CCY"]
        await pg.drag_and_drop(".dg-fields .dg-chip[data-field='TRADES']", ".dg-zone[data-well='values']")
        await settle(pg)
        assert [v["colId"] for v in (await wells(pg))["values"]] == ["MTM", "VAR", "TRADES"]
        await pg.drag_and_drop(".dg-zone[data-well='rows'] .dg-chip[data-field='BOOK']", ".dg-zone[data-well='rows'] .dg-chip[data-field='DESK']")
        assert (await wells(pg))["rows"][:2] == ["BOOK", "DESK"]                           # reordered in its well
        await pg.click(".dg-zone[data-well='rows'] .dg-chip[data-field='CCY'] .dg-chip-x")
        await settle(pg)
        assert (await wells(pg))["rows"] == ["BOOK", "DESK"]
        await pg.fill(".dg-field-search", "curr")
        assert await pg.locator(".dg-fields .dg-chip").count() == 1                       # the field search
        await pg.fill(".dg-field-search", "")
        await pg.focus(".dg-zone[data-well='rows'] .dg-chip[data-field='DESK']")
        await pg.keyboard.press("Enter")
        await pg.wait_for_selector(".dg-menu")
        await pg.keyboard.press("ArrowDown")
        await click_item(pg, "to:columns")
        w = await wells(pg)
        assert w["rows"] == ["BOOK"] and w["columns"] == ["REGION", "DESK"]
        await pg.focus(".dg-zone[data-well='values'] .dg-chip[data-field='TRADES']")
        await pg.keyboard.press("Delete")
        await settle(pg)
        assert len((await wells(pg))["values"]) == 2
        await pg.click(".dg-zone[data-well='values'] .dg-chip[data-field='VAR'] .dg-chip-menu")
        items = await menu_items(pg)
        assert ("to:rows", True) in items and ("agg-server", True) in items               # VaR cannot group rows; its aggregation is the server's
        await pg.keyboard.press("Escape")
    run(browsers, body)


def test_every_menu_item_runs(browsers):
    async def body(pg):
        # the column menu of the MTM grand total
        mtm = await pg.evaluate("() => window.__harness.grid.model.shownColumns().findIndex((c) => c.kind === 'grand' && c.measure.indexOf('MTM') === 0)")
        for action in ("sort-desc", "sort-asc", "sort-clear", "top-5", "top-10", "clear-filter", "agg:avg", "agg:sum", "hide", "show-all", "autosize", "autosize-all",
                       "pin-totals", "pin-totals", "explain-column"):
            at = mtm
            if not await pg.locator(f".dg-hcell.dg-leaf[data-c='{mtm}']").count():                # hidden, or scrolled away once unpinned
                at = await pg.get_attribute(".dg-hcell.dg-leaf.dg-num >> nth=0", "data-c")
            await pg.hover(f".dg-hcell.dg-leaf[data-c='{at}']")
            await pg.click(f".dg-hmenu[data-c='{at}']")
            await click_item(pg, action)
            mtm = await pg.evaluate("() => window.__harness.grid.model.shownColumns().findIndex((c) => c.kind === 'grand' && c.measure.indexOf('MTM') === 0)")
            if action == "sort-desc":
                assert await pg.get_attribute(f".dg-hcell.dg-leaf[data-c='{mtm}']", "aria-sort") == "descending"
            if action == "hide":
                assert await pg.locator(f".dg-hcell.dg-leaf[data-c='{mtm}']").count() == 0
        assert await pg.is_visible(".dg-drawer .dg-ex-title")                              # Explain the total
        # VaR's aggregation is the server's: disabled with its reason
        var = await pg.evaluate("() => window.__harness.grid.model.shownColumns().findIndex((c) => c.kind === 'grand' && c.measure === 'VaR 99% 1d')")
        await pg.hover(f".dg-hcell.dg-leaf[data-c='{var}']")
        await pg.click(f".dg-hmenu[data-c='{var}']")
        assert ("agg:server", True) in await menu_items(pg)
        await click_item(pg, "value-filter")
        await pg.fill(".dg-menu .dg-menu-input", "> 1m")
        await pg.click(".dg-menu [data-apply]")
        await settle(pg)
        assert await pg.locator(".dg-vf").count() == 1 and await pg.locator(".dg-st-vf").count() == 1
        await pg.click(".dg-vf .dg-chip-menu")
        await click_item(pg, "c:gt:0")
        await pg.click(".dg-vf .dg-chip-x")
        await settle(pg)
        assert await pg.locator(".dg-vf").count() == 0
        await pg.click(".dg-add-vf")
        await click_item(pg, "c:lt:0")
        assert await pg.locator(".dg-vf").count() == 1
        # the context menu: every item
        for action in ("explain", "copy", "copy-headers", "export-csv", "export-xlsx", "chart", "drill", "pin-totals", "group"):
            await pg.locator(".dg-row:not(.dg-k-header) .dg-cell.dg-num").nth(1).click(button="right")   # whatever the filters left
            await pg.wait_for_selector(".dg-menu")
            await click_item(pg, action)
            if action == "drill":
                await pg.wait_for_selector("dialog.dg-dialog[open] .dg-row")
                assert "rows" in await pg.text_content(".dg-dialog-head")
                await pg.click(".dg-dialog-close")
                await pg.wait_for_selector("dialog.dg-dialog", state="detached")             # closed and taken away
        h = await pg.evaluate("() => ({d: window.__harness.downloads.map((x) => x.name), c: window.__harness.charts.length})")
        assert h["d"] == ["grid.csv", "grid.xlsx"] and h["c"] == 1
        assert await ev(pg, "copied") == 2 and await ev(pg, "drillThrough") == 1 and await ev(pg, "chartRequested") == 1
        assert (await wells(pg))["rows"][-1] == "CCY"                                      # grouped by the innermost column field
        # chip menus: an aggregation, a member filter, the order and top 5 of a row field
        await pg.click(".dg-zone[data-well='values'] .dg-chip[data-field='MTM'] .dg-chip-menu")
        await click_item(pg, "agg:max")
        assert (await wells(pg))["values"][0]["aggFunc"] == "max"
        await pg.click(".dg-zone[data-well='filters'] .dg-chip[data-field='TRADER'] .dg-chip-menu")
        await click_item(pg, "member:A. Rao")                                              # kept open: a member's check
        assert await pg.get_attribute(".dg-menu [data-action='member:A. Rao']", "aria-checked") == "false"
        await click_item(pg, "members-all")
        await pg.click(".dg-zone[data-well='rows'] .dg-chip[data-field='DESK'] .dg-chip-menu")
        await click_item(pg, "order-measure")
        await pg.click(".dg-zone[data-well='rows'] .dg-chip[data-field='DESK'] .dg-chip-menu")
        await click_item(pg, "top-5")
        # the toolbar's menus
        await pg.click("[data-act='menu-format']")
        items = [a for a, off in await menu_items(pg) if not off]
        for a in items:
            if not await pg.locator(".dg-menu").count():
                await pg.click("[data-act='menu-format']")
            await click_item(pg, a)
        await pg.keyboard.press("Escape")
        for a in ("export-csv", "export-xlsx", "copy-headers", "save-view"):
            await pg.click("[data-act='menu-export']")
            await click_item(pg, a)
        assert await ev(pg, "viewSaved") == 1
        for a in ("density:comfortable", "layout:panel", "layout:terminal", "show-all", "autosize-all", "layout:wells", "density:compact"):
            await pg.click("[data-act='menu-view']")
            await click_item(pg, a)
        assert await pg.get_attribute("drishti-grid", "data-layout") == "wells"
    run(browsers, body)


def test_every_toolbar_button_and_function_key_runs(browsers):
    async def body(pg):
        await pg.click(cell(1, 1))
        for layout in ("wells", "panel", "terminal"):
            await pg.click(f".hx-seg [data-layout='{layout}']")
            await settle(pg)
            acts = await pg.eval_on_selector_all(".dg-toolbar [data-act], .dg-fkeys [data-act]", "els => els.map((e) => e.dataset.act)")
            for act in acts:
                b = pg.locator(f".dg-toolbar [data-act='{act}'], .dg-fkeys [data-act='{act}']").first
                if not await b.count():
                    continue                                                                # a pivot toggled off takes its buttons away
                await b.click()
                await settle(pg)
                if await pg.locator("dialog.dg-dialog[open]").count():
                    await pg.click(".dg-dialog-close")
                if await pg.locator(".dg-menu").count():
                    await pg.keyboard.press("Escape")
            if not await pg.evaluate("() => window.__harness.grid.model.pivotMode"):
                await pg.click(".dg-toolbar [data-act='toggle-pivot']")
                await settle(pg)
        # the panel's switches: deferred update, grand total column, subtotals above, pivot mode
        await pg.click(".hx-seg [data-layout='panel']")
        await settle(pg)
        await pg.check(".dg-switches [data-sw='defer']")
        await pg.drag_and_drop(".dg-field[data-field='TRADES']", ".dg-zone[data-well='values']")
        assert len((await wells(pg))["values"]) == 2                                       # deferred: not applied yet
        await pg.click("[data-act='apply-draft']")
        await settle(pg)
        assert len((await wells(pg))["values"]) == 3
        await pg.uncheck(".dg-switches [data-sw='defer']")
        n = await pg.locator(".dg-hcell.dg-right").count()
        await pg.uncheck(".dg-switches [data-sw='grand']")
        await settle(pg)
        assert await pg.locator(".dg-hcell.dg-right").count() < n
        await pg.check(".dg-switches [data-sw='above']")
        await settle(pg)
        assert await pg.evaluate("() => window.__harness.grid.model.pivotExtra.subtotals") == "above"
        await pg.uncheck(".dg-field [data-use='TRADES']")
        await settle(pg)
        assert len((await wells(pg))["values"]) == 2
    run(browsers, body)


def test_ranges_the_status_bar_and_the_non_additive_note(browsers):
    async def body(pg):
        mtm = await pg.evaluate("() => window.__harness.grid.model.shownColumns().findIndex((c) => c.measure === 'MTM' && c.kind === 'node')")
        a, b = (await pg.locator(cell(1, mtm)).bounding_box()), (await pg.locator(cell(3, mtm)).bounding_box())
        await pg.mouse.move(a["x"] + 5, a["y"] + 5)
        await pg.mouse.down()
        await pg.mouse.move(b["x"] + 5, b["y"] + 5, steps=4)
        await pg.mouse.up()
        await settle(pg)
        assert await pg.locator(".dg-cell.dg-in-range").count() == 3
        st = await pg.text_content(".dg-st-range")
        assert "Count 3" in st and "Sum" in st and "Average" in st and "Min" in st and "Max" in st and "not a" not in st
        var = await pg.evaluate("() => window.__harness.grid.model.shownColumns().findIndex((c) => c.measure === 'VaR 99% 1d' && c.kind === 'node')")
        await pg.click(cell(1, var))
        await pg.click(cell(4, var), modifiers=["Shift"])
        await settle(pg)
        assert await pg.locator(".dg-cell.dg-in-range").count() == 4
        st = await pg.text_content(".dg-st-range")
        assert "Sum —" in st and "is not a VaR 99% 1d" in st                              # a sum of VaR is not a VaR
        await pg.hover(".dg-cell.dg-nadd")
        assert await pg.is_visible(".dg-tip")
        assert "not the sum of its parts" in await pg.text_content(".dg-tip")
        await pg.hover(".dg-toolbar")
        assert await ev(pg, "rangeChanged") >= 2
        await pg.click(".dg-hcell .dg-check[data-all]")                                   # row checkboxes: all, none, two
        await settle(pg)
        assert int((await pg.text_content(".dg-st-rows")).split()[0]) > 10
        await pg.click(".dg-hcell .dg-check[data-all]")
        await settle(pg)
        assert await pg.locator(".dg-st-rows").count() == 0
        await pg.click(".dg-row .dg-check >> nth=1")
        await pg.click(".dg-row .dg-check >> nth=2")
        await settle(pg)
        assert await pg.text_content(".dg-st-rows") == "2 rows selected"
        assert await pg.locator(".dg-row[aria-selected='true']").count() == 2
    run(browsers, body)


def test_the_keyboard_roles_and_announcements(browsers):
    async def body(pg):
        await pg.click(cell(0, 0))
        await settle(pg)
        grid = ".dg-scroll"
        assert await pg.get_attribute(grid, "aria-activedescendant") == await pg.get_attribute(cell(0, 0), "id")
        await pg.keyboard.press("ArrowDown")
        await pg.keyboard.press("ArrowRight")
        await settle(pg)
        assert (await pg.get_attribute(grid, "aria-activedescendant")).endswith("-r1-c1")
        await pg.keyboard.press("ArrowLeft")
        await pg.keyboard.press("ArrowUp")
        expanded = await pg.get_attribute(".dg-row[aria-rowindex] >> nth=0", "aria-expanded")
        await pg.keyboard.press("Enter")                                                   # opens or closes the group
        await settle(pg)
        assert await pg.get_attribute(".dg-row >> nth=0", "aria-expanded") != expanded
        await pg.keyboard.press("ArrowRight")
        await pg.keyboard.press("e")
        await pg.wait_for_selector(".dg-drawer .dg-ex-value")
        assert await ev(pg, "explainShown") == 1
        await pg.keyboard.press("Shift+ArrowDown")
        await pg.keyboard.press("Control+c")
        assert await ev(pg, "copied") == 1
        assert "\n" in await pg.evaluate("() => window.__harness.grid.lastCopy")
        await pg.keyboard.press("Escape")
        await settle(pg)
        assert await pg.locator(".dg-drawer").is_hidden()
        await pg.keyboard.press("Shift+F10")
        await pg.wait_for_selector(".dg-menu[role='menu'] [role='menuitem']")
        await pg.keyboard.press("Escape")
        assert await pg.locator(".dg-menu").count() == 0
        assert await pg.locator("[data-drishti-live]").count() >= 1                        # announcements in a live region
        assert await pg.locator(".dg-hcell[role='columnheader']").count() > 5
        assert await pg.locator(".dg-cell[role='rowheader']").count() > 5
        # the terminal: F2 explains into the split pane; the function keys from the keyboard
        await pg.click(".hx-seg [data-layout='terminal']")
        await settle(pg)
        await pg.click(cell(1, 2))
        await pg.keyboard.press("F2")
        await pg.wait_for_selector(".dg-split .dg-ex-steps")
        assert await pg.locator(".dg-split .dg-spark").count() == 1 and await pg.locator(".dg-split .dg-ex-row").count() == 8
        await pg.keyboard.press("F4")
        await settle(pg)
        await pg.keyboard.press("F3")
        await settle(pg)
        await pg.keyboard.press("F10")
        assert await ev(pg, "viewSaved") == 1
        await pg.fill(".dg-cmd-in", "ROWS REGION BY DESK VALUES MTM")
        await pg.keyboard.press("Enter")
        await settle(pg)
        w = await wells(pg)
        assert w["rows"] == ["REGION"] and w["columns"] == ["DESK"]
        await pg.fill(".dg-cmd-in", "ROWS NOPE VALUES MTM")
        await pg.keyboard.press("Enter")
        assert await pg.get_attribute(".dg-cmd-in", "aria-invalid") == "true"
    run(browsers, body)


def test_live_flash_reduced_motion_server_blocks_and_virtual_rows(browsers):
    async def go():
        async with browsers.page(1360, 860, route=route) as pg:
            await opened(pg, "mode=grid&density=compact")
            await pg.evaluate("() => window.__harness.tick(30)")
            await pg.wait_for_selector(".dg-flash-up, .dg-flash-down")
            assert (await pg.evaluate("() => getComputedStyle(document.querySelector('.dg-flash-up, .dg-flash-down')).animationName")).startswith("dg-flash")
            await pg.emulate_media(reduced_motion="reduce")
            await pg.evaluate("() => window.__harness.tick(30)")
            await pg.wait_for_selector(".dg-flash-up, .dg-flash-down")
            assert await pg.evaluate("() => getComputedStyle(document.querySelector('.dg-flash-up, .dg-flash-down')).animationName") == "none"
            await pg.wait_for_function("() => !document.querySelector('.dg-flash-up, .dg-flash-down')")   # the flash ends
            assert pg.errors == []
        async with browsers.page(1360, 860, route=route) as pg:
            await pg.goto(ORIGIN + PAGE + "?mode=server&delay=600&rows=5000")
            await pg.wait_for_selector(".dg-loading")                                     # placeholders while a block loads
            await pg.wait_for_function(READY)
            await pg.evaluate("() => { const s = document.querySelector('.dg-scroll'); s.scrollTop = s.scrollHeight / 2; }")
            await pg.wait_for_selector(".dg-loading")
            await pg.wait_for_function(READY)
            assert await ev(pg, "pivotChanged") == 0 and pg.errors == []
        async with browsers.page(1360, 860, route=route) as pg:
            await opened(pg, "mode=grid&rows=100000")
            assert await pg.get_attribute(".dg-scroll", "aria-rowcount") == "100002"       # two heading rows: the column groups
            assert await pg.locator(".dg-row").count() < 60                                 # only what is in view
            await pg.evaluate("() => { const s = document.querySelector('.dg-scroll'); s.scrollTop = s.scrollHeight; }")
            await pg.wait_for_function("() => document.querySelector('.dg-row:last-child').getAttribute('aria-rowindex') === '100002'")
            await pg.evaluate("() => { const s = document.querySelector('.dg-scroll'); s.scrollLeft = s.scrollWidth; }")
            await settle(pg)
            assert await pg.locator(".dg-row:last-child .dg-cell").count() > 3
            await pg.click(".dg-toolbar [data-act='toggle-pivot']")                          # a pivot of the 100,000 rows
            await pg.wait_for_function("() => window.__harness.grid.model.pivotMode && document.querySelector('.dg-row')")
            assert pg.errors == [] and await pg.evaluate("window.__csp") == []
    browsers.run(go(), timeout=120)


def test_a_phone_pins_the_first_column_and_switches_the_pivot_measure(browsers):
    async def go():
        async with browsers.page(390, 800, route=route) as pg:
            await opened(pg)
            assert await pg.get_attribute("drishti-grid", "data-phone") == ""
            assert await pg.locator(".dg-hcell.dg-leaf").count() == 2                       # the row headings and one value column
            assert await pg.locator(".dg-wells").is_hidden()
            sel = ".dg-toolbar select.dg-measure"
            assert await pg.eval_on_selector_all(f"{sel} option", "els => els.map((e) => e.value)") == ["MTM", "VaR 99% 1d"]
            await pg.select_option(sel, "VaR 99% 1d")
            await settle(pg)
            assert "VaR" in await pg.text_content(".dg-hcell.dg-leaf.dg-num")
            assert await ev(pg, "measureSwitched") == 1
            await pg.click("[data-act='toggle-fields']")
            assert await pg.locator(".dg-wells").is_visible()
            await pg.click("[data-act='toggle-pivot']")
            await settle(pg)
            first = await pg.evaluate("() => getComputedStyle(document.querySelector('.dg-row .dg-cell')).position")
            assert first == "sticky"                                                         # the first column pinned
            await pg.evaluate("() => { document.querySelector('.dg-scroll').scrollLeft = 400; }")
            await settle(pg)
            box = await pg.locator(".dg-row .dg-cell >> nth=0").bounding_box()
            assert box["x"] < 20                                                              # still at the left edge
            assert pg.errors == [] and await pg.evaluate("window.__csp") == []
    browsers.run(go(), timeout=120)


AG_PAGE = "/static/elements/drishti-grid/drishti_ag-harness.html"
AG_READY = ("() => window.__ag && window.__ag.ready && document.querySelector('.dg-row') && !document.querySelector('.dg-pending')"
            " && !document.querySelector('.dg-loading')")


def test_the_ag_grid_adapter_draws_a_pivot_from_ag_shaped_options_only(browsers):
    """drishti_ag-harness.html: a pivot, server-side rows and tree data, each made purely from AG Grid Enterprise-shaped options by
    createGridFromAgOptions; the AG-named events, sideBar as the panel layout, the server's non-additive totals, the API."""
    async def go():
        async with browsers.page(1360, 860, route=route) as pg:
            await pg.goto(ORIGIN + AG_PAGE)
            await pg.wait_for_function(AG_READY)
            await pg.wait_for_function("() => window.__ag.events.some((e) => e.type === 'firstDataRendered')")
            assert [e["type"] for e in await pg.evaluate("window.__ag.events")][:2] == ["gridReady", "firstDataRendered"]
            assert await pg.locator(".dg-side .dg-wells-panel").count() == 1                         # sideBar: the panel layout
            heads = await pg.eval_on_selector_all(".dg-hcell", "els => els.map((e) => e.textContent)")
            assert any("EMEA" in h for h in heads) and any("Grand total" in h for h in heads)
            assert any(h.strip().startswith("VaR 99% 1d") for h in heads)                            # suppressAggFuncInHeader
            assert await pg.locator(".dg-cell.dg-nadd").count() > 0                                  # VaR's totals: the server's
            assert await pg.evaluate("window.__ag.asked") >= 1
            rows = await pg.evaluate("() => { const m = window.__ag.api.model; const out = []; for (let r = 0; r < m.rowCount(); r++) { out.push(m.cellText(r, 0)); } return out; }")
            assert rows == ["Credit", "Equities", "FX", "Rates", "Grand total"]
            await pg.click(cell(1, 1))
            clicked = [e for e in await pg.evaluate("window.__ag.events") if e["type"] == "cellClicked"]
            assert clicked and clicked[-1]["rowIndex"] == 1
            await pg.evaluate("() => window.__ag.api.expandAll()")
            await pg.wait_for_function("() => window.__ag.api.getDisplayedRowCount() > 5 && !document.querySelector('.dg-pending')")
            await pg.evaluate("() => window.__ag.api.setGridOption('pivotMode', false)")
            await pg.wait_for_function("() => !window.__ag.api.isPivotMode() && document.querySelector('.dg-row')")
            assert "columnPivotModeChanged" in [e["type"] for e in await pg.evaluate("window.__ag.events")]
            assert await pg.evaluate("() => window.__ag.api.getDataAsCsv().split('\\r\\n')[0]") != ""
            await pg.evaluate("() => window.__ag.api.exportDataAsExcel({ fileName: 'risk.xlsx' })")
            assert (await pg.evaluate("window.__ag.download"))["name"] == "risk.xlsx"
            report = await pg.evaluate("() => window.__ag.api.reportUnsupported()")
            assert {"option": "animateRows", "state": "no-op"} in [{"option": r["option"], "state": r["state"]} for r in report]
            assert pg.errors == [] and await pg.evaluate("window.__csp") == []
            # the server-side row model from an AG-shaped datasource
            await pg.goto(ORIGIN + AG_PAGE + "?mode=server")
            await pg.wait_for_function(AG_READY)
            req = (await pg.evaluate("window.__ag.requests"))[0]
            assert req["rowGroupCols"] == [{"id": "desk", "displayName": "Desk", "field": "desk"}]
            assert {"id": "var", "displayName": "VaR 99% 1d", "field": "var", "aggFunc": "drishti:server:var(99, 1d)"} in req["valueCols"]
            assert req["startRow"] == 0 and req["endRow"] == 50 and req["groupKeys"] == []
            await pg.click(".dg-row .dg-tw >> nth=0")
            await pg.wait_for_function("() => window.__ag.requests.some((r) => r.groupKeys.length === 1) && !document.querySelector('.dg-loading')")
            assert await pg.evaluate("() => window.__ag.api.getDisplayedRowCount()") > 4
            # tree data
            await pg.goto(ORIGIN + AG_PAGE + "?mode=tree")
            await pg.wait_for_function(AG_READY)
            assert "Organisation" in await pg.text_content(".dg-hcell >> nth=0")
            assert await pg.get_attribute(".dg-scroll", "role") == "treegrid"
            assert pg.errors == [] and await pg.evaluate("window.__csp") == []
            # tree data on server-side rows: the top level, then each node's level asked when it opens, groupKeys its path
            await pg.goto(ORIGIN + AG_PAGE + "?mode=server-tree")
            await pg.wait_for_function(AG_READY)
            assert await pg.get_attribute(".dg-scroll", "role") == "treegrid"
            assert [r["groupKeys"] for r in await pg.evaluate("window.__ag.requests")] == [[]]
            assert await pg.get_attribute(".dg-row >> nth=0", "aria-level") == "1" and await pg.get_attribute(".dg-row >> nth=0", "aria-expanded") == "false"
            await pg.click(".dg-row .dg-tw >> nth=0")
            await pg.wait_for_function("() => window.__ag.requests.some((r) => r.groupKeys.length === 1) && !document.querySelector('.dg-loading')")
            assert await pg.get_attribute(".dg-row >> nth=0", "aria-expanded") == "true"
            assert await pg.get_attribute(".dg-row >> nth=1", "aria-level") == "2"
            assert "Rates" in await pg.text_content(".dg-row >> nth=1")
            assert [r["groupKeys"] for r in await pg.evaluate("window.__ag.requests")][1] == ["Bank"]
            assert not any(r["option"].startswith("isServerSideGroup") or "treeData" in r["option"] for r in await pg.evaluate("() => window.__ag.api.reportUnsupported()"))
            assert pg.errors == [] and await pg.evaluate("window.__csp") == []
    browsers.run(go(), timeout=120)
