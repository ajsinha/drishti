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

"""Pictures of tutorial 11, BI that runs itself (docs/tutorials/11-bi-that-runs-itself.md), written to docs/tutorials/img/t11/.

A dataset that rebuilds itself every business morning, a subscription to a report (what Run now says without a mail server, and with one: the pictures marked
"mail" need the server started with spring.mail.host pointing at a catcher), an alert on a number, and the four export formats."""
from __future__ import annotations

import base64
import urllib.parse

from _bi import bi, clean_items, open_report, settled
from _tut import ROOT, OUT, elem_shot, SERVER_ENV, VERA, call, go, login, people  # noqa: F401
from shotlib import Ctx, register

shot = register("t11", OUT / "t11")
DATA = ROOT / "docs" / "tutorials" / "data"
DATASET_TITLE = "Trades refreshed daily"
WORKSPACE = "economics-desk"
WORLD_REPORT = "file:report:world-economics"
OVERVIEW = "file:report:trades-overview"


def scheduled_dataset(c: Ctx) -> str:
    """The published dataset that refreshes itself (made once; found again on later runs)."""
    cat = bi(c, "GET", "/catalogue")
    for it in (cat.get("items", []) if isinstance(cat, dict) else cat):
        if it["title"] == DATASET_TITLE and not it.get("shipped"):
            return it["id"]
    if not any(w["id"] == WORKSPACE for w in bi(c, "GET", "/workspaces")):
        bi(c, "POST", "/workspaces", {"name": "Economics desk", "description": "Reports on the world economy"})
    made = bi(c, "POST", f"/workspaces/{WORKSPACE}/items", {"kind": "dataset", "title": DATASET_TITLE, "body": (DATA / "trades-daily.dataset.yaml").read_text()})
    bi(c, "POST", f"/items/{made['id']}/publish", {"note": "refreshes itself on business mornings"})
    return made["id"]


@shot("01-refresh.jpg", also=("02-refresh-now.jpg",))
def refresh(c: Ctx):
    login(c, height=900)
    ds = scheduled_dataset(c)
    go(c, "/bi/d/" + ds, wait="h1", settle=1500)
    c.page.locator("[data-refresh-sec]").scroll_into_view_if_needed()
    c.page.evaluate("document.querySelector('[data-refresh-sec]').scrollIntoView()")
    c.page.wait_for_timeout(500)
    box = c.page.locator("[data-refresh-sec]").bounding_box()
    c.save("01-refresh.jpg", clip={"x": 240, "y": max(0, box["y"] - 20), "width": 1040, "height": min(500, 900 - max(0, box["y"] - 20))})
    call(c, "POST", f"/bi/api/datasets/{ds}/refresh", {})
    c.page.wait_for_timeout(5000)
    c.page.reload()
    c.page.wait_for_selector("[data-refresh-sec]")
    c.page.wait_for_timeout(1500)
    c.page.evaluate("document.querySelector('[data-refresh-sec]').scrollIntoView()")
    c.page.wait_for_timeout(400)
    box = c.page.locator("[data-refresh-sec]").bounding_box()
    c.save("02-refresh-now.jpg", clip={"x": 240, "y": max(0, box["y"] - 20), "width": 1040, "height": min(500, 900 - max(0, box["y"] - 20))})


@shot("03-subscribe-form.jpg", also=("04-subscription-saved.jpg", "05-run-no-smtp.jpg", "06-history-no-smtp.jpg"))
def subscribe_no_mail(c: Ctx):
    people(c)
    login(c, height=900)
    for name in ("Morning economy pack",):
        c.page.request.post(c.base + "/reports/" + urllib.parse.quote(name) + "/delete")
    open_report(c, WORLD_REPORT, wait_map=True)
    c.page.click("[data-subscribe]")
    c.page.wait_for_selector("[data-subscribe-form]")
    f = c.page.locator("[data-subscribe-form]")
    f.locator('input[name="name"]').fill("Morning economy pack")
    f.locator('input[name="schedule"]').fill("business-days 07:30")
    f.locator('input[name="recipients"]').fill(VERA)
    f.locator('select[name="format"]').select_option("xlsx")
    c.page.wait_for_timeout(500)
    c.save("03-subscribe-form.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 800})
    f.locator("button[type=submit]").click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(1500)
    c.save("04-subscription-saved.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 640})
    c.page.locator('tr[data-row-name="Morning economy pack"] form[action$="/run"] button').click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(3500)
    c.save("05-run-no-smtp.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 640})
    c.page.locator("details.dist-hist summary").first.click()
    c.page.wait_for_timeout(1200)
    c.save("06-history-no-smtp.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 700})


def clean_alerts(c: Ctx, *names: str):
    for n in names:
        c.page.request.post(c.base + "/alerts/measure/" + urllib.parse.quote(n) + "/delete")


@shot("07-alert-me.jpg", also=("08-alert-form.jpg", "09-alerts-list.jpg", "10-alert-history.jpg", "13-inbox-alert.jpg"))
def alerts(c: Ctx):
    login(c, height=900)
    clean_alerts(c, "MTM floor", "Notional ceiling")
    open_report(c, OVERVIEW, "overview")
    card = c.page.locator('[data-visual="mtm"]')
    card.scroll_into_view_if_needed()
    c.page.evaluate("window.scrollTo(0, 150)")
    c.page.wait_for_timeout(300)
    b = card.bounding_box()
    c.save("07-alert-me.jpg", clip={"x": max(0, b["x"] - 20), "y": max(0, b["y"] - 20), "width": b["width"] + 40, "height": b["height"] + 40})
    card.locator('[data-act="alert-me"]').click()
    c.page.wait_for_url("**/alerts/new**")
    c.page.fill('input[name="name"]', "MTM floor")
    c.page.select_option('select[name="op"]', "below")
    c.page.fill('input[name="value"]', "200000000")
    c.page.fill('input[name="hysteresis"]', "10000000")
    c.page.select_option('select[name="trigger_mode"]', "after-load")
    c.page.click("[data-check-now]")
    c.page.wait_for_function("() => document.querySelector('[data-check-out]').textContent.includes('Right now')")
    c.page.wait_for_timeout(500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("08-alert-form.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 844})
    c.page.click("[data-alert-form] button[type=submit]")
    c.page.wait_for_url("**/alerts?msg=**")
    c.page.wait_for_timeout(2500)
    c.page.reload()
    c.page.wait_for_timeout(1500)
    c.save("09-alerts-list.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 700})
    go(c, "/alerts/measure/MTM%20floor", wait="[data-history]", settle=1200)
    elem_shot(c, "10-alert-history.jpg", "[data-history]", pad=12)
    go(c, "/inbox?tab=alert", wait=".inbox", settle=1500)
    c.save("13-inbox-alert.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 480})


@shot("11-export-bar.jpg", also=("12-export-png.jpg",))
def exports(c: Ctx):
    login(c, height=900)
    open_report(c, OVERVIEW, "overview")
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("11-export-bar.jpg", clip={"x": 240, "y": 230, "width": 1040, "height": 150})
    url = f"/dist/bi/{urllib.parse.quote(OVERVIEW, safe='')}.png?page=overview"
    png = c.page.request.get(c.base + url).body()
    shown = c.page
    shown.goto("about:blank")
    shown.set_viewport_size({"width": 1280, "height": 900})
    shown.set_content('<body style="margin:0;background:#0b1020"><img style="max-width:100%" src="data:image/png;base64,' + base64.b64encode(png).decode() + '"></body>')
    shown.wait_for_timeout(800)
    from shotlib import out_dir
    shown.screenshot(path=str(out_dir("t11") / "12-export-png.jpg"), type="jpeg", quality=84)
    print("  wrote 12-export-png.jpg (a PNG of", len(png), "bytes)")


@shot("14-run-with-smtp.jpg", also=("15-history-with-smtp.jpg",))
def subscribe_with_mail(c: Ctx):
    """Needs the server started with spring.mail.host pointing at a mail catcher (the tutorial shows how); without it this picture says no SMTP host again."""
    people(c)
    login(c, height=900)
    go(c, "/reports", wait="tr[data-row-name='Morning economy pack']", settle=800)
    c.page.locator('tr[data-row-name="Morning economy pack"] form[action$="/run"] button').click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(4000)
    c.save("14-run-with-smtp.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 560})
    c.page.locator("details.dist-hist summary").first.click()
    c.page.wait_for_timeout(1200)
    c.save("15-history-with-smtp.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 640})
