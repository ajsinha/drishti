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

"""The pictures of "A deep trade: legs, cashflows and fixings" (docs/guides/USER_GUIDE.md), written to docs/guides/img/deep/: a three-leg commodity
swap with the index leg's tab open and one cashflow opened to its daily fixings, a vanilla swap's floating leg opened to its fixings, and the
commodity swap on a phone (390 px, touch). Views of the QUICKSTART packs, the real console:

    DRISHTI_SHOTS_SERVER_PORT=18988 DRISHTI_SHOTS_CONSOLE_PORT=17988 drishti-console/.venv/bin/python tools/docs/screenshots.py --guide deep
"""
from __future__ import annotations

import sys

from shotlib import ROOT, Ctx, register

sys.path.insert(0, str(ROOT / "tools" / "packgen" / "common"))
import packsamples  # noqa: E402  (samples/trade.jsonl)

shot = register("deep")
SAMPLES = ROOT / "config" / "packs" / "trading" / "samples"


def sample(product: str, legs: int) -> str:
    for d in packsamples.read(SAMPLES).get("trade", {}).values():
        if d["PRODUCT_TYPE"] == product and len(d["LEGS"]) == legs:
            return str(d["TRADE_ID"])
    raise AssertionError(f"no {product} sample with {legs} legs")


def open_trade(c: Ctx, trade: str, width: int, height: int):
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(f"{c.base}/v/trade/{trade}")
    c.page.locator("#p-legs .col-box").first.wait_for(state="attached", timeout=30000)      # the legs side by side
    c.page.wait_for_timeout(1500)


def legs_box(c: Ctx, extra: int):
    """The legs panel from its title down, scrolled to just under the top bar."""
    c.page.evaluate("() => { const e = document.querySelector('#p-legs'); window.scrollTo(0, e.getBoundingClientRect().top + window.scrollY - 70); }")
    c.page.wait_for_timeout(400)
    p = c.page.locator("#p-legs").first.bounding_box()
    y = max(0, p["y"] - 8)
    return {"x": max(0, p["x"] - 8), "y": y, "width": min(p["width"] + 16, 1440), "height": min(extra, 1700 - y)}


@shot("01-commodity.jpg")
def commodity(c: Ctx):
    open_trade(c, "1000001", 1440, 1700)
    c.page.locator("#p-legs .col-box").nth(1).locator("button.tr-tog").first.click()   # leg 2: its first cash flow opened to its resets
    c.page.mouse.move(1430, 1650)
    c.page.wait_for_timeout(500)
    c.save("01-commodity.jpg", clip=legs_box(c, 1000))


@shot("02-swap.jpg")
def swap(c: Ctx):
    open_trade(c, "MX-20000001", 1440, 1700)
    c.page.locator("#p-legs .col-box").nth(1).locator("button.tr-tog").first.click()   # leg 2: its first cash flow opened to its resets
    c.page.mouse.move(1430, 1650)
    c.page.wait_for_timeout(500)
    c.save("02-swap.jpg", clip=legs_box(c, 900))


@shot("03-phone.jpg")
def phone(c: Ctx):
    open_trade(c, "1000001", 390, 844)
    c.page.locator("#p-legs .col-box").nth(1).locator("button.tr-tog").first.click()   # leg 2: its first cash flow opened to its resets
    c.page.locator("#p-legs").scroll_into_view_if_needed()
    c.page.wait_for_timeout(600)
    c.save("03-phone.jpg")
