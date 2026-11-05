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

"""Pictures of tutorial 35, data out with Arrow Flight (docs/tutorials/35-data-out-with-arrow-flight.md), written to
docs/tutorials/img/t35/, in the light theme. Start a scratch stack on the VaR pictures' lake (tools/docs/shots/_var.py) with sign-in on
(_tut.SERVER_ENV), the Flight port's certificate and key as PEM files (DRISHTI_FLIGHT_TLS_CERT, DRISHTI_FLIGHT_TLS_KEY: a test PKI) and
Flight switched on (Admin -> Engines, then a restart), then point this at its console:

    DRISHTI_SHOTS_SERVER_PORT=<port> DRISHTI_SHOTS_CONSOLE_PORT=<port> drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t35

The pictures, in the tutorial's order: Admin -> Engines with Arrow Flight on, My account -> API tokens after making the token "risk
notebook" with data:read, and Admin -> Data out (take that one while a stream reads: step 7 of the tutorial)."""
from __future__ import annotations

import os
from pathlib import Path

from _tut import OUT, light, login
from shotlib import Ctx, register

shot = register("t35", OUT / "t35")
WIDE, HIGH = 1440, 900


def before(c: Ctx, _name: str) -> None:
    light(c)
    user = os.environ.get("DRISHTI_T35_USER")                  # the reader (a person of their own: their token list starts empty); default the admin
    login(c, user, os.environ.get("DRISHTI_T35_PASSWORD"), width=WIDE, height=HIGH) if user else login(c, width=WIDE, height=HIGH)
    c.page.request.fetch(c.base + "/api/settings", method="PATCH", data='{"theme": "light"}', headers={"Content-Type": "application/json"})


@shot("01-flight-on.jpg")
def flight_on(c: Ctx):
    c.page.goto(c.base + "/admin/engines")
    c.page.wait_for_timeout(2500)
    y = c.page.evaluate("""() => { const e = Array.from(document.querySelectorAll('td, th, b, strong, span, div, h3')).find(x => x.children.length === 0
        && /^Arrow Flight$/.test(x.textContent.trim())); return e ? e.getBoundingClientRect().top + window.scrollY : 0; }""")
    c.save("01-flight-on.jpg", clip=None) if not y else c.page.screenshot(
        path=str(OUT / "t35" / "01-flight-on.jpg"), type="jpeg", quality=84, full_page=True, clip={"x": 0, "y": max(0, y - 300), "width": WIDE, "height": HIGH})


@shot("02-token.jpg")
def token(c: Ctx):
    """Makes the token "risk notebook" (30 days, data:read) as the tutorial does, then reloads: the list shows it, never its secret."""
    c.page.goto(c.base + "/account#tokens")
    c.page.wait_for_timeout(1500)
    c.page.fill("[data-token-new] input[name=name]", "risk notebook")
    c.page.fill("[data-token-new] input[name=days]", "30")
    c.page.check("[data-token-new] input[name=scope][value='data:read']")
    c.page.locator("[data-token-new] button[type=submit]").click()
    c.until("!document.querySelector('[data-secret]').hidden", 30)
    keep = os.environ.get("DRISHTI_T35_TOKEN_FILE")              # the secret, for the tutorial's later steps: a scratch file, never printed
    if keep:
        Path(keep).write_text(c.page.locator("[data-secret-text]").text_content().strip())
    c.page.reload()                                            # the list: the new token, its scope and expiry, never its secret
    c.page.wait_for_timeout(1500)
    c.page.evaluate("() => window.scrollTo(0, 0)")
    box = c.page.evaluate("() => { const a = document.querySelector('#tokens').getBoundingClientRect(); const s = document.querySelector('#tokens table').getBoundingClientRect();"
                          " return {y: a.top + window.scrollY, h: s.bottom - a.top}; }")
    c.page.screenshot(path=str(OUT / "t35" / "02-token.jpg"), type="jpeg", quality=84, full_page=True,
                      clip={"x": 0, "y": box["y"] - 10, "width": WIDE, "height": box["h"] + 30})


@shot("03-data-out.jpg")
def data_out(c: Ctx):
    c.page.goto(c.base + "/admin/data-out")
    c.page.wait_for_timeout(3000)
    c.save("03-data-out.jpg")
