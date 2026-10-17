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

"""Shared pieces of the tutorials' pictures (docs/tutorials/, modules t01 .. t09 of tools/docs/shots): the server environment every tutorial runs
under (sign-in ON, as GETTING_STARTED.md step 13 sets it up), the people, a 1280 px window, and small helpers.

The tutorials are written for one reader on one machine: they sign in as the development administrator and, where a second person is needed
(sharing, discussion, reports for a colleague), as `vera`. Each picture module makes its own people if they are missing, so any one tutorial's
pictures can be remade on their own:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t03
    (DRISHTI_SHOTS_SERVER_PORT / DRISHTI_SHOTS_CONSOLE_PORT move the scratch ports; --base names the console)"""
from __future__ import annotations

import json
import re
import sys
import time

from shotlib import ROOT, Ctx, out_dir

OUT = ROOT / "docs" / "tutorials" / "img"
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": "tutorial-scratch-secret-0123456789abcdef-xyz",
              "DRISHTI_AUTH_ENABLED": "true", "DRISHTI_SESSION_SECRET": "tutorial-session-secret-0123456789abcdef-xyz", "DRISHTI_SECURE_COOKIE": "false",
              # the server runs a Python Transform in the console's sandboxed runner, started with the Python that makes the pictures (it has pandas and pyarrow)
              "DRISHTI_BI_TRANSFORM_PYTHON": sys.executable, "DRISHTI_BI_TRANSFORM_RUNNER": str(ROOT / "drishti-console" / "core" / "transform_cli.py")}
ADMIN, ADMIN_PASSWORD = "drishti-dev-admin", "drishti-dev-admin123"
VERA, PASSWORD = "vera", "tutorial-pass-2026"
WIDE, HIGH = 1280, 800
_people = {"done": False}


def lake_days(n: int = 7) -> list[str]:
    """The last `n` USNY business days ending on today's business date: the dates `drishti.py lake demo` writes (data/testdata/README.md), so a
    picture of a past date or of Compare uses days the demo lakes hold. DRISHTI_SHOTS_LAKE_END pins the last day (YYYY-MM-DD)."""
    import os
    from datetime import date, datetime
    from zoneinfo import ZoneInfo
    sys.path.insert(0, str(ROOT / "tools"))
    from samplegen.dates import Calendar
    from samplegen.lake import business_days
    end = os.environ.get("DRISHTI_SHOTS_LAKE_END")
    end = date.fromisoformat(end) if end else datetime.now(ZoneInfo("America/New_York")).date()
    return [d.isoformat() for d in business_days(end, n, Calendar.of("USNY"))]


def login(c: Ctx, user: str = ADMIN, password: str | None = None, width: int = WIDE, height: int = HIGH):
    """Signs in as `user` (clearing any other person's session first), on a 1280 px window."""
    try:                                                           # a picture before this one may have left kiosk (full screen) on: a window cannot be resized then
        c.page.evaluate("() => document.fullscreenElement && document.exitFullscreen()")
        c.page.wait_for_timeout(300)
    except Exception:  # noqa: BLE001 - no page yet, or nothing to leave
        pass
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.context.clear_cookies()
    c.page.goto(c.base + "/login")
    c.page.fill("input[name=user]", user)
    c.page.fill("input[name=password]", password or (ADMIN_PASSWORD if user == ADMIN else PASSWORD))
    c.page.locator("form button[type=submit]").first.click()
    c.page.wait_for_url(re.compile(r".*/(t|build.*)$"))


def people(c: Ctx):
    """Vera, a colleague with the viewer role, made once by the development administrator."""
    if _people["done"]:
        return
    login(c)
    c.page.request.post(c.base + "/admin/api/users", data=json.dumps({"username": VERA, "displayName": "Vera Lim", "roles": ["viewer"], "password": PASSWORD,
                                                                      "email": "vera@example.com", "mustChangePassword": False}), headers={"Content-Type": "application/json"})
    c.page.request.post(c.base + f"/admin/api/users/{VERA}/update", data=json.dumps({"displayName": "Vera Lim", "email": "vera@example.com", "roles": ["viewer"], "enabled": True}),
                       headers={"Content-Type": "application/json"})                   # an existing Vera gets her address too
    _people["done"] = True


def go(c: Ctx, path: str, width: int = WIDE, height: int = HIGH, wait: str = "[data-panel]", settle: int = 1500):
    """Opens a page at a window size and waits for `wait` and a moment for charts and live values."""
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + path)
    if wait:
        c.page.locator(wait).first.wait_for(timeout=30000)
    c.page.wait_for_timeout(settle)


def command(c: Ctx, text: str, enter: bool = True):
    """Types into the command line as a person would (the slash key focuses it)."""
    c.page.locator("#cmdInput").click()
    c.page.locator("#cmdInput").fill("")
    c.page.keyboard.type(text, delay=25)
    if enter:
        c.page.keyboard.press("Enter")


def call(c: Ctx, method: str, path: str, body: dict | None = None) -> dict:
    """A JSON call through the signed-in console (it forwards to the server as the person)."""
    r = c.page.request.fetch(c.base + path, method=method, data=json.dumps(body) if body is not None else None, headers={"Content-Type": "application/json"})
    try:
        return r.json()
    except Exception:  # noqa: BLE001 - a call without a body
        return {"status": r.status}


def clip_to(c: Ctx, name: str, selector: str, pad: int = 12, top_bar: bool = False):
    """A picture of one element with a margin, from the top of the window when `top_bar` (so the page chrome shows)."""
    box = c.page.locator(selector).first.bounding_box()
    y = 0 if top_bar else max(0, box["y"] - pad)
    c.save(name, clip={"x": 0, "y": y, "width": c.page.viewport_size["width"], "height": box["y"] + box["height"] + pad - y})


def wait_until(fn, seconds: int = 30):
    end = time.time() + seconds
    while time.time() < end:
        if fn():
            return True
        time.sleep(0.2)
    return False


def page_shot(c: Ctx, name: str, region: dict, extra: int = 0, pad: int = 12):
    """A picture of a region (a bounding box) of the whole page, even where it lies below the window."""
    c.hush()
    d = out_dir(c.guide)
    d.mkdir(parents=True, exist_ok=True)
    c.page.screenshot(path=str(d / name), full_page=True, type="jpeg", quality=84,
                      clip={"x": max(0, region["x"] - pad), "y": max(0, region["y"] - pad), "width": region["width"] + 2 * pad, "height": region["height"] + 2 * pad + extra})
    print("  wrote", (d / name).relative_to(ROOT))


def elem_shot(c: Ctx, name: str, selector: str, pad: int = 10, extra: int = 0):
    """A picture of one element, cut out of the whole page so the sticky top bar never covers it."""
    r = c.page.evaluate("(s) => { const e = document.querySelector(s); const b = e.getBoundingClientRect(); return {x: b.x, y: b.y + window.scrollY, width: b.width, height: b.height}; }", selector)
    page_shot(c, name, r, extra=extra, pad=pad)
