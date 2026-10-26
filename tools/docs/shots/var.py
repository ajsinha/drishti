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

"""Pictures of the VaR guide (docs/guides/VAR_GUIDE.md), written to docs/guides/img/var/, over the committed VaR test data (see _var.py).
Run it on its own (it builds its own lake and starts its own scratch server and console):

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide var

The pictures, in the guide's order: the explorer on a desk (the three columns), a scenario's contributors, the history with its
backtest, a desk's contributions, a book's positions, the new-scope builder with a rule, a capture compared with now, the provenance
column, the portfolio and position pages, the explorer on a phone, and var-result read as an ordinary kind."""
from __future__ import annotations

from _var import WIDE, open_var, painted, start_servers, tab  # noqa: F401 - start_servers is the driver's hook
from shotlib import Ctx, register

shot = register("var")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide var): it starts its own stack


@shot("01-explorer-desk.jpg")
def explorer_desk(c: Ctx):
    open_var(c, "/go?q=" + "VAR DESK DESK-RATES <GO>".replace(" ", "+"))
    painted(c, "DESK:DESK-RATES")
    c.until("document.querySelectorAll('.vx-tree .vx-row[data-scope]').length > 5")
    c.save("01-explorer-desk.jpg")


@shot("02-scenario-contributors.jpg")
def scenario(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES")
    painted(c, "DESK:DESK-RATES")
    c.page.locator("[data-vx-pathtable] tbody tr").first.click()
    c.until("document.querySelector('[data-vx-drill]') && !document.querySelector('[data-vx-drill]').hidden"
            " && document.querySelectorAll('[data-vx-drill] tbody tr').length > 0")
    c.page.wait_for_timeout(500)
    c.save("02-scenario-contributors.jpg", locator=".vx-main")


@shot("03-history.jpg")
def history(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES")
    painted(c, "DESK:DESK-RATES")
    tab(c, "hist")
    c.save("03-history.jpg", locator=".vx-main")


@shot("04-contributions.jpg")
def contributions(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES")
    painted(c, "DESK:DESK-RATES")
    tab(c, "contrib")
    c.save("04-contributions.jpg", locator=".vx-main")


@shot("05-book-positions.jpg")
def book_positions(c: Ctx):
    open_var(c, "/var?scope=BOOK:BOOK-EQD-2")
    painted(c, "BOOK:BOOK-EQD-2")
    tab(c, "contrib")
    c.until("document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"POS:\"]').length > 1")
    c.save("05-book-positions.jpg", locator=".vx-main")


@shot("06-builder-rule.jpg")
def builder(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES")
    painted(c, "DESK:DESK-RATES")
    c.page.locator("[data-vx-new-scope]").click()
    c.page.locator("[data-vx-scope-text]").fill("DESK:DESK-RATES & WHERE:CURRENCY == 'USD' and DIRECTION == 'SHORT'")
    c.page.locator("[data-vx-scope-text]").press("End")
    c.until("(document.querySelector('[data-var]').dataset.painted || '').indexOf('WHERE:') >= 0", 60)
    c.page.wait_for_timeout(800)
    c.save("06-builder-rule.jpg", locator=".vx-main")


@shot("07-capture-compare.jpg")
def capture(c: Ctx):
    open_var(c, "/var?scope=PORT:PF-USD-RATES")
    painted(c, "PORT:PF-USD-RATES")
    c.page.locator('.vx-tab[data-tab="captures"]').click()
    c.page.locator("[data-vx-cap-note]").fill("before the hedge")
    c.page.locator("[data-vx-capture]").click()
    c.until("document.querySelector('[data-var]').dataset.lastCapture && "
            "document.querySelectorAll('[data-vx-captable] tbody tr[data-capture]').length > 0")
    cap = c.page.get_attribute("[data-var]", "data-last-capture")
    c.page.locator(f'[data-vx-captable] tr[data-capture="{cap}"] button', has_text="Compare with now").click()
    c.until("(document.querySelector('[data-vx-compare]').dataset.compared || '').indexOf('now') > 0")
    c.page.wait_for_timeout(600)
    c.save("07-capture-compare.jpg", locator=".vx-main")


@shot("08-provenance.jpg")
def provenance(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES%20-%20BOOK:BOOK-RATES-1")
    painted(c, "DESK:DESK-RATES - BOOK:BOOK-RATES-1")
    c.save("08-provenance.jpg", locator=".vx-prov")


@shot("09-portfolio-page.jpg")
def portfolio(c: Ctx):
    # the lake holds the test data's own date (2026-09-30): the page is opened on it (the explorer finds the newest date by itself)
    open_var(c, "/asof?d=2026-09-30&next=/v/portfolio/PF-RATES-DESK")
    c.until("document.querySelectorAll('#p-positions tbody tr').length > 3", 90)
    c.page.locator("#p-positions tbody tr").first.locator("td").nth(1).click()
    c.until("document.querySelectorAll('#p-position-trades tbody tr').length > 0", 60)
    c.page.wait_for_timeout(800)
    c.save("09-portfolio-page.jpg")
    c.page.goto(c.base + "/asof?d=live&next=/t")


@shot("10-position-page.jpg")
def position(c: Ctx):
    open_var(c, "/go?q=" + "POS POS-BOOK-EQD-2%7ESEC-WARRANT-USD-01 <GO>".replace(" ", "+"))
    c.until("document.querySelectorAll('#p-trades tbody tr').length > 0 && !!document.querySelector('#p-dist')", 90)
    c.until("document.querySelectorAll('#p-trade-var tbody tr').length > 0", 60)
    c.page.wait_for_timeout(800)
    c.save("10-position-page.jpg")


@shot("11-phone.jpg")
def phone(c: Ctx):
    open_var(c, "/var?scope=BANK", 390, 844)
    painted(c, "BANK")
    c.save("11-phone.jpg")
    c.page.set_viewport_size({"width": WIDE, "height": 1000})


@shot("12-var-result-kind.jpg")
def computed_kind(c: Ctx):
    open_var(c, "/v/var-result/DESK:DESK-RATES")
    c.until("document.body.innerText.indexOf('VaR of a scope') >= 0 || document.body.innerText.indexOf('DESK:DESK-RATES') >= 0", 90)
    c.page.wait_for_timeout(2500)
    c.save("12-var-result-kind.jpg")
