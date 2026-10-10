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

"""The pictures of "Filter a table like Excel" (docs/guides/USER_GUIDE.md), written to docs/guides/img/colfilter/: a column's filter menu, a
condition, a filtered table with its funnels, and the menu on a phone (390 px, touch). A view of the QUICKSTART packs, the real console:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide colfilter
"""
from __future__ import annotations

from shotlib import Ctx, register

shot = register("colfilter")
TABLE_VIEW = "/v/trade/MX-20000001"           # a trade's schedule has dates, a leg, a type, a rate and amounts
PANEL = "section.pnl[data-panel]:is([data-kind=table], [data-kind=ladder])"


def open_view(c: Ctx, width: int, height: int):          # a tall window: the panel shows whole, with nothing under the top bar
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + TABLE_VIEW)
    c.page.locator(f"{PANEL} .cf-btn").first.wait_for(state="attached", timeout=30000)
    c.page.wait_for_timeout(1200)


def panel(c: Ctx):
    p = c.page.locator(PANEL).first
    if c.page.viewport_size["width"] < 700:
        p.scroll_into_view_if_needed()
    return p


def column(c: Ctx, name: str):
    return panel(c).locator("th", has_text=name).first.locator(".cf-btn")


def shot_box(c: Ctx, height: int):
    """The panel's top, its pager and as many pixels as the picture needs (the window is tall, so nothing is scrolled)."""
    b = panel(c).bounding_box()
    return {"x": b["x"], "y": b["y"], "width": min(b["width"] + 140, 1440 - b["x"]), "height": min(height, b["height"])}


@shot("01-menu.jpg")
def menu(c: Ctx):
    open_view(c, 1440, 1700)
    column(c, "Type").click()
    c.page.locator(".cf-menu").wait_for()
    c.page.wait_for_timeout(300)
    c.save("01-menu.jpg", clip=shot_box(c, 560))


@shot("02-condition.jpg")
def condition(c: Ctx):
    open_view(c, 1440, 1700)
    column(c, "Amount").click()
    m = c.page.locator(".cf-menu")
    m.locator(".cf-op").select_option(label="Greater than")
    m.locator(".cf-a").fill("1m")
    c.page.wait_for_timeout(200)
    c.save("02-condition.jpg", clip=shot_box(c, 560))


@shot("03-filtered.jpg")
def filtered(c: Ctx):
    open_view(c, 1440, 1700)
    column(c, "Type").click()
    m = c.page.locator(".cf-menu")
    m.locator(".cf-all input").uncheck()
    m.locator(".cf-item", has_text="Fixed").locator("input").check()
    m.get_by_role("button", name="OK").click()
    c.page.wait_for_selector(".cf-menu", state="detached")
    column(c, "Pay date").click()
    m = c.page.locator(".cf-menu")
    m.locator(".cf-op").select_option(label="After")
    m.locator(".cf-a").fill("2026-01-01")
    m.get_by_role("button", name="OK").click()
    c.page.wait_for_selector(".cf-menu", state="detached")
    c.page.mouse.move(1430, 1650)
    c.page.evaluate("document.activeElement.blur()")          # no hint over the first rows
    c.page.wait_for_timeout(300)
    c.save("03-filtered.jpg", clip=shot_box(c, 330))


@shot("04-phone.jpg")
def phone(c: Ctx):
    open_view(c, 390, 844)
    panel(c)
    column(c, "Amount").click()
    c.page.locator(".cf-menu").wait_for()
    c.page.wait_for_timeout(300)
    c.save("04-phone.jpg")
