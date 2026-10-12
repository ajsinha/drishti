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

"""Pictures of tutorial 9, Reports (docs/tutorials/09-reports.md), written to docs/tutorials/img/t09/.

A search becomes a scheduled report (CSV in the reports folder), a view becomes a subscription for Vera, and the Reports menu lists both in one place."""
from __future__ import annotations

import urllib.parse

from _tut import OUT, SERVER_ENV, VERA, call, go, login, people  # noqa: F401
from shotlib import Ctx, register

shot = register("t09", OUT / "t09")
QUERY = "TRD where mtm < -10000000 order by mtm"


def clean(c: Ctx, *names: str):
    """Starts from no reports of these names (a repeated run leaves nothing behind)."""
    for name in names:
        c.page.request.post(c.base + "/reports/" + urllib.parse.quote(name) + "/delete")


@shot("01-schedule-link.jpg", also=("02-report-form.jpg", "03-report-saved.jpg"))
def saved_report(c: Ctx):
    people(c)
    login(c, height=900)
    clean(c, "Big losers")
    go(c, "/s?q=" + urllib.parse.quote(QUERY), wait="table", settle=1200)
    c.save("01-schedule-link.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 420})
    go(c, "/reports?q=" + urllib.parse.quote(QUERY), wait="form[action='/reports']", settle=800)
    f = c.page.locator("form[action='/reports']")
    f.locator("[name=name]").fill("Big losers")
    f.locator("[name=schedule]").fill("business-days 18:30")
    f.locator("[name=date]").select_option("previous")
    c.page.wait_for_timeout(400)
    c.save("02-report-form.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 900})
    f.locator("button[type=submit]").click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(1200)
    c.save("03-report-saved.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 700})


@shot("04-run-now.jpg")
def run_now(c: Ctx):
    people(c)
    login(c, height=900)
    go(c, "/reports", wait="tr[data-row-name='Big losers']", settle=800)
    c.page.locator("tr[data-row-name='Big losers'] form[action$='/run'] button").click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(2500)
    c.save("04-run-now.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 700})


@shot("05-subscribe-form.jpg", also=("09-email-refused.jpg", "06-reports-menu.jpg", "07-reports-list.jpg", "10-subscription-run.jpg", "11-subscription-history.jpg", "08-reports-phone.jpg"))
def subscription(c: Ctx):
    people(c)
    login(c, height=900)
    clean(c, "Swap for Vera")
    go(c, "/v/trade/MX-20000001", wait="[data-panel]", settle=1500)
    c.page.get_by_text("Subscribe", exact=True).first.click()
    c.page.locator("[data-subscribe-form]").wait_for(timeout=30000)
    f = c.page.locator("[data-subscribe-form]")
    f.locator("[name=name]").fill("Swap for Vera")
    f.locator("[name=schedule]").fill("after-load trade")
    f.locator("[name=date]").select_option("load")
    f.locator("[name=format]").select_option("pdf")
    f.locator("[name=recipients]").fill("vera@example.com")
    f.locator("button[type=submit]").click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(1200)
    c.save("09-email-refused.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 360})
    f = c.page.locator("[data-subscribe-form]")
    f.locator("[name=recipients]").fill(VERA)
    c.page.wait_for_timeout(500)
    c.save("05-subscribe-form.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 760})
    f.locator("button[type=submit]").click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(1200)
    go(c, "/reports", wait="table", settle=800)
    c.page.locator(".tbar-menu-btn[title='Views']").first.click()                     # Reports is an entry of Views (with Forms, Terminal, Search, Workspaces, Monitors, Alerts)
    c.page.wait_for_timeout(500)
    c.page.locator(".dropdown-menu.show a.mega-item", has_text="Reports").first.hover()
    c.page.wait_for_timeout(300)
    c.save("06-reports-menu.jpg", clip={"x": 0, "y": 0, "width": 1280, "height": 460})
    c.page.keyboard.press("Escape")
    c.page.mouse.click(640, 700)
    c.page.wait_for_timeout(300)
    c.save("07-reports-list.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 760})
    go(c, "/reports", wait="tr[data-row-name='Swap for Vera']", settle=500)
    c.page.locator("tr[data-row-name='Swap for Vera'] form[action$='/run'] button").click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(3000)
    c.save("10-subscription-run.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 700})
    c.page.locator("details.dist-hist summary").first.click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(1200)
    c.save("11-subscription-history.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 600})
    go(c, "/reports", width=390, height=844, wait="table", settle=800)
    c.save("08-reports-phone.jpg")
