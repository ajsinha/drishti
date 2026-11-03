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

"""Pictures of tutorial 30, FRTB-SA from the hand-built cases to a desk (docs/tutorials/30-frtb-sa-from-the-cases-to-a-desk.md), written to
docs/tutorials/img/t30/. Run it on its own (it builds its own lake and starts its own scratch server and console):

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t30

The pictures, in the tutorial's order: the hand-built cases' capital stack, their three scenarios, the playground comparing a switch, a
recipe replayed, the rates desk's capital and its contributions by book."""
from __future__ import annotations

from _risk import SERVER_ENV, settled, start_servers  # noqa: F401 - SERVER_ENV and start_servers are the driver's hooks
from _tut import OUT
from _var import open_var
from shotlib import Ctx, register

shot = register("t30", OUT / "t30")


def _tab(c: Ctx, name: str) -> None:
    c.page.locator(f'[data-fr-tab="{name}"]').click()
    c.page.wait_for_timeout(1500)


@shot("01-cases.jpg")
def cases(c: Ctx):
    open_var(c, "/frtb?scope=EXAMPLE")
    settled(c, "[data-fr-meta]")
    c.until("document.querySelectorAll('[data-fr-classes] tr').length > 1", 60)
    c.page.locator("[data-fr-classes] tbody tr").first.click()
    c.page.wait_for_timeout(1200)
    c.save("01-cases.jpg")


@shot("02-scenarios.jpg")
def scenarios(c: Ctx):
    open_var(c, "/frtb?scope=EXAMPLE")
    settled(c, "[data-fr-meta]")
    _tab(c, "scenarios")
    c.until("document.querySelectorAll('[data-fr-scen] tr').length > 1", 60)
    c.save("02-scenarios.jpg")


@shot("03-compare.jpg")
def compare(c: Ctx):
    open_var(c, "/play/frtb?scope=EXAMPLE")
    settled(c, "[data-fr-meta]")
    c.until("document.querySelectorAll('[data-fr-compare] option').length > 1", 60)
    c.page.locator("[data-fr-compare]").select_option("girr-correlation=exact")
    c.until("!document.querySelector('[data-fr-diff]').hidden", 120)
    settled(c, "[data-fr-meta]")
    c.until("document.querySelectorAll('[data-fr-ws] tr[data-i]').length > 0", 60)
    c.page.locator("[data-fr-ws] tr[data-i]").first.click()
    c.page.wait_for_timeout(900)
    c.save("03-compare.jpg")


@shot("04-recipe.jpg")
def recipe(c: Ctx):
    open_var(c, "/play/frtb?scope=EXAMPLE")
    settled(c, "[data-fr-meta]")
    c.page.locator("[data-fr-save]").click()
    c.until("(document.querySelector('[data-fr-notice]').textContent || '').indexOf('Saved as recipe') >= 0", 60)
    rid = c.page.evaluate("(document.querySelector('[data-fr-notice]').textContent.match(/recipe (\\S+):/) || [])[1]")
    open_var(c, "/play/frtb?recipe=" + rid)
    c.until("(() => { const n = document.querySelector('[data-fr-notice]'); return n && !n.hidden; })()", 120)
    c.page.wait_for_timeout(900)
    c.save("04-recipe.jpg")


@shot("05-desk.jpg")
def desk(c: Ctx):
    open_var(c, "/frtb?scope=DESK:DESK-RATES&asOf=2026-09-30")
    settled(c, "[data-fr-meta]", 240)
    c.until("document.querySelectorAll('[data-fr-classes] tr').length > 1", 120)
    c.save("05-desk.jpg")


@shot("06-contributions.jpg")
def contributions(c: Ctx):
    open_var(c, "/frtb?scope=DESK:DESK-RATES&asOf=2026-09-30")
    settled(c, "[data-fr-meta]", 240)
    _tab(c, "contrib")
    c.page.locator("[data-fr-by]").select_option("TT_BOOK")
    c.until("document.querySelectorAll('[data-fr-contrib] tbody tr').length > 0", 120)
    c.page.wait_for_timeout(900)
    c.save("06-contributions.jpg")
