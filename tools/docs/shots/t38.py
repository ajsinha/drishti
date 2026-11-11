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

"""Pictures of tutorial 7, Part C, the MR cube on the pivot engine (docs/guides/BI_GUIDE.md), written to
docs/tutorials/img/t38/, in the light theme. Run it on its own: it builds the VaR pictures' scratch lake from the committed test data
(2026-09-30) and starts its own scratch server and console (tools/docs/shots/_var.py):

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t38

The pictures, in the tutorial's order: the landing (desks by risk class), the headline's tiles, the RATES × IR cell's provenance with its
tail and Explain the move, the lens at 10 days and 97.5% on DESK:RATES, RATES opened to its books with CURRENCY across, tabs B and C, and
the view saved as morning and shared with role:risk; then the fields (Step 21): the View picker, the Rows picker, VaR's menu and the panes."""
from __future__ import annotations

import _var
from _tut import OUT, light, page_shot
from shotlib import Ctx, register

SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}    # runs on its own (--guide t38): it starts its own stack
start_servers = _var.start_servers
shot = register("t38", OUT / "t38")
WIDE, HIGH = 1440, 900


def before(c: Ctx, _name: str) -> None:
    light(c)


def _grid(c: Ctx) -> None:
    c.until("document.querySelectorAll('[data-pv-grid] .dg-cell[data-cell]').length > 0", 300)
    c.until("!!document.querySelector('[data-mr-tiles] .mr-tile .mr-tv')", 120)
    c.page.wait_for_timeout(1500)


def _cube(c: Ctx, query: str = "") -> None:
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + "/pivot?preset=mr" + query.replace("?", "&", 1))              # the MR cube: the Pivot's preset mr
    _grid(c)


def _view(c: Ctx, name: str, view: dict) -> str:
    r = c.api("/api/pivot/views", {"name": name, "view": view})
    if "id" not in r:
        raise RuntimeError(f"{name}: {r}")
    return r["id"]


def _union(c: Ctx, *selectors: str) -> dict:
    boxes = c.page.evaluate("""(sels) => sels.map(s => document.querySelector(s)).filter(e => e && e.offsetParent !== null).map(e => {
        const b = e.getBoundingClientRect(); return {x: b.x, y: b.y + window.scrollY, r: b.right, b: b.bottom + window.scrollY}; })""", list(selectors))
    x0, y0 = min(b["x"] for b in boxes), min(b["y"] for b in boxes)
    return {"x": x0, "y": y0, "width": max(b["r"] for b in boxes) - x0, "height": max(b["b"] for b in boxes) - y0}


@shot("01-landing.jpg")
def landing(c: Ctx):
    _cube(c)
    c.save("01-landing.jpg")


@shot("02-diversification.jpg")
def diversification(c: Ctx):
    _cube(c)
    c.save("02-diversification.jpg", clip={"x": 0, "y": 60, "width": WIDE, "height": 260})


@shot("03-cell-provenance.jpg")
def cell(c: Ctx):
    _cube(c)
    rates = c.page.locator("[data-pv-grid] .dg-cell[data-cell*='DESK-RATES']").filter(has_text="m")     # the RATES × IR cell
    (rates if rates.count() else c.page.locator("[data-pv-grid] .dg-cell[data-cell]")).first.click()
    c.until("!!document.querySelector('[data-pv-grid] [data-pv-pop]')", 60)
    c.page.wait_for_timeout(3500)
    page_shot(c, "03-cell-provenance.jpg", _union(c, "[data-pv-grid]", "[data-pv-pop]", "[data-mr-tail]", "[data-mr-explain]"))


@shot("04-lens.jpg")
def lens(c: Ctx):
    vid = _view(c, "lens-10d-975", {"source": "var", "rows": ["MGMT.DESK", "MGMT.BOOK"], "columns": ["RISK_CLASS"], "values": [{"measure": "var()", "as": "VaR"}],
                                    "options": {"scope": "DESK:RATES", "horizon": "10d", "confidence": "0.975"}, "expandTo": {"rows": 2, "columns": 1}})
    _cube(c, "?view=" + vid)
    c.save("04-lens.jpg")


@shot("05-rates-books-currency.jpg")
def books(c: Ctx):
    vid = _view(c, "rates-books-by-currency", {"source": "var", "rows": ["MGMT.DESK", "MGMT.BOOK"], "columns": ["CURRENCY"], "values": [{"measure": "var()", "as": "VaR"}],
                                               "options": {"scope": "BANK"}, "expandTo": {"columns": 1},
                                               "expanded": [{"axis": "rows", "path": [["MGMT.DESK", "DESK-RATES"]], "by": "MGMT.BOOK"}]})
    _cube(c, "?view=" + vid)
    c.save("05-rates-books-currency.jpg")


@shot("06-tab-b.jpg", also=("07-tab-c.jpg",))
def tabs(c: Ctx):
    _cube(c)
    c.page.locator("[data-mr-tab]", has_text="B ·").click()
    c.page.wait_for_timeout(4000)
    c.page.evaluate("() => { const e = document.querySelector('[data-mr-comp]'); e.scrollIntoView({block: 'start'}); window.scrollBy(0, -120); }")
    c.page.wait_for_timeout(600)
    c.save("06-tab-b.jpg")
    c.page.locator("[data-mr-tab]", has_text="C ·").click()
    c.page.wait_for_timeout(4000)
    c.page.evaluate("() => { const e = document.querySelector('[data-mr-ladder]'); e.scrollIntoView({block: 'start'}); window.scrollBy(0, -120); }")
    c.page.wait_for_timeout(600)
    c.save("07-tab-c.jpg")


@shot("08-saved-morning.jpg")
def saved(c: Ctx):
    vid = _view(c, "morning", {"source": "var", "rows": ["MGMT.DESK"], "columns": ["RISK_CLASS"], "values": [{"measure": "var()", "as": "VaR"}],
                               "options": {"scope": "BANK"}})
    c.api(f"/api/pivot/views/{vid}/share", {"with": ["role:risk"]})
    _cube(c, "?view=" + vid)
    c.page.locator("[data-pv-export-open]").click()
    c.page.wait_for_timeout(1200)
    c.save("08-saved-morning.jpg")


@shot("09-view-picker.jpg")
def view_picker(c: Ctx):
    _cube(c)
    c.page.locator("[data-pv-title]").click()
    c.page.keyboard.press("Shift+V")
    c.page.wait_for_selector(".dg-views-pop .di-option")
    c.page.wait_for_timeout(600)
    c.save("09-view-picker.jpg")


@shot("10-rows-picker.jpg")
def rows_picker(c: Ctx):
    _cube(c)
    c.page.locator("[data-pv-title]").click()
    c.page.keyboard.press("r")
    c.page.locator(".di-fp-pop").wait_for()
    c.page.wait_for_timeout(600)
    c.save("10-rows-picker.jpg")


@shot("11-var-menu.jpg")
def var_menu(c: Ctx):
    _cube(c)
    c.page.click(".dg-zone[data-well='values'] .dg-vchip[data-field='m:var'] .dg-chip-menu")
    c.page.wait_for_selector(".dg-menu .dg-menu-note")
    c.page.wait_for_timeout(600)
    c.save("11-var-menu.jpg")


@shot("12-panes.jpg")
def panes(c: Ctx):
    _cube(c)
    c.page.locator("[data-pv-title]").click()
    c.page.keyboard.press("\\")
    c.page.wait_for_selector("[data-pv-grid] .dg-fieldpane")
    c.page.wait_for_timeout(800)
    c.save("12-panes.jpg")
