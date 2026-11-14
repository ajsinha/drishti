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

"""Rupaka phase 4 in Chromium, over the real server and console (tests/bi_gov_stack.py: ports 18992 and 17992, security on, a small file lake): an author saves a dataset
twice, compares the saved states side by side and restores one; a steward reviews a proposal with its diff, Check and data preview and approves it; the certified badge
shows in the catalogue; lineage shows where a dataset comes from and who reads it; a dataset change that would break a published report is refused, a bi-admin forces it and
the certification falls with a warning; a bundle is exported and imported with a dry run first; a phone gets finger-sized controls; and nothing violates the page policy.

The tests run in order and share one stack (each builds on the one before). Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in
use. Never run with pytest -n (the ports are fixed)."""
import json
import re

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_gov_stack  # noqa: E402
from bi_gov_stack import CONSOLE_URL, PASSWORD  # noqa: E402
from bi_report_helpers import code_text, type_code  # noqa: E402
from select_helpers import has_option


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_gov_stack.SERVER_PORT, bi_gov_stack.CONSOLE_PORT):
        if bi_gov_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = bi_gov_stack.Stack(tmp_path_factory.mktemp("bigov")).start()
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


VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"


def sign_in(browser, user, **ctx):
    context = browser.new_context(**{"viewport": {"width": 1400, "height": 1000}, "accept_downloads": True, **ctx})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(CONSOLE_URL + "/login?next=/bi/catalogue")
    page.fill("#lu", user)
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/bi/catalogue")
    page.errors = errors
    return page


def clean(page):
    assert page.evaluate("window.__csp") == [], "a policy violation"
    assert page.errors == [], page.errors


def open_item(page, item_id, wait="[data-meta]"):
    page.goto(CONSOLE_URL + "/bi/i/" + item_id)
    expect(page.locator(wait)).to_contain_text("owner")


def edit_body(page, change, message):
    """Opens the Edit form, rewrites the body with ``change(text)`` and saves the draft with a message."""
    rows = page.locator("[data-history] tbody tr")
    expect(rows.first).to_be_visible(timeout=15000)                      # the history has loaded (the item's first cut at least): count it then,
    before = rows.count()                                                 # not while it is still drawing (counted early, a save went unnoticed)
    page.get_by_role("button", name="Edit", exact=True).click()
    body = "[data-edit] textarea[name=body]"                              # drishti-inputs' CodeEditor: typed into as a person does
    type_code(page, body, change(code_text(page, body)))
    page.fill("[data-edit] input[name=message]", message)
    page.click("[data-edit] button[type=submit]")
    expect(page.locator("[data-edit]")).to_be_hidden(timeout=30000)        # this save went through (the message may still be the last save's)
    expect(page.locator("[data-msg]")).to_contain_text("Draft saved")
    expect(rows).to_have_count(before + 1, timeout=15000)                # this save's revision is listed (the message may still be the last save's)
    expect(rows.first).to_contain_text(message, timeout=15000)           # and it is this save, newest first


def test_an_author_saves_twice_compares_and_restores(stack, browser):
    page = sign_in(browser, "ann")
    open_item(page, stack.ids["positions"])
    edit_body(page, lambda t: t.replace("measures:\n", "measures:\n  Books: { expr: \"distinct(trade.book)\" }\n", 1), "add the books measure")
    edit_body(page, lambda t: re.sub(r"^  Trades:(\s*)\{", r"  Deals:\1{", t, flags=re.M), "rename the count measure")
    rows = page.locator("[data-history] tbody tr")
    expect(rows).to_have_count(3)                                                       # the first cut and the two saves
    expect(rows.first).to_contain_text("rename the count measure")
    expect(rows.first).to_contain_text("ann")
    page.select_option("[data-diff-from]", "r1")
    page.select_option("[data-diff-to]", "draft")
    page.click("[data-diff-form] button[type=submit]")
    diff = page.locator("[data-diff-out] table.bi-diff")
    expect(diff).to_be_visible()
    expect(page.locator("[data-diff-out] .bi-diff-added").first).to_contain_text("Books")
    expect(page.locator("[data-diff-out] .bi-diff-changed").first).to_contain_text("Trades")
    expect(page.locator("[data-diff-out] .bi-result-meta")).to_contain_text("1 changed, 1 added")
    page.locator('[data-history] tr[data-seq="2"] button:has-text("Restore as draft")').click()
    expect(page.locator("[data-msg]")).to_contain_text("Saved state 2 is the draft again")
    expect(page.locator("[data-body]")).to_contain_text("Books")
    expect(page.locator("[data-body]")).not_to_contain_text("Deals")
    expect(page.locator("[data-history] tbody tr")).to_have_count(4)                  # the restore is a new saved state: nothing was lost
    expect(page.locator("[data-history] tbody tr").first).to_contain_text("restored saved state 2")
    clean(page)


def test_a_steward_reviews_with_the_diff_check_and_preview_and_approves(stack, browser):
    ann = sign_in(browser, "ann")
    open_item(ann, stack.ids["positions"])
    ann.fill(".bi-note", "ready for a look")
    ann.get_by_role("button", name="Propose for review").click()
    expect(ann.locator("[data-msg]")).to_contain_text("Proposed")
    sue = sign_in(browser, "sue")
    open_item(sue, stack.ids["positions"])
    review = sue.locator("[data-sec-review]")
    expect(review).to_be_visible()
    expect(review.locator("[data-review-request]")).to_contain_text("ann")
    expect(review.locator("[data-review-request]")).to_contain_text("ready for a look")
    expect(review.locator(".bi-diff-added").first).to_be_visible()                      # nothing published yet: everything is new
    expect(review).to_contain_text("Check passed")
    expect(review.locator(".rc-host .dg-row[data-key]").first).to_be_visible()              # a data preview, with the reviewer's own rules
    assert review.locator(".rc-host .dg-row[data-key]").count() >= 3
    sue.get_by_role("link", name="Decide in Approvals").click()                       # decided in the one Approvals queue
    sue.locator("[data-ap-item]:not([hidden])").wait_for()
    sue.fill("[data-ap-comment]", "looks right")
    sue.get_by_role("button", name="Approve", exact=True).click()
    expect(sue.locator("[data-ap-said]")).to_have_text("Approved.")
    open_item(sue, stack.ids["positions"])
    expect(sue.locator("[data-badges]")).to_contain_text("published")
    expect(sue.locator("[data-sec-review]")).to_be_hidden()
    # the author is told through the inbox; bob may not approve his own request (four-eyes)
    ann.goto(CONSOLE_URL + "/inbox")
    expect(ann.locator("body")).to_contain_text("approved model")
    status, bob_item = bi_gov_stack.call("POST", "/bi/workspaces/rates-desk/items", "bob", ["bi-reviewer"], {"kind": "model", "title": "Bob's model", "body": "rachana: 3\nmodel: bobs\nversion: 1.0.0\ntables:\n  t: { table: bi.positions_model_trade, role: fact }\nmeasures:\n  Rows: { expr: \"count(t)\" }\n"})
    assert status == 201
    bi_gov_stack.call("POST", f"/bi/items/{bob_item['id']}/propose", "bob", ["bi-reviewer"], {})
    bob = sign_in(browser, "bob")
    open_item(bob, bob_item["id"])
    expect(bob.get_by_role("link", name="Decide in Approvals")).to_have_count(0)
    expect(bob.get_by_role("button", name="Withdraw proposal")).to_be_visible()
    for p in (ann, sue, bob):
        clean(p)


def test_the_certified_badge_shows_in_the_catalogue_and_search(stack, browser):
    cat = sign_in(browser, "cat")
    cat.goto(CONSOLE_URL + "/bi/catalogue?kind=all")                                   # the All tab: every kind (the catalogue is split by kind)
    expect(cat.locator(".bi-card")).to_have_count(5)                                    # the two tables, the two models and the report
    expect(cat.locator(".bi-card").first.locator(".bi-certified")).to_be_visible()      # certified items are pinned first
    cat.select_option("[name=endorsement]", "certified")
    cards = cat.locator(".bi-card")
    expect(cards).to_have_count(2)
    expect(cards.first.locator(".bi-certified")).to_contain_text("certified")
    cat.goto(CONSOLE_URL + "/bi/catalogue?kind=all&q=trades")
    expect(cat.locator(".bi-card").first.locator(".bi-certified")).to_be_visible()      # certified first in search
    clean(cat)


def test_lineage_shows_where_a_dataset_comes_from_and_who_reads_it(stack, browser):
    page = sign_in(browser, "ann")
    open_item(page, stack.ids["model"])
    lin = page.locator("[data-sec-lineage]")
    expect(lin).to_be_visible()
    expect(lin.locator("[data-upstream]")).to_contain_text("trade")
    expect(lin.locator("[data-upstream]")).to_contain_text("risk-store")                          # the valuations (2.3.0): market-risk's kind trade-valuation
    expect(lin.locator("[data-upstream]")).to_contain_text("market-risk")
    expect(lin.locator("[data-downstream] a")).to_have_text("Trades report")
    lin.locator("[data-downstream] a").click()
    page.wait_for_url("**/bi/i/*")
    lin = page.locator("[data-sec-lineage]")
    expect(lin.locator("[data-model]")).to_contain_text("gov-trades")
    expect(lin.locator("[data-uses]")).to_contain_text("trade.DESK")
    expect(lin.locator("[data-uses]")).to_contain_text("Notional")
    clean(page)


def test_a_model_change_that_breaks_its_contract_needs_a_new_major_version(stack, browser):
    """A model's consumers rely on what it exposes: taking a measure away in a minor version is refused (DRS-9126); a new major is published and the
    readers pinned to the old one keep it."""
    ann = sign_in(browser, "ann")
    open_item(ann, stack.ids["model"])
    edit_body(ann, lambda t: re.sub(r"^  Notional:.*\n", "", t, flags=re.M), "drop the Notional measure")
    status, r = bi_gov_stack.call("POST", f"/bi/items/{stack.ids['model']}/publish", "ann", ["bi-writer"], {"note": "drop Notional"})
    assert status == 409 and r["code"] == "DRS-9126" and "Notional" in r["detail"], r
    edit_body(ann, lambda t: re.sub(r"^version: .*$", "version: 2.0.0", t, flags=re.M), "a new major")
    status, r = bi_gov_stack.call("POST", f"/bi/items/{stack.ids['model']}/publish", "ann", ["bi-writer"], {"note": "version 2"})
    assert status == 200, r
    clean(ann)


def test_a_bundle_is_exported_and_imported_with_a_dry_run_first(stack, browser, tmp_path):
    ann = sign_in(browser, "ann")
    ann.goto(CONSOLE_URL + "/bi/environments")
    ann.wait_for_function(has_option("[data-ws-select]"))
    ann.select_option("[data-ws-select]", "rates-desk")
    with ann.expect_download() as d:
        ann.click("[data-export] button[type=submit]")
    path = tmp_path / "rates.bi-bundle.json"
    d.value.save_as(path)
    bundle = json.loads(path.read_text())
    assert bundle["format"] == "drishti-bi-bundle" and len(bundle["items"]) >= 3
    expect(ann.locator("[data-msg]")).to_contain_text("Bundle downloaded")
    # ann is no steward: the import is not hers
    expect(ann.locator("[data-import-need]")).to_be_visible()
    admin = sign_in(browser, "root")
    admin.goto(CONSOLE_URL + "/bi/environments")
    admin.set_input_files("[data-file]", str(path))
    admin.click("[data-dry]")
    expect(admin.locator("[data-plan-meta]")).to_contain_text("Dry run, nothing changed")
    expect(admin.locator("[data-plan] tbody tr")).to_have_count(len(bundle["items"]))
    assert all(a == "unchanged" for a in admin.locator("[data-plan] tbody tr").evaluate_all("rows => rows.map(r => r.dataset.action)"))
    # the content here moves on: the same bundle now conflicts, and overwrite resolves it
    status, r = bi_gov_stack.call("PUT", f"/bi/items/{stack.ids['report']}", "ann", ["bi-writer"], {"description": "changed after the export", "body": bi_gov_stack.REPORT.replace("Notional by desk", "Notional per desk"), "message": "retitle"})
    assert status == 200, r
    admin.click("[data-dry]")
    expect(admin.locator("[data-plan] tbody tr[data-action=conflict]")).to_have_count(1)
    expect(admin.locator("[data-plan]")).to_contain_text("exists here and differs")
    expect(admin.locator("[data-apply]")).to_be_disabled()
    admin.check("[data-import] [name=overwrite]")
    admin.click("[data-dry]")
    expect(admin.locator("[data-plan] tbody tr[data-action=update]")).to_have_count(1)
    expect(admin.locator("[data-apply]")).to_be_enabled()
    admin.click("[data-apply]")
    expect(admin.locator("[data-msg]")).to_contain_text("Imported 1 item(s)")
    open_item(admin, stack.ids["report"])
    expect(admin.locator("[data-body]")).to_contain_text("Notional by desk")
    expect(admin.locator("[data-history] tbody tr").first).to_contain_text("imported from a bundle")
    for p in (ann, admin):
        clean(p)


def test_a_phone_gets_finger_sized_governance_controls_and_no_sideways_scroll(stack, browser):
    page = sign_in(browser, "ann", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    for path in (f"/bi/i/{stack.ids['positions']}", f"/bi/i/{stack.ids['model']}", f"/bi/i/{stack.ids['report']}", "/bi/environments"):
        page.goto(CONSOLE_URL + path)
        page.wait_for_timeout(900)
        assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"), path
        small = page.evaluate("""() => [...document.querySelectorAll('.bi-shell a[href], .bi-shell button, .bi-shell input:not([type=hidden]), .bi-shell select, .bi-shell textarea')]
          .filter(e => { const r = e.getBoundingClientRect(), s = getComputedStyle(e); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && !e.closest('[hidden]')
            && !(e.tagName === 'A' && e.closest('p, .bi-empty')); })
          .filter(e => Math.min(e.getBoundingClientRect().width, e.getBoundingClientRect().height) < 43.5 && !(e.type === 'checkbox'))
          .map(e => e.tagName + '.' + e.className + ' ' + Math.round(e.getBoundingClientRect().width) + 'x' + Math.round(e.getBoundingClientRect().height))""")
        assert small == [], (path, small)
    page.goto(CONSOLE_URL + f"/bi/i/{stack.ids['positions']}")
    expect(page.locator("[data-sec-history]")).to_be_visible()
    box = page.locator("[data-sec-history] .bi-diff-wrap, [data-sec-history] .tbl-wrap").first.bounding_box()
    assert box["x"] >= 0 and box["x"] + box["width"] <= 391                                  # the table scrolls inside its own frame
    clean(page)
