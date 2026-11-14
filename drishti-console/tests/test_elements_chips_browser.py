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
shows every value growing down over the rows below. Served without a server under the strict CSP (the grid harness page)."""
import pytest

from test_elements_grid_browser import opened, route, settle

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
            await badge.click()                                                 # every value, growing down over the rows below
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
