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

"""Pictures of tutorial 26, exposure from the bank to a trade (docs/tutorials/26-exposure-from-the-bank-to-a-trade.md), written to
docs/tutorials/img/t26/, over the demo lake's exposure run (see _exp.py). Run it on its own (it builds its own lake and starts its own
scratch server and console):

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t26

The pictures, in the tutorial's order: the bank, a legal entity's counterparty board, CP-ALDERSHOT's netting sets, NS-ALDERSHOT-LDN's
collateral and trades, a trade alone and in its set, a capture compared with now, a pre-deal what-if by paths and by the addon."""
from __future__ import annotations

from _exp import open_exp, painted, start_servers, tab  # noqa: F401 - start_servers is the driver's hook
from _tut import OUT
from shotlib import Ctx, register

shot = register("t26", OUT / "t26")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide t26): it starts its own stack

SET = "NSET:NS-ALDERSHOT-LDN"


@shot("01-bank.jpg")
def bank(c: Ctx):
    open_exp(c, "/go?q=" + "EXP BANK <GO>".replace(" ", "+"))
    painted(c, "BANK")
    c.until("document.querySelectorAll('.xx-tree .xx-row[data-scope]').length > 3")
    c.save("01-bank.jpg")


@shot("02-legal-entity-board.jpg")
def legal_entity(c: Ctx):
    open_exp(c, "/exposure?scope=LE:LE-LDN")
    painted(c, "LE:LE-LDN")
    c.save("02-legal-entity-board.jpg", locator=".xx-main")


@shot("03-counterparty-sets.jpg")
def counterparty(c: Ctx):
    open_exp(c, "/exposure?scope=CPTY:CP-ALDERSHOT")
    painted(c, "CPTY:CP-ALDERSHOT")
    c.save("03-counterparty-sets.jpg")


@shot("04-collateral.jpg")
def collateral(c: Ctx):
    open_exp(c, "/exposure?scope=" + SET)
    painted(c, SET)
    tab(c, "coll")
    c.save("04-collateral.jpg", locator=".xx-main")


@shot("05-trades.jpg")
def trades(c: Ctx):
    open_exp(c, "/exposure?scope=" + SET)
    painted(c, SET)
    tab(c, "trades")
    c.save("05-trades.jpg", locator=".xx-main")


@shot("06-trade-in-set.jpg")
def trade(c: Ctx):
    open_exp(c, "/exposure?scope=" + SET)
    painted(c, SET)
    tab(c, "trades")
    row = c.page.locator('.xx-pb tbody tr[data-scope^="TRD:"]').first
    scope = row.get_attribute("data-scope")
    row.click()
    painted(c, scope)
    c.save("06-trade-in-set.jpg")


@shot("07-capture-compare.jpg")
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
    c.save("07-capture-compare.jpg", locator=".xx-main")


def _whatif(c: Ctx, method: str, name: str):
    open_exp(c, "/exposure?scope=" + SET)
    painted(c, SET)
    tab(c, "predeal")
    c.page.locator("[data-xx-factor]").fill("-1")
    c.page.locator("[data-xx-method]").select_option(method)
    c.page.locator("[data-xx-whatif]").click()
    c.until("document.querySelector('[data-exposure]').dataset.whatif === %r" % method, 120)
    c.page.wait_for_timeout(600)
    c.save(name, locator=".xx-main")


@shot("08-predeal-paths.jpg")
def predeal_paths(c: Ctx):
    _whatif(c, "paths", "08-predeal-paths.jpg")


@shot("09-predeal-addon.jpg")
def predeal_addon(c: Ctx):
    _whatif(c, "addon", "09-predeal-addon.jpg")
