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
"""drishti-designer in a real browser, against its static fixture harness (static/elements/drishti-designer/drishti_harness.html): no
Drishti server, the files served under a strict CSP (nothing inline), the per-worker browser only. The kit is tested here once and
deeply; the designers built on it test only their wiring.

* the studio layout (g7 A): the palette (a click, a drag onto a cell, a disabled entry says why), the canvas (a drag by the heading
  snaps to the grid, the corner resizes, the keys move and size, Shift+click selects more, align, group, undo and redo), the inspector
  (an edit is one step; a conditional field), the outline selecting on the canvas, the YAML following both ways (an edit read back, a
  broken line said at its line while the design keeps its last good version, the selected item's lines highlighted), the header's
  actions and Propose for review;
* the side-by-side layout (B: the inspector a card by the selection) and the terminal (C: Ctrl+K, a typed command, the function keys);
* a flow canvas (two columns of panels: Alt+arrows, a drag to the other column, the edges);
* a phone's width."""
from pathlib import Path

import pytest

pytestmark = pytest.mark.spread

STATIC = Path(__file__).resolve().parents[1] / "web" / "static"
ORIGIN = "http://render.test"
PAGE = "/static/elements/drishti-designer/drishti_harness.html"
HEADERS = {"content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self'"}
TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "application/javascript", ".json": "application/json"}


async def route(r):
    path = r.request.url[len(ORIGIN):].split("?")[0]
    f = STATIC / path[len("/static/"):] if path.startswith("/static/") else None
    if f and f.is_file():
        headers = {"content-type": TYPES.get(f.suffix, "application/octet-stream")}
        if f.suffix == ".html":
            headers.update(HEADERS)
        return await r.fulfill(status=200, body=f.read_bytes(), headers=headers)
    return await r.fulfill(status=404, body="")


READY = "() => window.__harness && window.__harness.ready && document.querySelector('#hx-canvas [data-dd-id]') && document.querySelector('[data-yaml-body] .CodeMirror')"


def run(browsers, body, query="", width=1500, height=1000):
    async def go():
        async with browsers.page(width, height, route=route) as pg:
            await pg.goto(ORIGIN + PAGE + ("?" + query if query else ""))
            await pg.wait_for_function(READY)
            await body(pg)
            assert pg.errors == [], pg.errors
            assert await pg.evaluate("window.__csp") == []
    browsers.run(go(), timeout=120)


async def h(pg, expr):
    return await pg.evaluate("() => " + expr)


async def at(pg, vid):
    return await h(pg, "(() => { const i = window.__harness.model.get('%s'); return i ? [i.x, i.y, i.w, i.h] : null; })()" % vid)


async def yaml_text(pg):
    return await h(pg, "window.__harness.yaml.getText()")


async def type_yaml(pg, find, replace):
    """An edit of the YAML as a person types it (CodeMirror's +input origin), then the pause that reads it."""
    await pg.evaluate("""([a, b]) => { const cm = window.__harness.yaml.editor.cm, t = cm.getValue(), i = t.indexOf(a);
      cm.replaceRange(b, cm.posFromIndex(i), cm.posFromIndex(i + a.length), '+input'); }""", [find, replace])
    await pg.wait_for_timeout(700)  # wait-ok: the YamlPane reads a typed edit only after its debounce (no settled signal for it)


def test_the_studio_layout_palette_canvas_inspector_outline_yaml_and_four_eyes(browsers):
    async def body(pg):
        assert await h(pg, "document.querySelector('[data-dd-shell]').getAttribute('data-dd-layout')") == "studio"
        assert await pg.locator("[data-dd-tab]").count() == 3
        # the palette: a disabled entry says why; a click adds; a drag lands on the cell under the pointer
        await pg.click("[data-dd-tab='palette']")
        await pg.click("#hx-palette [data-dd-entry='image']", force=True)
        await pg.wait_for_function("() => (document.querySelector('[data-drishti-live=polite]') || {}).textContent.indexOf('file store') >= 0")
        await pg.fill("#hx-palette [data-dd-palette-search]", "kpi")
        assert await pg.locator("#hx-palette [data-dd-entry]").count() == 1
        await pg.fill("#hx-palette [data-dd-palette-search]", "")
        await pg.click("#hx-palette [data-dd-entry='card']")
        assert await at(pg, "v11") is not None and await h(pg, "window.__harness.selection.primary()") == "v11"
        box = await pg.locator("#hx-canvas").bounding_box()
        src = await pg.locator("#hx-palette [data-dd-entry='line']").bounding_box()
        await pg.mouse.move(src["x"] + 20, src["y"] + 8)
        await pg.mouse.down()
        await pg.mouse.move(src["x"] + 60, src["y"] + 30, steps=4)
        await pg.mouse.move(box["x"] + box["width"] * 0.55, box["y"] + 15, steps=6)
        await pg.mouse.up()
        assert (await at(pg, "v12"))[0] == 6, "dropped on column 7"
        assert any(e["name"] == "drop" for e in await h(pg, "window.__harness.events"))
        # a drag by the heading snaps to whole columns; the corner resizes
        before = await at(pg, "v2")
        head = await pg.locator("#hx-canvas [data-dd-id='v2'] .dd-item-head").bounding_box()
        step = (box["width"] - 16 + 8) / 12
        await pg.mouse.move(head["x"] + 10, head["y"] + 5)
        await pg.mouse.down()
        await pg.mouse.move(head["x"] + 10 - step * 2.2, head["y"] + 5, steps=6)
        await pg.mouse.up()
        assert (await at(pg, "v2"))[0] == before[0] - 2
        corner = await pg.locator("#hx-canvas [data-dd-id='v2'] [data-dd-edge='se']").bounding_box()
        await pg.mouse.move(corner["x"] + 5, corner["y"] + 5)
        await pg.mouse.down()
        await pg.mouse.move(corner["x"] + 5 + step, corner["y"] + 5 + 42, steps=5)
        await pg.mouse.up()
        assert (await at(pg, "v2"))[2:] == [before[2] + 1, before[3] + 1]
        # the keys: arrows move, Shift+arrows size, Ctrl+Z takes back, Ctrl+Y does again
        await pg.click("#hx-canvas [data-dd-id='v1'] .dd-item-body")
        await pg.keyboard.press("ArrowRight")
        await pg.keyboard.press("Shift+ArrowRight")
        assert await at(pg, "v1") == [1, 0, 4, 2]
        await pg.keyboard.press("Control+z")
        await pg.keyboard.press("Control+z")
        assert await at(pg, "v1") == [0, 0, 3, 2]
        await pg.keyboard.press("Control+y")
        assert await at(pg, "v1") == [1, 0, 3, 2]
        # Shift+click selects more; align and group from the toolbar; a group moves together
        await pg.click("#hx-canvas [data-dd-id='v3'] .dd-item-body", modifiers=["Shift"])
        assert await h(pg, "window.__harness.selection.list()") == ["v1", "v3"]
        await pg.click("[data-hx-align-right]")
        assert (await at(pg, "v1"))[0] + 3 == (await at(pg, "v3"))[0] + 12
        await pg.click("[data-hx-group]")
        y3 = (await at(pg, "v3"))[1]
        await pg.focus("#hx-canvas [data-dd-id='v3']")
        await pg.keyboard.press("ArrowDown")
        assert (await at(pg, "v3"))[1] == y3 + 1 and await h(pg, "window.__harness.model.get('v1').group") == await h(pg, "window.__harness.model.get('v3').group")
        # the inspector: an edit is one step and shows in the YAML; a conditional field appears
        await pg.click("#hx-canvas [data-dd-id='v1'] .dd-item-body")
        title = pg.locator("#hx-inspector [data-path='title'] input")
        await title.fill("Gross notional")
        await title.press("Tab")
        assert "title: Gross notional" in await yaml_text(pg)
        assert await pg.text_content("#hx-canvas [data-dd-id='v1'] .dd-item-head") == "Gross notional"
        assert await pg.locator("#hx-inspector [data-path='target']").is_hidden()
        await pg.locator("#hx-inspector [data-path='format']").get_by_label("pct2").check()
        assert await pg.locator("#hx-inspector [data-path='target']").is_visible()
        # the selected item's lines are highlighted in the YAML
        hl = await h(pg, "document.querySelector('[data-yaml-body] .di-code').getAttribute('data-hl')")
        lines = (await yaml_text(pg)).split("\n")
        assert "id: v1" in lines[int(hl.split("-")[0]) - 1]
        # the outline selects on the canvas
        await pg.click("[data-dd-tab='outline']")
        await pg.click("#hx-outline [data-node='v3'] .dt-oname")
        assert await h(pg, "window.__harness.selection.primary()") == "v3"
        assert await h(pg, "document.querySelector('#hx-canvas [data-dd-id=v3]').classList.contains('dd-selected')")
        # the YAML read back: the canvas follows; a broken line is said and the design keeps what it had
        await type_yaml(pg, "title: By desk", "title: Desks")
        assert await pg.text_content("#hx-canvas [data-dd-id='v2'] .dd-item-head") == "Desks"
        await type_yaml(pg, "    type: bar", "    type bar")
        assert "needs a colon" in await pg.text_content("[data-yaml-body] .dd-yaml-status")
        assert await pg.text_content("#hx-canvas [data-dd-id='v2'] .dd-item-head") == "Desks"
        await pg.click("[data-mode='visual']")
        assert "waits until the YAML reads" in await pg.text_content("[data-msg]")
        await type_yaml(pg, "    type bar", "    type: bar")
        assert await pg.text_content("[data-yaml-body] .dd-yaml-status") == ""
        await pg.click("[data-mode='yaml']")
        assert await pg.locator("[data-visual]").is_hidden()
        await pg.click("[data-mode='both']")
        assert await h(pg, "window.__harness.modes") == ["yaml", "both"]
        # the header: History and Impact dialogs; Save then Propose for review (four eyes)
        await pg.click("[data-act='history']")
        assert "approved" in await pg.text_content("[data-history]")
        await pg.click("[data-dialog-close]")
        assert await pg.get_attribute("[data-act='propose']", "title") == "Save your changes first"
        await pg.click("[data-act='save']")
        assert "Draft" in await pg.text_content("[data-status]") and "approved v2" in await pg.text_content("[data-versions]")
        await pg.click("[data-act='propose']")
        await pg.fill("[data-propose-note]", "Look at the card")
        await pg.click("[data-propose-go]")
        await pg.wait_for_function("() => window.__harness.proposals.length === 1")
        assert "Proposed" in await pg.text_content("[data-status]")
        assert await pg.locator("[data-dialog]").evaluate("d => d.open") is False
    run(browsers, body)


def test_the_side_by_side_and_terminal_layouts(browsers):
    async def side(pg):
        assert await h(pg, "getComputedStyle(document.querySelector('.dd-main')).display") == "grid"
        assert await pg.locator("[data-dd-panel='palette']").is_visible() and await pg.locator("[data-dd-panel='outline']").is_visible()
        await pg.click("#hx-canvas [data-dd-id='v2'] .dd-item-body")
        card = await pg.locator(".dd-insp-side").bounding_box()
        item = await pg.locator("#hx-canvas [data-dd-id='v2']").bounding_box()
        assert abs(card["x"] - (item["x"] + item["width"] + 8)) < 2 or card["x"] + card["width"] <= item["x"], "the inspector is a card by the selection"
        await pg.select_option("[data-dd-layout-pick]", "studio")
        assert await h(pg, "window.__harness.layouts") == ["studio"]
    run(browsers, side, "layout=side-by-side")

    async def term(pg):
        assert await pg.locator(".dd-cmdbar").is_visible() and await pg.locator(".dd-fkeys").is_visible()
        await pg.click("#hx-canvas [data-dd-id='v1'] .dd-item-body")
        await pg.keyboard.press("Control+k")
        assert await h(pg, "document.activeElement.hasAttribute('data-dd-cmd')")
        await pg.keyboard.type("add card")
        await pg.keyboard.press("Enter")
        assert await at(pg, "v11") is not None
        await pg.fill("[data-dd-cmd]", "select v3")
        await pg.press("[data-dd-cmd]", "Enter")
        assert await h(pg, "window.__harness.selection.primary()") == "v3"
        await pg.fill("[data-dd-cmd]", "frobnicate")
        await pg.press("[data-dd-cmd]", "Enter")
        assert "No command" in await pg.text_content("[data-msg]")
        await pg.focus("#hx-canvas [data-dd-id='v3']")
        await pg.keyboard.press("F3")
        assert "Valid" in await pg.text_content("[data-msg]")
        await pg.keyboard.press("F4")
        assert await h(pg, "document.querySelector('[data-dd-shell]').getAttribute('data-dd-mode')") == "yaml"
        await pg.keyboard.press("F4")
        await pg.keyboard.press("F7")
        assert await pg.locator("[data-history]").is_visible()
    run(browsers, term, "layout=terminal&theme=light")


def test_a_flow_canvas_of_two_columns(browsers):
    async def body(pg):
        order = "window.__harness.flowModel.inArea('%s').map((i) => i.id)"
        await pg.click("#hx-flow [data-panel='p1'] .hx-ph")
        await pg.keyboard.press("Alt+ArrowDown")
        assert await h(pg, order % "main") == ["p2", "p1", "p3"]
        await pg.keyboard.press("ArrowDown")
        assert await h(pg, "window.__harness.flowSelection.primary()") == "p3"
        await pg.keyboard.press("Shift+ArrowLeft")
        assert await h(pg, "window.__harness.flowModel.get('p3').span") == 5
        await pg.keyboard.press("Alt+ArrowRight")
        assert await h(pg, order % "right") == ["p3", "s1"]
        src = await pg.locator("#hx-flow [data-panel='p2'] .hx-ph").bounding_box()
        dst = await pg.locator("#hx-flow [data-panel='s1']").bounding_box()
        await pg.mouse.move(src["x"] + 10, src["y"] + 5)
        await pg.mouse.down()
        await pg.mouse.move(dst["x"] + 20, dst["y"] + dst["height"] - 3, steps=8)
        assert await pg.locator(".dd-drop-line").count() == 1
        await pg.mouse.up()
        assert await h(pg, order % "right") == ["p3", "s1", "p2"]
        await pg.focus("#hx-flow [data-panel='p2']")
        await pg.keyboard.press("Control+z")
        assert await h(pg, order % "right") == ["p3", "s1"]
    run(browsers, body)


def test_a_phone_width(browsers):
    async def body(pg):
        assert await h(pg, "document.documentElement.scrollWidth <= window.innerWidth + 1")
        assert await h(pg, "getComputedStyle(document.querySelector('.dd-shell-grid')).flexDirection") == "column"
        await pg.click("#hx-canvas [data-dd-id='v1'] .dd-item-body")
        await pg.keyboard.press("ArrowDown")
        assert await at(pg, "v1") == [0, 1, 3, 2]
    run(browsers, body, width=390, height=844)


def test_a_pages_code_area_becomes_a_code_editor_and_keeps_its_value(browsers):
    async def body(pg):
        await pg.wait_for_selector("#hx-area-form + .di-code-host .CodeMirror, #hx-area-form .CodeMirror")
        assert await h(pg, "document.getElementById('hx-area').hidden")
        assert await h(pg, "document.getElementById('hx-area').value") == "title: A page's own text area"
        await pg.evaluate("() => { window.__inputs = 0; document.getElementById('hx-area').addEventListener('input', () => window.__inputs++); }")
        await pg.evaluate("() => { const cm = document.querySelector('#hx-area-form .CodeMirror').CodeMirror; cm.replaceRange('x: 1\\n', {line: 1, ch: 0}, null, '+input'); }")
        assert await h(pg, "window.__inputs") == 1                                        # the page's script hears the edit
        assert "x: 1" in await h(pg, "new FormData(document.getElementById('hx-area-form')).get('body')")    # and its form submits it
        await pg.evaluate("() => { document.getElementById('hx-area').value = 'set: by the page'; }")
        assert await h(pg, "document.querySelector('#hx-area-form .CodeMirror').CodeMirror.getValue()") == "set: by the page"
    run(browsers, body)
