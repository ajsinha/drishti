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

The pictures, in the guide's order: the explorer on a desk (the tree, the toolbar, the scope), a scenario's contributors, the history with its
backtest, a desk's contributions, a book's positions, Construct your basket (its sheet), a capture compared with now, the provenance
drawer (How calculated), the portfolio and position pages, the explorer on a phone, var-result read as an ordinary kind, and an id that is not a live trade."""
from __future__ import annotations

from _var import WIDE, choose, open_var, painted, start_servers, tab  # noqa: F401 - start_servers is the driver's hook
from shotlib import Ctx, register

shot = register("var")
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}   # marks the guide as one that runs on its own (--guide var): it starts its own stack


@shot("01-explorer-desk.jpg")
def explorer_desk(c: Ctx):
    open_var(c, "/go?q=" + "VAR DESK DESK-RATES <GO>".replace(" ", "+"))
    painted(c, "DESK:DESK-RATES")
    c.until("document.querySelectorAll('.vx-tree .dg-row[data-scope]').length > 5")
    c.save("01-explorer-desk.jpg")


@shot("02-scenario-contributors.jpg")
def scenario(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES")
    painted(c, "DESK:DESK-RATES")
    c.page.locator("[data-vx-pathtable] .dg-row").first.locator(".dg-cell").first.click()   # the rank: the row drills (its loss-from cell opens that part)
    c.until("document.querySelector('[data-vx-drill]') && !document.querySelector('[data-vx-drill]').hidden"
            " && document.querySelectorAll('[data-vx-drill] .dg-row').length > 0")
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
    c.until("document.querySelectorAll('[data-vx-ctable] .dg-row[data-scope^=\"POS:\"]').length > 1")
    c.save("05-book-positions.jpg", locator=".vx-main")


def basket_pick(c: Ctx, text: str) -> None:
    """Types into the basket sheet's Find and add and picks the engine's first hint (it lands in its kind's column)."""
    c.page.locator("[data-vb-find]").fill("")
    c.page.locator("[data-vb-find]").type(text, delay=20)
    c.until("document.querySelector('.vb-hints').dataset.q === %r && document.querySelectorAll('.vb-hint').length > 0" % text, 30)
    n = c.page.evaluate("window.Drishti.VarBasket.parts()")
    c.page.locator("[data-vb-find]").press("Enter")
    c.until("window.Drishti.VarBasket.parts() > %d" % n)


def basket_live(c: Ctx) -> None:
    """Waits until the sheet shows the server's answer for what it holds now."""
    c.until("(() => { const l = document.querySelector('[data-var-basket] .vb-kpis'); return l && l.dataset.sig === window.Drishti.VarBasket.signature()"
            " && !l.classList.contains('computing'); })()", 60)


def basket_paste(c: Ctx, key: str, row: int, text: str) -> None:
    """Pastes Excel's text into the sheet at a cell, as Ctrl+V does."""
    c.page.locator('[data-var-basket] [aria-label^="The basket\'s sheet"]').first.wait_for(state="visible", timeout=30000)
    c.page.evaluate("""(t) => { const dt = new DataTransfer(); dt.setData('text/plain', t);
        document.querySelector('[data-var-basket] [aria-label^="The basket\\'s sheet"]').dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true})); }""",
                    key + "\n" + text)                      # a heading row puts the column under its key


@shot("06-basket.jpg")
def basket(c: Ctx):
    open_var(c, "/var?scope=BOOK:BOOK-RATES-1")
    c.page.evaluate("localStorage.removeItem('drishti.varBasket2'); localStorage.removeItem('drishti.varBasket')")
    c.page.reload()
    painted(c, "BOOK:BOOK-RATES-1")
    tab(c, "contrib")
    c.until("document.querySelectorAll('[data-vx-ctable] .dg-row[data-scope^=\"POS:\"]').length > 1")
    c.page.locator('[data-vx-ctable] .dg-row[data-scope^="POS:"]').first.locator("[data-vx-add]").click()
    basket_pick(c, "pf-usd")
    c.page.locator('.vx-tree .dg-row[data-scope="NODE:MGMT~DESK-FX"] [data-vx-add]').click(force=True)
    trades = c.page.evaluate("fetch('/api/var/scope/contributions?scope=PORT:PF-USD-RATES&by=trade').then(r => r.json()).then(d => d.rows.slice(0, 3).map(r => r.key))")
    basket_paste(c, "TRD", 0, f"{trades[0]}\n{trades[1]}\n-MX-30000121\n10000000\n")
    basket_live(c)
    c.page.wait_for_timeout(600)
    c.save("06-basket.jpg", locator="[data-var-basket]")
    c.page.evaluate("localStorage.removeItem('drishti.varBasket2')")


@shot("13-not-live.jpg")
def not_live(c: Ctx):
    open_var(c, "/go?q=" + "VAR TRD 10000000 <GO>".replace(" ", "+"))
    c.until("document.querySelector('[data-var]') && document.querySelector('[data-var]').dataset.empty === 'true'", 90)
    c.page.wait_for_timeout(600)
    c.save("13-not-live.jpg")


@shot("14-trade-risk.jpg")
def trade_risk(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES")
    painted(c, "DESK:DESK-RATES")                                       # the day's vectors loaded: the trade page's links are set
    tid = c.page.evaluate("fetch('/api/var/scope/contributions?scope=DESK:DESK-RATES&by=trade').then(r => r.json())"
                          ".then(d => d.rows.map(r => r.key).filter(k => k.indexOf('MX-') === 0)[0])")
    open_var(c, f"/asof?d=2026-09-30&next=/v/trade/{tid}")               # the test data's own date, as the portfolio page's picture
    c.until("!!document.querySelector('#p-risk')", 90)
    if "HS-509" not in c.page.locator("#p-risk").inner_text():          # the engine's date was still loading: open it again
        c.page.wait_for_timeout(3000)
        c.page.reload()
        c.until("!!document.querySelector('#p-risk')", 90)
    c.page.wait_for_timeout(800)
    c.save("14-trade-risk.jpg")


@shot("07-capture-compare.jpg")
def capture(c: Ctx):
    open_var(c, "/var?scope=PORT:PF-USD-RATES")
    painted(c, "PORT:PF-USD-RATES")
    c.page.locator('.vx-tab[data-tab="captures"]').click()
    c.page.locator("[data-vx-cap-note]").fill("before the hedge")
    c.page.locator("[data-vx-capture]").click()
    c.until("document.querySelector('[data-var]').dataset.lastCapture && "
            "document.querySelectorAll('[data-vx-captable] .dg-row[data-capture]').length > 0")
    cap = c.page.get_attribute("[data-var]", "data-last-capture")
    c.page.locator(f'[data-vx-captable] .dg-row[data-capture="{cap}"] button', has_text="Compare with now").click()
    c.until("(document.querySelector('[data-vx-compare]').dataset.compared || '').indexOf('now') > 0")
    c.page.wait_for_timeout(600)
    c.save("07-capture-compare.jpg", locator=".vx-main")


@shot("08-provenance.jpg")
def provenance(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES%20-%20BOOK:BOOK-RATES-1")
    painted(c, "DESK:DESK-RATES - BOOK:BOOK-RATES-1")
    c.page.set_viewport_size({"width": WIDE, "height": 1900})          # the drawer is the window's height: tall enough for every line
    c.page.locator("[data-vx-explain]").click()                        # how a figure was calculated is the Explain drawer's
    c.until("document.querySelector('.dx-drawer [data-dx-body]') && document.querySelector('.dx-drawer [data-dx-body]').innerText.trim().length > 50", 60)
    c.page.wait_for_timeout(300)
    c.save("08-provenance.jpg", locator=".dx-drawer")
    c.page.locator("[data-dx-close]").click()
    c.page.set_viewport_size({"width": WIDE, "height": 1000})


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


def in_currency(c: Ctx, scope: str, ccy: str) -> None:
    """The explorer on a scope in a reporting currency (VAR_GUIDE.md section 15.11), through the header's picker."""
    open_var(c, "/var?scope=" + scope)
    painted(c, scope)
    c.until("document.querySelector('[data-var]').dataset.ccyReady === 'true'", 60)
    choose(c, "[data-vx-ccy]", ccy)
    c.until("document.querySelector('.vx-tile[data-tile=\"0\"] .l').innerText.indexOf(%r) >= 0" % ccy, 90)
    painted(c, scope)
    c.until("document.querySelector('.vx-tree .dg-row.hdr') && document.querySelector('.vx-tree .dg-row.hdr').innerText.indexOf(%r) >= 0" % ccy, 90)


def back_to_usd(c: Ctx) -> None:
    """The session's currency back to USD, so the pictures after this one are in USD."""
    c.page.evaluate("sessionStorage.removeItem('drishti.var.ccy')")


@shot("15-reporting-currency.jpg")
def reporting_currency(c: Ctx):
    in_currency(c, "DESK:DESK-RATES", "EUR")
    c.page.wait_for_timeout(600)
    c.save("15-reporting-currency.jpg")
    back_to_usd(c)


@shot("16-other-currencies.jpg")
def other_currencies(c: Ctx):
    open_var(c, "/var?scope=DESK:DESK-RATES&ccy=USD")
    painted(c, "DESK:DESK-RATES")
    tab(c, "ccy")
    c.until("document.querySelectorAll('[data-vx-ccytable] .dg-row[data-ccy]').length >= 5", 60)
    c.save("16-other-currencies.jpg", locator=".vx-main")
    back_to_usd(c)


@shot("17-baskets.jpg")
def baskets(c: Ctx):
    """The toolbar's System baskets open (key B) on the bank, a search typed: the bank's portfolios, each with its actions."""
    open_var(c, "/var?scope=BANK")
    painted(c, "BANK")
    c.until("document.querySelectorAll('[data-vx-baskets-system] [role=option]').length > 0", 60)
    c.page.locator("body").click(position={"x": 5, "y": 400})
    c.page.keyboard.press("b")
    c.page.locator('[data-vx-pick="system"] [data-vx-pick-q]').type("rates")
    c.page.keyboard.press("ArrowDown")
    c.page.wait_for_timeout(400)
    c.save("17-baskets.jpg")
    c.page.keyboard.press("Escape")
