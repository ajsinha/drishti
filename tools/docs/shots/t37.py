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

"""Pictures of tutorial 37, custom functions, "why did it move" and what-if (docs/tutorials/37-custom-functions-and-explain.md), written
to docs/tutorials/img/t37/, in the light theme. Run it on its own: it builds the VaR pictures' scratch lake with the trades of the three
source systems (as tutorial 33's pictures do) and starts its own scratch server and console:

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t37

The pictures, in the tutorial's order: the trades by product type with the large-notional share, the playground after Check, the
function's column in a view, the waterfall of two product types split by counterparty, and the what-if beside the original."""
from __future__ import annotations

import _var
import os

from _tut import OUT, light, login, page_shot
from shotlib import Ctx, register

_var.VAR_DATA = ",".join([_var.VAR_DATA, "trades/calypso", "trades/endur"])
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}    # runs on its own (--guide t37): it starts its own stack
start_servers = _var.start_servers
shot = register("t37", OUT / "t37")
WIDE, HIGH = 1440, 900
SHARE = "sum(NOTIONAL, where=NOTIONAL > 100000000) / sum(NOTIONAL)"
BY_PRODUCT = {"source": "trade", "rows": ["PRODUCT_TYPE"], "values": [{"measure": "sum(NOTIONAL)", "as": "Notional"}]}


def before(c: Ctx, _name: str) -> None:
    light(c)
    user = os.environ.get("DRISHTI_T37_USER")                   # with sign-in on: the function's author (step 4's approver is someone else)
    if user:
        login(c, user, os.environ.get("DRISHTI_T37_PASSWORD"), width=WIDE, height=HIGH)
        c.page.request.fetch(c.base + "/api/settings", method="PATCH", data='{"theme": "light"}', headers={"Content-Type": "application/json"})


def _open(c: Ctx, name: str, view: dict) -> None:
    r = c.api("/api/pivot/views", {"name": name, "view": view})
    if "id" not in r:
        raise RuntimeError(f"{name}: {r}")
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + "/pivot?view=" + r["id"])
    c.until("document.querySelectorAll('[data-pv-grid] td.pv-c').length > 0", 300)
    c.page.wait_for_timeout(1200)


def _card(c: Ctx, which: str) -> None:
    c.page.locator(f'[data-pf-open="{which}"]').click()
    c.page.locator(f'[data-pf-card="{which}"]').wait_for(state="visible", timeout=30000)
    c.page.wait_for_timeout(500)


def _card_shot(c: Ctx, name: str, which: str) -> None:
    r = c.page.evaluate("(w) => { const e = document.querySelector('[data-pf-card=\"' + w + '\"]'); const b = e.getBoundingClientRect(); "
                        "return {x: b.x, y: b.y + window.scrollY, width: b.width, height: Math.min(b.height, 1400)}; }", which)
    page_shot(c, name, r)


@shot("01-large-share.jpg")
def large_share(c: Ctx):
    _open(c, "large-share", {"source": "trade", "rows": ["PRODUCT_TYPE"], "values": [{"measure": SHARE, "as": "Large share"}]})
    c.save("01-large-share.jpg")


@shot("02-playground.jpg")
def playground(c: Ctx):
    _open(c, "by-product", BY_PRODUCT)
    _card(c, "functions")
    c.page.locator("[data-pf-check]").click()
    c.until("/harness/i.test(document.querySelector('[data-pf-check-result]').innerText)", 60)
    c.page.wait_for_timeout(800)
    _card_shot(c, "02-playground.jpg", "functions")


@shot("03-function-column.jpg")
def function_column(c: Ctx):
    """Step 4: the approved function in a view (DRISHTI_T37_FN, e.g. fn:ravi/wret@1, saved by the author and approved by another person)."""
    fn = os.environ.get("DRISHTI_T37_FN", "fn:you/wret@1")
    _open(c, "wret-by-product", {"source": "trade", "rows": ["PRODUCT_TYPE"], "values": [{"measure": "sum(NOTIONAL)", "as": "Notional"},
                                                                                         {"measure": fn + "(PRICE, NOTIONAL)"}]})
    c.save("03-function-column.jpg")


@shot("04-explain.jpg")
def explain(c: Ctx):
    _open(c, "by-product", BY_PRODUCT)
    _card(c, "explain")
    c.page.fill("[data-pf-from]", "PRODUCT_TYPE=FX_SWAP")
    c.page.fill("[data-pf-to]", "PRODUCT_TYPE=FX_FORWARD")
    c.page.select_option("[data-pf-by]", "COUNTERPARTY")
    c.page.locator("[data-pf-explain]").click()
    c.until("document.querySelector('[data-pf-waterfall]') && document.querySelector('[data-pf-waterfall]').innerHTML.length > 50", 60)
    c.page.wait_for_timeout(1000)
    _card_shot(c, "04-explain.jpg", "explain")


@shot("05-whatif.jpg")
def whatif(c: Ctx):
    _open(c, "by-product", BY_PRODUCT)
    _card(c, "whatif")
    c.page.fill("[data-pf-exclude]", "PRODUCT_TYPE=FX_FORWARD")
    c.page.fill("[data-pf-change]", "NOTIONAL*1.1 where COUNTERPARTY=CP-ALDERSHOT")
    c.page.locator("[data-pf-whatif]").click()
    c.until("document.querySelector('[data-pf-whatif-result]').innerText.trim().length > 20", 60)
    c.page.wait_for_timeout(1000)
    _card_shot(c, "05-whatif.jpg", "whatif")
