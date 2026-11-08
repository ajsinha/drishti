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

"""drishti-inputs, drishti-data and the grid's cell editing in a real browser, against their static fixture harness
(static/elements/drishti-inputs/drishti_harness.html): no Drishti server, the files served from the folder under a strict CSP (nothing
inline), the per-worker browser only. The components are tested here once and deeply; screens test only their wiring.

* the pickers: a business date typed (T-1) and refused (a holiday, in words), the calendar by the keyboard and its shortcuts, the range
  picker's presets and two clicked ends, the tree's lazy levels (loading, an error and Retry), partial checks and search, the
  multi-select's search, chips, select-all and its limit, the combobox's server type-ahead (aborted, an error in words);
* the form: every problem at submit with a summary, conditional fields, repeatable groups, the server's check, the four-eyes proposal;
* the filter bar driving an editable grid, and the editing: Enter, F2 and typing, each editor (number, select, date through the picker,
  checkbox, text), validation, Tab and Escape, undo and redo, a paste from Excel, Delete, a refusal by the server, four eyes;
* the code editor over the vendored CodeMirror: completion, lint marks and the problems list, read-only, Python, the diff view;
* the live client (a stand-in feed: messages, a drop, resume from the last id, a silent feed) and the Arrow client (Flight over HTTP and
  Arrow over HTTP from the fixture the test serves)."""
import base64
import json
import shutil
import subprocess
from pathlib import Path

import pytest

pytestmark = pytest.mark.spread

STATIC = Path(__file__).resolve().parents[1] / "web" / "static"
ORIGIN = "http://render.test"
PAGE = "/static/elements/drishti-inputs/drishti_harness.html"
HEADERS = {"content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self'"}
TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "application/javascript", ".json": "application/json"}
ARROW = "application/vnd.apache.arrow.stream"
ARROW_JS = ("const A = require(%s); const n = 500; const ids = [], desks = [], mtm = new Float64Array(n), days = [];"
            " for (let i = 0; i < n; i++) { ids.push('A' + i); desks.push(['Rates', 'FX', 'Credit'][i %% 3]); mtm[i] = (i %% 11) * 1000 - 5000;"
            " days.push(new Date(Date.UTC(2026, 10, 1 + (i %% 6)))); }"
            " const t = A.tableFromArrays({ ID: ids, DESK: A.vectorFromArray(desks, new A.Dictionary(new A.Utf8(), new A.Int32())), MTM: mtm,"
            " DAY: A.vectorFromArray(days, new A.DateDay()) });"
            " process.stdout.write(Buffer.from(A.tableToIPC(t, 'stream')).toString('base64'));")
_arrow = {}


def arrow_bytes() -> bytes:
    if "b" not in _arrow:
        node = shutil.which("node")
        if not node:
            pytest.skip("needs Node.js to write the Arrow fixture")
        js = ARROW_JS % json.dumps(str(STATIC / "vendor" / "apache-arrow" / "21.1.0" / "Arrow.es2015.min.js"))
        _arrow["b"] = base64.b64decode(subprocess.run([node, "-e", js], capture_output=True, text=True, timeout=60, check=True).stdout)
    return _arrow["b"]


async def route(r):
    url = r.request.url[len(ORIGIN):]
    path = url.split("?")[0]
    if path == "/hx-fixture/capabilities":
        flight = getattr(route, "flight", False)
        return await r.fulfill(status=200, body=json.dumps({"flightHttp": {"doGet": "/hx-fixture/do-get"}} if flight else {}), headers={"content-type": "application/json"})
    if path in ("/hx-fixture/trades.arrow", "/hx-fixture/do-get"):
        route.asked.append(r.request.method + " " + path)
        return await r.fulfill(status=200, body=arrow_bytes(), headers={"content-type": ARROW})
    f = STATIC / path[len("/static/"):] if path.startswith("/static/") else None
    if f and f.is_file():
        headers = {"content-type": TYPES.get(f.suffix, "application/octet-stream")}
        if f.suffix == ".html":
            headers.update(HEADERS)
        return await r.fulfill(status=200, body=f.read_bytes(), headers=headers)
    return await r.fulfill(status=404, body="")


route.asked = []
READY = "() => window.__harness && window.__harness.ready && document.querySelector('.dg-row') && document.querySelector('#hx-code .CodeMirror')"


def run(browsers, body, query="", width=1440, height=1000):
    async def go():
        async with browsers.page(width, height, route=route) as pg:
            await pg.goto(ORIGIN + PAGE + ("?" + query if query else ""))
            await pg.wait_for_function(READY)
            await body(pg)
            assert pg.errors == [], pg.errors
            assert await pg.evaluate("window.__csp") == []            # nothing inline: the strict CSP holds (CodeMirror and Arrow too)
    browsers.run(go(), timeout=120)


async def h(pg, expr):
    return await pg.evaluate("() => " + expr)


async def frames(pg, n=2):
    await pg.evaluate("(n) => new Promise((r) => { const f = (k) => (k ? requestAnimationFrame(() => f(k - 1)) : r()); f(n); })", n)


def test_the_pickers_dates_ranges_tree_multiselect_and_combobox(browsers):
    async def body(pg):
        date = "#hx-date input.di-field"
        await pg.fill(date, "T-1")
        await pg.press(date, "Enter")
        assert await h(pg, "document.getElementById('hx-date').value") == "2026-11-05"
        assert await pg.input_value(date) == "5 Nov 2026"
        await pg.fill(date, "26 Nov 2026")
        await pg.press(date, "Enter")
        assert "Thanksgiving" in await pg.text_content("#hx-date .di-msg")         # refused, in words, with the day before
        assert await pg.get_attribute(date, "aria-invalid") == "true"
        await pg.click("#hx-date .di-icon-btn")
        await pg.wait_for_selector(".di-date-pop [data-day]")
        assert await pg.locator(".di-date-pop .di-holiday[title='Thanksgiving']").count() == 1
        await pg.keyboard.press("ArrowLeft")                                         # from 5 Nov to 4 Nov
        await pg.keyboard.press("Enter")
        assert await h(pg, "document.getElementById('hx-date').value") == "2026-11-04"
        await pg.click("#hx-date .di-icon-btn")
        await pg.click(".di-date-pop [data-shortcut='ME']")
        assert await h(pg, "document.getElementById('hx-date').value") == "2026-10-30"
        # the range: a preset, then two clicked ends
        assert "Month to date" in await pg.text_content("#hx-range .di-range-btn")
        await pg.click("#hx-range .di-range-btn")
        await pg.click(".di-range-pop [data-preset='ytd']")
        await pg.click(".di-range-pop [data-act='apply']")
        assert await h(pg, "window.__harness.range.getValue()") == {"from": "2026-01-02", "to": "2026-11-06", "preset": "ytd"}
        await pg.click("#hx-range .di-range-btn")
        await pg.click(".di-range-pop [data-preset='mtd']")
        await pg.click(".di-range-pop [data-day='2026-11-12']")
        await pg.click(".di-range-pop [data-day='2026-11-03']")
        assert await pg.locator(".di-range-pop .di-in-range").count() == 8
        await pg.click(".di-range-pop [data-act='apply']")
        assert await h(pg, "window.__harness.range.getValue()") == {"from": "2026-11-03", "to": "2026-11-12", "preset": None}
        # the tree: lazy levels, an error and Retry, partial checks, search
        await pg.click("#hx-tree .di-tree-btn")
        await pg.click(".di-tree-pop .di-node[data-id='Rates'] [data-twist]")
        await pg.click(".di-tree-pop .di-node[data-id='Rates/Swaps'] [data-twist]")
        await pg.wait_for_selector(".di-tree-pop .di-node[data-id='Rates/Swaps/M. Chen']")
        await pg.click(".di-tree-pop .di-node[data-id='Rates/Swaps/M. Chen']")
        assert await pg.get_attribute(".di-tree-pop .di-node[data-id='Rates']", "aria-checked") == "mixed"
        await pg.click(".di-tree-pop .di-node[data-id='FX'] [data-twist]")
        await pg.click(".di-tree-pop .di-node[data-id='FX/EM Forwards'] [data-twist]")
        await pg.wait_for_selector(".di-tree-pop .di-error-row")
        assert "the server is busy" in await pg.text_content(".di-tree-pop .di-error-row")
        await pg.click(".di-tree-pop [data-retry]")
        await pg.wait_for_selector(".di-tree-pop .di-node[data-id='FX/EM Forwards/A. Rao']")
        await pg.fill(".di-tree-pop .di-search", "duval")
        await pg.wait_for_function("() => document.querySelectorAll('.di-tree-pop .di-node.di-match').length === 2")
        await pg.keyboard.press("ArrowDown")                                         # into the tree, then Space checks
        await pg.keyboard.press("End")
        await pg.keyboard.press(" ")
        assert len(await h(pg, "window.__harness.tree.getValue()")) == 2
        await pg.keyboard.press("Escape")
        assert "M. Chen" in await pg.text_content("#hx-tree .di-tree-btn")
        # the multi-select: search, chips, select-all, the limit, Backspace
        await pg.click("#hx-ms .di-ms-control")
        await pg.keyboard.type("n")
        assert await pg.locator(".di-ms-pop .di-option").count() >= 5
        assert "Select the" in await pg.text_content(".di-ms-pop [data-act='all']")
        await pg.click(".di-ms-pop [data-act='all']")
        value = await h(pg, "window.__harness.ms.getValue()")
        assert len(value) > 5 and value[:2] == ["USD", "EUR"] and all("N" in v for v in value[2:])   # the matching ones
        await pg.fill("#hx-ms .di-inline-field", "")
        await pg.click(".di-ms-pop [data-act='all']")
        assert len(await h(pg, "window.__harness.ms.getValue()")) == 12                 # every one, up to the limit of 12
        await pg.keyboard.press("Backspace")
        assert len(await h(pg, "window.__harness.ms.getValue()")) == 11
        assert await pg.locator("#hx-ms .di-chip-more").count() == 1
        await pg.keyboard.press("Escape")
        # the combobox: type-ahead from the stand-in server
        await pg.click("#hx-combo input")
        await pg.keyboard.type("gran", delay=15)
        await pg.wait_for_selector(".di-combo-pop .di-option")
        assert await h(pg, "window.__harness.asked") <= 2                            # debounced
        await pg.keyboard.press("ArrowDown")
        await pg.keyboard.press("Enter")
        assert (await h(pg, "window.__harness.combo.getValue()"))["label"].startswith("Granite")
        await pg.fill("#hx-combo input", "zz")
        await pg.wait_for_function("() => /failed/.test(document.querySelector('.di-combo-status').textContent)")
        await pg.keyboard.press("Escape")
        await pg.keyboard.press("Escape")
        assert await h(pg, "window.__harness.combo.getValue()") is None
        dark = await pg.evaluate("() => getComputedStyle(document.querySelector('#hx-ms .di-control')).backgroundColor")
        await pg.click("#hx-theme")
        assert dark != await pg.evaluate("() => getComputedStyle(document.querySelector('#hx-ms .di-control')).backgroundColor")
    run(browsers, body)


def test_the_single_choice_select_by_keyboard_type_ahead_search_and_aria(browsers):
    async def body(pg):
        ctl = "#hx-horizon .di-select-control"
        # from markup: the <option> children became the options, `selected` the value, the disabled one kept and refused
        assert await h(pg, "document.getElementById('hx-horizon').value") == "10d:sqrt10"
        assert await pg.text_content(ctl) == "10-day (√10)▾"
        assert await pg.get_attribute(ctl, "role") == "combobox" and await pg.get_attribute(ctl, "aria-expanded") == "false"
        assert await pg.get_attribute(ctl, "aria-haspopup") == "listbox" and await pg.get_attribute(ctl, "aria-label") == "Horizon"
        await pg.focus(ctl)
        await pg.keyboard.press("ArrowDown")                                         # opens on the value
        await pg.wait_for_selector(".di-select-pop [role=listbox]")
        assert await pg.get_attribute(ctl, "aria-expanded") == "true"
        lb = await pg.get_attribute(ctl, "aria-controls")
        assert await h(pg, f"document.getElementById('{lb}').getAttribute('role')") == "listbox"
        active = await pg.get_attribute(ctl, "aria-activedescendant")
        assert await pg.text_content(f"#{active}") == "10-day (√10)"
        assert await pg.locator(".di-select-pop .di-select-search").count() == 0       # a short list: no search field, the control keeps the focus
        await pg.keyboard.press("End")                                               # the last: disabled, Enter refuses it
        assert await pg.get_attribute(f"#{await pg.get_attribute(ctl, 'aria-activedescendant')}", "aria-disabled") == "true"
        await pg.keyboard.press("Enter")
        assert await h(pg, "document.getElementById('hx-horizon').value") == "10d:sqrt10"
        await pg.keyboard.press("Home")
        await pg.keyboard.press("Enter")                                             # picks 1-day, closes, focus back on the control
        assert await h(pg, "document.getElementById('hx-horizon').value") == "1d"
        assert await pg.get_attribute(ctl, "aria-expanded") == "false"
        assert await h(pg, "document.activeElement === document.querySelector('#hx-horizon .di-select-control')")
        await pg.keyboard.press("Escape")                                            # closed: nothing happens
        await pg.keyboard.press("1")                                                 # type-ahead opens on "10-day (√10)"
        await pg.keyboard.press("0")
        await pg.keyboard.press("-")
        await pg.keyboard.press("d")
        await pg.keyboard.press("a")
        await pg.keyboard.press("y")
        await pg.keyboard.press(" ")                                                 # Space picks the active option
        assert await h(pg, "document.getElementById('hx-horizon').value") == "10d:sqrt10"
        await pg.keyboard.press("ArrowDown")
        await pg.keyboard.press("ArrowDown")
        await pg.keyboard.press("Escape")                                            # Escape closes without picking
        assert await h(pg, "document.getElementById('hx-horizon').value") == "10d:sqrt10"
        assert await h(pg, "window.__harness.selectChanges") == ["1d", "10d:sqrt10"]
        # a long list: a search field on top holds the focus; groups shown; a disabled option refused; Page Down; the value by click
        sel = "#hx-select .di-select-control"
        await pg.click(sel)
        await pg.wait_for_selector(".di-select-pop .di-select-search")
        assert await h(pg, "document.activeElement.classList.contains('di-select-search')")
        assert await pg.locator(".di-select-pop .di-option-group").count() == 2
        await pg.keyboard.type("ru")
        assert "1 match" in await pg.text_content(".di-select-pop .di-select-status")
        await pg.keyboard.press("Enter")                                             # RUB is disabled
        assert await h(pg, "window.__harness.select.getValue()") == "USD"
        await pg.fill(".di-select-pop .di-select-search", "")
        await pg.keyboard.press("PageDown")
        await pg.keyboard.press("Enter")
        assert await h(pg, "window.__harness.select.getValue()") == "SEK"              # USD is first; eight down
        assert await h(pg, "document.activeElement === document.querySelector('#hx-select .di-select-control')")
        await pg.keyboard.press("j")                                                 # typed on the control: the search opens with it
        assert await pg.input_value(".di-select-pop .di-select-search") == "j"
        await pg.click(".di-select-pop .di-option:has-text('JPY')")
        assert await h(pg, "window.__harness.select.getValue()") == "JPY"
        assert await h(pg, "window.__harness.selectChanges") == ["1d", "10d:sqrt10", "SEK", "JPY"]
        h0 = await h(pg, "(window.__harness.select.setOptions(['EUR', 'JPY']), window.__harness.select.getValue())")
        assert h0 == "JPY"
        assert await h(pg, "window.__harness.selectChanges.length") == 4             # set by code: no change event
    run(browsers, body)


def test_a_form_from_a_schema_with_conditions_arrays_the_servers_check_and_four_eyes(browsers):
    async def body(pg):
        f = "#hx-form"
        await pg.fill(f"{f} [data-path='book'] input", "rt-1")
        await pg.fill(f"{f} [data-path='notional'] input", "500")
        await pg.click(f"{f} [data-act='submit']")
        summary = await pg.text_content(f"{f} .di-form-summary")
        assert "Book code must look like a book code such as RT-123" in summary and "Notional must be at least 1,000" in summary
        assert await pg.evaluate("() => document.activeElement.classList.contains('di-form-summary')")
        assert await pg.locator(f"{f} [data-path='strike']").is_hidden()
        await pg.select_option(f"{f} [data-path='product'] drishti-select", label="Option")
        assert await pg.locator(f"{f} [data-path='strike']").is_visible() and await pg.locator(f"{f} [data-path='legs']").is_hidden()
        await pg.select_option(f"{f} [data-path='product'] drishti-select", label="Swap")
        await pg.click(f"{f} [data-path='legs'] [data-act='add']")
        assert await pg.locator(f"{f} .di-repeat-item").count() == 2
        await pg.check(f"{f} [data-path='legs[1].pay'] input[type=radio] >> nth=1")
        assert await pg.locator(f"{f} [data-path='legs[1].rate']").is_hidden()      # a leg's own condition
        await pg.fill(f"{f} [data-path='book'] input", "RT-999")
        await pg.fill(f"{f} [data-path='notional'] input", "25m")
        await pg.press(f"{f} [data-path='notional'] input", "Tab")
        assert await pg.input_value(f"{f} [data-path='notional'] input") == "25,000,000"
        await pg.click(f"{f} [data-act='submit']")
        await pg.wait_for_function("() => /closed/.test(document.querySelector('#hx-form .di-form-summary').textContent)")   # the server's own check
        await pg.fill(f"{f} [data-path='book'] input", "RT-202")
        await pg.click(f"{f} [data-act='submit']")
        await pg.wait_for_selector("dialog.di-four-eyes")
        changes = await pg.text_content("dialog.di-four-eyes .di-changes")
        assert "book" in changes and "legs" in changes
        await pg.click("dialog.di-four-eyes [data-act='propose']")
        assert "Say why" in await pg.text_content("dialog.di-four-eyes .di-msg")     # a comment is required
        await pg.fill("dialog.di-four-eyes textarea", "Booked in the wrong book")
        await pg.click("dialog.di-four-eyes .di-combobox input")
        await pg.keyboard.type("Ch")
        await pg.keyboard.press("Enter")
        await pg.click("dialog.di-four-eyes [data-act='propose']")
        await pg.wait_for_selector(f"{f} .di-form-banner.di-tone-warn")
        prop = await h(pg, "window.__harness.proposals[0]")
        assert prop["comment"] == "Booked in the wrong book" and prop["approver"] == "M. Chen" and prop["values"]["book"] == "RT-202"
        assert await pg.locator(f"{f} [data-act='submit']").count() == 0              # read-only while waiting
        assert "P-501" in await pg.text_content(f"{f} .di-form-banner")
        await pg.evaluate("() => window.__harness.form.resolveProposal('approved')")
        assert await pg.locator(f"{f} [data-act='submit']").count() == 1
    run(browsers, body)


async def cell(pg, row_id, col):
    """The cell of a row by id and a column by field, scrolled into view: its selector."""
    rc = await pg.evaluate("([id, f]) => { const m = window.__harness.grid.model; let r = -1; for (let i = 0; i < m.rowCount(); i++) { if (m.idAt(i) === id) { r = i; break; } }"
                           " const c = m.shownColumns().findIndex((x) => x.field === f); window.__harness.grid.focusCell(r, c); return [r, c]; }", [row_id, col])
    await frames(pg)
    return f"#hx-grid .dg-cell[data-r='{rc[0]}'][data-c='{rc[1]}']"


async def row_of(pg, row_id):
    return await pg.evaluate("(id) => Object.assign({}, window.__harness.grid.model.rowModel.getRowById(id))", row_id)


def test_the_filter_bar_drives_an_editable_grid_and_every_editor_undo_paste_and_refusal(browsers):
    async def body(pg):
        total = await h(pg, "window.__harness.grid.model.rowCount()")
        await pg.click("#hx-fbar .di-fb-add")
        await pg.keyboard.type("desk")
        await pg.keyboard.press("Enter")
        await pg.click(".di-fb-pop .di-option:has-text('FX')")
        await pg.click(".di-fb-pop [data-act='apply']")
        assert "Desk in FX" in await pg.text_content("#hx-fbar .di-fb-chips")
        fx = await h(pg, "window.__harness.grid.model.rowCount()")
        assert 0 < fx < total
        await pg.click("#hx-fbar .di-fb-add")
        await pg.keyboard.type("notional")
        await pg.keyboard.press("Enter")
        await pg.fill(".di-fb-pop input[aria-label='Value']", "5m")
        await pg.keyboard.press("Enter")
        assert await h(pg, "window.__harness.grid.model.rowCount()") < fx
        await pg.click("#hx-fbar .di-fb-clear")
        assert await h(pg, "window.__harness.grid.model.rowCount()") == total
        # a number by typing: the first key replaces the value, Enter saves and moves down
        sel = await cell(pg, "T1003", "NOTIONAL")
        await pg.keyboard.type("2")
        await pg.wait_for_selector("#hx-grid .dg-editor input")
        await pg.keyboard.type(".5m")
        await pg.keyboard.press("Enter")
        await pg.wait_for_function("() => window.__harness.writes.length === 1")
        assert (await row_of(pg, "T1003"))["NOTIONAL"] == 2500000
        assert await h(pg, "window.__harness.grid.model.navigator.focus.row") == await pg.evaluate("(s) => +document.querySelector(s).dataset.r + 1", sel)
        assert "dg-edited" in await pg.get_attribute(sel, "class")
        # a value that is not valid: the problem in words, the editor stays; Escape cancels
        await cell(pg, "T1004", "NOTIONAL")
        await pg.keyboard.press("F2")
        await pg.fill("#hx-grid .dg-editor input", "-5")
        await pg.keyboard.press("Enter")
        assert "Notional must be at least 0" in await pg.text_content("#hx-grid .dg-editor-msg")
        await pg.fill("#hx-grid .dg-editor input", "9bn")
        await pg.keyboard.press("Enter")
        assert "needs a desk head" in await pg.text_content("#hx-grid .dg-editor-msg")   # the column's own rule
        await pg.keyboard.press("Escape")
        assert await pg.locator("#hx-grid .dg-editor").count() == 0
        # the select, Tab to the next cell; the date through drishti-inputs' picker; the checkbox by Space; text
        await cell(pg, "T1005", "DESK")
        await pg.keyboard.press("Enter")
        await pg.locator(".di-select-pop").get_by_role("option", name="Credit", exact=True).click()   # drishti-inputs' Select opens on the value; picking saves
        await pg.wait_for_function("() => window.__harness.writes.length === 2")
        assert (await row_of(pg, "T1005"))["DESK"] == "Credit"
        await cell(pg, "T1005", "TRADE_DATE")
        await pg.keyboard.press("Enter")
        await pg.wait_for_selector("#hx-grid .dg-editor .di-date input")
        await pg.wait_for_selector(".di-date-pop [data-day]")
        await pg.fill("#hx-grid .dg-editor .di-date input", "6 Nov 2026")
        await pg.keyboard.press("Enter")
        await pg.wait_for_function("() => window.__harness.writes.length === 3")
        assert (await row_of(pg, "T1005"))["TRADE_DATE"] == "2026-11-06"
        await cell(pg, "T1006", "ACTIVE")
        before = (await row_of(pg, "T1006"))["ACTIVE"]
        await pg.keyboard.press(" ")
        await pg.wait_for_function("() => window.__harness.writes.length === 4")
        assert (await row_of(pg, "T1006"))["ACTIVE"] is (not before)
        await pg.dblclick(await cell(pg, "T1001", "COMMENT"))
        await pg.fill("#hx-grid .dg-editor input", "checked")
        await pg.keyboard.press("Enter")
        await pg.wait_for_function("() => window.__harness.writes.length === 5")
        # undo and redo by the keys
        await pg.keyboard.press("Control+z")
        await pg.wait_for_function("() => window.__harness.writes.length === 6")
        assert (await row_of(pg, "T1001"))["COMMENT"] == ""
        await pg.keyboard.press("Control+y")
        await pg.wait_for_function("() => window.__harness.writes.length === 7")
        assert (await row_of(pg, "T1001"))["COMMENT"] == "checked"
        # a paste from Excel at the focus: one transaction
        await cell(pg, "T1010", "BOOK")
        await pg.evaluate("() => { const dt = new DataTransfer(); dt.setData('text/plain', 'Swaps\\tGBP\\t\"1,500,000\"\\nBonds\\tJPY\\tnope\\n');"
                          " document.querySelector('#hx-grid .dg-scroll').dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true })); }")
        await pg.wait_for_function("() => window.__harness.writes.length === 8")
        tx = await h(pg, "window.__harness.writes[7]")
        assert tx["source"] == "paste" and len(tx["edits"]) >= 4
        await pg.wait_for_function("() => (document.querySelector('#hx-grid .dg-toast') || {textContent: ''}).textContent.includes('not valid')")   # the paste's toast replaces the redo's
        # Delete over a range; a refusal by the server puts the value back
        await cell(pg, "T1001", "COMMENT")
        await pg.keyboard.press("Delete")
        await pg.wait_for_function("() => window.__harness.writes.length === 9")
        assert (await row_of(pg, "T1001"))["COMMENT"] is None
        await cell(pg, "T1001", "COMMENT")
        await pg.keyboard.type("REFUSE")
        await pg.keyboard.press("Enter")
        await pg.wait_for_function("() => window.__harness.writes.length === 10")
        await frames(pg, 3)
        assert (await row_of(pg, "T1001"))["COMMENT"] is None
        # the context menu offers editing
        await pg.click(await cell(pg, "T1002", "BOOK"), button="right")
        actions = await pg.eval_on_selector_all(".dg-menu [data-action]", "els => els.map((e) => e.dataset.action)")
        assert {"edit", "undo", "redo", "clear-cells"} <= set(actions)
        await pg.keyboard.press("Escape")
        events = {e["name"] for e in await h(pg, "window.__harness.events")}
        assert {"cellEditStarted", "cellEditStopped", "editTransaction", "editUndone", "editRedone", "bus:filters"} <= events
    run(browsers, body)


def test_four_eyes_edits_wait_for_a_second_person(browsers):
    async def body(pg):
        sel = await cell(pg, "T1003", "NOTIONAL")
        await pg.keyboard.type("7m")
        await pg.keyboard.press("Enter")
        await pg.wait_for_function("() => window.__harness.writes.length === 1")
        await frames(pg)
        assert "dg-proposed" in await pg.get_attribute(sel, "class")
        assert await h(pg, "window.__harness.grid.model.editing.canEdit(+document.querySelector(" + json.dumps(sel) + ").dataset.r, +document.querySelector(" + json.dumps(sel) + ").dataset.c)") is False
        assert await pg.evaluate("() => window.__harness.grid.resolveProposal('P-101', 'rejected')") is True
        await frames(pg)
        assert (await row_of(pg, "T1003"))["NOTIONAL"] != 7000000
        assert "dg-proposed" not in await pg.get_attribute(sel, "class")
    run(browsers, body, query="fourEyes=1")


def test_the_code_editor_completion_lint_read_only_python_and_the_diff(browsers):
    async def body(pg):
        assert await pg.locator("#hx-code .CodeMirror").count() == 1                 # the vendored CodeMirror, loaded on demand under the CSP
        await pg.click("#hx-code .CodeMirror-line >> nth=1")
        await pg.keyboard.press("End")
        await pg.keyboard.press("Enter")
        await pg.keyboard.type("str")
        await pg.wait_for_selector(".di-complete-pop .di-option")
        assert "stressed_var" in await pg.text_content(".di-complete-pop")
        await pg.keyboard.press("Enter")
        await pg.keyboard.type("99, 10d) + (foo(")
        await pg.wait_for_function("() => document.querySelectorAll('#hx-code .di-problem').length >= 2")
        problems = await pg.text_content("#hx-code .di-problems")
        assert "no function foo" in problems and "is not closed" in problems
        assert "stressed_var(99, 10d)" in await h(pg, "window.__harness.code.getValue()")
        assert await pg.locator("#hx-code .di-lint-sign.di-lint-error").count() >= 1
        assert await pg.locator("#hx-code .cm-builtin").count() >= 3                 # the Rachana mode
        before = await h(pg, "window.__harness.yaml.getValue()")
        await pg.click("#hx-yaml .CodeMirror-line >> nth=0")
        await pg.keyboard.type("zzz")
        assert await h(pg, "window.__harness.yaml.getValue()") == before            # read-only
        assert await pg.locator("#hx-yaml .cm-keyword").count() >= 3
        await pg.wait_for_selector("#hx-py .cm-keyword")                             # the vendored Python mode, loaded when asked
        assert await pg.locator("#hx-diff .di-diff-row").count() == 7
        assert "+2" in await pg.text_content("#hx-diff .di-diff-add")
        await pg.click("#hx-diff [data-act='layout']")
        assert await pg.locator("#hx-diff .di-diff-unified").count() == 1
    run(browsers, body)


def test_the_live_client_reconnects_and_resumes_and_the_arrow_client_reads_by_flight_and_by_http(browsers):
    async def body(pg):
        await pg.wait_for_function("() => window.__harness.client.state === 'open'")
        await pg.evaluate("() => { const es = window.__harness.live.es; for (let i = 1; i <= 300; i++) { es.push({ type: 'transaction', update: [{ ID: 'T100' + (i % 3), MTM: i }] }, i); } }")
        await pg.wait_for_function("() => window.__harness.liveGrid.model.rowModel.getRowById('T1000').MTM === 300")
        stats = await h(pg, "window.__harness.live.stats")
        assert stats["received"] == 300 and stats["batches"] < 300                  # folded under backpressure
        await pg.evaluate("() => window.__harness.live.es.drop()")
        await pg.wait_for_function("() => window.__harness.client.state === 'open' && window.__harness.live.urls.length === 2")
        assert (await h(pg, "window.__harness.live.urls"))[1].endswith("lastEventId=300")
        await pg.wait_for_function("() => window.__harness.live.urls.length >= 3", timeout=5000)   # silent past the heartbeat: opened again
        assert "reconnecting" in await h(pg, "window.__harness.live.states")
        route.asked.clear()
        route.flight = True
        res = await pg.evaluate("() => window.__harness.readArrow(true)")
        assert res["via"] == "flight-http" and res["rows"] == 500 and res["zeroCopy"] is True
        assert res["types"] == ["text", "text", "number", "date"] and res["first"]["DAY"] == "2026-11-01"
        assert route.asked == ["POST /hx-fixture/do-get"]
        route.flight = False
        res = await pg.evaluate("() => window.__harness.readArrow(false)")
        assert res["via"] == "arrow-http" and route.asked[-1] == "GET /hx-fixture/trades.arrow"
        assert await pg.locator("#hx-arrow-grid .dg-row").count() > 5
    run(browsers, body)
