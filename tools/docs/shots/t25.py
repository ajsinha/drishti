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

"""Pictures of tutorial 25, VaR from the bank to a trade (docs/tutorials/25-var-from-the-bank-to-a-trade.md), written to docs/tutorials/img/t25/,
over the committed VaR test data (see _var.py). Run it on its own (it builds its own lake and starts its own scratch server and console):

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t25

The pictures, in the tutorial's order: the bank, a trade reached by drilling down, a book's positions, a desk's P&L path, a desk's
contributions, an ad-hoc scope, a basket captured and compared, the history with its backtest, a position's page, the provenance
column with its tier, and the computed kind var-result."""
from __future__ import annotations

from _tut import OUT
import subprocess
import sys

from _var import LAKE, open_var, painted, server, start_servers, tab  # noqa: F401 - start_servers is the driver's hook
from shotlib import ROOT
from shotlib import Ctx, register

shot = register("t25", OUT / "t25")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide t25): it starts its own stack


@shot("01-bank.jpg")
def bank(c: Ctx):
    open_var(c, "/go?q=" + "VAR BANK <GO>".replace(" ", "+"))
    painted(c, "BANK")
    c.until("document.querySelectorAll('.vx-tree .vx-row[data-scope]').length > 5")
    c.save("01-bank.jpg")


@shot("02-trade.jpg")
def trade(c: Ctx):
    open_var(c, "/var?scope=BOOK:BOOK-RATES-1")
    painted(c, "BOOK:BOOK-RATES-1")
    tab(c, "contrib")
    c.until("document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"POS:\"]').length > 0")
    pos = c.page.locator('[data-vx-ctable] tbody tr[data-scope^="POS:"]').first.get_attribute("data-scope")
    c.page.locator('[data-vx-ctable] tbody tr[data-scope^="POS:"]').first.click()
    painted(c, pos)
    c.until("document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"TRD:\"]').length > 0")
    trd = c.page.locator('[data-vx-ctable] tbody tr[data-scope^="TRD:"]').first.get_attribute("data-scope")
    c.page.locator('[data-vx-ctable] tbody tr[data-scope^="TRD:"]').first.click()
    painted(c, trd)
    c.save("02-trade.jpg")


@shot("03-book-positions.jpg")
def book_positions(c: Ctx):
    open_var(c, "/var?scope=BOOK:BOOK-EQD-2")
    painted(c, "BOOK:BOOK-EQD-2")
    tab(c, "contrib")
    c.until("document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"POS:\"]').length > 1")
    c.save("03-book-positions.jpg", locator=".vx-main")


@shot("04-path.jpg")
def path(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES")
    painted(c, "DESK:DESK-RATES")
    c.save("04-path.jpg", locator=".vx-main")


@shot("05-contributions.jpg")
def contributions(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES")
    painted(c, "DESK:DESK-RATES")
    tab(c, "contrib")
    c.save("05-contributions.jpg", locator=".vx-main")


@shot("06-basket.jpg")
def basket(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES")
    c.page.evaluate("localStorage.removeItem('drishti.varBasket')")
    c.page.reload()
    painted(c, "DESK:DESK-RATES")
    c.until("document.querySelectorAll('.vx-tree .vx-row[data-scope]').length > 5")
    c.page.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~DESK-RATES"] [data-vx-add]').click(force=True)
    for text in ("pf usd", "-book-rates-1"):
        c.page.locator("[data-vb-find]").fill("")
        c.page.locator("[data-vb-find]").type(text, delay=20)
        c.until("document.querySelector('[data-vb-hints]').dataset.q === %r && document.querySelectorAll('[data-vb-hint]').length > 0" % text, 30)
        c.page.locator("[data-vb-find]").press("Enter")
        c.page.wait_for_timeout(300)
    c.until("document.querySelectorAll('[data-vb-chip]').length === 3")
    c.until("(() => { const l = document.querySelector('[data-vb-live]'); return l && l.dataset.scope === window.VarBasket.compose()"
            " && !l.classList.contains('computing'); })()", 60)
    c.page.locator("[data-vb-open]").click()
    c.until("window.VarExplorer.state.head && window.VarExplorer.state.head.kind === 'adhoc'"
            " && document.querySelector('[data-var]').dataset.painted === window.VarBasket.compose()", 60)
    tab(c, "contrib")
    c.page.wait_for_timeout(800)
    c.save("06-basket.jpg")
    c.page.evaluate("localStorage.removeItem('drishti.varBasket')")


@shot("07-capture.jpg")
def capture(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-FX")
    painted(c, "DESK:DESK-FX")
    c.page.locator('.vx-tab[data-tab="captures"]').click()
    c.page.locator("[data-vx-cap-note]").fill("tutorial 25")
    c.page.locator("[data-vx-capture]").click()
    c.until("document.querySelector('[data-var]').dataset.lastCapture && "
            "document.querySelectorAll('[data-vx-captable] tbody tr[data-capture]').length > 0")
    cap = c.page.get_attribute("[data-var]", "data-last-capture")
    c.page.locator(f'[data-vx-captable] tr[data-capture="{cap}"] button', has_text="Compare with now").click()
    c.until("(document.querySelector('[data-vx-compare]').dataset.compared || '').indexOf('now') > 0")
    c.page.wait_for_timeout(600)
    c.save("07-capture.jpg", locator=".vx-main")


@shot("08-history.jpg")
def history(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-FX")
    painted(c, "DESK:DESK-FX")
    tab(c, "hist")
    c.save("08-history.jpg", locator=".vx-main")


@shot("09-position-page.jpg")
def position(c: Ctx):
    open_var(c, "/go?q=" + "POS POS-BOOK-EQD-2%7ESEC-WARRANT-USD-01 <GO>".replace(" ", "+"))
    c.until("document.querySelectorAll('#p-trades tbody tr').length > 0 && !!document.querySelector('#p-dist')", 90)
    c.until("document.querySelectorAll('#p-trade-var tbody tr').length > 0", 60)
    c.page.wait_for_timeout(800)
    c.save("09-position-page.jpg")


@shot("10-provenance.jpg")
def provenance(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES%20-%20BOOK:BOOK-RATES-1")
    painted(c, "DESK:DESK-RATES - BOOK:BOOK-RATES-1")
    c.save("10-provenance.jpg", locator=".vx-prov")


@shot("11-var-result.jpg")
def var_result(c: Ctx):
    open_var(c, "/go?q=" + "VRES DESK:DESK-RATES <GO>".replace(" ", "+"))
    c.until("document.body.innerText.indexOf('DESK:DESK-RATES') >= 0", 90)
    c.page.wait_for_timeout(2500)
    c.save("11-var-result.jpg")


@shot("12-reporting-currency.jpg")
def reporting_currency(c: Ctx):
    """Step 1's picker: the bank in EUR, then the tab In other currencies (VAR_GUIDE.md section 15.11)."""
    open_var(c, "/var?scope=BANK")
    painted(c, "BANK")
    c.until("document.querySelector('[data-var]').dataset.ccyReady === 'true'", 60)
    c.page.select_option("[data-vx-ccy]", "EUR")
    c.until("document.querySelector('.vx-tile[data-tile=\"0\"] .l').innerText.indexOf('EUR') >= 0", 90)
    painted(c, "BANK")
    tab(c, "ccy")
    c.until("document.querySelectorAll('[data-vx-ccytable] tbody tr[data-ccy]').length >= 5", 60)
    c.page.wait_for_timeout(600)
    c.save("12-reporting-currency.jpg")
    c.page.evaluate("sessionStorage.removeItem('drishti.var.ccy')")


@shot("13-intraday.jpg")
def intraday(c: Ctx):
    """Step 13: the stand-in pricing system's intraday batches (tools/pvgen/intraday.py) appended to the shot lake and signalled; the
    bank's badge. Last: it changes the lake the other pictures read."""
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "pvgen" / "intraday.py"), "--batches", "3", "--trades-per-batch", "10",
                        "--out", str(LAKE[0].parent / "pvgen"), "--lake", str(LAKE[0]), "--server", server()],
                       capture_output=True, text=True, cwd=ROOT, timeout=600)
    if r.returncode:
        raise RuntimeError("intraday.py failed: " + (r.stderr or r.stdout)[-600:])
    open_var(c, "/var?scope=BANK")
    painted(c, "BANK")
    c.until("(document.querySelector('[data-vx-intraday]') || {}).textContent && "
            "document.querySelector('[data-vx-intraday]').textContent.indexOf('intraday') >= 0", 60)
    c.page.locator("[data-vx-intraday]").hover()
    c.page.wait_for_timeout(800)
    c.save("13-intraday.jpg")
