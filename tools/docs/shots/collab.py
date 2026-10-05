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

"""The pictures of Share with a note, the inbox and Discussion (docs/guides/USER_GUIDE.md, docs/architecture/HOW_IT_FITS.md section 3.10),
written to docs/guides/img/collab/.

Sign-in is ON with two people on the trade MX-20000001: `asha` (role `author`, raw: sees every field) shares the view, pinned to a past
business date, with a note, and writes a comment that mentions `vera` and holds the trader's name (typed and quoted); `vera` (role `viewer`,
no raw: the fields named in `drishti.security.redact` read as the mask) is the recipient. Run it on its own, which starts the scratch server:

    console/.venv/bin/python tools/docs/screenshots.py --guide collab
"""
from __future__ import annotations

import json
import re

from shotlib import Ctx, register

shot = register("collab")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": "collab-scratch-secret-0123456789abcdef-xyz",
              "DRISHTI_AUTH_ENABLED": "true", "DRISHTI_SESSION_SECRET": "collab-session-secret-0123456789abcdef-xyz", "DRISHTI_SECURE_COOKIE": "false"}
PASSWORD = "collab-pass-2026"
TRADE, PIN = "MX-20000001", "2026-09-30"
NOTE = "Please check the fixed leg before the 16:00 sign-off. The trader on this one is {t}."
_state: dict = {"users": False}


def login(c: Ctx, user: str, password: str = PASSWORD):
    c.page.context.clear_cookies()
    c.page.goto(c.base + "/login")
    c.page.fill("input[name=user]", user)
    c.page.fill("input[name=password]", password)
    c.page.locator("form button[type=submit]").first.click()
    c.page.wait_for_url(re.compile(r".*/(t|build.*)$"))


def people(c: Ctx):
    """The two people, made once by the development admin."""
    if _state["users"]:
        return
    login(c, "drishti-dev-admin", "drishti-dev-admin123")
    for user, display, role in (("asha", "Asha Rao", "author"), ("vera", "Vera Lim", "viewer")):
        c.page.request.post(c.base + "/admin/api/users", data=json.dumps({"username": user, "displayName": display, "roles": [role], "password": PASSWORD,
                                                                          "mustChangePassword": False}), headers={"Content-Type": "application/json"})
    _state["users"] = True


def trader(c: Ctx) -> str:
    """The trade's trader, a masked field for anyone who is not raw (read as asha, who sees it)."""
    return c.page.evaluate("async () => (await (await fetch('/export/trade/%s.json')).json()).trader || 'A. Shah'" % TRADE)


def open_view(c: Ctx, path: str, width: int = 1440, height: int = 900):
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + path)
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)


@shot("01-share-dialog.jpg", also=("02-share-sent.jpg",))
def share_dialog(c: Ctx):
    """asha shares the pinned view with vera through the dialog (Alt+S); the filled dialog, then the answer."""
    people(c)
    login(c, "asha")
    open_view(c, f"/v/trade/{TRADE}?asOf={PIN}")
    note = NOTE.format(t=trader(c))
    c.page.keyboard.press("Alt+s")
    c.page.locator("[data-share-dialog]:not([hidden])").wait_for()
    c.page.locator("[data-shr-to]").fill("ver")
    c.page.locator(".shr-list [role=option]").first.wait_for()
    c.page.keyboard.press("Enter")
    c.page.locator("[data-shr-note]").fill(note)
    c.page.wait_for_timeout(600)
    c.save("01-share-dialog.jpg")
    c.page.keyboard.press("Control+Enter")
    c.until("(document.querySelector('[data-shr-result]') || {}).textContent.indexOf('Sent') >= 0", 30)
    c.page.wait_for_timeout(600)
    c.save("02-share-sent.jpg")


@shot("03-recipient-bell.jpg", also=("04-inbox.jpg",))
def inbox(c: Ctx):
    """vera's top bar with the bell's count, then her inbox."""
    people(c)
    login(c, "vera")
    c.page.set_viewport_size({"width": 1440, "height": 700})
    c.page.goto(c.base + "/t")
    c.page.locator("[data-bell]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)
    c.save("03-recipient-bell.jpg", clip={"x": 0, "y": 0, "width": 1440, "height": 120})
    c.page.goto(c.base + "/inbox")
    c.page.locator(".inbox-row").first.wait_for(timeout=30000)
    c.page.set_viewport_size({"width": 1100, "height": 360})
    c.save("04-inbox.jpg")


@shot("05-shared-view.jpg", also=("06-shared-phone.jpg",))
def shared_view(c: Ctx):
    """The link from the inbox opens the view as asha sent it (her date, the banner and note), with vera's own masks."""
    people(c)
    login(c, "vera")
    c.page.goto(c.base + "/inbox")
    c.page.locator("a[data-inbox-open]").first.click()
    c.page.locator("[data-share-banner]").wait_for(timeout=30000)
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.set_viewport_size({"width": 1440, "height": 900})
    c.page.wait_for_timeout(1800)
    c.save("05-shared-view.jpg")
    c.page.set_viewport_size({"width": 390, "height": 844})
    c.page.wait_for_timeout(900)
    c.save("06-shared-phone.jpg")


@shot("07-discussion-author.jpg", also=("08-discussion-viewer.jpg", "09-discussion-phone.jpg"))
def discussion(c: Ctx):
    """asha mentions vera and writes the trader's name typed and quoted; the same thread as asha, as vera, and as vera on a phone."""
    people(c)
    login(c, "asha")
    secret = trader(c)
    open_view(c, f"/v/trade/{TRADE}?asOf={PIN}")
    c.page.keyboard.press("Alt+n")
    c.page.locator("#aboutDrawer:not([hidden])").wait_for()
    c.until("document.activeElement && document.activeElement.id === 'discBody'", 30)
    c.page.keyboard.type("@ver")
    c.page.locator("#discOpts [role=option]").first.wait_for()
    c.page.keyboard.press("Enter")
    c.page.keyboard.type(f"is {secret} the right trader? He is {{$.trader}} here, and the MTM is {{")
    c.page.locator("#discOpts [role=option]").first.wait_for()
    c.page.keyboard.type("mtm")
    c.page.locator("#discOpts [role=option]").first.wait_for()
    c.page.keyboard.press("Enter")
    c.page.keyboard.press("Control+Enter")
    c.page.locator(".disc-c").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(900)
    c.save("07-discussion-author.jpg")
    login(c, "vera")
    open_view(c, f"/v/trade/{TRADE}")
    c.page.keyboard.press("Alt+n")
    c.page.locator(".disc-c").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(900)
    c.save("08-discussion-viewer.jpg")
    open_view(c, f"/v/trade/{TRADE}", width=390, height=844)
    c.page.keyboard.press("Alt+n")
    c.page.locator(".disc-c").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(900)
    c.save("09-discussion-phone.jpg")
