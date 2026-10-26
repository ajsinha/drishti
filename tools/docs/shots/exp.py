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

"""Pictures of the exposure guide (docs/guides/EXPOSURE_GUIDE.md section 3, the explorer screen by screen), written to
docs/guides/img/exp/, over the demo lake's exposure run (see _exp.py). Run it on its own (it builds its own lake and starts its own
scratch server and console):

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide exp

The pictures, in the guide's order: the bank with the counterparty board, a netting set's profile with the path fan, its collateral,
a trade in its netting set, the pre-deal what-if, a capture compared with now, the provenance, and the explorer on a phone."""
from __future__ import annotations

from _exp import open_exp, painted, start_servers, tab  # noqa: F401 - start_servers is the driver's hook
from shotlib import Ctx, register

shot = register("exp")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide exp): it starts its own stack

SET = "NSET:NS-ALDERSHOT-LDN"


@shot("01-bank-board.jpg")
def bank(c: Ctx):
    open_exp(c, "/go?q=" + "EXP BANK <GO>".replace(" ", "+"))
    painted(c, "BANK")
    c.until("document.querySelectorAll('.xx-tree .xx-row[data-scope]').length > 3")
    c.save("01-bank-board.jpg")


@shot("02-netting-set-fan.jpg")
def netting_set(c: Ctx):
    open_exp(c, "/exposure?scope=" + SET)
    painted(c, SET)
    c.until("Number(document.querySelector('[data-exposure]').dataset.fan || 0) > 0", 60)
    c.page.wait_for_timeout(600)
    c.save("02-netting-set-fan.jpg", locator=".xx-main")


@shot("03-collateral.jpg")
def collateral(c: Ctx):
    open_exp(c, "/exposure?scope=" + SET)
    painted(c, SET)
    tab(c, "coll")
    c.save("03-collateral.jpg", locator=".xx-main")


@shot("04-trade-in-set.jpg")
def trade(c: Ctx):
    open_exp(c, "/exposure?scope=" + SET)
    painted(c, SET)
    tab(c, "trades")
    row = c.page.locator('.xx-pb tbody tr[data-scope^="TRD:"]').first
    scope = row.get_attribute("data-scope")
    row.click()
    painted(c, scope)
    c.save("04-trade-in-set.jpg")


@shot("05-predeal.jpg")
def predeal(c: Ctx):
    open_exp(c, "/exposure?scope=" + SET)
    painted(c, SET)
    tab(c, "predeal")
    c.page.locator("[data-xx-factor]").fill("-1")
    c.page.locator("[data-xx-whatif]").click()
    c.until("document.querySelector('[data-exposure]').dataset.whatif !== undefined", 120)
    c.page.wait_for_timeout(600)
    c.save("05-predeal.jpg", locator=".xx-main")


@shot("06-capture-compare.jpg")
def capture(c: Ctx):
    open_exp(c, "/exposure?scope=CPTY:CP-ALDERSHOT")
    painted(c, "CPTY:CP-ALDERSHOT")
    tab(c, "captures")
    c.page.locator("[data-xx-cap-note]").fill("before the unwind")
    c.page.locator("[data-xx-capture]").click()
    c.until("document.querySelector('[data-exposure]').dataset.lastCapture && "
            "document.querySelectorAll('.xx-pb tbody tr[data-capture]').length > 0", 90)
    cap = c.page.get_attribute("[data-exposure]", "data-last-capture")
    c.page.locator(f'.xx-pb tr[data-capture="{cap}"] button', has_text="Compare with now").click()
    c.until("(document.querySelector('[data-xx-compare]').dataset.compared || '').indexOf('now') > 0", 60)
    c.page.wait_for_timeout(600)
    c.save("06-capture-compare.jpg", locator=".xx-main")


@shot("07-provenance.jpg")
def provenance(c: Ctx):
    open_exp(c, "/exposure?scope=CPTY:CP-ALDERSHOT")
    painted(c, "CPTY:CP-ALDERSHOT")
    c.page.locator("[data-xx-prov-box] summary").click()
    c.page.wait_for_timeout(400)
    c.save("07-provenance.jpg", locator="[data-xx-prov-box]")


@shot("08-phone.jpg")
def phone(c: Ctx):
    open_exp(c, "/exposure?scope=BANK", 390, 844)
    painted(c, "BANK")
    c.save("08-phone.jpg")
    c.page.set_viewport_size({"width": 1600, "height": 1000})
