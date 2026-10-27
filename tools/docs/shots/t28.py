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

"""Pictures of tutorial 28, funds and ETFs (docs/tutorials/28-funds-and-etfs.md), written to docs/tutorials/img/t28/, and the funds
pack's guide picture (config/packs/funds/guides/images/funds-fund.jpg). They need a server and console with the public-data packs and
the funds pack on the demo lake (`drishti.py lake demo` builds it; DRISHTI_SHOTS_DELTA_ROOT names another) and sign-in off:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t28

The pictures, in the tutorial's order: the pack's overview, a fund's fact sheet, its look-through tab, its index tab, a search of the
funds, the funds holding a company, the company's Held by funds tab, the funds tracking an index, and the fact sheet on a phone."""
from __future__ import annotations

import os

from _tut import OUT, command, go
from shotlib import ROOT, Ctx, register

shot = register("t28", OUT / "t28")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false", "DRISHTI_DEMO_ENABLED": "false",
              "DRISHTI_DELTA_ROOT": os.environ.get("DRISHTI_SHOTS_DELTA_ROOT", str(ROOT / "data" / "delta")),
              "DRISHTI_PACKS": "core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds"}
GUIDE_IMAGE = ROOT / "config" / "packs" / "funds" / "guides" / "images" / "funds-fund.jpg"


WIDE_TABLE = 2000


def view(c: Ctx, path: str, name: str, height: int = 900, width: int = 1280):
    go(c, path, width=width, height=height, settle=2500)
    c.save(name)


def tab(c: Ctx, title: str):
    c.page.locator("#p-views [role=tab]", has_text=title).first.click()
    c.page.wait_for_timeout(1200)
    c.page.locator("#p-views").scroll_into_view_if_needed()
    # a picture of the whole tab is taller than the window: the fixed bar of function keys at the bottom would sit across the middle of it
    c.page.evaluate("""() => document.querySelectorAll('body *').forEach(e => {
        const s = getComputedStyle(e);
        if ((s.position === 'fixed' || s.position === 'sticky') && e.getBoundingClientRect().top > window.innerHeight / 2) e.style.visibility = 'hidden';
    })""")


@shot("01-pack-overview.jpg")
def overview(c: Ctx):
    go(c, "/p/funds", height=900, wait="main", settle=2500)
    c.save("01-pack-overview.jpg")


@shot("02-fund-fact-sheet.jpg")
def fact_sheet(c: Ctx):
    view(c, "/v/fund/QQQ", "02-fund-fact-sheet.jpg", height=1000)
    GUIDE_IMAGE.parent.mkdir(parents=True, exist_ok=True)
    c.page.screenshot(path=str(GUIDE_IMAGE), type="jpeg", quality=84)
    print("  wrote", GUIDE_IMAGE.relative_to(ROOT))


@shot("03-look-through.jpg")
def look_through(c: Ctx):
    go(c, "/v/fund/QQQ", height=1000, settle=2500)
    tab(c, "Look-through holdings")
    c.save("03-look-through.jpg", locator="#p-views")


@shot("04-versus-index.jpg")
def versus_index(c: Ctx):
    go(c, "/v/fund/QQQ", height=1000, settle=2500)
    tab(c, "vs Nasdaq-100")
    c.save("04-versus-index.jpg", locator="#p-views")


@shot("05-search.jpg")
def search(c: Ctx):
    go(c, "/t", wait="#cmdInput", settle=800)
    command(c, "FUND INDEX_ID=SP500")
    c.page.wait_for_timeout(2500)
    c.save("05-search.jpg")


@shot("06-held-by-funds.jpg")
def held(c: Ctx):
    view(c, "/v/fund-holder/AAPL", "06-held-by-funds.jpg")


@shot("07-useq-tab.jpg")
def useq_tab(c: Ctx):
    go(c, "/v/us-company/AAPL", height=1000, settle=2500)
    c.page.get_by_role("tab", name="Held by funds (a fund opens its page)").first.click()
    c.page.wait_for_timeout(1200)
    c.page.locator("#p-held-by-funds").scroll_into_view_if_needed()
    c.save("07-useq-tab.jpg")


@shot("08-track-index.jpg")
def track(c: Ctx):
    # a wide window: the table has eight columns, and at 1280 px it stopped at Net assets, before the active share the tutorial reads
    view(c, "/v/index-funds/SP500", "08-track-index.jpg", width=WIDE_TABLE)


@shot("09-phone.jpg")
def phone(c: Ctx):
    go(c, "/v/fund/VTI", width=390, height=844, settle=2500)
    c.save("09-phone.jpg")
