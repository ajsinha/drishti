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

"""Deleting from the workspace list, designer errors and several datasets in the designer, in Chromium over the real server and console (tests/bi_report_stack.py on
ports 18934 and 17934, security on, a small file lake; a second copy of the sample dataset, ``trades-second``, is added so a report can read two datasets).

The workspace list: a checkbox for each item you may delete, Select all, "Delete selected (N)" with one confirmation that lists the titles, a Delete in each row, a refused
item reported as skipped while the others are deleted, a short id and a created time on each row, and the New item form that asks a report for its dataset and opens the
designer on it. The designer: an incomplete visual shows its own "add a field" note while the others still draw and the Check list names the visual, a text field dropped on a
table joins its columns and a field already there is not called a misfit, a report with no dataset asks for one. Several datasets: Add dataset, a tree for each, a dataset tag on a
visual, a field of another dataset refused in a visual that reads one, a link ticked in the Links panel, a composed dataset offered as a draft to review, and a slicer that filters
the visuals of both datasets in the viewer.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import os
import re

import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18934")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17934")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import card_value, clean, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, ROOT, api  # noqa: E402

WES = ("wes", ["bi-writer"])


class Stack(bi_report_stack.ReportStack):
    def prepare(self, work):
        text = (ROOT / "config/bi/datasets/trades-basic.dataset.yaml").read_text(encoding="utf-8")
        text = text.replace("dataset: trades-basic", "dataset: trades-second", 1).replace("title: Trades, the basics", "title: Trades, second copy", 1)
        (work / "config/bi/datasets/trades-second.dataset.yaml").write_text(text, encoding="utf-8")


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = Stack(tmp_path_factory.mktemp("bidesign"), delta_root="").start()
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


def make(kind, title, body="", workspace="me"):
    s, r = api("POST", f"/bi/workspaces/{workspace}/items", *WES, {"kind": kind, "title": title, "description": "", "body": body})
    assert s in (200, 201), r
    return r["id"]


def report(name, visuals, extra="", dataset="trades-basic"):
    return (f"rachana: 1\nreport: {name}\ntitle: {name}\n" + (f"dataset: {dataset}\n" if dataset else "") + extra + "pages:\n  - id: p\n    title: P\n    visuals:\n" + visuals)


def open_designer(page, item):
    page.goto(f"{CONSOLE_URL}/bi/design/{item}")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)


def tree(page, dataset):
    return page.locator(f'[data-dataset-tree="file:dataset:{dataset}"]')


# ---- the workspace list: delete ---------------------------------------------------------------------------------------------------

def test_the_workspace_list_deletes_many_with_one_confirmation_and_reports_the_refused(stack, browser):
    ids = [make("report", "Twin report", report(f"twin-{i}", "")) for i in range(2)]                 # two items with one title: told apart by the short id
    keep = make("report", "Delete me too", report("delete-me-too", ""))
    page = sign_in(browser, "wes", "/bi/w/me")
    page.wait_for_selector("[data-items] tbody tr")
    rows = page.locator("[data-items] tbody tr")
    expect(page.locator("[data-delete-selected]")).to_have_text("Delete selected (0)")
    expect(page.locator("[data-delete-selected]")).to_be_disabled()
    twin = page.locator("[data-items] tbody tr", has_text="Twin report")
    assert twin.count() == 2
    short = [twin.nth(i).locator(".bi-shortid").inner_text() for i in range(2)]
    assert short[0] != short[1] and all(len(x) >= 6 for x in short)                                     # a short id and a created time on each row
    assert re.search(r"\d{4}-\d\d-\d\d \d\d:\d\d", twin.nth(0).inner_text())
    assert page.locator("[data-pick]").count() == rows.count()                                           # wes may delete everything in his own workspace
    # Select all, then un-tick one: the button counts the rest and one confirmation lists their titles
    page.check("[data-select-all]")
    expect(page.locator("[data-delete-selected]")).to_have_text(f"Delete selected ({rows.count()})")
    for pick in page.locator("[data-pick]").all():
        if pick.get_attribute("data-pick") not in ids + [keep]:
            pick.uncheck()
    expect(page.locator("[data-delete-selected]")).to_have_text("Delete selected (3)")
    refused = ids[1]
    page.route(f"**/bi/api/items/{refused}", lambda route: route.fulfill(status=409, content_type="application/json", body='{"code":"DRS-9004","detail":"other items depend on this one"}')
               if route.request.method == "DELETE" else route.continue_())
    page.click("[data-delete-selected]")
    expect(page.locator("[data-confirm]")).to_be_visible()
    listed = page.locator("[data-confirm-list] li").all_inner_texts()
    assert len(listed) == 3 and sum("Twin report" in x for x in listed) == 2 and any("Delete me too" in x for x in listed)
    page.click("[data-confirm-yes]")
    page.wait_for_function("() => /Skipped/.test(document.querySelector('[data-msg]').textContent)")
    msg = page.inner_text("[data-msg]")
    assert "Deleted 2 items" in msg and "other items depend on this one" in msg
    assert page.locator(f'[data-items] tbody tr[data-id="{refused}"]').count() == 1                     # the refused one is still listed
    expect(page.locator(f'[data-items] tbody tr[data-id="{ids[0]}"]')).to_have_count(0)                   # the list reloads
    # a Delete in the row: the same confirmation, for that one item
    page.unroute(f"**/bi/api/items/{refused}")
    page.click(f'[data-row-delete="{refused}"]')
    expect(page.locator("[data-confirm-list] li")).to_have_count(1)
    page.click("[data-confirm-yes]")
    page.wait_for_function("() => /Deleted 1 item/.test(document.querySelector('[data-msg]').textContent)")
    expect(page.locator(f'[data-items] tbody tr[data-id="{refused}"]')).to_have_count(0)
    clean(page)


def test_the_delete_controls_are_finger_sized_on_a_phone(stack, browser):
    make("report", "Phone item", report("phone-item", ""))
    page = sign_in(browser, "wes", "/bi/w/me", viewport={"width": 390, "height": 800}, has_touch=True, is_mobile=True)
    page.wait_for_selector("[data-items] tbody tr [data-pick]")
    for sel in ("[data-pick]", "[data-row-delete]", "[data-delete-selected]", "[data-select-all]"):
        box = page.locator(sel).first.bounding_box()
        assert box["height"] >= 43.5 and box["width"] >= 43.5 or sel == "[data-delete-selected]" and box["height"] >= 43.5, (sel, box)


def test_a_new_report_from_the_workspace_asks_for_its_dataset_and_a_new_title_is_distinct(stack, browser):
    page = sign_in(browser, "wes", "/bi/w/me")
    page.wait_for_selector("[data-new-item]")
    expect(page.locator("[data-dataset-row]")).to_be_visible()                                          # report is the first kind: it needs a dataset
    page.click("[data-create]")
    assert "Choose a dataset" in page.inner_text("[data-msg]")
    page.wait_for_function("() => document.querySelector('[data-new-dataset]').options.length > 1")
    page.select_option("[data-new-dataset]", index=1)
    page.click("[data-create]")                                                                         # the title is empty
    page.wait_for_url("**/bi/design/**")
    page.wait_for_selector("[data-design-root][data-ready=yes]")
    assert re.fullmatch(r"Report \d{4}-\d\d-\d\d \d\d:\d\d", page.inner_text("[data-title]")), page.inner_text("[data-title]")
    page.goto(CONSOLE_URL + "/bi/w/me")                                                                 # a board opens the board designer, a notebook goes to Analyse
    page.select_option("[data-new-item] [name=kind]", "board")
    page.click("[data-create]")
    page.wait_for_url("**/bi/design/board/**")
    page.goto(CONSOLE_URL + "/bi/w/me")
    page.select_option("[data-new-item] [name=kind]", "notebook")
    page.click("[data-create]")
    page.wait_for_url("**/bi/explore**")
    clean(page)


def test_a_report_with_no_dataset_asks_for_one_in_the_designer(stack, browser):
    item = make("report", "No dataset yet", report("no-dataset", "", dataset=""))
    page = sign_in(browser, "wes", "/bi/w/me")
    page.goto(f"{CONSOLE_URL}/bi/design/{item}")
    page.wait_for_selector("[data-choose-dataset]")
    page.select_option("[data-pick-dataset]", index=1)
    page.click("[data-pick-dataset-go]")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    expect(page.locator("[data-state]")).to_have_text("Unsaved changes")                                # the picked dataset is in the draft, not yet saved
    assert page.locator("[data-fields-tree] .bi-fieldtable").count() > 0                                # the dataset's fields are listed: there is something to draw
    clean(page)


def test_the_dataset_page_starts_a_report_on_it(stack, browser):
    page = sign_in(browser, "wes", "/bi/datasets")
    page.goto(CONSOLE_URL + "/bi/d/file:dataset:trades-basic")
    page.wait_for_selector("[data-new-report]:not([hidden])")
    page.click("[data-new-report]")
    page.wait_for_selector("[data-new-dataset]")
    page.wait_for_function("() => document.querySelector('[data-new-dataset]').value === 'file:dataset:trades-basic'")
    clean(page)


# ---- the designer: one incomplete visual, a text field in a table ----------------------------------------------------------------------

def test_an_incomplete_visual_does_not_blank_the_others_and_the_check_names_it(stack, browser):
    visuals = ("      - { id: trades, type: card, wells: { values: [Trades] }, at: { x: 0, y: 0, w: 3, h: 2 } }\n"
               "      - { id: empty-bar, type: bar, title: Empty bar, wells: {}, at: { x: 3, y: 0, w: 4, h: 3 } }\n"
               "      - { id: desks, type: table, wells: { columns: [trade.desk] }, at: { x: 0, y: 3, w: 6, h: 4 } }\n")
    item = make("report", "One incomplete", report("one-incomplete", visuals))
    page = sign_in(browser, "wes", "/bi/w/me")
    open_designer(page, item)
    page.wait_for_function("() => document.querySelector('[data-visual=\"trades\"] .bi-card-value')", timeout=30000)      # the valid card still draws
    page.wait_for_function("() => document.querySelectorAll('[data-visual=\"desks\"] tbody tr').length > 0", timeout=30000)
    assert "Add a" in page.inner_text('[data-visual="empty-bar"]')                                       # its own placeholder, naming what to drop
    assert "axis" in page.inner_text('[data-visual="empty-bar"]') or "values" in page.inner_text('[data-visual="empty-bar"]')
    page.click("[data-check]")
    page.wait_for_selector("[data-check-list] li")
    text = page.inner_text("[data-check-list]")
    assert "empty-bar" in text and "number 2 on page" in text                                            # the banner names the visual: id and position
    clean(page)


def test_a_text_field_dropped_on_a_table_joins_its_columns_and_says_where_when_it_cannot(stack, browser):
    visuals = "      - { id: books, type: table, wells: { columns: [trade.book] }, at: { x: 0, y: 0, w: 4, h: 4 } }\n"
    item = make("report", "Drop a text field", report("drop-text", visuals))
    page = sign_in(browser, "wes", "/bi/w/me")
    open_designer(page, item)
    page.wait_for_selector('[data-visual="books"]')
    page.drag_and_drop('[data-field="trade.desk"]', '[data-visual="books"]')
    page.click('[data-visual="books"] .bi-dhandle')
    page.wait_for_selector('[data-well="columns"] [data-item="trade.desk"]')                             # the text field is a column of the table
    assert "does not fit" not in page.inner_text("[data-msg]")
    page.drag_and_drop('[data-field="trade.desk"]', '[data-visual="books"]')                              # again: already there, which is not a misfit
    page.wait_for_function("() => /already in/.test(document.querySelector('[data-msg]').textContent)")
    n = page.locator(".bi-dvis").count()
    page.drag_and_drop('[data-field="trade.currency"]', "[data-canvas-grid]", target_position={"x": 560, "y": 40})      # between visuals: a suggested visual
    page.wait_for_function(f"() => document.querySelectorAll('.bi-dvis').length === {n + 1}")
    clean(page)


# ---- several datasets ---------------------------------------------------------------------------------------------------------------------

def test_several_datasets_in_the_designer_with_a_link_and_a_slicer_that_filters_both(stack, browser):
    visuals = ("      - { id: main-n, type: card, title: Main trades, wells: { values: [Trades] }, at: { x: 0, y: 0, w: 3, h: 2 } }\n"
               "      - { id: desk, type: slicer, wells: { field: [trade.desk] }, format: { mode: list, scope: report }, at: { x: 9, y: 0, w: 3, h: 5 } }\n")
    item = make("report", "Two datasets", report("two-datasets", visuals))
    page = sign_in(browser, "wes", "/bi/w/me")
    open_designer(page, item)
    expect(page.locator("[data-links-panel]")).to_be_hidden()                                             # one dataset: no links panel
    page.click("[data-add-dataset]")
    page.wait_for_function("() => document.querySelector('[data-dataset-picker]').options.length > 1")
    page.select_option("[data-dataset-picker]", "trades-second")
    page.click("[data-dataset-add-go]")
    expect(page.locator("[data-dataset-tree]")).to_have_count(2)                                          # one collapsible tree for each, labelled
    assert "Trades, second copy" in tree(page, "trades-second").inner_text() and "(main)" in page.locator("[data-dataset-tree]").first.inner_text()
    expect(tree(page, "trades-second").locator("[data-remove-dataset]")).to_be_enabled()                  # nothing reads it yet: it can be removed
    # a measure of the second dataset dropped on the empty canvas makes a visual that reads it, tagged
    page.drag_and_drop('[data-dataset-tree="file:dataset:trades-second"] [data-field="Trades"]', "[data-canvas-grid]", target_position={"x": 300, "y": 250})
    page.wait_for_selector("[data-dataset-tag]")
    assert "Trades, second copy" in page.inner_text("[data-dataset-tag]")
    assert page.locator("[data-dataset-tag]").count() == 1                                                # the main card carries no tag
    expect(tree(page, "trades-second").locator("[data-remove-dataset]")).to_be_disabled()                 # a visual reads it now
    second = page.locator(".bi-dvis:has([data-dataset-tag])").get_attribute("data-visual")
    # a field of the second dataset on the main card, which already reads the main dataset: refused, with the hint
    page.drag_and_drop('[data-dataset-tree="file:dataset:trades-second"] [data-field="trade.desk"]', '[data-visual="main-n"]')
    page.wait_for_function("() => /reads/.test(document.querySelector('[data-msg]').textContent)")
    msg = page.inner_text("[data-msg]")
    assert "another visual, or make a composed dataset" in msg and "Trades, the basics" in msg and "Trades, second copy" in msg
    assert page.locator('[data-visual="main-n"] [data-dataset-tag]').count() == 0
    # the links panel suggests desk = desk (same name and type); ticking writes links: into the report
    expect(page.locator("[data-links-panel]")).to_be_visible()
    tick = page.locator('[data-link-tick="trade.desk = trade.desk"]')
    expect(tick).to_have_count(1)
    tick.check()
    expect(page.locator("[data-links-current] li")).to_have_count(1)
    page.click("[data-tab=yaml]")
    page.wait_for_function("() => /links:/.test(document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value)")
    yaml = page.evaluate("document.querySelector('.CodeMirror') ? document.querySelector('.CodeMirror').CodeMirror.getValue() : document.querySelector('[data-yaml]').value")
    assert "trade.desk" in yaml and f"dataset: trades-second" in yaml
    page.click("[data-tab=canvas]")
    # a composed dataset is offered as text to review, not created
    page.click("[data-make-composed]")
    draft = page.inner_text("[data-composed-yaml]")
    assert "from:" in draft and "join:" in draft and "trades-basic" in draft and "trades-second" in draft
    s, items = api("GET", "/bi/workspaces/me/items", *WES)
    assert not [i for i in items if i["kind"] == "dataset"]                                              # nothing was saved silently
    page.click("[data-save]")
    page.wait_for_function("() => /Saved/.test(document.querySelector('[data-msg]').textContent)")
    # the viewer: the slicer filters both datasets' cards
    page.goto(f"{CONSOLE_URL}/bi/r/{item}")
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    settled(page)
    before = card_value(page, "main-n"), card_value(page, second)
    assert before[0] == before[1] and before[0] > 0
    page.locator('[data-visual="desk"] [data-slicer-value]').first.check()
    page.wait_for_function(f"() => !document.querySelector('.bi-vis-busy') && Number(document.querySelector('[data-visual=\"main-n\"] .bi-card-value').getAttribute('data-value')) < {before[0]}", timeout=30000)
    settled(page)
    after = card_value(page, "main-n"), card_value(page, second)
    assert after[0] == after[1] and 0 < after[0] < before[0]
    clean(page)
