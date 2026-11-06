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

"""Resizable panels and columns (static/js/drishti_resize.js), the one component every screen uses, in a real browser on a page of its
own markup under a strict CSP (script-src and style-src 'self'): no server, the per-worker browser only.

* a splitter: dragged, kept after a reload, held between its least and most, reset by a double-click;
* the keyboard on a handle: Left and Right (Shift: further), Home resets; role separator with aria-valuenow;
* CSS-grid columns (a tree's header row): the rows follow, a column never goes below its least, kept, reset;
* HTML-table columns: the body follows, fixed layout, reset;
* widths kept per mode (data-resize-mode), and markup that appears later is bound by itself;
* a phone (< 640 px): no handles and no remembered width applied."""
import json
from pathlib import Path

import pytest

from browser_harness import settled

pytestmark = pytest.mark.spread

STATIC = Path(__file__).resolve().parents[1] / "web" / "static"
ORIGIN = "http://render.test"
CSS = """
body { margin: 0; font: 13px sans-serif; background: var(--d-bg); color: var(--d-ink); }
.lay { display: grid; grid-template-columns: var(--side, 250px) minmax(0, 1fr); gap: 10px; padding: 10px; }
.lay > aside, .lay > main { border: 1px solid var(--d-border); min-height: 300px; min-width: 0; }
.row { display: grid; grid-template-columns: var(--rz-cols, minmax(0, 1fr) 60px 60px); }
.row > span { overflow: hidden; }
table { border-collapse: collapse; }
@media (max-width: 639.98px) { .lay { grid-template-columns: minmax(0, 1fr); } }
"""
HTML = """<!DOCTYPE html><html lang="en" data-theme="terminal"><head><meta charset="utf-8"><title>resize</title>
<link rel="stylesheet" href="/static/css/drishti_tokens.css"><link rel="stylesheet" href="/static/css/drishti_resize.css">
<link rel="stylesheet" href="/rz.css"></head><body>
<div class="lay" id="lay" data-resize-mode="historical">
  <aside id="side"><div data-resize="panel" data-resize-key="t.side" data-resize-var="--side" data-resize-min="150" data-resize-max="500"
       aria-label="Resize the side panel"></div>side</aside>
  <main>
    <div class="tree" id="tree">
      <div class="row hdr" id="hdr" data-resize="columns" data-resize-key="t.tree" data-resize-flex="0" data-resize-scope=".tree">
        <span>Name</span><span>VaR</span><span>ES</span></div>
      <div class="row body" id="r1"><span>Bank</span><span>1.2m</span><span>1.5m</span></div>
    </div>
    <table id="tbl" data-resize="columns" data-resize-key="t.tbl"><thead><tr><th>Trade</th><th>Product</th><th>Notional</th></tr></thead>
      <tbody><tr><td>T1</td><td>SWAP</td><td>10,000,000</td></tr></tbody></table>
    <div id="later"></div>
  </main>
</div>
<script src="/static/js/drishti_resize.js"></script></body></html>"""
HEADERS = {"content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self'"}


async def route(r):
    path = r.request.url[len(ORIGIN):].split("?")[0]
    if path == "/rz.html":
        return await r.fulfill(status=200, body=HTML, headers={**HEADERS, "content-type": "text/html; charset=utf-8"})
    if path == "/rz.css":
        return await r.fulfill(status=200, body=CSS, headers={"content-type": "text/css"})
    f = STATIC / path[len("/static/"):] if path.startswith("/static/") else None
    if f and f.is_file():
        kind = "text/css" if f.suffix == ".css" else "application/javascript"
        return await r.fulfill(status=200, body=f.read_bytes(), headers={"content-type": kind})
    return await r.fulfill(status=404, body="")


async def opened(pg):
    await pg.goto(ORIGIN + "/rz.html")
    await pg.wait_for_function("() => window.Drishti && window.Drishti.resize && document.querySelector('#hdr .rz-col') "
                               "&& document.querySelector('#tbl th .rz-col')")
    await settled(pg, animations=False)


async def width(pg, sel):
    return await pg.evaluate("(s) => document.querySelector(s).getBoundingClientRect().width", sel)


async def drag(pg, sel, dx):
    b = await pg.locator(sel).bounding_box()
    x, y = b["x"] + b["width"] / 2, b["y"] + b["height"] / 2
    await pg.mouse.move(x, y)
    await pg.mouse.down()
    await pg.mouse.move(x + dx, y, steps=6)
    await pg.mouse.up()


def run(browsers, body, width=1280):
    async def go():
        async with browsers.page(width, 800, route=route) as pg:
            await opened(pg)
            await body(pg)
            assert pg.errors == [], pg.errors
            assert await pg.evaluate("window.__csp") == []            # nothing inline: the strict CSP holds
    browsers.run(go(), timeout=120)


def test_a_splitter_drags_is_kept_held_in_its_range_and_reset(browsers):
    async def body(pg):
        handle = "#lay > [data-resize='panel']"
        assert await pg.get_attribute(handle, "role") == "separator"
        assert await width(pg, "#side") == pytest.approx(250, abs=1)
        await drag(pg, handle, 100)
        assert await width(pg, "#side") == pytest.approx(350, abs=2)
        assert json.loads(await pg.evaluate("localStorage.getItem('drishti.resize.t.side@historical')")) == pytest.approx(350, abs=2)
        await pg.reload()
        await opened(pg)
        assert await width(pg, "#side") == pytest.approx(350, abs=2)          # kept for this person
        await drag(pg, handle, 1000)
        assert await width(pg, "#side") == pytest.approx(500, abs=1)          # its most
        await drag(pg, handle, -1000)
        assert await width(pg, "#side") == pytest.approx(150, abs=1)          # its least
        await pg.dblclick(handle)
        assert await width(pg, "#side") == pytest.approx(250, abs=1)          # reset: the screen's own width, the memory gone
        assert await pg.evaluate("localStorage.getItem('drishti.resize.t.side@historical')") is None
    run(browsers, body)


def test_the_keyboard_moves_a_handle_and_home_resets_it(browsers):
    async def body(pg):
        handle = "#lay > [data-resize='panel']"
        await pg.focus(handle)
        await pg.keyboard.press("ArrowRight")
        assert await width(pg, "#side") == pytest.approx(266, abs=1)
        await pg.keyboard.press("Shift+ArrowRight")
        assert await width(pg, "#side") == pytest.approx(330, abs=1)
        await pg.keyboard.press("ArrowLeft")
        assert await width(pg, "#side") == pytest.approx(314, abs=1)
        assert int(await pg.get_attribute(handle, "aria-valuenow")) == pytest.approx(314, abs=1)
        await pg.keyboard.press("Home")
        assert await width(pg, "#side") == pytest.approx(250, abs=1)
        # a column handle too
        await pg.focus("#tbl th:nth-child(2) .rz-col")
        before = await width(pg, "#tbl td:nth-child(2)")
        await pg.keyboard.press("ArrowRight")
        assert await width(pg, "#tbl td:nth-child(2)") == pytest.approx(before + 16, abs=2)
    run(browsers, body)


def test_css_grid_columns_move_the_rows_never_vanish_and_are_kept(browsers):
    async def body(pg):
        assert await width(pg, "#r1 > :nth-child(2)") == pytest.approx(60, abs=1)
        await drag(pg, "#hdr > :nth-child(2) > .rz-col", 40)
        assert await width(pg, "#r1 > :nth-child(2)") == pytest.approx(100, abs=2)      # the body row follows the header
        assert "100px" in await pg.evaluate("document.getElementById('tree').style.getPropertyValue('--rz-cols')")
        await drag(pg, "#hdr > :nth-child(3) > .rz-col", -500)
        assert await width(pg, "#r1 > :nth-child(3)") == pytest.approx(40, abs=1)       # never below its least
        await pg.reload()
        await opened(pg)
        assert await width(pg, "#r1 > :nth-child(2)") == pytest.approx(100, abs=2)
        assert await width(pg, "#r1 > :nth-child(3)") == pytest.approx(40, abs=1)
        await pg.dblclick("#hdr > :nth-child(2) > .rz-col")
        assert await width(pg, "#r1 > :nth-child(2)") == pytest.approx(60, abs=1)
    run(browsers, body)


def test_html_table_columns_move_the_body_and_reset(browsers):
    async def body(pg):
        before = await width(pg, "#tbl td:nth-child(1)")
        await drag(pg, "#tbl th:nth-child(1) .rz-col", 80)
        assert await width(pg, "#tbl td:nth-child(1)") == pytest.approx(before + 80, abs=2)
        assert await pg.evaluate("document.getElementById('tbl').classList.contains('rz-fixed')")
        await pg.click("#tbl th:nth-child(1) .rz-col")                               # a click on a handle is not a click on its header
        await pg.dblclick("#tbl th:nth-child(1) .rz-col")
        assert not await pg.evaluate("document.getElementById('tbl').classList.contains('rz-fixed')")
        assert await width(pg, "#tbl td:nth-child(1)") == pytest.approx(before, abs=2)
    run(browsers, body)


def test_widths_are_kept_per_mode_and_later_markup_is_bound(browsers):
    async def body(pg):
        handle = "#lay > [data-resize='panel']"
        await drag(pg, handle, 60)
        assert await width(pg, "#side") == pytest.approx(310, abs=2)
        await pg.evaluate("document.getElementById('lay').setAttribute('data-resize-mode', 'parametric')")
        await pg.wait_for_function("() => document.getElementById('side').getBoundingClientRect().width < 252")
        await drag(pg, handle, -50)
        assert await width(pg, "#side") == pytest.approx(200, abs=2)
        await pg.evaluate("document.getElementById('lay').setAttribute('data-resize-mode', 'historical')")
        await pg.wait_for_function("() => Math.abs(document.getElementById('side').getBoundingClientRect().width - 310) < 3")
        await pg.evaluate("""() => { document.getElementById('later').innerHTML = '<table id="t2" data-resize="columns" data-resize-key="t.t2">'
                             + '<thead><tr><th>A</th><th>B</th></tr></thead><tbody><tr><td>1</td><td>2</td></tr></tbody></table>'; }""")
        await pg.wait_for_function("() => document.querySelectorAll('#t2 th .rz-col').length === 2")
    run(browsers, body)


def test_on_a_phone_there_are_no_handles_and_no_kept_width(browsers):
    async def go():
        async with browsers.page(390, 800, route=route) as pg:
            await pg.goto(ORIGIN + "/rz.html")
            await pg.evaluate("localStorage.setItem('drishti.resize.t.tree@historical', JSON.stringify([200, 120, 120]))")
            await pg.reload()
            await pg.wait_for_function("() => document.querySelector('#hdr .rz-col')")
            await settled(pg, animations=False)
            assert await pg.evaluate("[...document.querySelectorAll('.rz-handle')].every((h) => getComputedStyle(h).display === 'none')")
            assert await pg.evaluate("document.getElementById('tree').style.getPropertyValue('--rz-cols')") == ""
            assert await pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
            assert pg.errors == []
    browsers.run(go(), timeout=120)


def test_light_and_dark_themes_draw_the_line_from_the_theme(browsers):
    async def body(pg):
        handle = "#lay > [data-resize='panel']"
        await pg.emulate_media(reduced_motion="reduce")                               # no colour transition to wait for
        dark = await pg.evaluate("(s) => getComputedStyle(document.querySelector(s), '::after').backgroundColor", handle)
        await pg.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
        light = await pg.evaluate("(s) => getComputedStyle(document.querySelector(s), '::after').backgroundColor", handle)
        assert dark != light                                                          # the line of each theme
    run(browsers, body)
