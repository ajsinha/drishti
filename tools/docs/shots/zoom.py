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

"""The pictures of "Zoom a panel" (docs/guides/USER_GUIDE.md), written to docs/guides/img/zoom/: the button in a panel's heading, a chart and a
table zoomed on a desktop screen, and a zoomed chart on a phone (390 px, touch). A view of the QUICKSTART packs, the real console and scripts:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide zoom
"""
from __future__ import annotations

from shotlib import Ctx, register

shot = register("zoom")
VIEW = "/v/var/VAR-EQD"
TABLE_VIEW = "/v/trade/MX-20000001"           # a trade has tables


def open_view(c: Ctx, width: int, height: int, view: str = VIEW):
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + view)
    c.page.locator("[data-panel] .pnl-zoom-btn").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)


def zoomed(c: Ctx, kinds: str, name: str):
    panel = c.page.locator(f"section.pnl[data-panel]:is({kinds})").first
    panel.scroll_into_view_if_needed()
    panel.locator(".pnl-zoom-btn").click()
    c.page.wait_for_timeout(1200)               # the chart redraws at its new size
    c.save(name)


@shot("01-button.jpg")
def button(c: Ctx):
    open_view(c, 1440, 900)
    panel = c.page.locator("section.pnl[data-panel]:is([data-kind=line], [data-kind=area], [data-kind=hbar])").first
    box = panel.bounding_box()
    c.save("01-button.jpg", clip={"x": box["x"], "y": box["y"], "width": box["width"], "height": min(box["height"], 220)})


@shot("02-chart.jpg")
def chart(c: Ctx):
    open_view(c, 1440, 900)
    zoomed(c, "[data-kind=line], [data-kind=area], [data-kind=hbar]", "02-chart.jpg")


@shot("03-table.jpg")
def table(c: Ctx):
    open_view(c, 1440, 900, TABLE_VIEW)
    zoomed(c, "[data-kind=table], [data-kind=ladder]", "03-table.jpg")


@shot("04-phone.jpg")
def phone(c: Ctx):
    open_view(c, 390, 844)
    zoomed(c, "[data-kind=line], [data-kind=area], [data-kind=hbar]", "04-phone.jpg")
