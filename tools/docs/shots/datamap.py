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

"""The pictures of "The data map" (docs/guides/USER_GUIDE.md), written to docs/guides/img/datamap/: the Map view opened with `DATAMAP trade`
(the trade selected, its joins lit, the side panel), and the Cards view with the Trades domain open. A view of the QUICKSTART packs, the real
console:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide datamap
"""
from __future__ import annotations

from _tut import command
from shotlib import Ctx, register

shot = register("datamap")
READY = "(() => { const s = document.querySelector('[data-state]'); return s && !s.textContent.trim(); })()"


def settled(c: Ctx):
    c.page.locator("[data-datamap]").wait_for(timeout=30000)
    c.until(READY, seconds=120)                      # the first visit of a day counts and checks the data
    c.page.wait_for_timeout(1200)


@shot("01-map-trade.jpg")
def map_trade(c: Ctx):
    c.page.set_viewport_size({"width": 1440, "height": 900})
    c.page.goto(c.base + "/t")
    c.page.locator("#cmdInput").wait_for(timeout=30000)
    command(c, "DATAMAP trade")
    c.page.wait_for_url("**/datamap**", timeout=30000)
    settled(c)
    c.save("01-map-trade.jpg")


@shot("02-cards.jpg")
def cards(c: Ctx):
    c.page.set_viewport_size({"width": 1440, "height": 900})
    c.page.goto(c.base + "/datamap?view=cards&domain=trades")
    settled(c)
    c.save("02-cards.jpg")
