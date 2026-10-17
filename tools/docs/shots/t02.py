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

"""Pictures of tutorial 2, Look up anything (docs/tutorials/02-look-up-anything.md), written to docs/tutorials/img/t02/."""
from __future__ import annotations

from _tut import OUT, SERVER_ENV, command, go, login, page_shot  # noqa: F401
from shotlib import Ctx, register

shot = register("t02", OUT / "t02")
PAST = "2026-09-29"


def type_only(c: Ctx, text: str):
    c.page.locator("#cmdInput").click()
    c.page.locator("#cmdInput").fill("")
    c.page.keyboard.type(text, delay=30)
    c.page.wait_for_timeout(1300)


@shot("01-mnemonics.jpg", also=("02-entities.jpg", "03-find-by-name.jpg"))
def typeahead(c: Ctx):
    login(c)
    go(c, "/t", wait=".cmd-input")
    type_only(c, "T")
    c.save("01-mnemonics.jpg", clip={"x": 0, "y": 0, "width": 1280, "height": 420})
    type_only(c, "NSET SUMMIT")
    c.save("02-entities.jpg", clip={"x": 0, "y": 0, "width": 1280, "height": 260})
    type_only(c, "CPTY north")
    c.save("03-find-by-name.jpg", clip={"x": 0, "y": 0, "width": 1280, "height": 260})


@shot("04-bare-id.jpg", also=("05-pick-list.jpg",))
def open_and_pick(c: Ctx):
    login(c)
    go(c, "/t", wait=".cmd-input")
    command(c, "TRD MX-200000")
    c.page.wait_for_url("**/s?*")
    c.page.locator("table").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1000)
    c.save("05-pick-list.jpg")
    go(c, "/t", wait=".cmd-input")
    command(c, "cpty north")
    c.page.wait_for_url("**/v/counterparty/**")
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)
    c.save("04-bare-id.jpg")


@shot("06-links.jpg", also=("07-netting-set.jpg",))
def links(c: Ctx):
    login(c)
    go(c, "/v/trade/MX-20000001", wait="[data-panel]", settle=2000)
    c.save("06-links.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 240})
    c.page.keyboard.press("F7")
    c.page.wait_for_timeout(2500)
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.save("07-netting-set.jpg")


@shot("08-past-date.jpg", also=("09-live-toggle.jpg",))
def past_date(c: Ctx):
    login(c)
    go(c, "/v/trade/MX-20000001", wait="[data-panel]", settle=1500)
    c.page.fill("#asofDate", PAST)
    c.page.keyboard.press("Enter")
    c.page.wait_for_load_state()
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(2000)
    c.save("08-past-date.jpg", clip={"x": 0, "y": 0, "width": 1280, "height": 360})
    c.page.locator("[data-asof-live]").click()
    c.page.wait_for_load_state()
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1800)
    c.save("09-live-toggle.jpg", clip={"x": 0, "y": 0, "width": 1280, "height": 360})


@shot("10-compare.jpg", also=("11-over-time.jpg",))
def compare(c: Ctx):
    login(c)
    go(c, "/v/trade/MX-20000001", wait="[data-panel]", settle=1000)
    c.page.fill("#asofDate", "2026-09-30")
    c.page.keyboard.press("Enter")
    c.page.wait_for_load_state()
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)
    c.page.get_by_text("Compare", exact=True).first.click()
    c.page.wait_for_load_state()
    c.page.locator("table").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(1200)
    c.save("10-compare.jpg")
    go(c, "/v/trade/MX-20000001", wait="[data-panel]", settle=1500)
    c.page.locator('.strip dd[data-path="$.mtm"]').first.click()
    c.page.wait_for_timeout(2500)
    c.save("11-over-time.jpg")


@shot("12-public-data.jpg")
def public_data(c: Ctx):
    """A different domain: a country from the world-economy pack (World Bank, UNDP and CIA World Factbook data, published and public)."""
    login(c)
    go(c, "/t", wait=".cmd-input")
    command(c, "JUR JUR-USA")
    c.page.wait_for_url("**/v/jurisdiction/**")
    c.page.locator("[data-panel]").first.wait_for(timeout=30000)
    c.page.wait_for_timeout(2500)
    c.save("12-public-data.jpg")
