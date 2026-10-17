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

"""Pictures of tutorial 8, Share and collaborate (docs/tutorials/08-share-and-collaborate.md), written to docs/tutorials/img/t08/.

You (the development administrator) share a filtered view of the swap MX-20000001 with Vera (a viewer), write in its discussion and mention her; Vera
answers. Two people, one browser at a time: each sign-in clears the other's session."""
from __future__ import annotations

from _tut import OUT, SERVER_ENV, VERA, call, go, lake_days, login, people  # noqa: F401
from shotlib import Ctx, register

shot = register("t08", OUT / "t08")
TRADE = "/v/trade/MX-20000001"
PIN = lake_days(2)[0]                      # the business day before the demo lakes' last (2026-10-15 in the tutorial)


def done(c: Ctx, action):
    n = c.page.evaluate("(window.__drishtiControls || []).length")
    action()
    c.until(f"(window.__drishtiControls || []).length > {n}", 30)
    c.page.wait_for_timeout(400)


def filtered_view(c: Ctx, width: int = 1280, height: int = 900, path: str = TRADE):
    go(c, path, width=width, height=height, wait="[data-ctl]", settle=1500)


@shot("00-users.jpg", also=("01-filtered-view.jpg", "02-share-dialog.jpg", "03-share-sent.jpg"))
def share(c: Ctx):
    people(c)
    login(c, height=900)
    go(c, "/admin/users", height=900, wait="table", settle=800)
    c.save("00-users.jpg")
    login(c, height=1700)
    filtered_view(c, height=1700, path=f"{TRADE}?asOf={PIN}")
    done(c, lambda: c.page.locator("#p-event-list [data-ctl='when'] .ctl-preset").select_option(label="Next 90 days"))
    c.page.locator("#p-event-list [data-ctl='legs'] summary").click()
    done(c, lambda: c.page.locator("#p-event-list [data-ctl='legs'] input[value='2']").uncheck())
    c.page.keyboard.press("Escape")
    c.page.mouse.move(1270, 1650)
    c.page.wait_for_timeout(400)
    box = c.page.locator("#p-event-list").bounding_box()
    c.save("01-filtered-view.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": min(box["y"] + 330, 800)})
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.keyboard.press("Alt+s")
    c.page.locator("[data-share-dialog]:not([hidden])").wait_for()
    c.page.locator("[data-shr-to]").fill("ver")
    c.page.locator(".shr-list [role=option]").first.wait_for()
    c.page.keyboard.press("Enter")
    c.page.locator("[data-shr-note]").fill("Vera, these are leg 1's events for the next 90 days. Can you check the next settlement before 16:00?")
    c.page.wait_for_timeout(600)
    c.save("02-share-dialog.jpg")
    c.page.keyboard.press("Control+Enter")
    c.until("(document.querySelector('[data-shr-result]') || {}).textContent.indexOf('Sent') >= 0", 30)
    c.page.wait_for_timeout(600)
    c.save("03-share-sent.jpg")


@shot("04-bell.jpg", also=("05-inbox.jpg", "06-shared-view.jpg", "07-shared-phone.jpg"))
def recipient(c: Ctx):
    people(c)
    login(c, VERA, height=700)
    go(c, "/t", height=700, wait="[data-bell]", settle=1500)
    c.save("04-bell.jpg", clip={"x": 0, "y": 0, "width": 1280, "height": 120})
    c.page.goto(c.base + "/inbox")
    c.page.locator(".inbox-row").first.wait_for(timeout=30000)
    c.page.set_viewport_size({"width": 1280, "height": 420})
    c.save("05-inbox.jpg")
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.locator("a[data-inbox-open]").first.click()
    c.page.locator("[data-share-banner]").wait_for(timeout=30000)
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1800)
    c.save("06-shared-view.jpg")
    c.page.set_viewport_size({"width": 390, "height": 844})
    c.page.wait_for_timeout(900)
    c.save("07-shared-phone.jpg")


@shot("08-discussion-write.jpg", also=("09-discussion-posted.jpg",))
def write(c: Ctx):
    people(c)
    login(c)
    go(c, f"{TRADE}?asOf={PIN}", wait="[data-panel]", settle=1500)
    c.page.keyboard.press("Alt+n")
    c.page.locator("#aboutDrawer:not([hidden])").wait_for()
    c.until("document.activeElement && document.activeElement.id === 'discBody'", 30)
    c.page.keyboard.type("@ver")
    c.page.locator("#discOpts [role=option]").first.wait_for()
    c.page.keyboard.press("Enter")
    c.page.keyboard.type(" who booked this? the ticket says {")
    c.page.locator("#discOpts [role=option]").first.wait_for()
    c.page.keyboard.type("trader")
    c.page.locator("#discOpts [role=option]").first.wait_for()
    c.page.keyboard.press("Enter")
    c.page.keyboard.type(". Can you confirm with the desk?")
    c.page.wait_for_timeout(500)
    c.save("08-discussion-write.jpg")
    c.page.keyboard.press("Control+Enter")
    c.page.locator(".disc-c").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(900)
    c.save("09-discussion-posted.jpg")


@shot("10-mention-inbox.jpg", also=("11-reply.jpg",))
def reply(c: Ctx):
    people(c)
    login(c, VERA, height=700)
    go(c, "/inbox", height=700, wait=".inbox-row", settle=1200)
    c.save("10-mention-inbox.jpg")
    go(c, f"{TRADE}", height=900, wait="[data-panel]", settle=1500)
    c.page.keyboard.press("Alt+n")
    c.page.locator(".disc-c").first.wait_for(timeout=30000)
    c.until("document.activeElement && document.activeElement.id === 'discBody'", 30)
    c.page.keyboard.type("Yes, that matches the confirmation. Signing off now.")
    c.page.keyboard.press("Control+Enter")
    c.page.wait_for_timeout(1500)
    c.save("11-reply.jpg")
