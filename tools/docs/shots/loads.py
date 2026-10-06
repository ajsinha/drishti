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

"""The pictures of the data loads guide (docs/guides/DATA_LOADS.md), written to docs/guides/img/loads/.

They are made on the scratch server of tools/docs/screenshots.py (the QUICKSTART packs). Each picture first announces the loads and sets the
expectations it shows, through the real API, so the page is what a real ETL would produce; announcing the same batch twice is a no-op, so
the pictures can be remade at any time. The expectations are relative to "today", so run it on a business day to see an on-time load:

    DRISHTI_SHOTS_SERVER_PORT=18970 DRISHTI_SHOTS_CONSOLE_PORT=17970 \\
      drishti-console/.venv/bin/python tools/docs/screenshots.py --guide loads --base http://127.0.0.1:17970"""
from __future__ import annotations

import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shotlib import SERVER_PORT, Ctx, register  # noqa: E402

shot = register("loads")
PACK = "market-risk"
CONFIG = {"notify": {"roles": ["admin"], "users": [], "email": False}, "smoke": 2,
          "expect": {"var": {"by": "00:01", "zone": "America/New_York", "calendar": "USNY"},
                     "stress-result": {"by": "23:59", "zone": "America/New_York", "calendar": "USNY"},
                     "pnl-explain": {"by": "07:30", "zone": "Europe/London", "calendar": "GBLO"}}}


def call(method: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(f"http://127.0.0.1:{SERVER_PORT}/api/v1{path}", method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:      # noqa: S310 - the scratch server
        return json.loads(r.read())


def seed() -> None:
    today = call("GET", "/business-date")["current"]
    call("PUT", f"/admin/loads/{PACK}/config", CONFIG)
    loads = [("var", "2026-10-01", "ready", 1180, 0, "eod-20261001", None), ("var", "2026-10-02", "ready", 1195, 2, "eod-20261002", None),
             ("var", today, "failed", None, None, "eod-today", "copy stopped: disk full"), ("var", today, "ready", 1200, 3, "eod-today-2", None),
             ("stress-result", today, "ready", 840, 0, "stress-today", None), ("frtb-sensitivity", "2026-10-02", "ready", 9120, 41, "frtb-1002", None)]
    for kind, date, status, rows, rejected, batch, note in loads:
        body = {"kind": kind, "businessDate": date, "status": status, "source": "nightly-risk-etl", "batchId": batch}
        body.update({k: v for k, v in (("rows", rows), ("rejected", rejected), ("note", note)) if v is not None})
        call("POST", f"/packs/{PACK}/loads", body)


def open_page(c: Ctx, path: str = f"/admin/packs/{PACK}/loads") -> None:
    seed()
    c.page.set_viewport_size({"width": 1440, "height": 900})
    c.page.goto(c.base + path)
    c.page.locator("[data-loads]").wait_for(timeout=30000)
    c.page.wait_for_timeout(600)


@shot("01-data-loads-history.jpg")
def history(c: Ctx):
    open_page(c)
    c.page.set_viewport_size({"width": 1440, "height": 1700})
    c.page.locator("[data-loads-table] tr[data-status=ready] .loads-steps summary").first.click()
    c.page.wait_for_timeout(300)
    c.save("01-data-loads-history.jpg")
    c.page.set_viewport_size({"width": 1440, "height": 900})


@shot("02-expectations.jpg")
def expectations(c: Ctx):
    open_page(c)
    c.save("02-expectations.jpg", locator="[data-expectations]")


@shot("03-load-steps.jpg")
def steps(c: Ctx):
    open_page(c)
    c.page.set_viewport_size({"width": 1440, "height": 1700})                 # tall enough that nothing scrolls or hides under the sticky bar
    row = c.page.locator("[data-loads-table] tr[data-status=ready]").first
    row.locator(".loads-steps summary").click()
    c.page.wait_for_timeout(300)
    box = row.bounding_box()
    c.save("03-load-steps.jpg", clip={"x": box["x"], "y": box["y"], "width": box["width"], "height": box["height"]})
    c.page.set_viewport_size({"width": 1440, "height": 900})


@shot("04-settings.jpg")
def settings(c: Ctx):
    open_page(c)
    c.page.keyboard.press("e")
    c.page.locator("[data-config-dialog]").wait_for()
    c.page.wait_for_timeout(300)
    c.save("04-settings.jpg", locator="[data-config-dialog]")


@shot("05-health-late.jpg")
def health(c: Ctx):
    seed()
    c.page.set_viewport_size({"width": 1440, "height": 700})
    c.page.goto(c.base + "/admin/health")
    c.page.locator("[data-data-late]").first.wait_for(timeout=30000)
    c.save("05-health-late.jpg", locator=".hl-late")


@shot("06-phone.jpg")
def phone(c: Ctx):
    seed()
    c.page.set_viewport_size({"width": 390, "height": 844})
    c.page.goto(c.base + f"/admin/packs/{PACK}/loads")
    c.page.locator("[data-loads]").wait_for(timeout=30000)
    c.page.wait_for_timeout(600)
    c.save("06-phone.jpg")
    c.page.set_viewport_size({"width": 1440, "height": 900})
