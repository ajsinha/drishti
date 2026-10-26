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

"""The pictures of Functions, FUNC (docs/admin/COMMANDS.md): the page as a user and as an administrator, the administrator's Review side
panel, and the "not active" page of a command that is off. A real scratch server and console with sign-in on, over a small demo lake
(`drishti.py lake demo --only` a few kinds; the command list comes from the shipped packs, not from the data). The development administrator
turns FRTB off (with a reason) so the administrator's picture shows an off command and the user's does not; it is turned on again at the end.

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide functions
    (DRISHTI_SHOTS_SERVER_PORT / DRISHTI_SHOTS_CONSOLE_PORT move the scratch ports)"""
from __future__ import annotations

import json
import subprocess
import sys

from _tut import PASSWORD, SERVER_ENV, login
from shotlib import ROOT, Ctx, register
from shotlib import start_servers as _start

OUT = ROOT / "docs" / "admin" / "img" / "functions"
shot = register("functions", OUT)
SERVER_ENV = dict(SERVER_ENV)                 # sign-in on: a user and an administrator; marks the guide as one that runs on its own
USER = "vera"
LAKE_KINDS = "trading/trade,core/counterparty,market-risk/frtb-sensitivity"
WIDE, HIGH = 1440, 1000


def start_servers(work) -> list:
    """A small demo lake under the scratch folder, then the scratch server and console reading it (demo connector off, offline)."""
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--connectors-dir", str(work / "connectors"), "--only", LAKE_KINDS, "--no-signal", "-q", "--engine", "auto"],
                       capture_output=True, text=True, cwd=ROOT, timeout=900)
    if r.returncode != 0:
        sys.exit("could not build the demo lake: " + (r.stderr or r.stdout)[-600:])
    return _start(work, {**SERVER_ENV, "DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true"})


def _post(c: Ctx, path: str, body: dict) -> dict:
    r = c.page.request.post(c.base + path, data=json.dumps(body), headers={"Content-Type": "application/json"})
    return r.json() if r.ok else {"status": r.status, "text": r.text()[:300]}


def _png(c: Ctx, name: str, clip: dict | None = None) -> None:
    c.hush()
    OUT.mkdir(parents=True, exist_ok=True)
    c.page.screenshot(path=str(OUT / name), clip=clip)
    print("  wrote", (OUT / name).relative_to(ROOT))


def _setup(c: Ctx) -> None:
    """Vera, a colleague with the viewer role; FRTB off for everyone, with a reason."""
    login(c, width=WIDE, height=HIGH)
    _post(c, "/admin/api/users", {"username": USER, "displayName": "Vera Lim", "roles": ["viewer"], "password": PASSWORD,
                                  "email": "vera@example.com", "mustChangePassword": False})
    _post(c, "/admin/api/commands", {"reason": "FRTB figures under review this quarter", "changes": [{"scope": "command", "name": "FRTB", "on": False}]})


def _open(c: Ctx, path: str) -> None:
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + path)
    c.page.locator("[data-functions]").wait_for(timeout=30000)
    c.page.wait_for_timeout(800)


@shot("functions-user.png")
def as_user(c: Ctx):
    _setup(c)
    login(c, USER, PASSWORD, WIDE, HIGH)
    c.page.locator("#cmdInput").click()
    c.page.keyboard.type("FUNC", delay=25)
    c.page.keyboard.press("Enter")
    c.page.wait_for_url("**/func**")
    c.page.locator("[data-functions]").wait_for(timeout=30000)
    assert c.page.locator("tr[data-command='FRTB']").count() == 0, "a user never sees a command that is off"
    c.page.wait_for_timeout(800)
    _png(c, "functions-user.png")


@shot("command-not-active.png")
def not_active(c: Ctx):
    login(c, USER, PASSWORD, 1280, 720)
    c.page.goto(c.base + "/v/frtb-sensitivity/FRTB-RATES-1")
    c.page.locator("[data-command-off]").wait_for(timeout=30000)
    c.page.wait_for_timeout(500)
    _png(c, "command-not-active.png")


@shot("functions-admin.png")
def as_admin(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    _open(c, "/func?q=market-risk")
    assert c.page.locator("tr[data-command='FRTB'][data-on='false']").count() == 1
    _png(c, "functions-admin.png")


@shot("functions-review.png")
def review(c: Ctx):
    login(c, width=WIDE, height=HIGH)
    _open(c, "/func?q=market-risk")
    c.page.locator("tr[data-command='PNL'] input[data-switch]").uncheck()
    c.page.locator("[data-review]").click()
    c.until("!document.querySelector('[data-side-blocks]').innerText.includes('looking')", 30)
    c.page.locator("[data-reason-input]").fill("quarter-end freeze")
    c.page.wait_for_timeout(500)
    _png(c, "functions-review.png")
    _post(c, "/admin/api/commands", {"reason": "pictures made", "changes": [{"scope": "command", "name": "FRTB", "on": True}]})
