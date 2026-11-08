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

"""The pictures of the Deployment and QA guide (docs/DEPLOYMENT_AND_QA_GUIDE.md), written to docs/img/deploy/.

They are taken from a server and console started exactly as the guide says (sign-in on, the demo lake, samples off, offline, the four
lenses offered, the data inbox on and owned by this node), on the scratch ports, and for the cluster pictures from three such servers
joined as one Drishti*net cluster at this machine's real address. The guide's own steps start them; then:

    DRISHTI_SHOTS_CONSOLE_PORT=<console port> drishti-console/.venv/bin/python tools/docs/screenshots.py --guide deployqa \\
        --base http://<this machine's address>:<console port> [--only 0,1]        # the single-node pictures (01..18)
    ... --only 2                                                                    # the cluster pictures (20..24), with the cluster up
    ... --only 15                                                                   # with the banking module off (a restart)

DRISHTI_SHOTS_DRAIN names the node the drain picture drains (its server URL); DRISHTI_SHOTS_DRAIN_TOKEN an administrator's token for it.
"""
from __future__ import annotations

import json
import os
import urllib.request

from _tut import SERVER_ENV, command, go, login  # noqa: F401  (the tutorials' sign-in and helpers)
from shotlib import ROOT, Ctx, register

shot = register("deployqa", ROOT / "docs" / "img" / "deploy")
WIDE, HIGH = 1440, 900


def page(c: Ctx, name: str, path: str, wait: str = "main", settle: int = 2500, height: int = HIGH, width: int = WIDE):
    go(c, path, width=width, height=height, wait=wait, settle=settle)
    c.save(name)


def typed(c: Ctx, name: str, text: str, wait: str = "main", settle: int = 4000, height: int = HIGH):
    go(c, "/t", height=height, wait=".cmd-input", settle=800)
    command(c, text)
    c.page.wait_for_load_state("networkidle")
    c.page.locator(wait).first.wait_for(timeout=60000)
    c.page.wait_for_timeout(settle)
    c.save(name)


def lens(c: Ctx, name: str):
    c.page.goto(c.base + f"/lens/{name}")
    c.page.locator("form button[type=submit]").first.click()
    c.page.wait_for_load_state("networkidle")


# ---- one node --------------------------------------------------------------------------------------------------------------------------------

@shot("01-sign-in.jpg")
def sign_in(c: Ctx):
    c.page.context.clear_cookies()
    c.page.set_viewport_size({"width": WIDE, "height": 700})
    c.page.goto(c.base + "/login")
    c.page.fill("input[name=user]", "drishti-dev-admin")
    c.page.fill("input[name=password]", "drishti-dev-admin123")
    c.page.wait_for_timeout(600)
    c.save("01-sign-in.jpg")


@shot("02-home-risk.jpg", also=("03-home-world.jpg",))
def homes(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    lens(c, "risk")
    go(c, "/", width=WIDE, height=HIGH, wait="main", settle=3500)
    c.save("02-home-risk.jpg")
    lens(c, "world")
    go(c, "/", width=WIDE, height=HIGH, wait="main", settle=3500)
    c.save("03-home-world.jpg")
    c.page.goto(c.base + "/lens/leave")
    c.page.wait_for_load_state("networkidle")


@shot("04-admin-health.jpg")
def health(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    page(c, "04-admin-health.jpg", "/admin/health", height=1300, settle=3000)


@shot("05-admin-lakes.jpg")
def lakes(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    page(c, "05-admin-lakes.jpg", "/admin/lakes", wait="table", height=1100)


@shot("06-var-bank.jpg", also=("07-var-desk.jpg",))
def var(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    typed(c, "06-var-bank.jpg", "VAR BANK", settle=6000, height=1000)
    typed(c, "07-var-desk.jpg", "VAR VAR-RATES", settle=6000, height=1000)


@shot("08-exposure.jpg", also=("09-predeal.jpg",))
def exposure(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    typed(c, "08-exposure.jpg", "EXP BANK", settle=6000, height=1000)
    page(c, "09-predeal.jpg", "/predeal", height=1000, settle=4000)


@shot("10-bi-report.jpg", also=("11-mr-cube.jpg",))
def bi(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    page(c, "10-bi-report.jpg", "/bi/r/trades-overview", height=1000, settle=8000)
    typed(c, "11-mr-cube.jpg", "MCUBE", settle=8000, height=1000)


@shot("12-elements-gallery.jpg")
def gallery(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    page(c, "12-elements-gallery.jpg", "/elements/gallery", settle=4000)


@shot("13-inbox.jpg", also=("14-feeds.jpg",))
def loads(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    page(c, "13-inbox.jpg", "/admin/loads/inbox", height=1000)
    page(c, "14-feeds.jpg", "/admin/loads/feeds", height=1000)


@shot("16-phone-home.jpg", also=("17-phone-var.jpg",))
def phone(c: Ctx):
    login(c, width=390, height=844)
    go(c, "/t", width=390, height=844, wait="main", settle=2500)
    c.save("16-phone-home.jpg")
    go(c, "/t", width=390, height=844, wait=".cmd-input", settle=800)
    command(c, "VAR BANK")
    c.page.wait_for_load_state("networkidle")
    c.page.wait_for_timeout(6000)
    c.save("17-phone-var.jpg")


@shot("18-engines.jpg")
def engines(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    page(c, "18-engines.jpg", "/admin/engines", height=1100)


# ---- the banking module off (the server restarted with DRISHTI_MODULES_BANKING_ENABLED=false) ---------------------------------------------------

@shot("15-banking-off.jpg")
def banking_off(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    typed(c, "15-banking-off.jpg", "VAR BANK", settle=3000, height=700)


# ---- three nodes ------------------------------------------------------------------------------------------------------------------------------

@shot("20-net-members.jpg", also=("21-net-drained.jpg", "22-var-while-drained.jpg", "23-health-net.jpg", "24-inbox-owner.jpg"))
def cluster(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    page(c, "20-net-members.jpg", "/admin/net", height=1100, settle=4000)
    node = os.environ.get("DRISHTI_SHOTS_DRAIN", "")
    token = os.environ.get("DRISHTI_SHOTS_DRAIN_TOKEN", "")
    if node:
        drain(node, token, True)
    try:
        page(c, "21-net-drained.jpg", "/admin/net", height=1100, settle=6000)
        typed(c, "22-var-while-drained.jpg", "VAR BANK", settle=6000, height=1000)
    finally:
        if node:
            drain(node, token, False)
    page(c, "23-health-net.jpg", "/admin/health", height=1300, settle=3000)
    page(c, "24-inbox-owner.jpg", "/admin/loads/inbox", height=1000)


def drain(server: str, token: str, on: bool) -> dict:
    req = urllib.request.Request(server.rstrip("/") + "/api/v1/admin/node/drain", data=json.dumps({"on": on}).encode(), method="POST",
                                 headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    return json.load(urllib.request.urlopen(req, timeout=30))
