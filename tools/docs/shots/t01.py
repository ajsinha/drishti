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

"""Pictures of tutorial 1, Your first 15 minutes (docs/tutorials/01-first-15-minutes.md), written to docs/tutorials/img/t01/."""
from __future__ import annotations

from _tut import ADMIN, ADMIN_PASSWORD, OUT, SERVER_ENV, command, go, login, page_shot  # noqa: F401
from shotlib import Ctx, register

shot = register("t01", OUT / "t01")


@shot("01-sign-in.jpg")
def sign_in(c: Ctx):
    c.page.set_viewport_size({"width": 1280, "height": 800})
    c.page.context.clear_cookies()
    c.page.goto(c.base + "/login")
    c.page.fill("input[name=user]", ADMIN)
    c.page.fill("input[name=password]", ADMIN_PASSWORD)
    c.page.wait_for_timeout(400)
    c.save("01-sign-in.jpg")


@shot("02-home.jpg", also=("03-typeahead.jpg",))
def home(c: Ctx):
    login(c)
    go(c, "/t", wait=".cmd-input")
    c.save("02-home.jpg")
    c.page.locator("#cmdInput").click()
    c.page.keyboard.type("TRD MX-200000", delay=30)
    c.page.locator("#cmdInput").evaluate("e => e.focus()")
    c.page.wait_for_timeout(1200)
    c.save("03-typeahead.jpg", clip={"x": 0, "y": 0, "width": 1280, "height": 460})


@shot("04-trade.jpg", also=("05-trade-phone.jpg",))
def trade(c: Ctx):
    login(c)
    go(c, "/t", wait=".cmd-input")
    command(c, "TRD MX-20000001")
    c.page.wait_for_url("**/v/trade/MX-20000001")
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(2200)
    c.save("04-trade.jpg")
    go(c, "/v/trade/MX-20000001", width=390, height=844)
    c.save("05-trade-phone.jpg")


@shot("06-search.jpg")
def search(c: Ctx):
    login(c)
    go(c, "/t", wait=".cmd-input")
    command(c, "TRD where mtm > 1m order by mtm desc limit 50")
    c.page.wait_for_url("**/s?*")
    c.page.locator("table").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)
    c.save("06-search.jpg")


@shot("07-forms-home.jpg", also=("08-form-trading.jpg",))
def forms(c: Ctx):
    login(c)
    go(c, "/forms", wait=None, settle=1200)
    c.save("07-forms-home.jpg")
    go(c, "/form/trading?kind=trade", height=900, wait="[data-qform]", settle=900)
    c.save("08-form-trading.jpg")


@shot("09-bi-home.jpg")
def bi(c: Ctx):
    login(c)
    go(c, "/bi", wait=None, settle=2500)
    c.save("09-bi-home.jpg")


@shot("10-api-tokens.jpg", also=("11-api-token-secret.jpg",))
def tokens(c: Ctx):
    """My account -> API tokens: a token for the command line with the four write scopes the tutorials use, then the secret, shown once."""
    login(c, height=2400)
    go(c, "/account#tokens", height=2400, wait=None, settle=1000)
    box = c.page.locator("[data-token-new]")
    box.locator("[name=name]").fill("tutorials")
    box.locator("[name=days]").fill("30")
    for scope in ("design:write", "loads:write", "packs:admin", "bi:write"):
        box.locator(f'[name=scope][value="{scope}"]').check()
    c.page.evaluate("window.scrollTo(0, 0)")
    b = box.bounding_box()
    page_shot(c, "10-api-tokens.jpg", b)
    box.locator("button[type=submit]").click()
    c.page.locator("[data-secret]:not([hidden])").wait_for(timeout=30000)
    c.page.wait_for_timeout(600)
    c.page.evaluate("window.scrollTo(0, 0)")
    b = c.page.locator("[data-secret]").bounding_box()
    page_shot(c, "11-api-token-secret.jpg", b, 58)
