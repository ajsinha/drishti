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

"""The VaR explorer in a real browser (docs/guides/VAR_GUIDE.md section 3), over the market-risk pack's committed VaR
data built into a scratch lake (lake demo --only market-risk/<the VaR kinds>) and served by its Delta connector, the demo connector OFF:

- VAR DESK DESK-RATES <GO> opens the explorer on the desk; the bank, a division, the desk, a book, a position and a trade each move every
  panel in place (no page load), drilled from the tree, the breadcrumb and the contributions; a desk is painted within the target and
  the time is printed (-s shows it);
- the three tabs: the P&L path's charts and ranked table, the history with its backtest (a replay until a node has two official
  end-of-day captures: the server captures the newest date when it starts), the contributions adding up to the VaR, by position and
  by trade;
- the official end-of-day record of the newest date: every node of both hierarchies, captured at start;
- the portfolio and position pages: Drishti's derived positions (the var-positions connector) with the engine's figures computed as
  the page opens;
- "Construct your basket": "+ basket" on a contribution row and on a tree row, hints as you type (arrow keys and Enter), a part taken
  away, the selection's VaR live with the sum of the parts and the diversification, an id that matches nothing flagged at once and said
  in words, saved, the explorer moved to it, its parts by term, reopened in a fresh browser with its parts loaded back into the tray,
  captured, and the capture reopened with its numbers; elsewhere no Basket in the top bar and Alt+B does nothing, the explorer opens
  the tray with the basket kept;
- the pre-deal what-if in the tray: a copy of a booked trade, unwound, before and after by the Python VaR engine (compute on);
- an id that is not a live trade (VAR TRD 10000000) says so, never a VaR of 0 that looks computed;
- a trade page's Risk group (risk-links@1): its P&L vector and its VaR one click away, and why it has no exposure.

Screenshots go to DRISHTI_VAR_SHOTS when it names a folder. Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
VAR_DATA = ",".join([f"market-risk/{k}" for k in ("pnl-vector", "scenario-pv", "fx-scenario", "scenario-set", "risk-hierarchy", "portfolio", "var-limit")] + ["trades/murex"])
SHOTS = os.environ.get("DRISHTI_VAR_SHOTS")
#: the time from a click on a desk to its painted panels (the product owner's target is 300 ms on the demo data); generous here, as the
#: suite runs beside builds: the measured time is printed and kept in the docs
PAINT_BUDGET_MS = int(os.environ.get("DRISHTI_VAR_PAINT_BUDGET_MS", "1500"))


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    work = tmp_path_factory.mktemp("var-lake")
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--connectors-dir", str(work / "connectors"), "--as-of", "today", "--only", VAR_DATA, "--no-signal", "-q",
                        "--engine", "auto"], capture_output=True, text=True, cwd=ROOT, timeout=600)
    if r.returncode != 0 or not (lake / "risk" / "pnl-vector").is_dir():
        pytest.skip("could not build the VaR demo lake: " + (r.stderr or r.stdout)[-400:])
    for st in _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                                        "DRISHTI_COMPUTE_ENABLED": "true", "DRISHTI_PYTHON": sys.executable}):    # the what-if's Python engine
        _engine_ready(str(st))
        yield st


def _engine_ready(base, seconds=120):
    """Waits until the engine answers the bank's VaR: the Delta connector comes up a moment after the server, and until then every VaR
    request answers "no P&L vectors", which a page would draw as its error, not as the figures the tests look for."""
    deadline = time.monotonic() + seconds
    while True:
        try:
            with urllib.request.urlopen(base + "/api/var/scope?scope=BANK", timeout=10) as r:
                if r.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            pass
        if time.monotonic() > deadline:
            pytest.fail(f"the VaR engine did not answer within {seconds} s")
        time.sleep(1)


def shot(pg, name):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        pg.screenshot(path=str(Path(SHOTS) / f"{name}.png"), full_page=True)


def painted(pg, scope):
    wait(pg, "document.querySelector('[data-var]').dataset.painted === %r" % scope)
    return int(pg.get_attribute("[data-var]", "data-paint-ms"))


def open_var(browser, base, path, size=None):
    pg = browser.new_page(viewport=size or {"width": 1600, "height": 1000})
    pg.goto(base + path)
    return pg


def test_the_command_opens_the_desk_and_a_drill_from_bank_to_trade_stays_in_place(stack, browser):
    pg = open_var(browser, str(stack), "/go?q=" + "VAR DESK DESK-RATES <GO>".replace(" ", "+"))
    assert "/var?scope=DESK%3ADESK-RATES" in pg.url or "/var?scope=DESK:DESK-RATES" in pg.url
    painted(pg, "DESK:DESK-RATES")
    assert pg.locator(".vx-tile").count() == 6
    assert pg.locator(".vx-crumb .cur").inner_text().startswith("Rates")
    pg.evaluate("window.__marker = 1")                                       # survives only while the page is not reloaded
    wait(pg, "document.querySelectorAll('.vx-tree .vx-row[data-scope]').length > 5")

    pg.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~BANK"]').click()
    painted(pg, "NODE:MGMT~BANK")
    bank_var = pg.locator('.vx-tile[data-tile="0"] .v').inner_text()
    # a desk the page has not fetched ahead (the bank's children are its divisions): the whole round trip
    pg.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~DESK-INFL"]').click()
    cold = painted(pg, "NODE:MGMT~DESK-INFL")
    pg.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~DIV-MARKETS"]').click()
    painted(pg, "NODE:MGMT~DIV-MARKETS")
    # the desk, timed from the click (its children were fetched ahead while the division was read)
    pg.wait_for_timeout(300)
    ms = []
    for _ in range(3):
        pg.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~DESK-COMM"]').click()
        ms.append(painted(pg, "NODE:MGMT~DESK-COMM"))
        pg.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~DIV-MARKETS"]').click()
        painted(pg, "NODE:MGMT~DIV-MARKETS")
    pg.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~DESK-RATES"]').click()
    first = painted(pg, "NODE:MGMT~DESK-RATES")
    print(f"\nVaR explorer, click to painted for a desk: not fetched ahead {cold} ms; fetched ahead (DESK-RATES) {first} ms; "
          f"again {ms} ms")
    assert min(ms) <= PAINT_BUDGET_MS and first <= PAINT_BUDGET_MS and cold <= PAINT_BUDGET_MS * 2
    assert pg.locator('.vx-tile[data-tile="0"] .v').inner_text() != bank_var
    shot(pg, "explorer-desk-path")

    # contributions: the desk's books add up to its VaR; drill into the first book, its first position, its first trade
    pg.locator('.vx-tab[data-tab="contrib"]').click()
    wait(pg, "document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope]').length > 0")
    book = pg.locator("[data-vx-ctable] tbody tr[data-scope]").first.get_attribute("data-scope")
    assert book.startswith("NODE:MGMT~BOOK-")
    shot(pg, "explorer-desk-contributions")
    pg.locator("[data-vx-ctable] tbody tr[data-scope]").first.click()
    painted(pg, book)
    wait(pg, "document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"POS:\"]').length > 0")
    pos = pg.locator('[data-vx-ctable] tbody tr[data-scope^="POS:"]').first.get_attribute("data-scope")
    assert pg.locator('[data-vx-ctable] tbody tr[data-scope^="POS:"] .vx-chip').first.inner_text() in ("LONG", "SHORT")
    pg.locator('[data-vx-ctable] tbody tr[data-scope^="POS:"]').first.click()
    painted(pg, pos)
    assert "BOOK-" in pg.locator(".vx-crumb").inner_text()
    wait(pg, "document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"TRD:\"]').length > 0")
    trd = pg.locator('[data-vx-ctable] tbody tr[data-scope^="TRD:"]').first.get_attribute("data-scope")
    pg.locator('[data-vx-ctable] tbody tr[data-scope^="TRD:"]').first.click()
    painted(pg, trd)
    shot(pg, "explorer-trade")
    # the breadcrumb goes back up; the browser's Back too; never a page load
    pg.locator(".vx-crumb a").first.click()
    painted(pg, "NODE:MGMT~BANK")
    pg.go_back()
    painted(pg, trd)
    assert pg.evaluate("window.__marker") == 1
    pg.close()


def test_the_limit_letters_tree_a_letter_its_desk_and_a_trade(stack, browser):
    """2.9.5 (docs/guides/VAR_GUIDE.md section 15.14): the selector lists the date's hierarchies by title (the pack's MGMT, LEGAL and LL);
    LL is kept in the link; a letter carries its limit bar; a drill from the letter to its desk node to a position and a trade stays in
    place; LL:<letter> in the scope box is the letter's node."""
    pg = open_var(browser, str(stack), "/var?scope=BANK")
    painted(pg, "BANK")
    wait(pg, "document.querySelectorAll('[data-vx-hierarchy] option').length >= 3")
    titles = pg.locator("[data-vx-hierarchy] option").all_inner_texts()
    assert {"Management", "Legal entities", "Limit letters"} <= set(titles)
    pg.select_option("[data-vx-hierarchy]", "LL")
    wait(pg, "document.querySelectorAll('.vx-tree .vx-row[data-scope^=\"NODE:LL~LL-\"]').length > 3")
    assert "hierarchy=LL" in pg.url
    letter = pg.locator('.vx-tree .vx-row[data-scope^="NODE:LL~LL-"]').first.get_attribute("data-scope")
    assert "/" not in letter
    assert pg.locator('.vx-tree .vx-row[data-scope="%s"] .lim .vx-ubar, .vx-tree .vx-row[data-scope="%s"] .lim svg' % (letter, letter)).count() + \
        pg.locator('.vx-tree .vx-row[data-scope="%s"] .lim *' % letter).count() > 0
    pg.locator('.vx-tree .vx-row[data-scope="%s"]' % letter).click()
    painted(pg, letter)
    shot(pg, "explorer-limit-letter")
    pg.locator('.vx-tab[data-tab="contrib"]').click()
    wait(pg, "document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope]').length > 0")
    desk = pg.locator("[data-vx-ctable] tbody tr[data-scope]").first.get_attribute("data-scope")
    assert desk.startswith(letter + "/DESK-")
    pg.locator("[data-vx-ctable] tbody tr[data-scope]").first.click()
    painted(pg, desk)
    wait(pg, "document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"POS:\"]').length > 0")
    pos = pg.locator('[data-vx-ctable] tbody tr[data-scope^="POS:"]').first.get_attribute("data-scope")
    pg.locator('[data-vx-ctable] tbody tr[data-scope^="POS:"]').first.click()
    painted(pg, pos)
    wait(pg, "document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"TRD:\"]').length > 0")
    trd = pg.locator('[data-vx-ctable] tbody tr[data-scope^="TRD:"]').first.get_attribute("data-scope")
    pg.locator('[data-vx-ctable] tbody tr[data-scope^="TRD:"]').first.click()
    painted(pg, trd)
    pg.close()
    # the scope word: LL:<letter> is the letter's node (its VaR the tree's)
    word = "LL:" + letter.split("~", 1)[1]
    pg = open_var(browser, str(stack), "/var?scope=" + word)
    painted(pg, word)
    assert pg.locator('.vx-tile[data-tile="0"] .v').inner_text()
    pg.close()


def test_the_tabs_path_history_and_contributions(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=DESK:DESK-FX")
    painted(pg, "DESK:DESK-FX")
    assert pg.locator("[data-vx-histo] svg rect.vx-bar").count() > 10
    assert pg.locator("[data-vx-tail] svg rect.vx-bar").count() == 25
    assert pg.locator("[data-vx-series] svg rect.vx-bar").count() == 509
    assert pg.locator("[data-vx-pathtable] tbody tr").count() == 60
    assert pg.locator("[data-vx-pathtable] tbody tr.hl td").first.inner_text() == "6"       # the VaR scenario
    pg.locator("[data-vx-more]").click()
    wait(pg, "document.querySelectorAll('[data-vx-pathtable] tbody tr').length === 120")
    pg.locator("[data-vx-pathtable] tbody tr td").first.click()                             # the worst scenario's contributors
    wait(pg, "document.querySelector('[data-vx-drill]') && !document.querySelector('[data-vx-drill]').hidden"
             " && document.querySelectorAll('[data-vx-drill] tbody tr').length > 0")
    pg.locator('.vx-tab[data-tab="hist"]').click()
    wait(pg, "document.querySelector('[data-var]').dataset.tabPainted === 'hist'")
    assert pg.locator("[data-vx-histtable] tbody tr").count() >= 200                        # today's members over 250 scenario days
    assert "Replay" in pg.locator("[data-vx-hist-note]").inner_text()                       # one official date so far: no official series
    assert pg.locator("[data-vx-hline] svg path.vx-line").count() == 2
    shot(pg, "explorer-history")
    pg.locator('.vx-tab[data-tab="contrib"]').click()
    wait(pg, "document.querySelector('[data-var]').dataset.tabPainted === 'contrib'")
    total = pg.evaluate("Number(document.querySelector('[data-vx-ctable] tr.vx-total td:nth-child(4)').textContent.replace(/,/g, ''))")
    var = pg.evaluate("window.VarExplorer.state.head.var")
    assert abs(total - var) <= len(pg.locator("[data-vx-ctable] tbody tr[data-scope]").all())   # rounded each: within a unit a row
    pg.locator('.vx-segb[data-by="trade"]').click()
    wait(pg, "document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"TRD:\"]').length > 0")
    pg.close()


def tray_live(pg):
    """Waits until the tray shows the engine's answer for what it holds now (its scope text)."""
    wait(pg, "(() => { const l = document.querySelector('[data-vb-live]'); return l && l.dataset.scope === window.VarBasket.compose()"
             " && !l.classList.contains('computing'); })()", 60)


def type_and_pick(pg, text, expect=None):
    """Types into the tray, waits for the engine's hints, picks the first with Enter (after ArrowDown and ArrowUp: the keys work)."""
    pg.locator("[data-vb-find]").fill("")
    pg.locator("[data-vb-find]").type(text, delay=15)
    wait(pg, "document.querySelector('[data-vb-hints]').dataset.q === %r && document.querySelectorAll('[data-vb-hint]').length > 0" % text, 30)
    first = pg.locator("[data-vb-hint]").first
    if expect:
        assert expect in first.inner_text(), first.inner_text()
    pg.locator("[data-vb-find]").press("ArrowDown")
    pg.locator("[data-vb-find]").press("ArrowUp")
    scope = first.get_attribute("data-vb-hint")
    pg.locator("[data-vb-find]").press("Enter")
    wait(pg, "!!document.querySelector('[data-vb-chip=%s]')" % repr(scope).replace("'", '"'))
    return scope


def test_construct_your_basket_hints_live_save_reopen_and_capture(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=BOOK:BOOK-RATES-1")
    pg.evaluate("localStorage.removeItem('drishti.varBasket')")
    pg.reload()
    painted(pg, "BOOK:BOOK-RATES-1")
    assert pg.locator("[data-vx-basket]").is_hidden()                       # a node, an empty tray: no strip
    # "+ basket" on a contribution row: the strip opens with the position in it
    pg.locator('.vx-tab[data-tab="contrib"]').click()
    wait(pg, "document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"POS:\"]').length > 1")
    pos = pg.locator('[data-vx-ctable] tbody tr[data-scope^="POS:"]').first.get_attribute("data-scope")
    pg.locator(f'[data-vx-ctable] tbody tr[data-scope="{pos}"] [data-vx-add]').click()
    wait(pg, "!document.querySelector('[data-vx-basket]').hidden && !!document.querySelector('[data-vb-chip=\"%s\"]')" % pos)
    assert pg.evaluate("document.querySelector('[data-var]').dataset.painted") == "BOOK:BOOK-RATES-1"   # the row's click did not drill
    assert pg.locator("[data-basket-count]").inner_text() == "1"
    assert "Construct your basket" in pg.locator("[data-var-basket]").inner_text()
    assert "unsaved basket" in pg.locator("[data-vb-state]").inner_text()
    # hints: a portfolio by typing, with its type, id, name, trades and standalone VaR
    port = type_and_pick(pg, "pf-usd", expect="PORT")
    assert port == "PORT:PF-USD-RATES"
    hint_text = pg.evaluate("window.VarBasket.state.items[1].trades")
    assert hint_text and hint_text > 100
    # "+ basket" on a tree row (a counterparty by typing would do too): the FX desk
    pg.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~DESK-FX"] [data-vx-add]').click(force=True)
    wait(pg, "!!document.querySelector('[data-vb-chip=\"NODE:MGMT~DESK-FX\"]')")
    # a trade taken away: "-" first
    tray_live(pg)
    trd = pg.evaluate("fetch('/api/var/scope/contributions?scope=PORT:PF-USD-RATES&by=trade').then(r => r.json()).then(d => d.rows[0].scope)")
    type_and_pick(pg, "-" + trd[4:], expect="TRD")
    assert pg.locator(f'[data-vb-chip="{trd}"]').get_attribute("class").find("minus") >= 0
    tray_live(pg)
    live = pg.locator("[data-vb-live]").inner_text()
    assert "VaR 99% 1D of the selection" in live and "sum of the parts" in live and "diversification" in live, live
    sel_var = float(pg.get_attribute("[data-vb-live]", "data-var"))
    assert sel_var > 0
    shot(pg, "basket-tray")
    # an id that matches nothing is flagged at once and said in words
    pg.locator("[data-vb-find]").fill("TRD:10000000")
    wait(pg, "document.querySelector('[data-vb-hints]').dataset.q === 'TRD:10000000' && document.querySelectorAll('[data-vb-hint]').length > 0", 30)
    assert "not a live trade" in pg.locator("[data-vb-hint]").first.inner_text()
    pg.locator("[data-vb-find]").press("Enter")
    wait(pg, "!!document.querySelector('[data-vb-chip=\"TRD:10000000\"][data-flagged]')")
    tray_live(pg)
    assert "TRD:10000000 is not a live trade" in pg.locator("[data-vb-notices]").inner_text()
    pg.locator('[data-vb-chip="TRD:10000000"] [data-vb-del]').click()
    tray_live(pg)
    assert abs(float(pg.get_attribute("[data-vb-live]", "data-var")) - sel_var) < 1
    # open without saving: the explorer on the ad-hoc scope, its parts by term
    pg.locator("[data-vb-open]").click()
    wait(pg, "window.VarExplorer.state.head && window.VarExplorer.state.head.kind === 'adhoc'"
             " && document.querySelector('[data-var]').dataset.painted === window.VarBasket.compose()", 60)
    assert pg.evaluate("window.VarExplorer.state.head.var") == sel_var
    assert "unsaved basket" in pg.locator(".vx-crumb").inner_text()
    assert pg.evaluate("window.VarExplorer.state.head.childKind") == "part"
    # save: a basket, the explorer moves to it; its contributions are its parts
    pg.locator("[data-vb-name]").fill("rates-mix")
    pg.locator("[data-vb-save]").click()
    wait(pg, "document.querySelector('[data-var]').dataset.saved && (document.querySelector('[data-var]').dataset.painted || '').indexOf('BASKET:') === 0", 60)
    basket = pg.get_attribute("[data-var]", "data-saved")
    assert basket.endswith("/rates-mix")
    assert pg.evaluate("window.VarExplorer.state.head.var") == sel_var
    assert "saved basket · " + basket in pg.locator("[data-vb-state]").inner_text()
    wait(pg, "document.querySelectorAll('[data-vx-adhoc] .vx-row[data-scope=\"BASKET:%s\"]').length > 0" % basket)
    pg.locator('.vx-tab[data-tab="contrib"]').click()
    wait(pg, "document.querySelector('[data-var]').dataset.tabPainted === 'contrib' && document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope]').length === 3")
    assert pg.locator(f'[data-vx-ctable] tbody tr[data-scope="{port}"]').count() == 1
    shot(pg, "basket-saved")
    # captured, and the capture reopened with its numbers
    pg.locator('.vx-tab[data-tab="captures"]').click()
    pg.locator("[data-vx-cap-note]").fill("before the unwind")
    pg.locator("[data-vx-capture]").click()
    wait(pg, "document.querySelector('[data-var]').dataset.lastCapture && document.querySelectorAll('[data-vx-captable] tbody tr[data-capture]').length > 0")
    cap = pg.get_attribute("[data-var]", "data-last-capture")
    pg.locator(f'[data-vx-captable] tr[data-capture="{cap}"] button', has_text="Reopen").click()
    wait(pg, "document.querySelector('[data-vx-compare]').dataset.reopened === %r" % cap)
    assert float(pg.get_attribute("[data-vx-compare]", "data-var")) == sel_var
    pg.close()

    # a fresh browser (nothing kept): opening the basket loads its parts back into the tray, for editing
    ctx = browser.new_context(viewport={"width": 1600, "height": 1000})
    pg = ctx.new_page()
    pg.goto(str(stack) + "/go?q=" + f"VAR SCOPE {basket} <GO>".replace(" ", "+"))
    wait(pg, "window.VarExplorer && window.VarExplorer.state.head && window.VarExplorer.state.head.kind === 'basket'", 60)
    wait(pg, "document.querySelectorAll('[data-vb-chip]').length === 4 && !document.querySelector('[data-vx-basket]').hidden")
    assert pg.locator(f'[data-vb-chip="{trd}"]').get_attribute("class").find("minus") >= 0
    assert "saved basket · " + basket in pg.locator("[data-vb-state]").inner_text()
    assert pg.locator("[data-vb-name]").input_value() == "rates-mix"
    # the selection survives a reload
    pg.reload()
    wait(pg, "document.querySelectorAll('[data-vb-chip]').length === 4", 60)
    # anywhere else: no Basket in the top bar and Alt+B does nothing; the explorer opens the tray with the basket kept (this browser)
    pg.goto(str(stack) + "/t")
    wait(pg, "!!document.querySelector('header, nav')")
    assert pg.locator("[data-basket-btn]").count() == 0
    pg.keyboard.press("Alt+b")
    pg.wait_for_timeout(300)
    assert pg.locator("[data-vb-popup]").count() == 0 and pg.locator("[data-vb-chip]").count() == 0
    pg.goto(str(stack) + "/var?scope=BANK")
    wait(pg, "window.VarExplorer && window.VarExplorer.state.head", 60)
    assert pg.locator("[data-vx-basket-open] [data-basket-count]").inner_text() == "4"
    pg.keyboard.press("Alt+b")
    wait(pg, "document.querySelectorAll('[data-vb-chip]').length === 4")
    ctx.close()


def test_the_pre_deal_what_if_in_the_tray(stack, browser):
    """A copy of a booked trade, unwound (factor -1), added to the basket: VaR and ES before and after by the Python VaR engine."""
    pg = open_var(browser, str(stack), "/var?scope=DESK:DESK-FX")
    pg.evaluate("localStorage.removeItem('drishti.varBasket')")
    pg.reload()
    painted(pg, "DESK:DESK-FX")
    trd = pg.evaluate("fetch('/api/var/scope/contributions?scope=DESK:DESK-FX&by=trade').then(r => r.json()).then(d => d.rows[0].key)")
    pg.locator("[data-vx-whatif]").click()                                   # the provenance column's What-if…: the desk into the tray
    wait(pg, "!document.querySelector('[data-vb-whatif]').hidden && document.querySelectorAll('[data-vb-chip]').length === 1")
    pg.locator("[data-vb-whatif-trade]").fill(trd)
    pg.locator("[data-vb-whatif-factor]").fill("-1")
    pg.locator("[data-vb-whatif-run]").click()
    wait(pg, "!!document.querySelector('[data-vb-whatif-out]').dataset.done", 180)
    out = pg.locator("[data-vb-whatif-out]").inner_text()
    assert pg.get_attribute("[data-vb-whatif-out]", "data-done") == "ready", out
    assert all(w in out.lower() for w in ("before", "after", "incremental", "var 99%")), out
    shot(pg, "basket-whatif")
    pg.close()


def test_an_id_that_is_not_a_live_trade_says_so(stack, browser):
    pg = open_var(browser, str(stack), "/go?q=" + "VAR TRD 10000000 <GO>".replace(" ", "+"))
    wait(pg, "document.querySelector('[data-var]') && document.querySelector('[data-var]').dataset.empty === 'true'", 60)
    notes = pg.locator("[data-vx-notices]").inner_text()
    assert "TRD:10000000 is not a live trade on" in notes and "no P&L vector" in notes, notes
    assert pg.locator('.vx-tile[data-tile="0"] .v').inner_text() == "—"
    assert "no trades" in pg.locator('.vx-tile[data-tile="0"] .s').inner_text()
    shot(pg, "explorer-not-live")
    pg.close()


def test_a_trade_page_has_its_risk_links(stack, browser):
    """risk-links@1 on every deal Sutra: the P&L vector and the VaR of a live trade one click away; no exposure run here, said so."""
    trd = urllib.request.urlopen(str(stack) + "/api/var/scope/contributions?scope=DESK:DESK-RATES&by=trade", timeout=60)
    import json
    tid = [r["key"] for r in json.loads(trd.read())["rows"] if r["key"].startswith("MX-")][0]
    pg = open_var(browser, str(stack), "/go?q=" + f"TRD {tid} <GO>".replace(" ", "+"))
    wait(pg, "!!document.querySelector('#p-risk')", 60)
    risk = pg.locator("#p-risk")
    wait(pg, "document.querySelector('#p-risk').innerText.indexOf('HS-509') >= 0 || document.querySelector('#p-risk').innerText.indexOf('not loaded') >= 0", 30)
    if "not loaded" in risk.inner_text():                                     # the date was still loading: open it again
        pg.reload()
        wait(pg, "!!document.querySelector('#p-risk') && document.querySelector('#p-risk').innerText.indexOf('HS-509') >= 0", 60)
    text = risk.inner_text()
    assert f"PNL-{tid}-HS-509" in text and f"TRD:{tid}" in text, text   # 2.9.5: PNL-<trade>-<set>
    assert "no exposure" in text, text
    assert risk.locator(f'a[href*="/v/pnl-vector/PNL-{tid}-HS-509"]').count() == 1
    assert risk.locator('a[href*="/v/var-result/"]').count() == 1
    shot(pg, "trade-risk-links")
    pg.locator(f'#p-risk a[href*="/v/pnl-vector/"]').click()
    wait(pg, "location.pathname.indexOf('/v/pnl-vector/') === 0", 30)
    pg.close()


def test_a_portfolio_page_lists_its_positions_and_opens_one_beside(stack, browser):
    """The portfolio kind's page (the market-risk pack's portfolio Sutra), the demo connector off: PORT <id> opens it; the positions
    table has its rows; a click on a position (not its link) opens its trades beside; POS <id> opens the position's own page."""
    pg = open_var(browser, str(stack), "/go?q=" + "PORT PF-RATES-DESK <GO>".replace(" ", "+"))
    assert "/v/portfolio/PF-RATES-DESK" in pg.url
    wait(pg, "document.querySelectorAll('#p-positions tbody tr').length > 3")
    assert "Rates desk" in pg.content()
    first = pg.evaluate("document.querySelector('#p-positions tbody tr').cells[0].textContent.trim()")
    pg.locator("#p-positions tbody tr").first.locator("td").nth(1).click()
    wait(pg, "document.querySelectorAll('#p-position-trades tbody tr').length > 0")
    shot(pg, "portfolio-page")
    pg.goto(str(stack) + "/go?q=" + f"POS {first} <GO>".replace(" ", "+").replace("~", "%7E"))
    wait(pg, "document.querySelectorAll('#p-trades tbody tr').length > 0 && !!document.querySelector('#p-dist')")
    assert "/v/position/" in pg.url
    wait(pg, "document.querySelectorAll('#p-trade-var tbody tr').length > 0")               # the engine's figures, computed as it opened
    shot(pg, "position-page")
    pg.goto(str(stack) + "/v/portfolio/PF-ASHA-UNWIND")                   # a basket of positions and trades
    wait(pg, "document.querySelectorAll('#p-positions tbody tr').length > 3")
    pg.close()


def test_the_newest_date_is_captured_officially_at_start(stack):
    """Every node of every hierarchy (MGMT and LEGAL from the rows, 88; the limit letters LL from the columns, 43, 2.9.5) captured by the
    server for the newest date (VAR_GUIDE.md section 19)."""
    import json
    deadline = time.monotonic() + 120
    record = None
    while record is None and time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(str(stack) + "/api/var/official", timeout=30) as r:
                record = json.loads(r.read())
        except urllib.error.HTTPError:
            time.sleep(1)                                                   # 404 until the capture at start has written it
    assert record is not None, "no official record within 120 s"
    assert record["resultVersion"] == 1 and len(record["nodes"]) == 88 + 43
    assert record["nodes"]["LL~BANK"]["captureId"].startswith("CAP-")
    assert record["nodes"]["MGMT~DESK-FX"]["captureId"].startswith("CAP-")
    assert {n["hierarchy"] for n in record["nodes"].values()} == {"MGMT", "LEGAL", "LL"}


def test_on_a_phone_the_explorer_stacks_without_scrolling_sideways(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=BANK", {"width": 390, "height": 844})
    painted(pg, "BANK")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    shot(pg, "explorer-phone")
    pg.close()
