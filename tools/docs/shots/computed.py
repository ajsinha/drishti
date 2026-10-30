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

"""Pictures of the computed attributes on the pages (docs/architecture/COMPUTED_ATTRIBUTES.md section 13, docs/guides/EXPOSURE_GUIDE.md
section 22), written to docs/guides/img/computed/, over the packs' samples on the shared scratch server and console:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide computed

The pictures: the netting set's panel computed from the trade valuations beside the stored figures (NSMTM) and its trades past maturity,
the counterparty's computed panel and its netting sets (CPMTM), a scenario's bank P&L and desks (SSPNL), and the Checks panels of a
collateral balance and a credit limit."""
from __future__ import annotations

from shotlib import Ctx, register

shot = register("computed")
WIDE = 1440


def open_page(c: Ctx, path: str, panel: str, height: int = 1800):
    """Opens an entity page and waits until the panel has drawn its rows."""
    c.page.set_viewport_size({"width": WIDE, "height": height})
    c.page.goto(c.base + path)
    c.page.locator(f"#p-{panel}").first.wait_for(state="attached", timeout=60000)
    c.until(f"(() => {{ const p = document.querySelector('#p-{panel}'); return p && /\\d/.test(p.innerText); }})()", 120)
    c.page.wait_for_timeout(1200)


def panels(c: Ctx, *ids: str) -> dict:
    """The clip around the named panels (they sit one under another or side by side), scrolled into the window first."""
    c.page.evaluate(f"() => {{ const p = document.querySelector('#p-{ids[0]}'); window.scrollBy(0, p.getBoundingClientRect().top - 60); }}")
    c.page.wait_for_timeout(400)
    boxes = [c.page.locator(f"#p-{i}").first.bounding_box() for i in ids]
    x0, y0 = min(b["x"] for b in boxes), min(b["y"] for b in boxes)
    x1, y1 = max(b["x"] + b["width"] for b in boxes), max(b["y"] + b["height"] for b in boxes)
    return {"x": max(0, x0 - 10), "y": max(0, y0 - 10), "width": min(x1 - x0 + 20, WIDE), "height": y1 - y0 + 20}


@shot("01-netting-set-computed.jpg")
def netting_set(c: Ctx):
    open_page(c, "/v/netting-set/NS-MERIDIAN-RE-NY", "computed")
    c.save("01-netting-set-computed.jpg", clip=panels(c, "computed"))


@shot("02-netting-set-page.jpg")
def netting_set_page(c: Ctx):
    open_page(c, "/v/netting-set/NS-MERIDIAN-RE-NY", "computed", height=900)
    c.until("(() => { const p = document.querySelector('#p-byProductGroup'); return p && /\\d/.test(p.innerText); })()", 120)
    c.page.wait_for_timeout(800)
    c.page.evaluate("() => window.scrollTo(0, 0)")
    c.page.wait_for_timeout(300)
    c.save("02-netting-set-page.jpg")


@shot("03-counterparty-computed.jpg")
def counterparty(c: Ctx):
    open_page(c, "/v/counterparty/CP-ALDERSHOT", "computed")
    c.save("03-counterparty-computed.jpg", clip=panels(c, "computed"))


@shot("07-counterparty-sets.jpg")
def counterparty_sets(c: Ctx):
    open_page(c, "/v/counterparty/CP-ALDERSHOT", "computedSets")
    c.save("07-counterparty-sets.jpg", clip=panels(c, "computedSets"))


@shot("04-stress-scenario.jpg")
def scenario(c: Ctx):
    open_page(c, "/v/stress-scenario/SCN-COVID2020", "bank")
    c.save("04-stress-scenario.jpg", clip=panels(c, "bank", "desks"))


@shot("05-collateral-checks.jpg")
def collateral(c: Ctx):
    open_page(c, "/v/collateral-balance/COLL-ALDERSHOT-NY", "checks")
    c.save("05-collateral-checks.jpg", clip=panels(c, "checks"))


@shot("06-credit-limit-checks.jpg")
def credit_limit(c: Ctx):
    open_page(c, "/v/credit-limit/LIM-ALDERSHOT", "checks")
    c.save("06-credit-limit-checks.jpg", clip=panels(c, "checks"))
