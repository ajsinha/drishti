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

"""The VaR explorer in a real browser (VAR_ENGINE.md section 10, docs/guides/VAR_EXPLORER.md), over the market-risk pack's committed VaR
data built into a scratch lake (lake demo --only market-risk/<the VaR kinds>) and served by its Delta connector, the demo connector OFF:

- VAR DESK DESK-RATES <GO> opens the explorer on the desk; the bank, a division, the desk, a book, a position and a trade each move every
  panel in place (no page load), drilled from the tree, the breadcrumb and the contributions; a desk is painted within the target and
  the time is printed (-s shows it);
- the three tabs: the P&L path's charts and ranked table, the history with its backtest, the contributions adding up to the VaR, by
  position and by trade;
- an ad-hoc basket: ids pasted into "+ New scope", the VaR live, saved, listed, captured, and the capture reopened with its numbers.

Screenshots go to DRISHTI_VAR_SHOTS when it names a folder. Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import os
import subprocess
import sys
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
VAR_DATA = ",".join(f"market-risk/{k}" for k in ("position", "pnl-vector", "scenario-set", "risk-hierarchy", "var-node", "portfolio"))
SHOTS = os.environ.get("DRISHTI_VAR_SHOTS")
#: the time from a click on a desk to its painted panels (the product owner's target is 300 ms on the demo data); generous here, as the
#: suite runs beside builds: the measured time is printed and kept in the docs
PAINT_BUDGET_MS = int(os.environ.get("DRISHTI_VAR_PAINT_BUDGET_MS", "1500"))


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    work = tmp_path_factory.mktemp("var-lake")
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--connectors-dir", str(work / "connectors"), "--as-of", "data", "--only", VAR_DATA, "--no-signal", "-q",
                        "--engine", "auto"], capture_output=True, text=True, cwd=ROOT, timeout=600)
    if r.returncode != 0 or not (lake / "risk" / "pnl-vector").is_dir():
        pytest.skip("could not build the VaR demo lake: " + (r.stderr or r.stdout)[-400:])
    yield from _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true"})


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
    pg.locator("[data-vx-pathtable] tbody tr").first.click()                                # the worst scenario's contributors
    wait(pg, "document.querySelector('[data-vx-drill]') && !document.querySelector('[data-vx-drill]').hidden"
             " && document.querySelectorAll('[data-vx-drill] tbody tr').length > 0")
    pg.locator('.vx-tab[data-tab="hist"]').click()
    wait(pg, "document.querySelector('[data-var]').dataset.tabPainted === 'hist'")
    assert pg.locator("[data-vx-histtable] tbody tr").count() >= 200                        # the node's official history
    assert "Official history" in pg.locator("[data-vx-hist-note]").inner_text()
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


def test_an_adhoc_basket_is_built_saved_and_captured(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=BOOK:BOOK-RATES-1")
    painted(pg, "BOOK:BOOK-RATES-1")
    pg.locator('.vx-tab[data-tab="contrib"]').click()
    wait(pg, "document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"POS:\"]').length > 1")
    rows = pg.locator('[data-vx-ctable] tbody tr[data-scope^="POS:"]')
    p1, p2 = rows.nth(0).get_attribute("data-scope")[4:], rows.nth(1).get_attribute("data-scope")[4:]
    rows.nth(0).locator("input[type=checkbox]").check()
    pg.locator("[data-vx-add-basket]").click()
    assert not pg.locator("[data-vx-builder]").is_hidden()
    pg.locator("[data-vx-paste]").fill(p2)
    pg.locator("[data-vx-add-ids]").click()
    text = pg.locator("[data-vx-scope-text]").input_value()
    assert p1 in text and p2 in text
    wait(pg, "(document.querySelector('[data-var]').dataset.painted || '').indexOf(%r) >= 0" % p2)
    adhoc_var = pg.evaluate("window.VarExplorer.state.head.var")
    assert pg.evaluate("window.VarExplorer.state.head.kind") == "adhoc"
    assert "ad-hoc" in pg.locator(".vx-crumb").inner_text()
    shot(pg, "explorer-adhoc")
    pg.locator("[data-vx-name]").fill("two-rates")
    pg.locator("[data-vx-save]").click()
    wait(pg, "document.querySelector('[data-var]').dataset.saved && document.querySelector('[data-var]').dataset.painted.indexOf('BASKET:') === 0")
    assert pg.evaluate("window.VarExplorer.state.head.var") == adhoc_var
    wait(pg, "document.querySelectorAll('[data-vx-adhoc] .vx-row[data-scope^=\"BASKET:\"]').length > 0")
    pg.locator('.vx-tab[data-tab="captures"]').click()
    pg.locator("[data-vx-cap-note]").fill("before the unwind")
    pg.locator("[data-vx-capture]").click()
    wait(pg, "document.querySelector('[data-var]').dataset.lastCapture && document.querySelectorAll('[data-vx-captable] tbody tr[data-capture]').length > 0")
    cap = pg.get_attribute("[data-var]", "data-last-capture")
    pg.locator(f'[data-vx-captable] tr[data-capture="{cap}"] button', has_text="Reopen").click()
    wait(pg, "document.querySelector('[data-vx-compare]').dataset.reopened === %r" % cap)
    assert float(pg.get_attribute("[data-vx-compare]", "data-var")) == adhoc_var
    pg.locator(f'[data-vx-captable] tr[data-capture="{cap}"] button', has_text="Compare with now").click()
    wait(pg, "(document.querySelector('[data-vx-compare]').dataset.compared || '').indexOf('now') > 0")
    shot(pg, "explorer-capture")
    # VAR CAPTURE <id> reopens it from the command line
    pg.goto(str(stack) + "/go?q=" + f"VAR CAPTURE {cap} <GO>".replace(" ", "+"))
    wait(pg, "document.querySelector('[data-vx-compare]') && document.querySelector('[data-vx-compare]').dataset.reopened === %r" % cap)
    pg.close()


def test_on_a_phone_the_explorer_stacks_without_scrolling_sideways(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=BANK", {"width": 390, "height": 844})
    painted(pg, "BANK")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    shot(pg, "explorer-phone")
    pg.close()
