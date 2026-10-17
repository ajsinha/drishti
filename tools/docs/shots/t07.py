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

"""Pictures of tutorial 7, Load data daily (docs/tutorials/07-load-data-daily.md), written to docs/tutorials/img/t07/.

The pack is `trading`, the kind `trade`. The expectation is set on the page; the loads are announced the way a nightly job does (a token that holds only
`loads:write`, the real API); the measure alert is made on the alert form. Dates are business days before the date the server calls current."""
from __future__ import annotations

import json
import urllib.request

from _tut import lake_days, OUT, SERVER_ENV, call, go, login  # noqa: F401
from shotlib import SERVER_PORT, Ctx, register

shot = register("t07", OUT / "t07")
PACK, KIND = "trading", "trade"


def token(c: Ctx) -> str:
    """A token that may only read and announce loads: what a nightly job gets."""
    r = call(c, "POST", "/api/tokens", {"name": "nightly-load", "days": 30, "scopes": ["loads:write"]})
    return r["secret"]


def announce(secret: str, body: dict) -> dict:
    req = urllib.request.Request(f"http://127.0.0.1:{SERVER_PORT}/api/v1/packs/{PACK}/loads", method="POST", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {secret}"})
    with urllib.request.urlopen(req, timeout=60) as r:      # noqa: S310 - the scratch server
        return json.loads(r.read())


def business_dates(c: Ctx) -> dict:
    return call(c, "GET", "/api/business-date") or {}


@shot("01-loads-empty.jpg", also=("02-expectations.jpg",))
def expectations(c: Ctx):
    login(c, height=900)
    call(c, "PUT", f"/admin/api/loads/{PACK}/config", {"notify": {"roles": ["admin"], "users": [], "email": False}, "smoke": 2, "expect": {}})
    go(c, f"/admin/packs/{PACK}/loads", height=900, wait="[data-loads]", settle=800)
    c.save("01-loads-empty.jpg")
    c.page.keyboard.press("e")
    d = c.page.locator("[data-config-dialog]")
    d.wait_for()
    d.locator("[data-expect-add]").click()
    row = d.locator("[data-expect-rows] tbody tr").last
    row.locator("select[name=x-kind]").select_option(KIND)
    row.locator("input[name=x-by]").fill("18:00")
    row.locator("input[name=x-zone]").fill("America/New_York")
    row.locator("input[name=x-cal]").fill("USNY")
    c.page.wait_for_timeout(400)
    c.save("02-expectations.jpg", "[data-config-dialog]")
    d.locator("[data-config-save]").click()
    c.page.wait_for_timeout(1500)


@shot("03-history.jpg", also=("04-expectation-states.jpg", "05-health-late.jpg"))
def history(c: Ctx):
    login(c, height=1000)
    secret = token(c)
    d13, d14, d15 = lake_days(4)[:3]                      # the tutorial: 2026-10-13 ready (API), 2026-10-14 failed, 2026-10-15 ready; the last day left missing
    for body in ({"kind": KIND, "businessDate": d15, "status": "ready", "rows": 15000, "rejected": 0, "source": "nightly-trade-etl", "batchId": "eod-" + d15.replace("-", "")},
                 {"kind": KIND, "businessDate": d13, "status": "ready", "rows": 14998, "rejected": 2, "source": "nightly-trade-etl", "batchId": "eod-" + d13.replace("-", "")},
                 {"kind": KIND, "businessDate": d14, "status": "failed", "batchId": "eod-" + d14.replace("-", ""), "note": "copy stopped: disk full"}):
        announce(secret, body)
    go(c, f"/admin/packs/{PACK}/loads", height=1100, wait="[data-loads-table] tr", settle=1000)
    c.page.locator("[data-loads-table] tr[data-status=ready] .loads-steps summary").first.click()
    c.page.wait_for_timeout(400)
    c.save("03-history.jpg")
    c.save("04-expectation-states.jpg", "[data-expectations]")
    go(c, "/admin/health", height=800, wait="[data-data-late]", settle=800)
    c.save("05-health-late.jpg", ".hl-late")


@shot("06-alert-form.jpg", also=("07-alert-checked.jpg", "08-alert-saved.jpg"))
def alert(c: Ctx):
    login(c, height=1500)
    go(c, "/alerts/new", height=1500, wait="[data-alert-form]", settle=600)
    f = c.page.locator("[data-alert-form]")
    f.locator("[name=name]").fill("trades-loaded")
    f.locator("[name=title]").fill("Trades have not been loaded")
    f.locator("[name=severity]").select_option("warn")
    f.locator("[name=source_type]").select_option("model")             # since 2.2 the measures live on the model (Trades, the basics)
    f.locator("[data-al-model] option[value='trades-basic@1']").wait_for(state="attached", timeout=30000)
    f.locator("[data-al-model]").select_option("trades-basic@1")
    f.locator("[data-al-measure] option[value='Trades']").wait_for(state="attached", timeout=30000)
    f.locator("[data-al-measure]").select_option("Trades")
    f.locator("[name=condition_type]").select_option("nodata")
    f.locator("[name=by]").fill("18:30")
    f.locator("[name=trigger_mode]").select_option("after-load")
    f.locator("[name=load_kind]").fill("trade")
    f.locator("[name=d_inbox]").check()
    c.page.wait_for_timeout(500)
    c.save("06-alert-form.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 1050})
    f.locator("[data-check-now]").click()
    c.page.locator("[data-check-out]:not(:empty)").wait_for(timeout=60000)
    c.page.wait_for_timeout(600)
    c.save("07-alert-checked.jpg", clip={"x": 0, "y": 700, "width": 1280, "height": 700})
    f.locator("button[type=submit]").click()
    c.page.wait_for_load_state()
    c.page.wait_for_timeout(1200)
    c.save("08-alert-saved.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 700})
