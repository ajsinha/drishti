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

"""Pictures of tutorial 3, Query by form (docs/tutorials/03-query-by-form.md), written to docs/tutorials/img/t03/."""
from __future__ import annotations

from _tut import OUT, SERVER_ENV, go, login  # noqa: F401
from shotlib import Ctx, register

shot = register("t03", OUT / "t03")
FORM = "/form/trading?kind=trade"
RESULT = ("/form/trading?kind=trade&run=1&op.counterparty.name=contains&v.counterparty.name=Meridian&op.notional=%3E%3D&v.notional=100m"
          "&v.currency=EUR&v.currency=USD&op.currency=in&op.tradeDate=between&v.tradeDate=2025-01-01&v2.tradeDate=2025-12-31&order=mtm&desc=1&limit=25")


def open_form(c: Ctx, path: str = FORM, height: int = 1100):
    login(c)
    go(c, path, height=height, wait="[data-qform]", settle=900)


@shot("01-empty-form.jpg", also=("02-filled.jpg", "03-checkbox-dropdown.jpg"))
def fill(c: Ctx):
    open_form(c)
    c.save("01-empty-form.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 760})
    c.page.locator('[name="op.counterparty.name"]').select_option("contains")
    c.page.fill('[name="v.counterparty.name"]', "Meridian")
    c.page.locator('[name="op.notional"]').select_option(">=")
    c.page.fill('[name="v.notional"]', "100m")
    c.page.locator('[name="op.tradeDate"]').select_option("between")
    c.page.fill('[name="v.tradeDate"]', "2025-01-01")
    c.page.fill('[name="v2.tradeDate"]', "2025-12-31")
    cur = c.page.locator("details.ms-box", has=c.page.locator('[name="v.currency"]'))
    cur.locator("summary").click()
    c.page.wait_for_timeout(300)
    cur.locator('[name="v.currency"][value="EUR"]').check()
    cur.locator('[name="v.currency"][value="USD"]').check()
    c.page.wait_for_timeout(300)
    c.save("03-checkbox-dropdown.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 900})
    cur.locator("summary").click()
    c.page.wait_for_timeout(300)
    c.save("02-filled.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 900})


@shot("04-results.jpg", also=("05-save-and-watch.jpg",))
def results(c: Ctx):
    open_form(c, RESULT, height=1500)
    c.page.locator("[data-qf-command]").wait_for(timeout=30000)
    c.page.wait_for_timeout(800)
    top = c.page.locator(".qf-command").bounding_box()
    c.save("04-results.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 760})
    keep = c.page.locator(".qf-keep").bounding_box()
    c.save("05-save-and-watch.jpg", clip={"x": 0, "y": max(0, keep["y"] - 20), "width": 1280, "height": keep["height"] + 40})


@shot("06-regex.jpg", also=("07-regex-refused.jpg",))
def regex(c: Ctx):
    open_form(c, "/form/trading?kind=trade&run=1&op.counterparty.name=regex&v.counterparty.name=%5E%28Meridian%7CNorthbridge%29&limit=25", height=1500)
    c.page.locator("[data-qf-command]").wait_for(timeout=30000)
    c.page.wait_for_timeout(800)
    c.save("06-regex.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 760})
    go(c, "/form/trading?kind=trade&run=1&op.counterparty.name=regex&v.counterparty.name=%28a%29%5C1", height=1500, wait="[data-qf-error]", settle=500)
    box = c.page.locator("[data-qf-error]").bounding_box()
    c.save("07-regex-refused.jpg", clip={"x": 0, "y": max(0, box["y"] - 60), "width": 1280, "height": 260})


@shot("08-saved-search.jpg", also=("09-monitor.jpg",))
def keep(c: Ctx):
    open_form(c, RESULT, height=1500)
    c.page.locator("[data-qf-save]").wait_for(timeout=30000)
    c.page.fill("#qfName", "Meridian EUR and USD 2025")
    c.page.locator("[data-qf-save] button").click()
    c.page.locator("[data-qf-saved]:not(:empty)").wait_for(timeout=30000)
    c.page.wait_for_timeout(400)
    box = c.page.locator(".qf-keep").bounding_box()
    c.save("08-saved-search.jpg", clip={"x": 0, "y": max(0, box["y"] - 20), "width": 1280, "height": box["height"] + 40})
    c.page.fill("#qfWatch", "Meridian big trades")
    c.page.locator(".srch-watch button", has_text="Watch as a monitor").click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(2500)
    c.save("09-monitor.jpg")
