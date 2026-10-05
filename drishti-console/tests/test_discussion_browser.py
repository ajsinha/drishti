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

"""Discussion in a real browser, against a real server with sign-in on (COLLABORATION.md, build step 6): two people discuss a trade, an @mention
reaches the bell, a comment is edited inside its window and retracted, a viewer without raw sees a masked value as ••• (typed or quoted),
*Open as it was* lands on the pinned date, an administrator hides a comment, the whole thing works from the keyboard alone, and the drawer
is a bottom sheet on a phone.

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
    """The administrator makes the cast: ann (author, risk), ravi (trader, masked), rng (risk)."""
    base = str(stack)
    admin = sign_in(browser, base, "drishti-dev-admin", "drishti-dev-admin123")
    for name, display, roles in (("ann", "Ann Author", ["risk"]), ("ravi", "Ravi Kumar", ["trader"]), ("rng", "Rachel Ng", ["risk"])):
        status, out = api(admin, base, "/admin/api/users", {"username": name, "displayName": display, "roles": roles, "packs": ["finance"], "enabled": True,
                                                            "password": PASSWORD, "mustChangePassword": False})
        assert status == 201, out
    yield admin
    admin.context.close()


def open_view(pg, base, query=""):
    pg.goto(f"{base}/v/trade/{TRADE}{query}")
    pg.locator(".vtitle .vid").wait_for()
    pg.locator("[data-disc-panel-open]").first.wait_for()                  # the panel badges are drawn once the page knows sharing is on


def drawer_text(pg):
    return pg.locator("#aboutDrawer").inner_text()


def post_by_keyboard(pg, text):
    """Alt+N, the comment box has the focus, type, Ctrl+Enter."""
    pg.keyboard.press("Alt+n")
    pg.locator("#aboutDrawer:not([hidden])").wait_for()
    wait(pg, "document.activeElement && document.activeElement.id === 'discBody'")
    pg.keyboard.type(text)
    pg.keyboard.press("Control+Enter")
    wait(pg, "document.querySelector('[data-disc-msg]').textContent.indexOf('Posted.') === 0")


def test_two_people_discuss_a_trade_with_a_mention_a_masked_value_and_open_as_it_was(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    ravi = sign_in(browser, base, "ravi", PASSWORD)
    ravi.goto(base + "/t")
    ravi.locator("[data-bell]").wait_for()
    open_view(ann, base, f"?asOf={PIN}")                                     # Ann writes on a past date

    ann.keyboard.press("Alt+n")
    ann.locator("#aboutDrawer:not([hidden])").wait_for()
    assert ann.locator("#discTab").get_attribute("aria-selected") == "true"
    wait(ann, "document.activeElement && document.activeElement.id === 'discBody'")
    ann.keyboard.type("@rav")                                                # the mention picker: users and roles from the directory
    ann.locator("#discOpts [role=option]").first.wait_for()
    assert "Ravi Kumar" in ann.locator("#discOpts").inner_text()
    ann.keyboard.press("Enter")
    assert ann.locator("#discBody").input_value().startswith("@ravi ")
    ann.keyboard.type(f"did {SECRET} book this? The trader is {{$.trader}} and the MTM is {{")   # a typed secret, a typed quote, the { picker
    ann.locator("#discOpts [role=option]").first.wait_for()
    ann.keyboard.type("mtm")
    ann.locator("#discOpts [role=option]").first.wait_for()
    ann.keyboard.press("Enter")
    assert "{$.trader}" in ann.locator("#discBody").input_value() and "{$.mtm} " in ann.locator("#discBody").input_value()
    ann.keyboard.press("Control+Enter")
    wait(ann, "document.querySelector('[data-disc-msg]').textContent.indexOf('Posted.') === 0")
    msg = ann.locator("[data-disc-msg]").inner_text()
    assert "1 person notified" in msg and "hidden from some readers" in msg      # the warning about the typed value is shown to the author
    ann.locator(".disc-c").first.wait_for()
    assert ann.locator(".disc-c .disc-mention").first.inner_text() == "@ravi"
    assert SECRET in ann.locator(".disc-c").first.inner_text()                  # Ann may see it

    # the bell, without a reload
    wait(ravi, "(document.querySelector('[data-bell-count]') || {}).textContent === '1' && !document.querySelector('[data-bell-count]').hidden")

    # Ravi (a viewer without raw) reads it live: the typed value and the quote are both •••, and *Open as it was* is offered
    open_view(ravi, base)
    ravi.keyboard.press("Alt+n")
    ravi.locator(".disc-c").first.wait_for()
    text = ravi.locator(".disc-c").first.inner_text()
    assert SECRET not in text and "•••" in text and SECRET not in ravi.content()
    assert ravi.locator(".disc-quote").first.inner_text() == "•••"
    link = ravi.locator(".disc-c a", has_text="Open as it was")
    link.wait_for()
    link.click()
    ravi.wait_for_url(lambda u: f"asOf={PIN}" in u)                              # the pinned date
    ravi.locator(".pin-banner").wait_for()
    assert PIN in ravi.locator(".pin-banner").inner_text()
    assert not [e for e in ann.errors + ravi.errors if "discussion" in e.lower()]
    ann.context.close()
    ravi.context.close()


def test_edit_in_the_window_retract_and_a_moderators_hide(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    open_view(ann, base)
    post_by_keyboard(ann, "First thought on the curve.")
    item = ann.locator(".disc-c").first
    item.wait_for()
    item.locator("button:text-is('Edit')").click()                              # inside the window
    ann.keyboard.press("Control+a")
    ann.keyboard.type("Second thought on the curve.")
    ann.keyboard.press("Control+Enter")
    ann.locator(".disc-c", has_text="Second thought").wait_for()
    assert ann.locator(".disc-c button", has_text="(edited)").count() == 1
    ann.locator(".disc-c button", has_text="(edited)").click()                   # the earlier version is kept and shown
    ann.locator(".disc-hist li", has_text="First thought").wait_for()

    rng = sign_in(browser, base, "rng", PASSWORD)                                 # someone else cannot edit or retract it
    open_view(rng, base)
    rng.keyboard.press("Alt+n")
    rng.locator(".disc-c", has_text="Second thought").wait_for()
    assert rng.locator(".disc-c button:text-is('Edit')").count() == 0 and rng.locator(".disc-c button", has_text="Retract").count() == 0
    assert rng.locator(".disc-c button", has_text="Hide").count() == 0            # moderation is for administrators

    admin = people                                                                # an administrator hides it with a reason
    admin.goto(f"{base}/v/trade/{TRADE}")
    admin.locator("[data-disc-panel-open]").first.wait_for()
    admin.keyboard.press("Alt+n")
    admin.locator(".disc-c", has_text="Second thought").locator("button", has_text="Hide").click()
    admin.keyboard.type("client name in the text")
    admin.keyboard.press("Control+Enter")
    admin.locator(".disc-c .disc-gone", has_text="Hidden by a moderator: client name in the text").wait_for()
    rng.reload()
    rng.locator("[data-disc-panel-open]").first.wait_for()
    rng.keyboard.press("Alt+n")
    rng.locator(".disc-c .disc-gone", has_text="Hidden by a moderator").wait_for()
    assert "Second thought" not in rng.locator("#aboutDrawer").inner_text()

    post_by_keyboard(ann, "A comment I will take back.")                         # the author retracts (two presses: the second confirms)
    mine = ann.locator(".disc-c", has_text="take back")
    mine.locator("button", has_text="Retract").click()
    mine.locator("button", has_text="sure?").click()
    ann.locator(".disc-c .disc-gone", has_text="Retracted by the author").wait_for()
    assert "take back" not in ann.locator("#aboutDrawer").inner_text()
    ann.context.close()
    rng.context.close()


def test_a_panel_thread_from_its_badge_and_follow_resolve_and_mute(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    open_view(ann, base)
    badge = ann.locator("[data-disc-panel-open]").first
    pid = badge.get_attribute("data-disc-panel-open")
    assert "Start a discussion on" in badge.get_attribute("aria-label")
    badge.click()                                                                  # opens the drawer on the panel
    ann.locator("#aboutDrawer:not([hidden])").wait_for()
    wait(ann, f"document.querySelector('#discBody') && document.querySelector('[data-disc-anchor]').value === 'panel:{pid}'")
    ann.fill("#discBody", "Panel question for the desk.")
    ann.keyboard.press("Control+Enter")
    wait(ann, "document.querySelector('[data-disc-msg]').textContent.indexOf('Posted.') === 0")
    wait(ann, f"/1 comment/.test(document.querySelector('[data-disc-panel-open=\"{pid}\"]').getAttribute('aria-label'))")   # the count in the panel header
    thread = ann.locator(".disc-t").first
    thread.locator("button", has_text="Follow").first.wait_for()
    thread.locator("button", has_text="Resolve").click()
    wait(ann, "document.querySelector('.disc-t').classList.contains('resolved')")
    ann.locator(".disc-t.resolved > summary").first.click()                      # a resolved thread folds away; open it to reopen it
    ann.locator(".disc-t button", has_text="Reopen").click()
    ann.locator(".disc-t.open").wait_for()
    ann.locator(".disc-t button[aria-pressed]", has_text="Mute").wait_for()      # the author follows their own thread
    ann.locator(".disc-t button", has_text="Mute").click()
    ann.locator(".disc-t button", has_text="Unmute").wait_for()
    ann.reload()                                                                   # the badge is drawn from the server's counts
    ann.locator(f"[data-disc-panel-open=\"{pid}\"].has").wait_for()
    ann.context.close()


def test_keyboard_only_tabs_escape_and_focus(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    open_view(ann, base)
    ann.locator("[data-discussion-open]").focus()
    ann.keyboard.press("Enter")
    ann.locator("#aboutDrawer:not([hidden])").wait_for()
    assert ann.locator("#discTab").get_attribute("aria-selected") == "true"
    ann.keyboard.press("?")                                                  # ? still opens About (on the same drawer)
    wait(ann, "document.getElementById('aboutTab').getAttribute('aria-selected') === 'true'")
    assert ann.evaluate("document.getElementById('aboutDrawer').getAttribute('data-tab')") == "about"
    assert ann.locator("#aboutPanel").is_visible() and not ann.locator("#discPanel").is_visible()
    ann.keyboard.press("Alt+n")                                                    # back to Discussion, then F1 from there opens About
    wait(ann, "document.getElementById('aboutDrawer').getAttribute('data-tab') === 'discussion'")
    ann.locator("#aboutTitle").focus()
    ann.keyboard.press("F1")
    ann.locator("#aboutDrawer:not([hidden])").wait_for()
    assert ann.evaluate("document.getElementById('aboutDrawer').getAttribute('data-tab')") == "about"
    ann.locator("#aboutTab").focus()
    ann.keyboard.press("ArrowRight")                                               # arrow keys move between the tabs
    wait(ann, "document.getElementById('discTab').getAttribute('aria-selected') === 'true' && document.activeElement.id === 'discTab'")
    ann.keyboard.press("Alt+n")                                                    # Alt+N while open puts the focus in the comment box
    wait(ann, "document.activeElement.id === 'discBody'")
    ann.keyboard.press("Escape")
    ann.locator("#aboutDrawer").wait_for(state="hidden")
    assert ann.evaluate("document.activeElement.tagName") != "BODY"                # the focus went back to where it was
    ann.context.close()


def test_the_drawer_is_a_bottom_sheet_on_a_phone(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD, viewport={"width": 390, "height": 800})
    ann.goto(f"{base}/v/trade/{TRADE}")
    ann.locator(".vtitle .vid").wait_for()
    ann.locator("[data-disc-panel-open]").first.wait_for(state="attached")
    ann.keyboard.press("Alt+n")
    sheet = ann.locator("#aboutDrawer")
    sheet.wait_for()
    box = sheet.bounding_box()
    assert box["width"] >= 388 and box["y"] > 50 and box["y"] + box["height"] >= 790       # a sheet from the bottom edge, full width
    assert ann.locator("#discBody").is_visible() and ann.locator("#discTab").is_visible()
    assert ann.evaluate("document.getElementById('aboutDrawer').getAttribute('aria-modal')") == "true"
    assert ann.evaluate("document.documentElement.scrollWidth <= window.innerWidth")        # no sideways scroll
    ann.locator("#aboutTab").click()
    ann.locator("#aboutPanel").wait_for()
    ann.locator("[data-about-close]").click()
    sheet.wait_for(state="hidden")
    ann.context.close()
