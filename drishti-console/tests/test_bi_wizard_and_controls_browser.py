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

"""A business user builds BI without typing YAML, in Chromium over the real server and console (tests/bi_report_stack.py on ports 18932 and 17932, security on, a small
file lake read through the connector ``trading-store``, and through ``trade-endur``, the route of kind trade since 2.3.0, which the wizard offers).

One story in order: the dataset wizard builds a dataset from a connector (tick columns, set a type, add an aggregate measure and a formula with field suggestions, a daily
refresh), the review shows the YAML it wrote and the server's check, Save makes a draft; a report is started on it from the wizard's own link; in the designer a slicer becomes
a relative-date slicer with a default and chosen windows, a "top 3 by a measure" filter is added in the Filters pane, and a bookmark keeps the page's filters and a slicer
choice (then is renamed); the item page duplicates the report into a chosen workspace and shows what depends on the dataset before a delete (and the workspace list shows it
in the confirmation); "Edit in the wizard" opens the saved dataset and writes it back with a key the wizard has no control for kept; a dataset with no schedule gets one from
the dataset page; a Python item starts from a form with a valid commented body; the designer start copies a shipped sample into a chosen workspace; a member is found by
typing part of a name. The wizard has no horizontal scroll at 390 px and nothing violates the policy.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import os
import re

import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18932")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17932")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import clean, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, api  # noqa: E402

WES = ("wes", ["bi-writer"])
state = {}


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = bi_report_stack.ReportStack(tmp_path_factory.mktemp("biwizard"), delta_root="").start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    yield s
    s.close()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


@pytest.fixture(scope="module")
def page(browser):
    p = sign_in(browser, "wes", "/bi/datasets")
    yield p
    p.context.close()


def item(item_id):
    s, r = api("GET", f"/bi/items/{item_id}", *WES)
    assert s == 200, r
    return r


# ---- a table and a model of the layer, then a report on the model ------------------------------------------------------------------

TABLE = """rachana: 3
table: bi.wiz_trades_trade
version: 1.0.0
title: Wizard trades
from: { kind: trade, connector: trading-store }
keys: [id]
columns:
  desk:      { path: "$.desk", type: string, description: The desk that owns the trade }
  book:      { path: "$.book", type: string }
  currency:  { path: "$.currency", type: string }
  notional:  { path: "$.notional", type: decimal }
  tradeDate: { path: "$.tradeDate", type: date }
rules:
  - { role: [bi-writer, bi-reader, bi-steward, admin], where: "true" }
"""
MODEL = """rachana: 3
model: wiz-trades
version: 1.0.0
title: Wizard trades
tables:
  trade: { table: bi.wiz_trades_trade, role: fact }
measures:
  Gross notional:   { expr: "sum(trade.notional)" }
  Average notional: { expr: "avg(trade.notional)" }
  Trades:           { expr: "count(trade)" }
"""


def test_a_table_and_its_model_are_published_in_a_workspace(stack, page):
    """Tables and models are written in Build › Tables & models (the dataset wizard is gone); here they are seeded through the API."""
    s, ws = api("POST", "/bi/workspaces", *WES, {"name": "Wizard Lab", "description": "where the tables and models live"})
    assert s in (200, 201), ws
    state["ws"] = ws["id"]
    for kind, title, body in (("table", "Wizard trades table", TABLE), ("model", "Wizard trades", MODEL)):
        s, it = api("POST", f"/bi/workspaces/{state['ws']}/items", *WES, {"kind": kind, "title": title, "description": "", "body": body})
        assert s in (200, 201), it
        s, r = api("POST", f"/bi/items/{it['id']}/publish", *WES, {"note": "first"})
        assert s == 200, r
        state[kind] = it["id"]
    state["dataset"] = state["model"]


def test_a_report_is_started_on_the_model_and_gets_a_relative_date_slicer_a_top_filter_and_a_bookmark(stack, page):
    page.goto(f"{CONSOLE_URL}/bi/designer?dataset=mq:wiz-trades@1")
    page.wait_for_selector("[data-new-dataset]")
    try:
        page.wait_for_function("() => document.querySelector('[data-new-dataset]').value === 'mq:wiz-trades@1'", timeout=8000)
    except Exception:
        raise AssertionError(page.url + " | " + str(page.evaluate("[...document.querySelector('[data-new-dataset]').options].map(o => o.value)")))
    expect(page.locator("[data-new-workspace] option").first).to_be_attached()
    page.fill("[data-new-title]", "Wizard report")
    page.click("[data-new] button[type=submit]")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    state["report"] = re.sub(r".*/bi/design/", "", page.url).replace("%3A", ":")
    # a slicer on the date field: click the field, make it a slicer, then make it a relative-date one
    page.click('[data-field="trade.tradeDate"]')
    page.click('[data-types] [data-type="slicer"]')
    page.click('[data-ptab="format"]')
    page.select_option("[data-format-mode]", "relative-date")
    page.select_option("[data-format-default]", "last-30-days")
    page.uncheck('[data-format-options] [data-option="last-90-days"]')
    page.uncheck('[data-format-options] [data-option="last-12-months"]')
    expect(page.locator("[data-format-default] option")).not_to_have_count(0)
    # a top filter in the Filters pane
    page.click('[data-add-filter="report"]')
    page.select_option('[data-filter-editor] [name="field"]', "trade.desk")
    page.select_option('[data-filter-editor] [name="mode"]', "top")
    page.fill('[data-filter-editor] [name="n"]', "3")
    page.click('[data-filter-editor] button[type=submit]')
    expect(page.locator("[data-filters] .bi-filter-text")).to_contain_text("top 3")
    # a bookmark of the page (with its filters and a slicer choice); the report-level filter is not the page's, so add one on the page too
    page.click('[data-add-filter="page"]')
    page.select_option('[data-filter-editor] [name="field"]', "trade.currency")
    page.fill('[data-filter-editor] [name="values"]', "USD")
    page.click('[data-filter-editor] button[type=submit]')
    page.evaluate("window.drishtiBi.design.select(null)")                                                # what a click on the empty canvas does: the report's own settings show
    page.click('[data-ptab="format"]')
    page.fill("[data-bookmark-name]", "USD view")
    page.select_option("[data-bookmark-slicer]", "last-7-days")
    page.click("[data-add-bookmark]")
    expect(page.locator('[data-bookmark="USD view"]')).to_contain_text("1 filter")
    page.fill('[data-bookmark-rename="0"]', "USD last week")
    page.press('[data-bookmark-rename="0"]', "Tab")
    expect(page.locator('[data-bookmark="USD last week"]')).to_be_visible()
    page.click("[data-save]")
    page.wait_for_function("() => /^Saved/.test((document.querySelector('[data-save] + .bi-near') || {}).textContent || '')", timeout=20000)
    body = item(state["report"])["body"]
    assert "mode: relative-date" in body and "default: last-30-days" in body and "last-90-days" not in body.split("options:")[1].split("]")[0]
    assert "op: top" in body and "n: 3" in body and "USD last week" in body and "relative: last-7-days" in body
    clean(page)


def test_duplicate_and_delete_show_what_depends(stack, page):
    page.goto(f"{CONSOLE_URL}/bi/i/{state['report']}")
    page.get_by_role("button", name="Duplicate…").click()
    expect(page.locator("[data-ask-target] option").first).to_be_attached()
    page.click("[data-ask-go]")
    page.wait_for_url(re.compile(r"/bi/i/(?!" + re.escape(state["report"]) + r"$)"))
    copy = re.sub(r".*/bi/i/", "", page.url).replace("%3A", ":")
    assert copy != state["report"] and item(copy)["kind"] == "report" and item(copy)["title"].endswith("(copy)")
    # the model: both reports read it
    page.goto(f"{CONSOLE_URL}/bi/i/{state['dataset']}")
    page.get_by_role("button", name="Delete", exact=True).click()
    expect(page.locator("[data-delete-refused]")).to_contain_text("2 items use this model")                # a model is shared: not deleted while used
    expect(page.locator("[data-delete-refused]")).to_contain_text("Wizard report")
    # the workspace list says the same in its confirmation, and Cancel keeps everything
    page.goto(f"{CONSOLE_URL}/bi/w/{state['ws']}")
    page.wait_for_selector("[data-items] tbody tr")
    page.check(f'[data-pick="{state["dataset"]}"]')
    page.click("[data-delete-selected]")
    expect(page.locator(f'[data-confirm-item="{state["dataset"]}"] [data-impact]')).to_have_attribute("data-impact", "2")
    page.click("[data-confirm-no]")
    assert item(state["dataset"])["kind"] == "model"
    state["copy"] = copy
    clean(page)


# ---- the small gaps -----------------------------------------------------------------------------------------------------------------------

def test_a_python_item_starts_from_a_form_with_a_valid_body(stack, page):
    page.goto(f"{CONSOLE_URL}/bi/w/me")
    page.wait_for_selector("[data-new-item]:not([hidden])")
    page.select_option('[data-new-item] [name="kind"]', "python")
    expect(page.locator("[data-python-row]")).to_be_visible()
    page.select_option('[data-new-item] [name="pyinterface"]', "measure")
    page.fill('[data-new-item] [name="title"]', "Mean notional")
    page.fill('[data-new-item] [name="pyinputs"]', "notional")
    page.click("[data-create]")
    page.wait_for_url(re.compile(r"/bi/i/.*edit=1"))
    page.wait_for_selector("[data-edit]:not([hidden])")
    body = page.input_value('[data-edit] [name="body"]')
    assert "interface: measure" in body and "inputs: [notional]" in body and "def compute(table, params)" in body and "# " in body
    page.get_by_role("button", name="Check the Python").click()
    page.wait_for_selector('[data-py-check]', timeout=30000)
    assert page.locator('[data-py-check]').first.get_attribute('data-py-check') == 'ok', page.inner_text('[data-warnings]')
    clean(page)


def test_the_designer_start_copies_a_sample_into_a_chosen_workspace_and_lists_drafts_everywhere(stack, page):
    s, ws = api("POST", "/bi/workspaces", *WES, {"name": "Wizard Desk", "description": "for the copy"})
    assert s in (200, 201), ws
    page.goto(f"{CONSOLE_URL}/bi/designer")
    page.wait_for_selector("[data-sample-copy]", timeout=20000)
    assert page.locator("[data-new-workspace] option").count() >= 2                                      # private first, then the shared one
    page.locator("[data-sample-copy]").first.click()
    page.select_option("[data-ask-target]", ws["id"])
    page.click("[data-ask-go]")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    copied = re.sub(r".*/bi/design/", "", page.url).replace("%3A", ":")
    assert item(copied)["workspace"] == ws["id"]
    page.goto(f"{CONSOLE_URL}/bi/designer")
    page.wait_for_function(f"() => [...document.querySelectorAll('[data-drafts] li')].some(li => li.textContent.includes('Wizard Desk'))")
    clean(page)


def test_a_member_is_found_by_typing_part_of_a_name(stack, page):
    s, ws = api("POST", "/bi/workspaces", *WES, {"name": "People Desk", "description": ""})
    assert s in (200, 201), ws
    page.goto(f"{CONSOLE_URL}/bi/w/{ws['id']}")
    page.click("[data-share-hint] a")                                                       # a workspace with only you in it says where people are added (BIR-14)
    page.wait_for_selector('[data-ws-tab="members"][aria-selected="true"]')
    assert "tab=members" in page.url
    page.wait_for_selector("[data-add-member]:not([hidden])")
    expect(page.locator("[data-member-user]")).to_be_visible()
    page.fill("[data-member-user]", "ri")
    page.wait_for_selector(".di-combo-pop [role=option]", timeout=10000)
    page.click(".di-combo-pop [role=option]:has-text('rita')")
    assert page.input_value("[data-member-user]") == "rita"
    clean(page)


# ---- saving safely: two tabs, a frozen draft, a refresh, add visual, one bad visual -------------------------------------------------------------

def make_report(title, visuals, workspace=None):
    body = f"rachana: 1\nreport: {title.lower().replace(' ', '-')}\ntitle: {title}\nmodel: wiz-trades@1\npages:\n  - id: page1\n    title: Page 1\n    visuals:\n{visuals}"
    if not visuals:
        body = body.replace("    visuals:\n", "    visuals: []\n")
    s, r = api("POST", f"/bi/workspaces/{workspace or state['ws']}/items", *WES, {"kind": "report", "title": title, "description": "", "body": body})
    assert s in (200, 201), r
    return r["id"]


def test_two_tabs_on_one_draft_the_second_save_is_refused_and_offers_reload_copy_or_overwrite(stack, browser):
    rid = make_report("Two tabs", "")
    a = sign_in(browser, "wes", "/bi/designer")
    b = a.context.new_page()
    for pg in (a, b):
        pg.goto(f"{CONSOLE_URL}/bi/design/{rid}")
        pg.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    b.click("[data-add-visual]")                                                                         # an explicit Add visual
    expect(b.locator("[data-canvas-grid] .bi-vis")).to_have_count(1)
    b.click("[data-save]")
    b.wait_for_function("() => /^Saved/.test((document.querySelector('[data-save] + .bi-near') || {}).textContent || '')", timeout=20000)
    a.click("[data-add-visual]")
    a.click("[data-save]")
    a.wait_for_selector("[data-stale-choice]", timeout=20000)                                            # 409 DRS-9007: saved elsewhere since you opened it
    for want in ("data-stale-reload", "data-stale-copy", "data-stale-overwrite"):
        assert a.locator(f"[{want}]").count() == 1, want
    assert "visuals: []" not in item(rid)["body"] and item(rid)["body"].count("type:") == 1               # nothing of tab A was written
    a.click("[data-stale-overwrite]")
    a.wait_for_function("() => /^Saved/.test((document.querySelector('[data-save] + .bi-near') || {}).textContent || '')", timeout=20000)
    assert item(rid)["body"].count("type:") == 1                                                          # A overwrote with its own single visual
    clean(a)
    a.context.close()


def test_a_proposed_draft_opens_read_only_with_a_banner_instead_of_failing_on_save(stack, browser):
    rid = make_report("Frozen", "")
    s, r = api("POST", f"/bi/items/{rid}/propose", *WES, {"note": "please"})
    assert s == 200, r
    pg = sign_in(browser, "wes", "/bi/designer")
    pg.goto(f"{CONSOLE_URL}/bi/design/{rid}")
    pg.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    expect(pg.locator("[data-readonly]")).to_contain_text("Proposed for review")
    expect(pg.locator("[data-save]")).to_be_disabled()
    expect(pg.locator("[data-withdraw]")).to_be_visible()
    clean(pg)
    pg.context.close()


def test_one_broken_visual_is_one_error_card_and_the_others_are_drawn_for_a_reader(stack, browser):
    rid = make_report("One bad visual", "      - { id: good, type: card, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Gross notional] } }\n"
                                        "      - { id: bad, type: card, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [NoSuchMeasure] } }\n")
    pg = sign_in(browser, "wes", "/bi/catalogue")
    pg.goto(f"{CONSOLE_URL}/bi/r/{rid}")
    pg.wait_for_selector("[data-ready=yes]", timeout=30000)
    pg.wait_for_function("() => !document.querySelector('.bi-vis-busy') && document.querySelectorAll('.bi-vis').length === 2", timeout=30000)
    expect(pg.locator(".bi-vis-failed")).to_have_count(1)
    expect(pg.locator(".bi-vis:not(.bi-vis-failed) .bi-vis-ms")).to_have_count(1)
    clean(pg)
    pg.context.close()


# ---- a board page: a draft report's tile, publishing over a draft; view as readers see it; ids follow the type; Back after sign-in ----------------------

REPORT_BODY = ("report: kpis\nmodel: trades-basic@1\npages:\n  - id: main\n    title: Main\n    visuals:\n"
               "      - { id: card-1, type: card, title: Trades, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Trades] } }\n")
EMPTY_BOARD = "rachana: 1\nboard: kpi-board\ntitle: KPI board\npages:\n  - id: page1\n    title: Page 1\n    tiles: []\n"


def shared_workspace(name):
    s, ws = api("POST", "/bi/workspaces", *WES, {"name": name, "description": ""})
    assert s in (200, 201), ws
    return ws["id"]


def make(ws, kind, title, body):
    s, it = api("POST", f"/bi/workspaces/{ws}/items", *WES, {"kind": kind, "title": title, "body": body})
    assert s in (200, 201), it
    return it["id"]


def test_a_board_page_lists_a_draft_report_draws_its_tile_and_refuses_to_publish_over_the_draft(stack, page):
    """2.4.0: a board is a report with board pages, designed in the report designer; an item made as a board is stored as one."""
    ws = shared_workspace("Board Lab")
    rep, brd = make(ws, "report", "Draft KPIs", REPORT_BODY), make(ws, "board", "KPI board", EMPTY_BOARD)
    assert item(brd)["kind"] == "report" and "layout: board" in item(brd)["body"]
    page.goto(f"{CONSOLE_URL}/bi/design/board/{brd}")                                                    # the 2.3 address opens the report designer
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    assert "/bi/design/board/" not in page.url
    page.click("[data-design-root] [data-title]")
    page.keyboard.press("Escape")
    page.click('[data-types] [data-type="tile"]')
    page.wait_for_function("() => { const v = window.drishtiBi.design.sel(); return v && v.type === 'tile'; }")
    tile = page.evaluate("window.drishtiBi.design.sel().id")
    expect(page.locator(f'[data-design-root] [data-visual="{tile}"] [data-tile-placeholder]')).to_have_count(1)      # nothing chosen yet: it says what to do
    page.click("[data-ptab='format']")
    option = page.locator(f'[data-tile-report] option[value="{rep}"]')
    expect(option).to_have_text("Draft KPIs (draft)")                                                    # the author's own draft is offered, labelled
    page.select_option("[data-tile-report]", rep)
    page.wait_for_function("() => document.querySelectorAll('[data-tile-visual] option').length > 1")
    page.select_option("[data-tile-visual]", "main/card-1")
    expect(page.locator(f'[data-design-root] [data-visual="{tile}"] .bi-card-value')).to_have_count(1, timeout=20000)    # drawn with the report's numbers
    page.click("[data-save]")
    page.wait_for_function("() => /^Saved/.test((document.querySelector('[data-save] + .bi-near') || {}).textContent || '')", timeout=20000)
    assert "from: { report: " in item(brd)["body"] and "visual: card-1" in item(brd)["body"]
    # publishing the board is refused, in words, while the report it shows is a draft
    page.goto(f"{CONSOLE_URL}/bi/i/{brd}")
    page.get_by_role("button", name="Publish", exact=True).click()
    expect(page.locator("[data-actions] .bi-near.bi-bad")).to_contain_text("Publish it first")
    s, r = api("POST", f"/bi/items/{rep}/publish", *WES, {})
    assert s == 200, r
    page.get_by_role("button", name="Publish", exact=True).click()
    expect(page.locator("[data-actions] .bi-near").first).to_contain_text("Published")                   # and the success is beside the same button
    assert item(brd)["status"] == "published"
    clean(page)


def test_view_as_readers_see_it_shows_the_published_version_not_the_draft(stack, page):
    ws = shared_workspace("Reader Lab")
    rep = make(ws, "report", "Two views", REPORT_BODY)
    never = make(ws, "report", "Never out", REPORT_BODY)
    s, r = api("POST", f"/bi/items/{rep}/publish", *WES, {})
    assert s == 200, r
    two = REPORT_BODY + "      - { id: card-2, type: card, title: Notional, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [Notional] } }\n"
    s, r = api("PUT", f"/bi/items/{rep}", *WES, {"body": two})
    assert s == 200, r
    page.goto(f"{CONSOLE_URL}/bi/design/{never}")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    assert page.locator('[data-view-as] option[value="published"]').get_attribute("disabled") is not None       # never published: nothing to look at
    page.goto(f"{CONSOLE_URL}/bi/design/{rep}")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    assert page.locator('[data-view-as] option[value="published"]').get_attribute("disabled") is None
    page.select_option("[data-view-as]", "published")
    assert "as=published" in page.get_attribute("[data-view-link]", "href")
    page.click("[data-view-link]")
    page.wait_for_selector("[data-published-banner]")
    expect(page.locator("[data-published-banner]")).to_contain_text(re.compile("published version 1.*draft has changes"))
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    expect(page.locator(".bi-vis")).to_have_count(1)                                                     # the published report has one visual
    page.goto(f"{CONSOLE_URL}/bi/r/{rep}")
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    expect(page.locator(".bi-vis")).to_have_count(2)                                                     # the draft has two
    assert page.locator("[data-published-banner]").count() == 0
    clean(page)


def test_a_visual_id_follows_its_type_and_what_named_it_follows_the_id(stack, page):
    ws = shared_workspace("Id Lab")
    body = ("report: ids\nmodel: trades-basic@1\nbookmarks:\n  - { name: USD only, page: main, slicers: { slicer-1: [USD] } }\n"
            "layouts:\n  phone:\n    main: { order: [card-1, slicer-1], hide: [slicer-1], span: { card-1: 3 } }\n"
            "pages:\n  - id: main\n    title: Main\n    visuals:\n"
            "      - { id: slicer-1, type: slicer, title: Currency, at: { x: 0, y: 0, w: 3, h: 3 }, wells: { field: [trade.currency] } }\n"
            "      - { id: card-1, type: card, title: Trades, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [Trades] } }\n")
    rep = make(ws, "report", "Ids", body)
    page.on("dialog", lambda d: d.accept())
    page.goto(f"{CONSOLE_URL}/bi/design/{rep}")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    page.evaluate("window.drishtiBi.design.select('card-1')")
    page.click('[data-types] [data-type="bar"]')
    m = page.evaluate("window.drishtiBi.design.model")
    ids = [v["id"] for v in m["pages"][0]["visuals"]]
    assert "bar-1" in ids and "card-1" not in ids, ids
    phone = m["layouts"]["phone"]["main"]
    assert "bar-1" in phone["order"] and "card-1" not in phone["order"] and "bar-1" in phone["span"] and "card-1" not in phone["span"], phone
    page.evaluate("window.drishtiBi.design.select('slicer-1')")
    page.click('[data-types] [data-type="table"]')
    m = page.evaluate("window.drishtiBi.design.model")
    assert "table-1" in [v["id"] for v in m["pages"][0]["visuals"]]
    assert list(m["bookmarks"][0]["slicers"]) == ["table-1"] and "table-1" in m["layouts"]["phone"]["main"]["hide"], m["bookmarks"]
    page.click("[data-undo]")                                                                            # Undo brings the old id and its references back together
    m = page.evaluate("window.drishtiBi.design.model")
    assert list(m["bookmarks"][0]["slicers"]) == ["slicer-1"] and "slicer-1" in m["layouts"]["phone"]["main"]["hide"], m
    clean(page)


def test_back_from_the_landing_page_does_not_show_the_sign_in_form_again(stack, browser):
    ctx = browser.new_context()
    pg = ctx.new_page()
    pg.goto(CONSOLE_URL + "/login?next=/bi/catalogue")
    pg.fill("#lu", "wes")
    pg.fill("#lp", bi_report_stack.PASSWORD)
    pg.click("button[type=submit]")
    pg.wait_for_url("**/bi/catalogue")
    pg.go_back()                                                                                         # the form is never kept: signed in, it sends you on
    pg.wait_for_url("**/bi/catalogue")
    assert pg.locator("#lu").count() == 0
    pg.goto(CONSOLE_URL + "/login?next=/bi/workspaces")                                                  # asking for it signed in goes straight on
    pg.wait_for_url("**/bi/workspaces")
    ctx.close()


def test_a_range_slicer_two_rows_high_shows_both_from_and_to_and_hints_at_an_inverted_range(stack, browser):
    ws = shared_workspace("Range Lab")
    body = ("report: range\nmodel: trades-basic@1\npages:\n  - id: main\n    title: Main\n    visuals:\n"
            "      - { id: slicer-1, type: slicer, title: Notional, at: { x: 0, y: 0, w: 4, h: 2 }, format: { mode: range }, wells: { field: [trade.NOTIONAL] } }\n"
            "      - { id: card-1, type: card, title: Trades, at: { x: 4, y: 0, w: 3, h: 2 }, wells: { values: [Trades] } }\n")
    rep = make(ws, "report", "Range", body)
    page = sign_in(browser, "wes")
    page.goto(f"{CONSOLE_URL}/bi/r/{rep}")
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    expect(page.locator('[data-range="to"]')).to_be_visible(timeout=30000)
    box = page.locator(".bi-vis-slicer").bounding_box()
    for which in ("from", "to"):
        b = page.locator(f'[data-range="{which}"]').bounding_box()
        assert b["y"] >= box["y"] and b["y"] + b["height"] <= box["y"] + box["height"] + 1, (which, b, box)      # inside the visual: nothing is clipped
    page.fill('[data-range="from"]', "9000")
    page.fill('[data-range="to"]', "100")
    page.press('[data-range="to"]', "Tab")
    expect(page.locator("[data-range-hint]")).to_contain_text("From is after To")
    page.close()
