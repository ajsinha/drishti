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

"""The workbench (/build/d/{id}) in a real browser, over a real server: the all-panels showcase built from its samples by mouse and by
keyboard alone, a panel moved and sized with the YAML changing and its comments kept, a stale revision (409), undo and redo,
"Preview with a file...", the tests matrix following the design, and layout mode still working on the shared grid-keys.js.

Runs Chromium through Playwright against ``wb_live.py``'s server; skipped when Playwright, its Chromium, the server jar or JDK 25 is
missing."""
import json
import re
import time

import pytest

from conftest import CONSOLE
from wb_live import BROWSER_WAIT_MS, should_reload, live_console, new_design, post, showcase_json, showcase_sutra  # noqa: F401 - the fixture

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
ALL_KINDS = ["kv", "status", "provenance", "markdown", "table", "ladder", "pivot", "tabs", "line", "area", "candlestick", "surface", "histogram",
             "scatter", "gauge", "waterfall", "hbar", "graph", "links", "timeline"]


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed: skip, do not fail
            pytest.skip(f"needs Playwright's Chromium (playwright install chromium): {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture()
def page(browser):
    pg = browser.new_page(viewport={"width": 1500, "height": 950})
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    # a script that fails to download raises no page error, yet leaves the workbench unstarted: record failed and bad responses
    # (kept apart from pg.errors, which tests assert empty: a 422 for a mistyped expression is expected, not an error)
    pg.network = []
    pg.on("requestfailed", lambda r: pg.network.append(f"request failed: {r.url} ({r.failure})"))
    pg.on("response", lambda r: r.status >= 400 and pg.network.append(f"HTTP {r.status}: {r.url}"))
    yield pg
    pg.close()


def _poll(page, js, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if page.evaluate(js):
            return True
        page.wait_for_timeout(100)
    return False


def wait(page, js, seconds=BROWSER_WAIT_MS / 1000):   # generous: the full suite runs beside Maven builds
    """Polls a JavaScript expression until it is truthy (Playwright's own wait_for_function evaluates a string, which the console's CSP forbids).
    When it times out and the page's failed requests hold net::ERR_NETWORK_CHANGED (Chromium cancels requests when Docker changes the
    network interfaces), the page is reloaded once and waited on again; any other cause fails as before."""
    if _poll(page, js, seconds):
        return
    if should_reload(getattr(page, "network", None), getattr(page, "reloaded_for_network", False)):
        page.reloaded_for_network = True
        page.network.append("drill: reloaded once after ERR_NETWORK_CHANGED")
        page.reload()
        if _poll(page, js, seconds):
            return
    # say what the page was doing: a timeout on its own cannot tell a slow machine from a page that failed to start
    try:
        state = page.evaluate("({url: location.href, title: document.title, ready: document.readyState,"
                              " wb: typeof window.drishtiWorkbench, text: (document.body ? document.body.innerText : '').slice(0, 400)})")
    except Exception as e:                                   # the page itself is gone
        state = {"evaluate": repr(e)}
    errors = getattr(page, "errors", None)
    network = getattr(page, "network", None)
    raise AssertionError(f"still false after {seconds}s: {js}\n  page: {state}\n  page errors: {errors}\n  network: {network}")


def open_design(page, base, name, **kw):
    id_ = new_design(page, base, name, **kw)
    page.goto(f"{base}/build/d/{id_}")
    wait(page, "window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    page.locator("[data-preview] [data-panel]").first.wait_for(timeout=BROWSER_WAIT_MS)
    return id_


def state(page, key):
    return page.evaluate(f"window.drishtiWorkbench.store.state.{key}")


def kinds(page):
    return page.evaluate("window.DrishtiWB.model(window.drishtiWorkbench.store.state.yaml).panels.map(p => p.kind)")


def settle(page, rev):
    """Waits until the Design is past revision ``rev`` and the screen has drawn it."""
    wait(page, f"window.drishtiWorkbench.store.state.rev > {rev}")
    page.wait_for_timeout(250)


def drag(page, source, x, y, steps=8):
    source.scroll_into_view_if_needed()
    b = source.bounding_box()
    page.mouse.move(b["x"] + min(30, b["width"] / 2), b["y"] + min(10, b["height"] / 2))
    page.mouse.down()
    page.mouse.move(b["x"] + 60, b["y"] + 30, steps=3)
    page.mouse.move(x, y, steps=steps)
    page.mouse.up()


def first_panel_edge(page):
    """A point on the bottom rim of the first panel on the canvas (the rim is "between panels" for a drop)."""
    b = page.locator("[data-preview] .pnl[data-panel]").first.bounding_box()
    return b["x"] + b["width"] / 2, b["y"] + b["height"] - 6


# ---- building a screen -----------------------------------------------------------------------------------------------------------

def test_the_showcase_is_built_from_its_samples_by_mouse_without_typing_yaml(live_console, page):
    open_design(page, live_console, "by mouse", files={"showcase.json": showcase_json()})
    for kind in ALL_KINDS:                                         # every palette kind dropped on the canvas
        rev = state(page, "rev")
        x, y = first_panel_edge(page)
        drag(page, page.locator(f'[data-palette] [data-kind="{kind}"]'), x, y)
        settle(page, rev)
    assert sorted(set(kinds(page)) & set(ALL_KINDS)) == sorted(ALL_KINDS)
    # a field dropped on the rim of a panel: ranked suggestions, then the first one is added
    rev = state(page, "rev")
    x, y = first_panel_edge(page)
    drag(page, page.locator(".wb-field-row", has_text="productName").first, x, y)
    page.locator(".wb-menu").wait_for()
    assert "Panels for $.productName" in page.locator(".wb-menu").inner_text()
    page.keyboard.press("Enter")
    settle(page, rev)
    assert "$.productName" in state(page, "yaml")
    # a field dropped on the middle of a panel binds it
    rev = state(page, "rev")
    kv = page.locator('[data-preview] [data-panel="kv"]')
    kv.scroll_into_view_if_needed()
    b = kv.bounding_box()
    drag(page, page.locator(".wb-field-row", has_text="currency").first, b["x"] + b["width"] / 2, b["y"] + b["height"] / 2)
    settle(page, rev)
    assert "$.currency" in state(page, "yaml")
    # the inspector: select the gauge, change its max
    rev = state(page, "rev")
    page.locator('[data-preview] [data-panel="gauge"] .pnl-h').scroll_into_view_if_needed()
    page.locator('[data-preview] [data-panel="gauge"] .pnl-h').click()
    page.locator('[data-inspector] [data-required], [data-inspector] .wb-field').first.wait_for()
    max_field = page.get_by_label("max", exact=True)
    max_field.fill("$.notional")
    max_field.blur()
    settle(page, rev)
    assert re.search(r"max:\s*\$\.notional", state(page, "yaml"))
    wait(page, "document.querySelector('[data-result]').textContent.includes('1/1')")
    assert not page.errors


def test_the_showcase_is_built_from_its_samples_by_keyboard_alone(live_console, page):
    open_design(page, live_console, "by keyboard", files={"showcase.json": showcase_json()})
    page.evaluate("document.querySelector('[data-add-menu]').focus()")        # the first Tab stop; the rest is keys
    for kind in ALL_KINDS:
        rev = state(page, "rev")
        page.keyboard.press("Enter")                                          # Add panel...
        page.locator(".wb-menu input").wait_for()
        page.keyboard.type(kind)
        page.keyboard.press("Enter")
        settle(page, rev)
        page.evaluate("document.querySelector('[data-add-menu]').focus()")
    assert sorted(set(kinds(page)) & set(ALL_KINDS)) == sorted(ALL_KINDS)
    # arrows select, Alt+arrows move, Shift+arrows size, B binds
    page.evaluate("document.querySelector('[data-preview] [data-panel=\"kv\"]').focus()")
    order = page.evaluate("window.drishtiWorkbench.canvas.ids()")
    rev = state(page, "rev")
    page.keyboard.press("Alt+ArrowDown")
    settle(page, rev)
    after = page.evaluate("window.drishtiWorkbench.canvas.ids()")
    assert after.index("kv") == order.index("kv") + 1
    rev = state(page, "rev")
    page.keyboard.press("Shift+ArrowLeft")
    settle(page, rev)
    assert page.locator('[data-preview] [data-panel="kv"]').get_attribute("data-span") == "11"
    rev = state(page, "rev")
    page.keyboard.press("b")
    page.locator(".wb-menu input").wait_for()
    page.keyboard.type("currency")
    page.keyboard.press("Enter")
    settle(page, rev)
    assert "$.currency" in state(page, "yaml")
    page.keyboard.press("Enter")                                              # opens the inspector on the selected panel
    assert page.evaluate("!!document.activeElement.closest('[data-inspector]')")
    assert "kv" in page.locator("[data-inspector]").inner_text()
    rev = state(page, "rev")
    page.evaluate("document.querySelector('[data-preview] [data-panel=\"kv\"]').focus()")
    page.keyboard.press("Delete")
    settle(page, rev)
    assert "kv" not in page.evaluate("window.drishtiWorkbench.canvas.ids()")
    assert "removed" in page.locator("[data-live]").inner_text().lower()
    assert not page.errors


# ---- moving, sizing, comments ------------------------------------------------------------------------------------------------------

def test_moving_and_sizing_a_panel_changes_the_yaml_and_keeps_the_comments(live_console, page):
    open_design(page, live_console, "comments", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    before = state(page, "yaml")
    comments = [ln for ln in before.splitlines() if ln.lstrip().startswith("#")]
    assert len(comments) > 5
    rev = state(page, "rev")
    ops = page.locator('[data-preview] [data-panel="ops"] .pnl-h').bounding_box()
    terms = page.locator('[data-preview] [data-panel="terms"]').bounding_box()
    page.mouse.move(ops["x"] + 30, ops["y"] + 10)
    page.mouse.down()
    page.mouse.move(ops["x"] + 10, ops["y"] - 30, steps=4)
    page.mouse.move(terms["x"] + 20, terms["y"] + 40, steps=8)
    page.mouse.up()
    settle(page, rev)
    ids = page.evaluate("window.drishtiWorkbench.canvas.ids()")
    assert ids.index("ops") < ids.index("terms")
    rev = state(page, "rev")
    t = page.locator('[data-preview] [data-panel="terms"]')
    t.hover()
    e = t.locator(".lh-e").bounding_box()
    page.mouse.move(e["x"] + 5, e["y"] + 30)
    page.mouse.down()
    page.mouse.move(e["x"] + 90, e["y"] + 30, steps=5)
    page.mouse.up()
    settle(page, rev)
    new = state(page, "yaml")
    assert new != before and re.search(r"id: terms[^\n]*\n(?:[^\n]*\n)*?[^\n]*span: \d+", new) or "span:" in new
    assert [ln for ln in new.splitlines() if ln.lstrip().startswith("#")] == comments
    page.get_by_role("tab", name="YAML").click()
    assert "span:" in page.evaluate("document.querySelector('.CodeMirror').CodeMirror.getValue()")


def test_the_yaml_editor_follows_the_canvas_after_a_typed_edit_and_a_save_does_not_send_it_stale(live_console, page):
    """Typing in the YAML tab, then changing the screen on the canvas, then saving: the editor must have followed the canvas (a fired
    pause timer once stayed 'pending', so the editor kept its old text and the save sent that over the newer design)."""
    id_ = open_design(page, live_console, "typed-then-canvas", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    page.get_by_role("tab", name="YAML").click()
    rev = state(page, "rev")
    page.evaluate("document.querySelector('.CodeMirror').CodeMirror.replaceRange('# typed in the tab\\n', {line: 0, ch: 0})")
    wait(page, "window.drishtiWorkbench.store.state.yaml.indexOf('# typed in the tab') >= 0")
    settle(page, rev)
    rev = state(page, "rev")
    page.evaluate("window.drishtiWorkbench.store.send([{op: 'setMatch', match: {kind: 'trade', priority: 777}}])")
    settle(page, rev)
    wait(page, "document.querySelector('.CodeMirror').CodeMirror.getValue().indexOf('priority: 777') >= 0")      # the editor followed
    assert "# typed in the tab" in page.evaluate("document.querySelector('.CodeMirror').CodeMirror.getValue()")
    page.keyboard.press("Control+Enter")                       # preview: flushes the editor, which must not send its old text over the design
    page.wait_for_timeout(1500)
    assert "priority: 777" in state(page, "yaml") and not page.errors


def test_a_stale_revision_is_reloaded_and_said(live_console, page):
    id_ = open_design(page, live_console, "stale", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    rev = state(page, "rev")
    other = post(page, f"{live_console}/build/designs/{id_}/ops", {"baseRev": rev, "ops": [{"op": "setOption", "panel": "terms", "option": "span", "value": 5}]})
    assert other["http"] == 200
    page.evaluate("document.querySelector('[data-preview] [data-panel=\"ops\"]').focus()")
    page.keyboard.press("Shift+ArrowLeft")                              # built on the old revision: refused with 409
    wait(page, "document.querySelector('[data-say]').textContent.includes('changed somewhere else')")
    assert state(page, "rev") == other["rev"]
    wait(page, "document.querySelector('[data-preview] [data-panel=\"terms\"]').dataset.span === '5'")
    page.evaluate("document.querySelector('[data-preview] [data-panel=\"ops\"]').focus()")
    page.keyboard.press("Shift+ArrowLeft")                              # and the same edit now goes through
    settle(page, other["rev"])
    assert page.locator('[data-preview] [data-panel="ops"]').get_attribute("data-span") == "3"


def test_undo_and_redo_by_buttons_and_keys(live_console, page):
    open_design(page, live_console, "undo", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    start = state(page, "yaml")
    assert page.locator("[data-undo]").is_disabled()
    page.evaluate("document.querySelector('[data-preview] [data-panel=\"terms\"]').focus()")
    rev = state(page, "rev")
    page.keyboard.press("Shift+ArrowLeft")
    settle(page, rev)
    changed = state(page, "yaml")
    assert changed != start and page.locator("[data-undo]").is_enabled()
    rev = state(page, "rev")
    page.locator("[data-undo]").click()
    settle(page, rev)
    assert state(page, "yaml") == start and page.locator("[data-redo]").is_enabled()
    rev = state(page, "rev")
    page.locator("[data-redo]").click()
    settle(page, rev)
    assert state(page, "yaml") == changed
    rev = state(page, "rev")
    page.evaluate("document.querySelector('[data-preview] [data-panel=\"terms\"]').focus()")
    page.keyboard.press("Control+z")
    settle(page, rev)
    assert state(page, "yaml") == start
    rev = state(page, "rev")
    page.keyboard.press("Control+Shift+z")
    settle(page, rev)
    assert state(page, "yaml") == changed


# ---- previewing a file, tests as you type --------------------------------------------------------------------------------------------

def test_preview_with_a_file_shows_it_at_once_and_adds_nothing(live_console, page, tmp_path):
    open_design(page, live_console, "file", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    doc = json.loads(showcase_json())
    doc["tradeId"] = "FILE-PREVIEW-7"
    f = tmp_path / "other.json"
    f.write_text(json.dumps(doc))
    assert "FILE-PREVIEW-7" not in page.locator("[data-preview]").inner_text()
    page.locator("[data-preview-file]").set_input_files(str(f))
    wait(page, "document.querySelector('[data-preview]').textContent.includes('FILE-PREVIEW-7')")
    assert state(page, "samples") == ["showcase.json"]
    assert "other.json" in page.locator("[data-sample-pos]").inner_text()
    page.locator("[data-file-clear]").click()
    wait(page, "!document.querySelector('[data-preview]').textContent.includes('FILE-PREVIEW-7')")


def test_the_tests_matrix_follows_the_design_as_it_changes(live_console, page):
    thin = json.loads(showcase_json())
    thin.pop("legs", None)
    thin.pop("coupons", None)
    open_design(page, live_console, "matrix", sutra=showcase_sutra(), files={"full.json": showcase_json(), "thin.json": json.dumps(thin)})
    page.get_by_role("tab", name="Tests").click()
    page.locator(".wb-matrix").wait_for(timeout=BROWSER_WAIT_MS)
    assert page.locator(".wb-matrix thead th").count() == 3                 # Panel + two samples
    rows = page.locator(".wb-matrix tbody tr").count()
    page.get_by_role("tab", name="Design").click()
    rev = state(page, "rev")
    x, y = first_panel_edge(page)
    drag(page, page.locator('[data-palette] [data-kind="hbar"]'), x, y)
    settle(page, rev)
    page.get_by_role("tab", name="Tests").click()
    wait(page, f"document.querySelectorAll('.wb-matrix tbody tr').length === {rows + 1}")
    assert re.search(r"\d+/2", page.locator("[data-result]").inner_text())
    cell = page.locator('.wb-matrix tbody tr:last-child button').nth(1)
    cell.click()                                                          # a cell previews that sample
    assert "thin.json" in page.locator("[data-sample-pos]").inner_text()


# ---- layout mode on the shared module ----------------------------------------------------------------------------------------------------

LAYOUT_PAGE = """<div data-view data-sutra="s" data-kind="k" data-layout-base='[]'>
<div class="vmain"><section class="pnl" data-panel="a" data-span="12" data-height="0"><div class="pnl-h"><h3>A</h3><span class="pnl-code">A</span></div></section>
<section class="pnl" data-panel="b" data-span="12" data-height="0"><div class="pnl-h"><h3>B</h3><span class="pnl-code">B</span></div></section></div><div class="vright"></div>
<div data-layout-bar hidden><span data-layout-live></span><span data-layout-msg></span><button data-layout-cancel>c</button><button data-layout-save>s</button><button data-layout-reset>r</button></div></div>"""


def test_layout_mode_still_moves_and_sizes_with_the_shared_grid_keys(browser):
    root = CONSOLE / "web" / "static" / "js"
    page = browser.new_page()
    page.set_content(LAYOUT_PAGE)
    for f in ("layout-rules.js", "build/grid-keys.js", "layout.js"):
        page.add_script_tag(path=str(root / f))
    page.keyboard.press("Alt+l")
    assert page.evaluate("window.drishtiLayout.active()")
    first = page.locator('[data-panel="a"]')
    first.focus()
    page.keyboard.press("ArrowDown")                                          # layout mode: the arrows themselves move the panel
    assert page.evaluate("Array.from(document.querySelectorAll('.vmain > .pnl')).map(p => p.dataset.panel).join('')") == "ba"
    page.keyboard.press("Shift+ArrowLeft")
    assert first.get_attribute("data-span") == "11"
    page.keyboard.press("ArrowRight")                                         # to the side column
    assert page.evaluate("document.querySelector('.vright > .pnl').dataset.panel") == "a"
    page.close()
