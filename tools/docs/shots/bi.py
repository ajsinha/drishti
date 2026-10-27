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

"""The pictures of the BI guide (docs/guides/BI_GUIDE.md), written to docs/guides/img/bi/, on a desktop and on a phone (390 px).

They are made on a scratch stack of their own (drishti-console/tests/bi_stack.py: the built server jar with sign-in on, the trading pack, five users
with different BI powers, a shared workspace with published, certified and draft items, two shipped files). The ports are 18983 and 17983, so run:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide bi --base http://127.0.0.1:17983"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "drishti-console" / "tests"))
from shotlib import Ctx, register  # noqa: E402

shot = register("bi")
STACK = {}


def start_servers(work: Path) -> list:
    import bi_stack

    STACK["stack"] = bi_stack.Stack(work).start()
    return list(STACK["stack"].procs.values())


def sign_in(c: Ctx, user: str, password: str = "long-enough-pass-1", to: str = "/bi") -> None:
    c.page.context.clear_cookies()
    c.page.goto(c.base + "/login?next=" + to)
    c.page.fill("#lu", user)
    c.page.fill("#lp", password)
    c.page.click("button[type=submit]")
    c.page.wait_for_url("**" + to.split("?")[0])
    c.page.wait_for_timeout(900)


def desktop(c: Ctx, height: int = 900) -> None:
    c.page.set_viewport_size({"width": 1600, "height": height})


def phone(c: Ctx, user: str, to: str, name: str, full: bool = False) -> None:
    ctx = c.page.context.browser.new_context(viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True, device_scale_factor=2)
    page = ctx.new_page()
    page.goto(c.base + "/login?next=" + to)
    page.fill("#lu", user)
    page.fill("#lp", "long-enough-pass-1")
    page.click("button[type=submit]")
    page.wait_for_url("**" + to.split("?")[0].rstrip("/") + "*")
    page.wait_for_timeout(1200)
    out = c.page
    c.page = page
    try:
        c.save(name) if not full else page.screenshot(path=str(Path(__file__).resolve().parents[3] / "docs" / "guides" / "img" / "bi" / name), type="jpeg", quality=84, full_page=True)
    finally:
        c.page = out
        ctx.close()


@shot("01-home.jpg")
def home(c: Ctx):
    desktop(c)
    sign_in(c, "cat")
    c.page.goto(c.base + "/bi/catalogue")
    c.page.locator(".bi-card h3 a").first.click()                    # opens Desk P&L: it is now Recent
    c.page.wait_for_url("**/bi/i/*")
    c.page.locator("[data-favourite]:not([hidden])").wait_for()
    c.page.wait_for_timeout(800)                                    # the item has loaded and the star is wired
    c.page.click("[data-favourite]")
    c.page.locator('[data-favourite][aria-pressed="true"]').wait_for()
    c.page.goto(c.base + "/bi")
    c.page.locator('[data-sec="favourites"] .bi-card').first.wait_for()
    c.page.wait_for_timeout(500)
    c.save("01-home.jpg")


@shot("02-bi-menu.jpg")
def menu(c: Ctx):
    desktop(c, 560)
    c.page.goto(c.base + "/bi")
    c.page.locator("button:has-text('BI')").first.click()
    c.page.wait_for_timeout(500)
    c.save("02-bi-menu.jpg", clip={"x": 0, "y": 0, "width": 1600, "height": 420})


@shot("03-keyword.jpg")
def keyword(c: Ctx):
    desktop(c, 520)
    c.page.goto(c.base + "/t")
    c.page.fill("#cmdInput", "rupaka ")
    c.page.locator("#cmdList li").first.wait_for()
    c.page.wait_for_timeout(500)
    c.save("03-keyword.jpg", clip={"x": 0, "y": 0, "width": 1600, "height": 420})
    c.page.fill("#cmdInput", "")


@shot("04-catalogue.jpg")
def catalogue(c: Ctx):
    desktop(c, 1000)
    c.page.goto(c.base + "/bi/catalogue")
    c.page.locator(".bi-card").first.wait_for()
    c.page.wait_for_timeout(500)
    c.save("04-catalogue.jpg")


@shot("05-catalogue-filtered.jpg")
def filtered(c: Ctx):
    desktop(c, 760)
    c.page.goto(c.base + "/bi/catalogue?q=daily&kind=report")
    c.page.locator(".bi-card").first.wait_for()
    c.page.wait_for_timeout(600)
    c.save("05-catalogue-filtered.jpg")


@shot("06-workspace.jpg")
def workspace(c: Ctx):
    sign_in(c, "ann")
    desktop(c, 1100)
    c.page.goto(c.base + "/bi/w/rates-desk")
    c.page.locator("[data-items] tbody tr").first.wait_for()         # the Items tab (Members is a tab of its own since the BI runs wave)
    c.page.wait_for_timeout(500)
    c.save("06-workspace.jpg")


@shot("07-item.jpg")
def item(c: Ctx):
    desktop(c, 1100)
    c.page.goto(c.base + "/bi/w/rates-desk")
    c.page.locator('[data-items] a:has-text("Trades dataset")').click()
    c.page.wait_for_url("**/bi/i/*")
    c.page.locator("[data-actions] button").first.wait_for()
    c.page.click('[data-actions] button:text-is("Edit")')
    c.page.wait_for_timeout(500)
    c.save("07-item.jpg")


def head_clip(c: Ctx) -> dict:
    """From just above the item's title, 520 px down (the top bar is taller than it was, so a fixed offset cut the title off)."""
    c.page.evaluate("window.scrollTo(0, 0)")
    y = max(0, c.page.locator("h1[data-title]").bounding_box()["y"] - 24)
    return {"x": 0, "y": y, "width": 1600, "height": 520}


@shot("08-review.jpg", also=("09-approve.jpg",))
def review(c: Ctx):
    desktop(c, 760)
    c.page.goto(c.base + "/bi/w/rates-desk")
    c.page.locator('[data-items] a:has-text("Trades dataset")').click()
    c.page.wait_for_url("**/bi/i/*")
    url = c.page.url
    ws = c.page.request.put(c.base + "/bi/api/workspaces/rates-desk", data='{"reviewRequired": true}', headers={"Content-Type": "application/json"})
    assert ws.ok
    c.page.goto(url)
    c.page.fill(".bi-note", "Ready for review: the trade table and its desk and book relationships")
    c.page.click('[data-actions] button:text-is("Propose for review")')
    c.page.locator("[data-badges]:has-text('proposed')").wait_for()
    c.page.wait_for_timeout(400)
    c.save("08-review.jpg", clip=head_clip(c))
    sign_in(c, "bob", to="/bi")
    c.page.goto(url)
    c.page.locator('[data-actions] button:has-text("Approve")').wait_for()
    c.page.fill(".bi-note", "Checked against the trades pack")
    c.save("09-approve.jpg", clip=head_clip(c))
    c.page.click('[data-actions] button:has-text("Approve")')
    try:                                                            # the sample dataset's body fails today's check, so the approval may be refused
        c.page.locator("[data-meta]:has-text('published v')").wait_for(timeout=10000)
    except Exception:  # noqa: BLE001 - the pictures are made; only the workspace setting below matters
        pass
    c.page.request.put(c.base + "/bi/api/workspaces/rates-desk", data='{"reviewRequired": false}', headers={"Content-Type": "application/json"})


@shot("10-settings.jpg")
def settings(c: Ctx):
    sign_in(c, "drishti-dev-admin", "drishti-dev-admin123", to="/admin/bi")
    desktop(c, 760)
    c.page.goto(c.base + "/admin/bi")
    c.page.locator('[data-roles] tr[data-role="bi-reader"]').wait_for()
    c.page.wait_for_timeout(500)
    c.save("10-settings.jpg")


@shot("11-phone-home.jpg")
def phone_home(c: Ctx):
    phone(c, "cat", "/bi", "11-phone-home.jpg")


@shot("12-phone-catalogue.jpg")
def phone_catalogue(c: Ctx):
    phone(c, "cat", "/bi/catalogue", "12-phone-catalogue.jpg", full=False)


@shot("13-phone-workspace.jpg")
def phone_workspace(c: Ctx):
    phone(c, "ann", "/bi/w/rates-desk", "13-phone-workspace.jpg")
