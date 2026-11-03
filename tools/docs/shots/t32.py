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

"""Pictures of tutorial 32, SIMM from the hand-built examples to a netting set (docs/tutorials/32-simm-from-the-examples-to-a-netting-set.md),
written to docs/tutorials/img/t32/, with the toy parameter file of the tests installed (round illustrative numbers, never ISDA's). Run it on
its own (it builds its own lake and starts its own scratch server and console):

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t32

The pictures, in the tutorial's order: the netting sets with the installed version, the hand-built examples, a netting set's breakdown,
the playground comparing a switch, a recipe replayed, and the netting set against the counterparty's call."""
from __future__ import annotations

from _risk import SERVER_ENV, settled, start_servers  # noqa: F401 - SERVER_ENV and start_servers are the driver's hooks
from _tut import OUT
from _var import open_var
from shotlib import Ctx, register

shot = register("t32", OUT / "t32")
NS = "NS-ALDERSHOT-LDN"


def _sets(c: Ctx) -> None:
    settled(c, "[data-sm-meta]", 900)
    c.until("document.querySelectorAll('[data-sm-sets] tr[data-ns]').length > 0", 900)


def _detail(c: Ctx, name: str) -> None:
    c.until("(() => { const d = document.querySelector('[data-sm-detail] h3'); return d && d.textContent.indexOf(%r) >= 0; })()" % name, 240)
    c.page.wait_for_timeout(900)


@shot("01-version.jpg")
def version(c: Ctx):
    open_var(c, "/simm?asOf=2026-09-30")
    _sets(c)
    c.save("01-version.jpg")


@shot("02-examples.jpg")
def examples(c: Ctx):
    open_var(c, "/simm?nettingSet=EXAMPLE")
    settled(c, "[data-sm-meta]", 120)
    _detail(c, "EXAMPLE")
    c.save("02-examples.jpg")


@shot("03-netting-set.jpg")
def netting_set(c: Ctx):
    open_var(c, "/simm?asOf=2026-09-30&nettingSet=" + NS)
    _sets(c)
    _detail(c, NS)
    c.save("03-netting-set.jpg")


@shot("04-compare.jpg")
def compare(c: Ctx):
    open_var(c, "/play/simm?asOf=2026-09-30&nettingSet=" + NS)
    settled(c, "[data-sm-meta]", 240)
    c.until("document.querySelectorAll('[data-sm-compare] option').length > 1", 120)
    c.page.locator("[data-sm-compare]").select_option("hvr=off")
    c.until("!document.querySelector('[data-sm-diff]').hidden", 240)
    settled(c, "[data-sm-meta]", 240)
    c.until("document.querySelectorAll('[data-sm-ws] tr[data-i]').length > 0", 120)
    c.page.locator("[data-sm-ws] tr[data-i]").first.click()
    c.page.wait_for_timeout(900)
    c.save("04-compare.jpg")


@shot("05-recipe.jpg")
def recipe(c: Ctx):
    open_var(c, "/play/simm?asOf=2026-09-30&nettingSet=" + NS)
    settled(c, "[data-sm-meta]", 240)
    c.page.locator("[data-sm-save]").click()
    c.until("(document.querySelector('[data-sm-notice]').textContent || '').indexOf('Saved as recipe') >= 0", 120)
    rid = c.page.evaluate("(document.querySelector('[data-sm-notice]').textContent.match(/recipe (\\S+):/) || [])[1]")
    open_var(c, "/play/simm?recipe=" + rid)
    c.until("(() => { const n = document.querySelector('[data-sm-notice]'); return n && !n.hidden; })()", 240)
    c.page.wait_for_timeout(900)
    c.save("05-recipe.jpg")


@shot("06-against.jpg")
def against(c: Ctx):
    open_var(c, "/simm?asOf=2026-09-30&nettingSet=" + NS)
    _sets(c)
    _detail(c, NS)
    c.page.locator('[data-sm-tab="against"]').click()
    c.until("document.querySelectorAll('[data-sm-against] tr').length > 0", 120)
    c.page.wait_for_timeout(900)
    c.save("06-against.jpg")
