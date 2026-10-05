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

"""Console polish in a real browser, against a real server with sign-in on (COLLABORATION.md, Console polish): the share dialog's "also start a
discussion" tick, the reply box on a shared view, and Admin > Collaboration (moderation by keyboard, holds, export and verify for a compliance
role, retention dry run, bridges), with times in the top bar's zone.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py). Every wait is wb_live.BROWSER_WAIT_MS."""
import json
import re

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import BROWSER_WAIT_MS, TOKEN_SECRET, _stack

TRADE = "IRS-48213"
PASSWORD = "long-enough-pass-1"
LOCAL = re.compile(r"\d{4}-\d\d-\d\d \d\d:\d\d [A-Za-z ]+")


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from _stack(tmp_path_factory, {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET}, auth=True)


def sign_in(browser, base, user, password, viewport=None):
    ctx = browser.new_context(viewport=viewport or {"width": 1400, "height": 900}, accept_downloads=True)
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


def api(pg, base, path, body, method="post"):
    r = getattr(pg.request, method)(base + path, data=json.dumps(body), headers={"Content-Type": "application/json"})
    return r.status, (r.json() if r.headers.get("content-type", "").startswith("application/json") else {})


@pytest.fixture(scope="module")
def people(browser, stack):
    """The administrator makes ann and ravi (people who share) and cmp, whose role holds the compliance power and nothing else."""
    base = str(stack)
    admin = sign_in(browser, base, "drishti-dev-admin", "drishti-dev-admin123")
    status, out = api(admin, base, "/admin/api/roles/auditor", {"description": "compliance", "kinds": ["trade"], "collaborate": True, "compliance": True}, "post")
    assert status == 200, out
    for name, display, roles in (("ann", "Ann Author", ["risk"]), ("ravi", "Ravi Kumar", ["trader"]), ("cmp", "Cora Compliance", ["auditor"])):
        status, out = api(admin, base, "/admin/api/users", {"username": name, "displayName": display, "roles": roles, "packs": ["finance"], "enabled": True,
                                                            "password": PASSWORD, "mustChangePassword": False})
        assert status == 201, out
    yield admin
    admin.context.close()


def share_to(ann, who_prefix, note, tick=None):
    ann.keyboard.press("Alt+s")
    dlg = ann.locator("[data-share-dialog]:not([hidden])")
    dlg.wait_for()
    ann.keyboard.type(who_prefix)
    ann.locator("#shrList [role=option]").first.wait_for()
    ann.keyboard.press("Enter")
    ann.locator("[data-shr-chips] .shr-chip").first.wait_for()
    if tick is not None:
        box = dlg.locator("[data-shr-thread]")
        assert box.is_visible() and box.is_checked() is False         # the server's default (drishti.collab.share.post-to-thread) is off
        if tick:
            box.check()
    ann.locator("[data-shr-note]").fill(note)
    ann.locator("[data-shr-note]").press("Control+Enter")
    wait(ann, "document.querySelector('[data-shr-result]').textContent.indexOf('Sent to 1') === 0")
    ann.keyboard.press("Escape")
    return dlg


def thread_bodies(pg, base):
    r = pg.request.get(f"{base}/api/threads/trade/{TRADE}")
    assert r.status == 200
    return [c.get("body") for t in r.json() for c in t.get("items", [])]


def test_the_discussion_tick_starts_a_thread_only_when_ticked(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    ann.goto(f"{base}/v/trade/{TRADE}")
    ann.locator(".vtitle .vid").wait_for()
    assert thread_bodies(ann, base) == []
    share_to(ann, "rav", "Untick: nothing in the thread.", tick=False)
    assert thread_bodies(ann, base) == []
    ann.locator("[data-share-dialog]").wait_for(state="hidden")
    share_to(ann, "rav", "Ticked: also in the thread.", tick=True)
    assert any("Ticked: also in the thread." in (b or "") for b in thread_bodies(ann, base))
    assert not any("Untick" in (b or "") for b in thread_bodies(ann, base))
    ann.context.close()


def test_the_parties_of_a_share_reply_in_the_banner(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    ann.goto(f"{base}/v/trade/{TRADE}")
    ann.locator(".vtitle .vid").wait_for()
    share_to(ann, "rav", "Please look at the curve.")
    ravi = sign_in(browser, base, "ravi", PASSWORD)
    ravi.goto(base + "/inbox")
    ravi.locator(".inbox-row.unread a.inbox-open").first.click()
    ravi.locator("[data-share-banner]").wait_for()
    assert LOCAL.search(ravi.locator("[data-share-when]").inner_text())              # the time is in the top bar's zone, with its label
    box = ravi.locator("[data-share-reply-text]")
    box.fill("Looking now.")
    box.press("Control+Enter")
    ravi.locator("[data-share-reply-list] li", has_text="Looking now.").wait_for()
    assert LOCAL.search(ravi.locator("[data-share-reply-list] li time").first.inner_text())
    ann.goto(ravi.url)                                                                 # the sender opens the same link and sees the reply, then answers
    ann.locator("[data-share-reply-list] li", has_text="Looking now.").wait_for()
    ann.locator("[data-share-reply-text]").fill("Thanks.")
    ann.locator("[data-share-reply-send]").click()
    ann.locator("[data-share-reply-list] li", has_text="Thanks.").wait_for()
    ravi.reload()
    ravi.locator("[data-share-reply-list] li", has_text="Thanks.").wait_for()
    ann.context.close()
    ravi.context.close()


def test_moderation_by_keyboard_retention_and_bridges_for_the_administrator(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    status, out = api(ann, base, f"/api/threads/trade/{TRADE}", {"anchor": "entity", "body": "A comment to moderate.", "generation": 1})
    assert status == 201, out
    admin = people
    admin.goto(base + "/admin/collab")
    admin.locator("[data-acol-search]").wait_for()
    assert admin.locator(".adm-tabs a[aria-current=page]").inner_text().strip() == "Collaboration"
    admin.fill("[data-acol-search] [name=kind]", "trade")
    admin.press("[data-acol-search] [name=kind]", "Enter")
    tid = out["threadId"]
    mine = f"[data-acol-threads] tbody tr[data-thread={tid}]"
    row = admin.locator(mine)
    row.wait_for()
    assert LOCAL.search(row.inner_text())
    row.locator("button", has_text="Comments").click()
    item = admin.locator(".acol-c", has_text="A comment to moderate.")
    item.wait_for()
    item.locator("button", has_text="Hide").click()
    admin.keyboard.type("client name")
    admin.keyboard.press("Enter")
    admin.locator(".acol-c.hidden", has_text="client name").wait_for()
    admin.locator("[data-acol-hidden-only]").check()
    admin.locator(".acol-c.hidden").first.wait_for()
    admin.locator(".acol-c.hidden button", has_text="Unhide").click()
    admin.locator("[data-acol-msg]", has_text="Comment shown again.").wait_for()
    admin.locator(mine).locator("button:text-is('Lock')").click()
    admin.locator(mine, has_text="locked").wait_for()
    status, out = api(ann, base, f"/api/thread/{tid}/comments", {"body": "x", "generation": 1})
    assert status == 409 and out["code"] == "DRS-7007"                                 # a locked thread takes no replies
    admin.locator(mine).locator("button:text-is('Unlock')").click()
    admin.locator(mine, has_text="open").wait_for()

    admin.locator("[data-acol-retention]").click()
    admin.locator("[data-acol-ret]", has_text="Dry run:").wait_for()
    assert "Nothing was deleted" in admin.locator("[data-acol-ret]").inner_text()
    admin.locator("[data-acol-bridges] tbody tr").first.wait_for()
    assert "No bridges are configured" in admin.locator("[data-acol-bridges]").inner_text()
    ann.context.close()


def test_a_compliance_role_places_and_releases_a_hold_exports_once_and_verifies(stack, people, browser):
    base = str(stack)
    cmp = sign_in(browser, base, "cmp", PASSWORD)
    cmp.goto(base + "/admin/collab")
    cmp.locator("[data-acol-hold-form]").wait_for()
    assert cmp.locator("[data-acol-retention]").count() == 0 and cmp.locator("[data-acol-bridges]").count() == 0     # administrators' sections
    cmp.select_option("[data-acol-scope]", "all")
    cmp.fill("[data-acol-hold-form] [name=reason]", "Case 7: keep everything.")
    cmp.press("[data-acol-hold-form] [name=reason]", "Enter")
    row = cmp.locator("[data-acol-holds] tbody tr", has_text="Case 7")
    row.wait_for()
    assert LOCAL.search(row.inner_text()) and "active" in row.inner_text()
    row.locator("button", has_text="Release").click()
    cmp.locator("[data-acol-holds] tbody tr", has_text="released by cmp").wait_for()

    cmp.locator("[data-acol-export-form] button[type=submit]").click()
    link = cmp.locator("[data-acol-download]")
    link.wait_for()
    with cmp.expect_download() as d:
        link.click()
    assert d.value.suggested_filename.endswith(".zip")
    cmp.locator("[data-acol-export]", has_text="was downloaded").wait_for()

    cmp.locator("[data-acol-verify-form] button[type=submit]").click()
    cmp.locator("[data-acol-verify]", has_text="All intact").wait_for()
    assert cmp.locator(".acol-c button", has_text="Hide").count() == 0                 # compliance reads, administrators moderate
    cmp.context.close()


def test_the_admin_page_is_usable_on_a_phone(stack, people, browser):
    base = str(stack)
    pg = sign_in(browser, base, "drishti-dev-admin", "drishti-dev-admin123", viewport={"width": 390, "height": 800})
    pg.goto(base + "/admin/collab")
    pg.locator("[data-acol-search]").wait_for()
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")      # no sideways scrolling
    pg.context.close()
