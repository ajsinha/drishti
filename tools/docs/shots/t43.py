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

"""Pictures of the VaR tutorial, Part B, parametric VaR beside historical (docs/guides/VAR_GUIDE.md), written to
docs/tutorials/img/t43/. Run it on its own: it builds the VaR pictures' scratch lake from the committed test data (2026-09-30, the
tutorial's date) and starts its own scratch server and console (tools/docs/shots/_var.py):

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t43

The pictures, in the tutorial's order: VAR BANK with Parametric on, the rates desk's ? on the Parametric VaR tile, its P&L histogram with
the fitted normal, the MR cube with var() and pvar() by desk, and the VaR Playground's Parametric tab."""
from __future__ import annotations

import _var
from _tut import OUT, light
from _var import open_var, painted
from shotlib import Ctx, register

SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}    # runs on its own (--guide t43): it starts its own stack
start_servers = _var.start_servers
shot = register("t43", OUT / "t43")


def before(c: Ctx, _name: str) -> None:
    light(c)                                           # the light theme for every picture


def _go(c: Ctx, command: str) -> None:
    open_var(c, "/go?q=" + command.replace(" ", "+"))


@shot("01-bank-parametric.jpg")
def bank(c: Ctx):
    _go(c, "PVAR BANK <GO>")
    painted(c, "BANK")
    c.until("document.querySelectorAll('[data-vp-tiles] .vx-tile, [data-vp-tiles] > *').length >= 3", 90)
    c.page.wait_for_timeout(800)
    c.save("01-bank-parametric.jpg")


@shot("02-rates-describe.jpg")
def describe(c: Ctx):
    _go(c, "PVAR DESK DESK-RATES <GO>")
    painted(c, "DESK:DESK-RATES")
    c.until("document.querySelectorAll('[data-vp-tiles] .vp-q').length > 0", 90)
    c.page.locator("[data-vp-tiles] .vp-q").first.dispatch_event("click")       # a click, without the hover that peeks first
    c.page.wait_for_timeout(900)
    c.save("02-rates-describe.jpg")


@shot("03-rates-normal-curve.jpg")
def curve(c: Ctx):
    _go(c, "PVAR DESK DESK-RATES <GO>")
    painted(c, "DESK:DESK-RATES")
    c.page.wait_for_timeout(1500)
    tabs = c.page.locator("[data-vx-tabs]").first
    tabs.scroll_into_view_if_needed()
    c.page.evaluate("() => { const t = document.querySelector('[data-vx-tabs]'); window.scrollBy(0, t.getBoundingClientRect().top - 80); }")
    c.page.wait_for_timeout(800)
    c.save("03-rates-normal-curve.jpg")


@shot("04-mr-cube-pvar.jpg")
def cube(c: Ctx):
    view = {"source": "var", "rows": ["MGMT.DESK"], "values": [{"measure": "var()", "as": "VaR"}, {"measure": "pvar()", "as": "Parametric VaR"}],
            "options": {"scope": "BANK"}}
    r = c.api("/api/pivot/views", {"name": "var-and-pvar-by-desk", "view": view})
    c.page.set_viewport_size({"width": 1440, "height": 900})
    c.page.goto(c.base + "/pivot?view=" + r["id"])
    c.until("document.querySelectorAll('[data-pv-grid] td.pv-c').length > 0", 300)
    c.page.wait_for_timeout(1500)
    c.save("04-mr-cube-pvar.jpg")


@shot("05-playground-parametric.jpg")
def playground(c: Ctx):
    open_var(c, "/play?scope=DESK%3ADESK-RATES&measure=parametric")        # PLAY VAR DESK DESK-RATES <GO>, the Parametric tab
    c.until("document.querySelector('[data-pg-param-figures]') && /\\d/.test(document.querySelector('[data-pg-param-figures]').innerText)", 180)
    c.page.wait_for_timeout(1200)
    c.save("05-playground-parametric.jpg")
