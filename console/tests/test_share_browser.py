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

"""Share with a note in a real browser, against a real server with sign-in on (COLLABORATION.md, build step 3): an author shares a trade
view, pinned to a past date, with a colleague by keyboard alone; the colleague's bell shows the notice and opening it lands on the pinned date
with the note and the masked field still masked; a recipient whose role no longer opens the kind gets the clean no-access page; the dialog
is a full-screen sheet on a phone and gives the focus back.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py). Every wait is wb_live.BROWSER_WAIT_MS."""
import json

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import BROWSER_WAIT_MS, TOKEN_SECRET, _stack

TRADE = "IRS-48213"
SECRET = "A. Shah"                       # the trade's trader: a masked field for anyone who is not raw
PASSWORD = "long-enough-pass-1"
PIN = "2026-09-29"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    """The live stack with sign-in on (the development administrator: drishti-dev-admin / drishti-dev-admin123)."""
    yield from _stack(tmp_path_factory, {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET}, auth=True)


def sign_in(browser, base, user, password, viewport=None):
    ctx = browser.new_context(viewport=viewport or {"width": 1400, "height": 900})
    pg = ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.set_default_timeout(BROWSER_WAIT_MS)
    pg.goto(base + "/login")
    pg.fill("input[name=user]", user)
    pg.fill("input[name=password]", password)
    pg.click("form button[type=submit], form input[type=submit]")
    pg.wait_for_url(lambda u: "/login" not in u)
    return pg


def api(pg, base, path, body):
    r = pg.request.post(base + path, data=json.dumps(body), headers={"Content-Type": "application/json"})
    return r.status, (r.json() if r.headers.get("content-type", "").startswith("application/json") else {})


@pytest.fixture(scope="module")
def people(browser, stack):
    """The administrator makes the cast: ann (author, risk), ravi (trader, masked), rng (risk: his role is changed later)."""
    base = str(stack)
    admin = sign_in(browser, base, "drishti-dev-admin", "drishti-dev-admin123")
    for name, display, roles in (("ann", "Ann Author", ["risk"]), ("ravi", "Ravi Kumar", ["trader"]), ("rng", "Rachel Ng", ["risk"])):
        status, out = api(admin, base, "/admin/api/users", {"username": name, "displayName": display, "roles": roles, "packs": ["finance"], "enabled": True,
                                                            "password": PASSWORD, "mustChangePassword": False})
        assert status == 201, out
    yield admin
    admin.context.close()


def share_by_keyboard(ann, who_prefix, note):
    ann.keyboard.press("Alt+s")                                           # the shortcut opens the dialog
    dlg = ann.locator("[data-share-dialog]:not([hidden])")
    dlg.wait_for()
    assert ann.evaluate("document.activeElement.id") == "shrTo"            # the focus starts on the people field
    ann.keyboard.type(who_prefix)
    ann.locator("#shrList [role=option]").first.wait_for()
    ann.keyboard.press("Enter")                                           # picks the highlighted person
    ann.locator("[data-shr-chips] .shr-chip").first.wait_for()
    ann.keyboard.press("Tab")
    ann.keyboard.type(note)
    ann.keyboard.press("Control+Enter")
    return dlg


def test_a_share_is_sent_received_and_opened_on_the_pinned_date(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    ann.goto(f"{base}/v/trade/{TRADE}?asOf={PIN}")
    ann.locator(".vtitle .vid").wait_for()
    ravi = sign_in(browser, base, "ravi", PASSWORD)
    ravi.goto(base + "/t")
    ravi.locator("[data-bell]").wait_for()
    assert ravi.locator("[data-bell-count]").is_hidden()                  # nothing yet

    dlg = share_by_keyboard(ann, "rav", "Can you confirm the curve? A. Shah booked it.")
    wait(ann, "document.querySelector('[data-shr-result]').textContent.indexOf('Sent to 1') === 0")
    assert PIN in dlg.locator("[data-shr-asof]").inner_text()             # the sender saw which date the link opens on
    ann.keyboard.press("Escape")
    ann.locator("[data-share-dialog]").wait_for(state="hidden")
    assert ann.evaluate("document.activeElement.hasAttribute('data-share')")      # the focus went back to the Share button

    # the colleague's bell counts it without a reload
    wait(ravi, "(document.querySelector('[data-bell-count]') || {}).textContent === '1' && !document.querySelector('[data-bell-count]').hidden")
    ravi.locator(".toasts a.toast-a").first.wait_for()
    ravi.keyboard.press("Alt+i")                                          # Alt+I opens the inbox
    ravi.wait_for_url(lambda u: u.endswith("/inbox"))
    row = ravi.locator(".inbox-row.unread").first
    row.wait_for()
    assert "Ann Author shared trade " + TRADE in row.inner_text() and "Can you confirm" in row.inner_text()

    row.locator("a.inbox-open").click()                                   # opening resolves the link to the pinned view
    ravi.wait_for_url(lambda u: f"/v/trade/{TRADE}" in u and f"asOf={PIN}" in u and "share=sh_" in u)
    banner = ravi.locator("[data-share-banner]")
    banner.wait_for()
    assert "Shared by Ann Author" in banner.inner_text() and "Can you confirm the curve? \u2022\u2022\u2022 booked it." in banner.inner_text()
    pin = ravi.locator(".pin-banner")
    pin.wait_for()
    assert PIN in pin.inner_text()
    text = ravi.locator("main").inner_text()
    assert SECRET not in text, text[max(0, text.find(SECRET) - 200): text.find(SECRET) + 60]
    assert "•••" in text                                   # the masked field stays masked for the viewer
    assert SECRET not in ravi.content()
    assert not [e for e in ravi.errors + ann.errors if "share" in e.lower()]
    # opening marked it read: the bell is clear on the next page
    ravi.goto(base + "/inbox")
    ravi.locator(".inbox-row").first.wait_for()
    assert ravi.locator(".inbox-row.unread").count() == 0
    ann.context.close()
    ravi.context.close()


def test_a_recipient_whose_role_no_longer_opens_the_kind_gets_the_clean_page(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    ann.goto(f"{base}/v/trade/{TRADE}?asOf={PIN}")
    ann.locator(".vtitle .vid").wait_for()
    share_by_keyboard(ann, "rac", "Please look")
    wait(ann, "document.querySelector('[data-shr-result]').textContent.indexOf('Sent to 1') === 0")
    status, out = api(people, base, "/admin/api/roles/curve-only", {"kinds": ["curve"]})      # a role that opens curves, not trades
    assert status == 200, out
    status, out = api(people, base, "/admin/api/users/rng/update", {"roles": ["curve-only"]})
    assert status == 200, out
    rng = sign_in(browser, base, "rng", PASSWORD)
    rng.goto(base + "/inbox")
    row = rng.locator(".inbox-row").first
    row.wait_for()
    assert "(no access)" in row.inner_text() and TRADE not in row.inner_text()
    row.locator("a.inbox-open").click()
    rng.locator("[data-share-denied]").wait_for()
    page = rng.locator("[data-share-denied]").inner_text()
    assert "A shared view you cannot open" in page and "Ann Author shared a trade view" in page and "do not open trade views" in page
    assert TRADE not in page and "Please look" not in page and SECRET not in rng.content()      # nothing about what it holds
    assert rng.locator("[data-share-banner]").count() == 0
    ann.context.close()
    rng.context.close()


def test_the_dialog_is_a_full_screen_sheet_on_a_phone_and_copy_link_stays(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD, viewport={"width": 390, "height": 800})
    ann.goto(f"{base}/v/trade/{TRADE}")
    ann.locator(".vtitle .vid").wait_for()
    ann.locator("button[data-share]").click()
    dlg = ann.locator(".shr-dlg")
    dlg.wait_for()
    box = dlg.bounding_box()
    assert box["width"] >= 388 and box["height"] >= 790                   # the whole screen
    assert "live" in ann.locator("[data-shr-asof]").inner_text()           # a live page says its link is live
    ann.locator("[data-shr-send]").click()                                # nobody chosen: told so, nothing sent
    wait(ann, "document.querySelector('[data-shr-result]').textContent.indexOf('Choose at least one') === 0")
    assert ann.locator("[data-shr-copy]").is_visible()
    ann.locator("[data-shr-cancel]").click()
    ann.locator("[data-share-dialog]").wait_for(state="hidden")
    assert ann.evaluate("document.activeElement.hasAttribute('data-share')")
    ann.context.close()
