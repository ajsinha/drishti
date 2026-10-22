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

"""UI Board P4b in a real browser (Chromium): the chip designer, Chip mode of Build's one designer. A chip authored from scratch (pins, a body
built with the screen designer's panel tools, the manual, an example), a CP-02 violation in red and fixed; the bench in two themes and on a
phone; submit, approve as a second person, publish, and the chip used on a board; a composite chip (a board with no connectors) with one
exposed in pin and one out pin, used on a board, a connector inside refused; Make a chip from two wired chips; a P5 candidate opened;
Open as board through the converter. Against the stand-in server of ui_chip_backend.py; skipped without Playwright's Chromium."""
import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_ui_board_browser import browser, doc_of, page, sheet_set  # noqa: E402,F401
from ui_board_backend import serve  # noqa: E402
from ui_chip_backend import UiChipBackend  # noqa: E402


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve(UiChipBackend())
    yield url, backend
    stop()


def chip(page):
    return page.evaluate("JSON.parse(JSON.stringify(window.drishtiUiBoard.chipEditor.doc))")


def problems(page):
    return page.evaluate("window.drishtiUiBoard.chipEditor.problems.filter(p => p.level !== 'warning').map(p => p.rule + ' ' + p.path)")


def step(page, name):
    page.locator(f"[data-ce-step='{name}']").click()


def ins_field(page, label):
    return page.locator(f'[data-ce-inspector] .wb-field:has(> label:text-is("{label}")) input').first


def fill_manual(page, pins):
    step(page, "manual")
    for k, v in (("purpose", "Horizontal bars of a value per label."), ("behaviour", "Nothing to click."), ("defaults", "All rows."),
                 ("limits", "Up to 50 bars."), ("not-for", "Time series (use a line)."), ("accessibility", "Read as a list.")):
        page.locator(f'[data-ce-manual] textarea[data-path="manual.{k}"]').fill(v)
    for p in pins:
        page.locator(f'[data-ce-manual] input[data-path="manual.pins.{p}"]').fill("The " + p + "; empty shows nothing.")


def test_author_a_chip_bench_it_release_it_and_use_it(page, stack):
    url, be = stack
    page.goto(url + "/build/chip/new")
    page.wait_for_selector("[data-ce-add-pin='in']")
    page.fill("[data-ce-name]", "pnl-bars")
    page.fill("[data-ce-title]", "P&L bars")
    page.locator("[data-ce-add-pin='in']").click()                        # a rowset pin 'rows' with id and label columns
    page.locator("tr[data-pin='rows'] input[data-f='shape']").fill("id:id!, label:dimension, value:measure")
    page.locator("[data-ce-add-pin='in']").click()
    row = page.locator("tr[data-pin='pin']")
    row.locator("select[data-f='type']").select_option("enum")            # an enum without values: red at once
    page.wait_for_selector("tr[data-pin='pin'] input[data-f='values'].ub-bad")
    page.locator("tr[data-pin='pin'] input[data-f='values']").fill("asc, desc")
    page.locator("tr[data-pin='pin'] input[data-f='']").fill("order")
    page.wait_for_function("!document.querySelector('tr[data-pin=\"order\"] .ub-bad')")
    # the body: the panel palette and the inspector, reading only pins
    step(page, "body")
    page.locator("[data-ce-kind='hbar']").click()
    page.wait_for_selector("[data-ce-inspector] .wb-field")
    assert chip(page)["body"]["panels"][0]["rows"] == "@rows"
    label = ins_field(page, "label")
    label.fill("@trades.label")                                            # not a pin: red (CP-02)
    page.wait_for_selector('[data-ce-inspector] input.ub-bad')
    page.wait_for_function("window.drishtiUiBoard.chipEditor.problems.some(p => p.rule === 'CP-02')")
    assert page.locator("[data-ce-problems] li.ub-bad-text[data-rule='CP-02']").count() >= 1
    label.fill("@.label")
    label.dispatch_event("change")
    ins_field(page, "value").fill("@.value")
    ins_field(page, "value").dispatch_event("change")
    page.wait_for_function("!window.drishtiUiBoard.chipEditor.problems.some(p => p.rule === 'CP-02')")
    fill_manual(page, ["rows", "order"])
    page.locator("[data-ce-add-example]").click()
    page.wait_for_function("window.drishtiUiBoard.chipEditor.problems.filter(p => p.level !== 'warning').length === 0", timeout=5000)
    assert "unused" not in problems(page)
    # the bench: made-up samples, two themes, a phone
    step(page, "bench")
    page.wait_for_selector("[data-ce-preview] .ub-body *")
    page.locator("[data-ce-theme]").select_option("light")
    assert page.evaluate("document.documentElement.getAttribute('data-theme')") == "light"
    page.wait_for_selector("[data-ce-preview][data-bench-theme='light'] .ub-body *")
    page.locator("[data-ce-theme]").select_option("blue")
    page.wait_for_selector("[data-ce-preview][data-bench-theme='blue'] .ub-body *")
    page.locator("[data-ce-phone]").click()
    assert page.locator("[data-ce-preview].ub-ce-phone").evaluate("e => e.getBoundingClientRect().width") <= 392
    assert "order" in page.locator("[data-ce-usage] [data-ce-unused]").get_attribute("data-ce-unused")
    page.locator("[data-ce-run]").click()
    page.wait_for_selector("[data-ce-bench] [data-test='0'] [data-result='pass']")
    page.wait_for_selector("[data-ce-bench] .ub-chip[data-instance='c']")
    # release: submit, four eyes, a second person approves, publish
    step(page, "release")
    page.locator("[data-ce-act='submit']").click()
    page.wait_for_selector("[data-ce-version-row='1'][data-state='in-review']")
    page.locator("[data-ce-act='approve']").click()
    page.wait_for_function("document.querySelector('[data-ub-status]').textContent.indexOf('DRS-2007') >= 0")
    be.acting = "bob"
    try:
        page.locator("[data-ce-act='approve']").click()
        page.wait_for_selector("[data-ce-version-row='1'][data-state='approved']")
    finally:
        be.acting = None
    page.locator("[data-ce-act='publish']").click()
    page.wait_for_selector("[data-ce-version-row='1'][data-state='published']")
    # used on a board
    page.locator("[data-ub-mode='board']").click()
    page.fill("[data-ub-palette-q]", "pnl")
    page.wait_for_selector('.ub-pal-add[data-chip="pnl-bars"]')
    page.locator('.ub-pal-add[data-chip="pnl-bars"]').click()
    page.wait_for_function("Object.values(window.drishtiUiBoard.designer.doc.chips).some(i => i.use === 'pnl-bars@1')")
    assert page.errors == []


def test_a_composite_chip_is_a_board_with_no_connectors(page, stack):
    url, be = stack
    page.goto(url + "/build/chip/new")
    page.wait_for_selector("[data-ce-add-pin='in']")
    page.fill("[data-ce-name]", "book-search")
    page.fill("[data-ce-title]", "Book search")
    step(page, "body")
    page.locator("[data-ce-body-tab='chips']").click()
    page.locator("[data-ce-make-composite]").click()
    page.locator("[data-ce-edit-inside]").click()
    page.wait_for_selector("[data-ub-composite]:not([hidden])")
    page.wait_for_selector('.ub-pal-add[data-chip="search-box"]')
    page.evaluate("window.drishtiUiBoard.designer.addInstance('search-box@1')")
    page.wait_for_function("window.drishtiUiBoard.designer.doc.chips['search-box']")
    page.evaluate("window.drishtiUiBoard.designer.addInstance('filterable-table@2')")
    page.wait_for_function("window.drishtiUiBoard.designer.doc.chips['filterable-table']")
    assert page.locator("[data-ub-sheet] [data-ub-supply-kind]").is_disabled()            # no connectors inside a chip
    page.evaluate("window.drishtiUiBoard.designer.addTrace('search-box.filter-out', 'filterable-table.filter', 'filter', 'filter')")
    page.locator("[data-ub-composite-done]").click()
    page.wait_for_selector("[data-ce-expose]")
    page.wait_for_selector("[data-ce-expose] tr.ub-bad-row")                               # filterable-table.rows is required and not fed
    page.locator("[data-ce-expose-pin='filterable-table.rows']").fill("rows")
    page.locator("[data-ce-expose-pin='filterable-table.rows']").dispatch_event("change")
    page.locator("[data-ce-expose-pin='filterable-table.selected']").fill("selected")
    page.locator("[data-ce-expose-pin='filterable-table.selected']").dispatch_event("change")
    d = chip(page)
    assert d["body"]["wires"] == {"rows": "filterable-table.rows"} and d["body"]["exposes"] == {"selected": "filterable-table.selected"}
    assert d["pins"]["in"]["rows"]["type"] == "rowset" and d["pins"]["out"]["selected"]["type"] == "id"
    assert not [p for p in problems(page) if p.startswith("CP-07")]
    fill_manual(page, ["rows", "selected"])
    page.locator("[data-ce-add-example]").click()
    page.locator("[data-ce-save]").click()
    page.wait_for_function("document.querySelector('[data-ub-status]').textContent.indexOf('Saved your draft') >= 0")
    # the bench paints it chip by chip through its pins
    step(page, "bench")
    page.wait_for_selector("[data-ce-preview] .ub-ce-composite .ub-chip[data-instance='filterable-table'] .ub-body *")
    # used on a board (your own draft, on your own board)
    page.locator("[data-ub-mode='board']").click()
    page.evaluate("window.drishtiUiBoard.designer.pick('book-search@1')")
    page.wait_for_function("Object.values(window.drishtiUiBoard.designer.doc.chips).some(i => i.use === 'book-search@1')")
    # a connector inside is refused
    page.locator("[data-ub-mode='chip']").click()
    step(page, "body")
    page.locator("[data-ce-body-tab='chips']").click()
    page.locator("[data-ce-edit-inside]").click()
    page.wait_for_selector("[data-ub-composite]:not([hidden])")
    page.evaluate("const D = window.drishtiUiBoard.designer; D.doc.chips['filterable-table'].supply = { connector: 'trading/trade-search' }; D.changed('chips');")
    page.locator("[data-ub-composite-done]").click()
    page.wait_for_function("document.querySelector('[data-ub-status]').textContent.indexOf('CP-07') >= 0")
    assert page.locator("[data-ub-composite]").is_visible()                                 # still inside: not taken
    r = page.request.post(url + "/ui/api/chips/check", data=dict(chip(page), body=dict(chip(page)["body"], board={"chips": {"t": {"use": "filterable-table@2", "supply": {"connector": "trading/trade-search"}}}, "traces": []})))
    assert any(p["rule"] == "CP-07" and p["path"].endswith(".supply") for p in r.json()["problems"])
    page.locator("[data-ub-composite-cancel]").click()
    assert page.errors == []


def test_make_a_chip_from_two_wired_chips(page, stack):
    url, be = stack
    page.goto(url + "/build/board/new?advanced=1")
    page.wait_for_selector('.ub-pal-add[data-chip="search-box"]')
    page.evaluate("window.drishtiUiBoard.designer.addInstance('search-box@1')")
    page.wait_for_function("window.drishtiUiBoard.designer.doc.chips['search-box']")
    page.evaluate("window.drishtiUiBoard.designer.addInstance('filterable-table@2')")
    page.wait_for_function("window.drishtiUiBoard.designer.doc.chips['filterable-table']")
    page.evaluate("""const D = window.drishtiUiBoard.designer; D.doc.chips['filterable-table'].supply = { connector: 'trading/trade-search' };
        D.doc.chips['filterable-table'].map = { 'rows.id': 'TRADE_ID' }; D.addTrace('search-box.filter-out', 'filterable-table.filter', 'filter', 'filter');""")
    page.locator("[data-ub-make-chip]").click()
    page.locator("[data-ub-make-dlg] input[data-make-inst='search-box']").check()
    page.locator("[data-ub-make-dlg] input[data-make-inst='filterable-table']").check()
    page.fill("[data-ub-make-name]", "trade-finder")
    page.locator("[data-ub-make-dlg] button.btn-accent").click()
    page.wait_for_function("window.drishtiUiBoard.designer.doc.chips['trade-finder']")
    d = doc_of(page)
    assert set(d["chips"]) == {"trade-finder"} and d["chips"]["trade-finder"]["supply"] == {"connector": "trading/trade-search"}
    assert d["chips"]["trade-finder"]["map"] == {"filterable-table-rows.id": "TRADE_ID"}
    c = chip(page)
    assert c["body"]["board"]["traces"] == [{"from": "search-box.filter-out", "to": "filterable-table.filter"}]
    assert "filterable-table-rows" in c["pins"]["in"] and "filterable-table-filter" not in c["pins"]["in"] and "filterable-table-selected" in c["pins"]["out"]
    assert not any((i or {}).get("supply") for i in c["body"]["board"]["chips"].values())
    page.wait_for_function("document.querySelector('[data-ub-status]').textContent.indexOf('Made the chip trade-finder@1') >= 0")
    assert "trade-finder" in be.drafts
    assert page.errors == []


def test_a_candidate_opens_in_chip_mode_and_a_design_opens_as_a_converted_board(page, stack):
    url, be = stack
    page.goto(url + "/build/chip/new")
    page.wait_for_selector("[data-candidate='core/core-book/local-limits']")
    page.locator("[data-candidate='core/core-book/local-limits']").click()
    page.wait_for_function("window.drishtiUiBoard.chipEditor.doc.chip === 'core-limits'")
    assert "CP-01 manual.behaviour" in problems(page)                      # the manual's prompts show what is missing
    sutra = "rachana: 1\nsutra: book\nversion: 1\nmatch: { kind: book, priority: 10 }\npanels:\n  - { id: limits, kind: table, rows: $.LIMITS }\n"

    async def designs(method, path, ident, body=None, **params):
        return {"id": "d-9", "name": "Book", "kind": "book", "sutra": sutra}
    be.designs = designs
    page.goto(url + "/build/board/new?design=d-9&advanced=1")
    page.wait_for_function("window.drishtiUiBoard.designer.doc.chips && window.drishtiUiBoard.designer.doc.chips.limits")
    d = doc_of(page)
    assert d["board"] == "core-book" and d["chips"]["limits"]["use"] == "local-limits@1" and d["params"]["id"]["of"] == "core/book"
    assert "candidate" in page.locator("[data-ub-status]").text_content()
    assert page.errors == []


def test_build_new_offers_board_and_chip_examples_that_open_in_their_mode(page, stack):
    url, be = stack
    page.goto(url + "/build/new")
    kinds = page.eval_on_selector_all("[data-ui-example-card]", "cs => cs.map(c => c.dataset.kind)")
    assert "board" in kinds and "chip" in kinds
    assert "open as a board" in page.locator("#bsExH").text_content().lower()           # the screen examples are one list, opening as boards
    page.locator('[data-ui-example="master-detail"]').click()
    page.wait_for_url("**/build/board/**")
    page.wait_for_function("window.drishtiUiBoard && window.drishtiUiBoard.designer && window.drishtiUiBoard.designer.mode === 'board' && window.drishtiUiBoard.designer.doc.chips && window.drishtiUiBoard.designer.doc.chips.card")
    assert page.locator("[data-ub-page]").is_visible() and page.input_value("[data-ub-name]") == "master-detail"   # the live page (concept A)
    page.goto(url + "/build/new")
    page.locator('[data-ui-example="entity-list"]').click()
    page.wait_for_url("**/build/chip/**")
    page.wait_for_function("window.drishtiUiBoard && window.drishtiUiBoard.chipEditor && window.drishtiUiBoard.designer.mode === 'chip' && window.drishtiUiBoard.chipEditor.doc.chip === 'entity-list'")
    assert page.locator("[data-ub-chip-mode]").is_visible() and "board" in page.evaluate("window.drishtiUiBoard.chipEditor.doc.body")
    page.goto(url + "/build/chip/new?example=filterable-table")
    page.wait_for_function("window.drishtiUiBoard.chipEditor.doc.chip === 'filterable-table-mine'")
    assert page.errors == []
