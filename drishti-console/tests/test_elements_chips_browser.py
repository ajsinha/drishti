#
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
#
"""Chips (drishti-core): a list attribute's values. Collapsed, the first few chips and "+N more ▾"; a click, Enter or Space expands it
in place to every value ("▴ less" collapses it), aria-expanded says which; in a grid cell, the first chip and a count badge, and a click
shows every value in the cell and the row grows taller (the rows below move down, nothing is covered). Served without a server under the strict CSP (the grid harness page)."""
import pytest

from test_elements_grid_browser import menu_items, opened, route, settle

pytestmark = pytest.mark.spread

MOUNT = """() => {
  const C = window.Drishti.elements.core;
  const host = C.chipList(document, '["fx","rates","credit","hedge","emea"]', { max: 2, label: 'Tags' });
  host.id = 'chips-under-test';
  document.body.appendChild(host);
  return true;
}"""


def test_chips_expand_and_collapse_by_mouse_and_keyboard(browsers):
    async def go():
        async with browsers.page(1200, 800, route=route) as pg:
            await opened(pg)
            await pg.evaluate(MOUNT)
            box = "#chips-under-test"
            toggle = box + " .drishti-chips-toggle"
            assert await pg.locator(box + " .drishti-chip").count() == 2
            assert await pg.text_content(toggle) == "+3 more ▾"
            assert await pg.get_attribute(toggle, "aria-expanded") == "false"
            await pg.evaluate("() => { document.getElementById('chips-under-test').style.width = '140px'; }")   # a narrow place
            h0 = (await pg.locator(box).bounding_box())["height"]
            await pg.click(toggle)                                              # the mouse: every value, in place
            assert await pg.locator(box + " .drishti-chip").count() == 5
            assert await pg.get_attribute(toggle, "aria-expanded") == "true"
            assert await pg.text_content(toggle) == "▴ less"
            assert (await pg.locator(box).bounding_box())["height"] > h0       # the chips wrap downward: the box grows vertically
            await pg.click(toggle)
            assert await pg.locator(box + " .drishti-chip").count() == 2
            await pg.focus(toggle)                                              # the keyboard: Enter, then Space
            await pg.keyboard.press("Enter")
            assert await pg.get_attribute(toggle, "aria-expanded") == "true"
            assert await pg.locator(box + " .drishti-chip").count() == 5
            await pg.keyboard.press(" ")
            assert await pg.get_attribute(toggle, "aria-expanded") == "false"
            assert await pg.evaluate("() => document.activeElement.classList.contains('drishti-chips-toggle')")   # focus stays on it
            for theme in ("light", "terminal"):                                 # dark and light: token colours, readable
                await pg.evaluate("(t) => document.documentElement.setAttribute('data-theme', t)", theme)
                ink, ground = await pg.evaluate("() => { const s = getComputedStyle(document.querySelector('#chips-under-test .drishti-chip'));"
                                                " return [s.color, s.backgroundColor]; }")
                assert ink != ground
            assert pg.errors == [], pg.errors
            assert await pg.evaluate("window.__csp") == []
    browsers.run(go(), timeout=120)


def test_a_list_column_in_a_grid_shows_one_chip_and_a_count_and_expands_down(browsers):
    async def go():
        async with browsers.page(1200, 800, route=route) as pg:
            await opened(pg)
            await pg.evaluate("""() => {
              const el = document.createElement('drishti-grid');
              el.id = 'list-grid';
              el.style.height = '300px';
              document.body.prepend(el);
              el.options = { layout: 'wells', density: 'compact', columns: [{ field: 'ID' }, { field: 'TAGS', headerName: 'Tags', type: 'list' }],
                rows: [{ ID: 'T-1', TAGS: ['fx', 'rates', 'credit'] }, { ID: 'T-2', TAGS: '["hedge"]' }, { ID: 'T-3', TAGS: [] }] };
              return true;
            }""")
            await pg.wait_for_function("() => document.querySelectorAll('#list-grid .dg-list').length >= 2")
            await settle(pg)
            first = "#list-grid .dg-list .drishti-chips-host >> nth=0"
            assert await pg.locator("#list-grid .dg-list").first.locator(".drishti-chip").count() == 1
            badge = pg.locator("#list-grid .dg-list").first.locator(".drishti-chips-count")
            assert await badge.text_content() == "+2"
            assert await badge.get_attribute("aria-expanded") == "false"
            row_h = (await pg.locator("#list-grid .dg-list").first.bounding_box())["height"]
            await badge.click()                                                 # every value: the row grows, the rows below move down
            open_box = pg.locator("#list-grid .drishti-chips-open").first
            assert await open_box.locator(".drishti-chip").count() == 3
            assert (await open_box.bounding_box())["height"] > row_h
            assert await pg.locator(first).locator(".drishti-chips-toggle").get_attribute("aria-expanded") == "true"
            await pg.locator(first).locator(".drishti-chips-toggle").press("Enter")   # collapsed again by the keyboard
            assert await pg.locator("#list-grid .drishti-chips-open").count() == 0
            assert await pg.locator("#list-grid .dg-list").nth(1).locator(".drishti-chip").count() == 1   # JSON text reads as its list
            assert await pg.locator("#list-grid .dg-list").nth(1).locator(".drishti-chips-toggle").count() == 0
            assert pg.errors == [], pg.errors
    browsers.run(go(), timeout=120)


GROWING = """() => {
  const el = document.createElement('drishti-grid');
  el.id = 'grow-grid';
  el.style.height = '420px';
  document.body.prepend(el);
  const rows = [];
  for (let i = 0; i < 40; i++) { rows.push({ ID: 'T-' + i, TAGS: i === 1 ? ['fx', 'rates', 'credit', 'hedge', 'emea'] : i === 5 ? ['one', 'two', 'three'] : ['solo'] }); }
  el.options = { layout: 'wells', density: 'compact', columns: [{ field: 'ID' }, { field: 'TAGS', headerName: 'Tags', type: 'list' }], rows };
  return true;
}"""


async def tops(pg, r):
    return await pg.evaluate("(r) => { const e = document.querySelector('#grow-grid .dg-row .dg-cell[data-r=\\'' + r + '\\']'); "
                             "const b = e.closest('.dg-row').getBoundingClientRect(); return [b.top, b.height]; }", r)


def test_an_expanded_list_cell_makes_its_row_taller_by_mouse_and_keyboard_and_virtual_scrolling_holds(browsers):
    async def go():
        async with browsers.page(1200, 800, route=route) as pg:
            await opened(pg)
            await pg.evaluate(GROWING)
            await pg.wait_for_function("() => document.querySelectorAll('#grow-grid .dg-list').length >= 3")
            await settle(pg)
            row = lambda r: f"#grow-grid .dg-cell[data-r='{r}'][data-c='1']"
            top1, h1 = await tops(pg, 1)
            top2, h2 = await tops(pg, 2)
            assert abs(top2 - top1 - h1) < 1.5                                   # one height: the rows sit edge to edge
            await pg.click(row(1) + " .drishti-chips-toggle")                   # the mouse: the row grows, the next row moves down
            await settle(pg)
            top1b, h1b = await tops(pg, 1)
            top2b, h2b = await tops(pg, 2)
            assert h1b > h1 + 20, (h1, h1b)
            assert abs(top2b - top1b - h1b) < 1.5                                # nothing is covered: row 2 starts where row 1 ends
            assert top2b - top2 > 20 and abs(h2b - h2) < 1.5                     # the rows below keep their own height
            assert await pg.locator(row(1) + " .drishti-chip").count() == 5
            area0 = await pg.evaluate("() => parseFloat(document.querySelector('#grow-grid .dg-area').style.height)")
            assert await pg.evaluate("() => document.activeElement.classList.contains('drishti-chips-toggle')")   # focus stays on the toggle
            await pg.keyboard.press("Enter")                                    # the keyboard: collapsed, the row shrinks back
            await settle(pg)
            top2c, _ = await tops(pg, 2)
            assert abs(top2c - top2) < 1.5
            assert await pg.evaluate("() => parseFloat(document.querySelector('#grow-grid .dg-area').style.height)") < area0   # the scroll height shrinks too
            await pg.focus(row(5) + " .drishti-chips-toggle")                   # another row, by the keyboard alone
            await pg.keyboard.press(" ")
            await settle(pg)
            assert await pg.locator(row(5) + " .drishti-chip").count() == 3
            assert await pg.get_attribute(row(5) + " .drishti-chips-toggle", "aria-expanded") == "true"
            # virtual scrolling stays exact: the scroll height counts the extra, and the last row is still reachable
            row_h = (await tops(pg, 0))[1]
            assert await pg.evaluate("() => parseFloat(document.querySelector('#grow-grid .dg-area').style.height)") > row_h * 40   # counts the extra
            await pg.evaluate("() => { const s = document.querySelector('#grow-grid .dg-scroll'); s.scrollTop = s.scrollHeight; }")
            await settle(pg)
            assert await pg.locator("#grow-grid .dg-cell[data-r='39']").count() >= 1
            await pg.evaluate("() => { document.querySelector('#grow-grid .dg-scroll').scrollTop = 0; }")
            await settle(pg)
            await pg.click(row(5) + " .drishti-chips-toggle")                  # collapse again: the usual height
            await settle(pg)
            assert abs((await tops(pg, 5))[1] - row_h) < 1.5
            assert pg.errors == [], pg.errors
    browsers.run(go(), timeout=120)


def test_the_grids_column_menu_filters_a_list_column_by_contains_any_of_and_all_of(browsers):
    async def go():
        async with browsers.page(1200, 800, route=route) as pg:
            await opened(pg)
            await pg.evaluate("""() => {
              const el = document.createElement('drishti-grid');
              el.id = 'flt-grid';
              el.style.height = '320px';
              document.body.prepend(el);
              el.options = { layout: 'wells', density: 'compact', columns: [{ field: 'ID' }, { field: 'TAGS', headerName: 'Tags', type: 'list' }],
                rows: [{ ID: 'A', TAGS: ['fx', 'rates'] }, { ID: 'B', TAGS: ['hedge'] }, { ID: 'C', TAGS: ['fx', 'hedge'] }, { ID: 'D', TAGS: [] }] };
              window.__flt = el;
              return true;
            }""")
            await pg.wait_for_function("() => document.querySelectorAll('#flt-grid .dg-row').length >= 4")
            ids = lambda: pg.eval_on_selector_all("#flt-grid .dg-row .dg-cell[data-c='0']", "els => els.map(e => e.textContent.trim())")

            async def choose(action, text):
                await pg.hover("#flt-grid .dg-hcell.dg-leaf[data-c='1']")
                await pg.click("#flt-grid .dg-hmenu[data-c='1']")
                assert action in [a for a, _ in await menu_items(pg)]            # the list conditions are offered for a list column
                await pg.click(f".dg-menu [data-action='{action}']")
                box = pg.locator(".dg-menu input")
                await box.fill(text)
                await box.press("Enter")                                          # a menu's input runs on Enter
                await settle(pg)

            await choose("list-has", "fx")                                        # the menu opened by the mouse
            assert await ids() == ["A", "C"]
            await choose("list-any", "hedge, rates")
            assert await ids() == ["A", "B", "C"]
            await choose("list-all", "fx, hedge")
            assert await ids() == ["C"]
            assert pg.errors == [], pg.errors
    browsers.run(go(), timeout=120)
