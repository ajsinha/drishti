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

"""UI Board P4 in a real browser (Chromium): the book-trades board of UI_BOARD.md 3.1 built in the designer's Board mode (chips dragged from
the Chip Store, pins mapped in the mapping sheet, two traces drawn), a bad wire shown in red, save and reopen, an edit in the YAML tab that
the canvas follows, Undo, the viewer with its data (a selection repainting the ticket, Chart on a table chip, a live frame), the phone layout,
keyboard-only use, Chip mode, and all ten themes. The console runs in-process against the stand-in server of ui_board_backend.py, whose
render stream has P3's event shapes; skipped when Playwright's Chromium is not installed."""
import copy
from pathlib import Path

import pytest
import yaml

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from dnd import drag  # noqa: E402
from ui_board_backend import serve  # noqa: E402

THEMES = ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue")
BOOK = yaml.safe_load((Path(__file__).resolve().parent / "fixtures" / "ui_book_trades.yaml").read_text())


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve()
    yield url, backend
    stop()


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture
def page(browser):
    c = browser.new_context(viewport={"width": 1440, "height": 900}, accept_downloads=True, bypass_csp=True)
    p = c.new_page()
    p.errors = []
    p.on("pageerror", lambda e: p.errors.append(str(e)))
    yield p
    c.close()


def put_board(backend, name, doc):
    """A board saved straight into the stand-in (as P2 would hold it)."""
    d = copy.deepcopy(doc)
    d.update(board=name, version=1)
    backend.boards[name] = {"doc": d, "yaml": yaml.safe_dump(d, sort_keys=False), "version": 1, "etag": '"e1"', "revisions": {}, "share": []}


def sheet_set(page, selector, value):
    el = page.locator("[data-ub-sheet] " + selector)
    if el.evaluate("e => e.tagName") == "SELECT":
        el.select_option(value)
    else:
        el.fill(value if isinstance(value, str) else ", ".join(value))
    el.dispatch_event("change")


def add_chip(page, chip, inst, supply_kind, supply, maps, at=None):
    if at:
        drag(page, f'.ub-pal-add[data-chip="{chip}"]', "[data-ub-canvas]", target_position=at)
    else:
        page.locator(f'.ub-pal-add[data-chip="{chip}"]').click()
    page.wait_for_function("c => { const i = document.querySelector('[data-ub-sheet] [data-ub-inst-name]'); return i && i.value === c; }", arg=chip)
    sheet_set(page, "[data-ub-inst-name]", inst)
    page.wait_for_selector(f'.ub-box[data-instance="{inst}"]')
    sheet_set(page, "[data-ub-supply-kind]", supply_kind)
    sheet_set(page, "[data-ub-supply-name]", supply)
    for key, value in maps.items():
        el = page.locator(f'[data-ub-sheet] select[data-map="{key}"]')
        el.wait_for()
        el.select_option(value)
        el.dispatch_event("change")


def wire(page, src, dst):
    a, b = page.locator(f'.ub-pin.ub-out[data-pin="{src}"]'), page.locator(f'.ub-pin.ub-in[data-pin="{dst}"]')
    a.scroll_into_view_if_needed()
    ba, bb = a.bounding_box(), b.bounding_box()
    page.mouse.move(ba["x"] + ba["width"] / 2, ba["y"] + ba["height"] / 2)
    page.mouse.down()
    page.mouse.move(bb["x"] + bb["width"] / 2, bb["y"] + bb["height"] / 2, steps=8)
    page.mouse.up()


def doc_of(page):
    return page.evaluate("JSON.parse(JSON.stringify(window.drishtiUiBoard.designer.doc))")


def build_book_trades(page, url):
    page.goto(url + "/build/board/new?advanced=1")
    page.wait_for_selector('.ub-pal-add[data-chip="filterable-table"]')
    page.fill("[data-ub-name]", "book-trades")
    page.locator("[data-ub-name]").dispatch_event("change")
    add_chip(page, "filterable-table", "trades", "connector", "trading/trade-search",
             {"rows.id": "TRADE_ID", "rows.date": "TRADE_DATE", "rows.label": "COUNTERPARTY_NAME", "rows.measures": ["NOTIONAL", "MTM_USD"]}, at={"x": 60, "y": 30})
    add_chip(page, "entity-card", "ticket", "connector", "trading/trade", {"doc.id": "TRADE_ID", "doc.title": "DEAL_NUMBER"})
    add_chip(page, "cashflow-ladder", "flows", "model", "trading-risk",
             {"rows.date": "cashflows.PAY_DATE", "rows.group": "cashflows.CCY", "rows.amount": "FLOW_AMOUNT", "rows.trade": "cashflows.TRADE_ID"})
    wire(page, "trades.selected", "ticket.id")
    wire(page, "trades.selected", "flows.filter")
    page.wait_for_function("window.drishtiUiBoard.designer.doc.traces.length === 2")


def test_build_the_book_trades_board_catch_a_bad_wire_save_and_reopen(page, stack):
    url, be = stack
    build_book_trades(page, url)
    d = doc_of(page)
    assert set(d["chips"]) == {"trades", "ticket", "flows"}
    assert d["chips"]["trades"]["map"]["rows.measures"] == ["NOTIONAL", "MTM_USD"]
    assert d["traces"] == [{"from": "trades.selected", "to": "ticket.id"},
                           {"from": "trades.selected", "to": "flows.filter", "transform": {"filter-on": "rows.trade"}}]
    page.wait_for_selector("svg[data-ub-wires] path.ub-wire[data-trace='1']", state="attached")
    # only compatible attributes are offered: an id pin gets no measure
    page.locator('.ub-box[data-instance="trades"]').focus()
    opts = page.locator('[data-ub-sheet] [data-map="rows.id"] option').all_text_contents()
    assert any("TRADE_ID" in o for o in opts) and not any("NOTIONAL" in o for o in opts)
    # a bad wire: rows into a filter pin is red, with the reason, and Save waits
    wire(page, "trades.visible", "flows.filter")
    page.wait_for_selector("svg[data-ub-wires] path.ub-wire.ub-bad", state="attached")
    prob = page.locator(".ub-prob[data-rule='BC-04']")
    assert "cannot feed" in prob.text_content()
    assert page.locator("[data-ub-save]").is_disabled()
    page.locator("[data-trace-row='2'] button").click()                      # remove it from the traces list
    page.wait_for_function("!document.querySelector('.ub-prob[data-rule=\"BC-04\"]')")
    page.wait_for_function("!document.querySelector('[data-ub-save]').disabled")
    page.locator("[data-ub-save]").click()
    page.wait_for_function("document.querySelector('[data-ub-status]').textContent.indexOf('Saved book-trades') >= 0")
    assert be.boards["book-trades"]["version"] == 1 and len(be.boards["book-trades"]["doc"]["traces"]) == 2
    page.goto(url + "/build/board/book-trades?advanced=1")
    page.wait_for_selector('.ub-box[data-instance="flows"]')
    assert page.locator(".ub-box").count() == 3
    # the YAML tab edits the same document: a new title shows on the canvas
    page.locator("[data-ub-tab='yaml']").click()
    page.wait_for_function("document.querySelector('[data-ub-yaml]').value.indexOf('ticket:') >= 0")
    text = page.input_value("[data-ub-yaml]").replace("  ticket:\n    use: entity-card@1\n", "  ticket:\n    use: entity-card@1\n    title: The ticket\n")
    assert "title: The ticket" in text
    page.fill("[data-ub-yaml]", text)
    page.locator("[data-ub-tab='canvas']").click()
    page.wait_for_function("document.querySelector('.ub-box[data-instance=\"ticket\"] .ub-box-title').textContent === 'The ticket'")
    page.keyboard.press("Control+z")
    page.wait_for_function("document.querySelector('.ub-box[data-instance=\"ticket\"] .ub-box-title').textContent !== 'The ticket'")
    # a stale save: someone else saved meanwhile
    be.boards["book-trades"]["etag"] = '"elsewhere"'
    page.keyboard.press("Control+y")
    page.wait_for_function("!document.querySelector('[data-ub-save]').disabled")
    page.locator("[data-ub-save]").click()
    page.wait_for_selector("[data-ub-conflict]:not([hidden])")
    assert page.errors == []


def test_live_data_while_designing(page, stack):
    url, be = stack
    put_board(be, "live-design", BOOK)
    page.goto(url + "/build/board/live-design?advanced=1")
    page.wait_for_selector('.ub-box[data-instance="trades"]')
    page.locator("[data-ub-live]").check()
    page.wait_for_selector('.ub-box[data-instance="trades"] table.ub-tbl tbody tr')
    assert "Waiting for id" in page.locator('.ub-box[data-instance="ticket"] .ub-body').text_content()
    assert page.errors == []


def test_the_viewer_paints_layout_then_chips_and_a_selection_repaints_the_ticket(page, stack):
    url, be = stack
    put_board(be, "book-trades-v", BOOK)
    page.goto(url + "/ui/b/book-trades-v")
    page.wait_for_selector('.ub-chip[data-instance="trades"] table.ub-tbl tbody tr')
    assert page.locator('.ub-chip[data-instance="ticket"]').get_attribute("data-state") == "waiting"
    assert page.locator('.ub-chip[data-instance="trades"] td.ub-masked').count() > 0          # MTM_USD masked for the viewer
    page.locator('.ub-chip[data-instance="trades"] tr.ub-pick').nth(3).click()
    page.wait_for_selector('.ub-chip[data-instance="ticket"] .ub-kv')
    assert "D-5003" in page.locator('.ub-chip[data-instance="ticket"]').text_content()
    assert be.pin_calls[-1] == {"instance": "trades", "pin": "selected", "value": "1000013"}
    page.wait_for_selector('.ub-chip[data-instance="trades"] [data-table-chart]')            # Chart any table on a table chip
    page.locator('.ub-chip[data-instance="trades"] [data-table-chart]').click()
    page.wait_for_selector("dialog.tc-dlg[open]")
    page.keyboard.press("Escape")
    assert page.errors == []


def test_a_live_chip_takes_frames_and_a_failing_chip_says_why(page, stack):
    url, be = stack
    doc = {"rachana": 1, "board": "live-one", "chips": {
        "px": {"use": "entity-card@1", "supply": {"live": "trading/trade"}, "map": {"doc.id": "TRADE_ID", "doc.title": "DEAL_NUMBER"}},
        "bad": {"use": "cashflow-ladder@1", "supply": {"model": "broken-model"}, "map": {"rows.date": "cashflows.PAY_DATE", "rows.amount": "FLOW_AMOUNT"}}},
        "traces": [], "layout": {"desktop": {"columns": 12, "areas": [{"chip": "px", "at": [1, 1], "span": [6, 3]}, {"chip": "bad", "at": [7, 1], "span": [6, 3]}]}}}
    put_board(be, "live-one", doc)
    page.goto(url + "/ui/b/live-one")
    page.wait_for_function("document.querySelector('.ub-chip[data-instance=\"px\"]').textContent.indexOf('#2') >= 0", timeout=10000)
    bad = page.locator('.ub-chip[data-instance="bad"]')
    assert bad.get_attribute("data-state") == "error" and "DRS-9010" in bad.text_content() and "not readable" in bad.text_content()


def test_the_phone_layout_one_column_collapsed_flows_no_sideways_scroll(browser, stack):
    url, be = stack
    put_board(be, "book-trades-p", BOOK)
    c = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True, bypass_csp=True)
    p = c.new_page()
    p.goto(url + "/ui/b/book-trades-p")
    p.wait_for_selector('.ub-phone .ub-chip[data-instance="trades"] table')
    assert p.locator('details.ub-collapsed .ub-chip[data-instance="flows"]').count() == 1
    order = p.eval_on_selector_all(".ub-phone .ub-chip", "els => els.map(e => e.dataset.instance)")
    assert order == ["trades", "ticket", "flows"]
    assert p.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    p.goto(url + "/build/board/book-trades-p?advanced=1")
    p.wait_for_selector('.ub-box[data-instance="trades"]')
    p.locator("[data-ub-device]").click()
    p.wait_for_selector(".ub-phone-list")
    p.locator('[data-phone-item="flows"] button[aria-label="Move flows up"]').click()
    assert p.evaluate("window.drishtiUiBoard.designer.doc.layout.phone.order") == ["trades", "flows", "ticket"]
    c.close()


def test_keyboard_only_add_move_and_wire(page, stack):
    url, _ = stack
    page.goto(url + "/build/board/new?advanced=1")
    page.wait_for_selector('.ub-pal-add[data-chip="filterable-table"]')
    page.locator('.ub-pal-add[data-chip="filterable-table"]').focus()
    page.keyboard.press("Enter")
    page.wait_for_selector('.ub-box[data-instance="filterable-table"]')
    page.locator('.ub-pal-add[data-chip="entity-card"]').focus()
    page.keyboard.press("Enter")
    page.wait_for_selector('.ub-box[data-instance="entity-card"]')
    box = page.locator('.ub-box[data-instance="entity-card"]')
    box.focus()
    before = page.evaluate("window.drishtiUiBoard.designer.doc.layout.desktop.areas.find(a => a.chip === 'entity-card').at")
    page.keyboard.press("ArrowRight")
    page.keyboard.press("Shift+ArrowDown")
    a = page.evaluate("window.drishtiUiBoard.designer.doc.layout.desktop.areas.find(a => a.chip === 'entity-card')")
    assert a["at"] == [before[0] + 1, before[1]] and a["span"][1] == 5
    page.locator('.ub-pin.ub-out[data-pin="filterable-table.selected"]').focus()
    page.keyboard.press("Enter")
    assert page.locator('.ub-pin.ub-in[data-pin="entity-card.id"]').get_attribute("class").find("ub-ok") >= 0
    page.locator('.ub-pin.ub-in[data-pin="entity-card.id"]').focus()
    page.keyboard.press("Enter")
    page.wait_for_function("window.drishtiUiBoard.designer.doc.traces.length === 1")
    box.focus()
    page.keyboard.press("Delete")
    page.wait_for_function("!window.drishtiUiBoard.designer.doc.chips['entity-card'] && window.drishtiUiBoard.designer.doc.traces.length === 0")
    assert page.errors == []


def test_chip_mode_shares_the_shell_the_palette_and_the_yaml_tab(page, stack):
    url, _ = stack
    page.goto(url + "/build/board/new?advanced=1")
    page.wait_for_selector('.ub-pal-add[data-chip="search-box"]')
    page.locator("[data-ub-mode='chip']").click()
    assert page.locator("[data-ub-chip-mode]").is_visible() and not page.locator("[data-ub-canvas]").is_visible()
    assert page.url.endswith("/build/chip/new")
    page.locator('.ub-pal-add[data-chip="search-box"]').click()
    page.wait_for_function("document.querySelector('[data-ub-yaml]').value.indexOf('chip: search-box-mine') >= 0")
    page.locator("[data-ce-save]").click()
    page.wait_for_function("document.querySelector('[data-ub-status]').textContent.indexOf('Saved your draft') >= 0")
    page.locator("[data-ub-tab='canvas']").click()
    page.locator("[data-ce-step='bench']").click()
    page.locator("[data-ce-run]").click()
    page.wait_for_selector("[data-ce-bench] .ub-chip[data-instance='c']")
    page.locator("[data-ub-mode='board']").click()
    assert page.locator("[data-ub-canvas]").is_visible()
    assert page.errors == []


def test_every_theme_paints_the_designer_and_the_viewer_from_its_tokens(page, stack):
    url, be = stack
    put_board(be, "book-trades-t", BOOK)
    for path, sel in (("/ui/b/book-trades-t", '.ub-chip[data-instance="trades"] table'), ("/build/board/book-trades-t?advanced=1", '.ub-box[data-instance="trades"]')):
        page.goto(url + path)
        page.wait_for_selector(sel)
        seen = set()
        for name in THEMES:
            page.evaluate("n => { document.documentElement.setAttribute('data-theme', n); document.dispatchEvent(new CustomEvent('drishti:theme', { detail: n })); }", name)
            got = page.evaluate("""(s) => { const e = document.querySelector(s).closest('.ub-chip, .ub-box'), cs = getComputedStyle(e);
                return [cs.backgroundColor, cs.color, getComputedStyle(document.documentElement).getPropertyValue('--d-surface').trim()]; }""", sel)
            assert got[0] not in ("", "rgba(0, 0, 0, 0)") and got[0] != got[1], (name, got)
            seen.add(got[0])
        assert len(seen) >= 5, seen
    assert page.errors == []
