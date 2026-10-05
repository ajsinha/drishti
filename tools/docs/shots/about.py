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

"""The pictures of About this page (docs/guides/USER_GUIDE.md "About this page", docs/guides/PACK_DEVELOPER_GUIDE.md "About text and glossary"),
written to docs/guides/img/about/.

Sign-in is ON with two people, because what the drawer says depends on who asks: `asha` is an author (role `author`, sees every field) and
`vera` a viewer (role `viewer`, fields named in `drishti.security.redact` read as the mask). The scratch server adds `limit` to `redact`
for this guide ONLY, as a clearly labelled demo: it makes the VaR page's limit a masked field, so the picture of vera's drawer shows the pack
text with the mask in it. No shipped configuration masks `limit`. Run it on its own, which starts that server:

    console/.venv/bin/python tools/docs/screenshots.py --guide about
"""
from __future__ import annotations

import json
import re

from shotlib import Ctx, register

shot = register("about")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": "about-scratch-secret-0123456789abcdef-xyz",
              "DRISHTI_SECURITY_REDACT": "trader,counterpartyId,patientName,lifecycle.timeline.description,limit",   # `limit` added: a demo only
              "DRISHTI_AUTH_ENABLED": "true", "DRISHTI_SESSION_SECRET": "about-session-secret-0123456789abcdef-xyz", "DRISHTI_SECURE_COOKIE": "false"}
PASSWORD = "about-pass-2026"
VAR, VARIANT = "/v/var/VAR-EQD", "/v/variant/VRNT-BRAF-V600E"
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
    for user, role in (("asha", "author"), ("vera", "viewer")):
        c.page.request.post(c.base + "/admin/api/users", data=json.dumps({"username": user, "displayName": user.title(), "roles": [role], "password": PASSWORD}),
                            headers={"Content-Type": "application/json"})
    _state["users"] = True


def drawer(c: Ctx, user: str, path: str, width: int = 1440, height: int = 900, layers: bool = False):
    """Signs in as `user`, opens the view and presses ? (the first press fetches the explanation); `layers` opens every layer."""
    people(c)
    login(c, user)
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + path)
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)
    c.page.keyboard.press("?")
    c.page.locator("#aboutDrawer:not([hidden]) details").first.wait_for(timeout=30000)
    if layers:
        c.page.evaluate("document.querySelectorAll('#aboutDrawer details').forEach(function (d) { d.open = true; })")
    c.page.wait_for_timeout(900)


@shot("01-var-drawer.jpg")
def var_desktop(c: Ctx):
    drawer(c, "asha", VAR)
    c.save("01-var-drawer.jpg")


@shot("02-var-layers.jpg")
def var_layers(c: Ctx):
    drawer(c, "asha", VAR, height=1300, layers=True)
    c.save("02-var-layers.jpg", locator="#aboutDrawer")


@shot("03-var-phone.jpg")
def var_phone(c: Ctx):
    drawer(c, "asha", VAR, width=390, height=844)
    c.save("03-var-phone.jpg")


@shot("04-genomics-variant.jpg")
def genomics(c: Ctx):
    drawer(c, "asha", VARIANT)
    c.save("04-genomics-variant.jpg")


@shot("05-viewer-masked.jpg")
def viewer(c: Ctx):
    """Demo: `limit` is masked for vera by this guide's scratch server only; the pack text reads the mask where the limit would be."""
    drawer(c, "vera", VAR)
    c.save("05-viewer-masked.jpg")
