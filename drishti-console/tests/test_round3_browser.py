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

"""QA round 3 in Chromium (the stack of test_bi_explore_browser.py, ports 18956 and 17956): Publish on an item in a private workspace explains itself (item page and the
designer, a board too); one title for a notebook whether the item or its YAML is renamed; Ctrl+S saves from the YAML editors; Analyse fits 390 px with long titles, promotes a cell once
(a second press offers to update that draft) and disables Promote to a Transform until there is a second extract; a dataset can be pasted as YAML; an item nobody may see shows
only the not-found message; a value that rounds to zero is never signed.

Skipped when Playwright, Chromium, the built server jar or pandas and pyarrow are missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import pytest
from playwright.sync_api import expect

from test_bi_explore_browser import browser, open_extract, stack  # noqa: F401  (the module's stack and browser fixtures)
from bi_report_helpers import sign_in
from bi_report_stack import CONSOLE_URL, PASSWORD, admin

REPORT = 'rachana: 1\nreport: pr\ntitle: "Private report"\nmodel: trades-basic@1\npages:\n  - id: page1\n    title: Page 1\n    visuals: []\n'
BOARD = 'rachana: 1\nboard: pb\ntitle: "Private board"\npages:\n  - id: page1\n    title: Page 1\n    tiles: []\n'
MAKE = "([k, t, b]) => window.drishtiBi.api('POST', '/workspaces/me/items', {kind: k, title: t, description: '', body: b}).then(i => i.id)"


def test_publish_in_a_private_workspace_explains_and_ctrl_s_saves_the_yaml(stack, browser):
    page = sign_in(browser, "wes", "/bi")
    rid = page.evaluate(MAKE, ["report", "Private report", REPORT])
    bid = page.evaluate(MAKE, ["board", "Private board", BOARD])
    for iid in (rid, bid):
        page.goto(f"{CONSOLE_URL}/bi/i/{iid}")
        publish = page.locator("[data-publish-private]")
        expect(publish).to_be_disabled()
        expect(page.locator("[data-private-hint]")).to_have_text("Move it to a shared workspace to publish.")
        expect(page.get_by_role("button", name="Move…")).to_be_visible()
    page.goto(f"{CONSOLE_URL}/bi/design/{rid}")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    page.click("[data-publish]")
    expect(page.locator("[data-publish] + .bi-near")).to_contain_text("private workspace")
    page.click("[data-tab=yaml]")
    page.click(".CodeMirror")
    page.keyboard.press("Control+s")                                               # the page's own save, never the browser's dialog
    expect(page.locator("[data-save] + .bi-near")).to_contain_text("Saved", timeout=15000)
    page.goto(f"{CONSOLE_URL}/bi/design/board/{bid}")                             # a 2.3 board address: the report designer, a report with board pages (R7)
    page.wait_for_url(f"**/bi/design/{bid}")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    page.click("[data-publish]")
    expect(page.locator("[data-publish] + .bi-near")).to_contain_text("private workspace")
    page.click("[data-tab=yaml]")
    page.click(".CodeMirror")
    page.keyboard.press("Control+s")
    expect(page.locator("[data-save] + .bi-near")).to_contain_text("Saved", timeout=15000)
    assert page.inner_text("[data-msg]").strip() == ""                              # one message, beside the button
    page.goto(f"{CONSOLE_URL}/bi/i/bi-nothing-here")
    page.wait_for_function("() => /may not see it/.test(document.getElementById('biCanvas').innerText)", timeout=15000)
    assert page.inner_text("#biCanvas").split() == "Not found This item does not exist, or you may not see it.".split()      # only what is visible: nothing of the item


def test_a_notebook_has_one_title_and_analyse_promotes_once_and_fits_a_phone(stack, browser):
    page = sign_in(browser, "wes", "/bi/explore")
    open_extract(page)
    assert "trade.PNL_1D" in page.eval_on_selector_all("[data-fields] input:checked", "xs => xs.map(x => x.value)")      # the numeric columns are ticked, not only the first eight
    page.click("[data-tab=sql]")
    page.click("[data-cells=sql] [data-promote-report]")
    expect(page.locator("[data-cells=sql] [data-promote-report] + .bi-near")).to_contain_text("Trades, the basics SQL", timeout=30000)
    page.click("[data-cells=sql] [data-promote-report]")
    page.click("[data-promote-update]")
    expect(page.locator("[data-cells=sql] .bi-near").last).to_contain_text("updated", timeout=30000)
    page.click("[data-tab=python]")
    expect(page.locator("[data-promote=transform]")).to_be_disabled()
    expect(page.locator("[data-no-transform]")).to_contain_text("second extract")
    page.fill("input[name=title]", "A very long notebook title that goes on and on to test how the selects wrap on a phone")
    page.click("[data-save-go]")
    page.wait_for_selector("[data-saved]:not([hidden])")
    item = page.get_attribute("[data-saved]", "href")
    page.goto(CONSOLE_URL + item)
    page.click("[data-actions] >> text=Edit")
    page.fill("[data-edit] input[name=title]", "Renamed on the item")
    page.click("[data-edit] [type=submit]")
    expect(page.locator("[data-body]")).to_contain_text('title: "Renamed on the item"', timeout=15000)       # the item's title is the YAML's
    page.click("[data-actions] >> text=Edit")
    page.fill("[data-edit] textarea[name=body]", page.input_value("[data-edit] textarea[name=body]").replace('title: "Renamed on the item"', "title: Changed in the YAML"))
    page.click("[data-edit] [type=submit]")
    expect(page.locator("[data-title]")).to_have_text("Changed in the YAML", timeout=15000)                  # and the YAML's is the item's
    page.set_viewport_size({"width": 390, "height": 800})
    page.goto(f"{CONSOLE_URL}/bi/explore?item={item.split('/bi/i/')[1]}")
    page.wait_for_function("() => /rows,/.test(document.querySelector('[data-info]').textContent)", timeout=60000)
    assert page.evaluate("() => [document.querySelector('[data-dataset]').value, document.querySelector('input[name=title]').value]") == ["mq:trades-basic@1", "Changed in the YAML"]
    assert page.evaluate("() => document.documentElement.scrollWidth <= document.documentElement.clientWidth")
    assert page.evaluate("() => window.drishtiBi.fmt(-0.001, 'signed2')") == "0.00"                         # never "-0.00"


def test_a_dataset_can_be_created_from_pasted_yaml(stack, browser):
    page = sign_in(browser, "wes", "/bi/w/me")
    page.wait_for_selector("[data-new-item]:not([hidden])", timeout=15000)
    page.select_option("[data-new-item] [name=kind]", "dataset")
    expect(page.locator("[data-paste-dataset]")).to_be_visible()
    page.click("[data-paste-dataset]")
    page.fill("[name=yamlbody]", 'rachana: 1\ndataset: pasted-one\ntitle: "Pasted one"\ntables: []\n')
    page.click("[data-paste-dataset]")
    page.wait_for_url("**/bi/i/**", timeout=15000)
    expect(page.locator("[data-title]")).to_have_text("Pasted one")


def test_a_steward_outside_the_audience_approves_and_is_told_instead_of_seeing_an_error(stack, browser):
    assert admin("PUT", "/admin/role-definitions/bi-stew", {"kinds": ["*"], "bi": ["bi", "bi-steward"]})[0] == 200
    assert admin("POST", "/admin/users", {"username": "stew", "displayName": "Stew", "email": "stew@desk.test", "roles": ["bi-stew"], "packs": ["trading"], "password": PASSWORD,
                                          "mustChangePassword": False})[0] in (200, 201, 409)
    wes = sign_in(browser, "wes", "/bi")
    ws = wes.evaluate("() => window.drishtiBi.api('POST', '/workspaces', {name: 'Review desk', description: 'r'}).then(w => w.id)")
    wes.evaluate("(w) => window.drishtiBi.api('PUT', '/workspaces/' + w, {reviewRequired: true})", ws)
    iid = wes.evaluate("(w) => window.drishtiBi.api('POST', '/workspaces/' + w + '/items', {kind: 'report', title: 'To approve', description: '', body: %r}).then(i => i.id)" % REPORT, ws)
    wes.evaluate("(i) => window.drishtiBi.api('POST', '/items/' + i + '/propose', {})", iid)
    stew = sign_in(browser, "stew", f"/bi/i/{iid}")
    stew.click("[data-actions] >> text=Approve and publish")
    expect(stew.locator("[data-msg]")).to_contain_text("you are not in it", timeout=15000)
    assert not stew.errors and "undefined" not in stew.inner_text("#biCanvas")
    expect(stew.locator("[data-back-inbox]")).to_be_visible()
