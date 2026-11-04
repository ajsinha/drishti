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

"""PVAR in a real browser (docs/architecture/PARAMETRIC_VAR.md section 15; VAR_GUIDE.md section 3.12), over the market-risk pack's committed
VaR data built into a scratch lake:

- ``PVAR BANK`` opens the explorer in parametric mode, view A: the three tiles (parametric VaR, parametric ES, the fat-tail gap), the
  tree's Parametric and Gap columns, the normal curve on the P&L path's histogram with the parametric marker and the gap bracket;
- every ? shows the server's lines: the tile's popover carries the answer's own numbers (its VaR in the formula line), no number formed in
  the page; Escape closes it;
- N hides the curve and the choice is remembered in the link (nc=0) and on the next visit;
- view B draws the tail table, view C the components adding up to the parametric VaR and the correlation heat map;
- VAR BANK stays historical, with the "Compare with parametric" link to PVAR;
- at 390 px the ? is a bottom sheet and nothing scrolls sideways; no page error.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import pytest

from test_var_lens_browser import stack  # noqa: F401 - the market-risk lake and the stack
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers


def page(browser, stack, path, width=1500):
    pg = browser.new_page(viewport={"width": width, "height": 1000})
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(stack + path)
    return pg


def test_pvar_view_a_the_tooltips_and_the_curve(browser, stack):
    pg = page(browser, stack, "/go?q=PVAR%20BANK%20%3CGO%3E")
    wait(pg, "document.querySelectorAll('[data-vp-tiles] .vp-tile').length === 3", seconds=60)
    api = pg.evaluate("fetch('/api/var/scope?scope=BANK').then(r => r.json())")
    pvar = api["parametric"]["var"]
    tile = pg.inner_text("[data-vp-tile='pvar']")
    assert "Parametric VaR" in tile
    pg.click("[data-vp-tile='pvar'] .vp-q")
    pop = pg.inner_text(".vp-pop")
    assert "not the official VaR" in pop and f"{pvar:,}".replace("-", "−") in pop          # the server's number, as the server wrote it
    assert "never in the browser" in pop
    pg.keyboard.press("Escape")
    assert pg.is_hidden(".vp-pop")
    wait(pg, "document.querySelector('[data-vx-tree]').classList.contains('vp-cols')")
    assert "Parametric" in pg.inner_text("[data-vx-tree] .vx-row.hdr") and "Gap" in pg.inner_text("[data-vx-tree] .vx-row.hdr")
    wait(pg, "!!document.querySelector('[data-vx-histo] .vp-curve')", seconds=60)
    assert pg.locator("[data-vx-histo] .vp-mark").count() >= 1 and pg.locator("[data-vx-histo] .vp-gap-label").count() == 1
    pg.keyboard.press("n")
    wait(pg, "!document.querySelector('[data-vx-histo] .vp-curve')")
    assert "nc=0" in pg.url
    pg.reload()
    wait(pg, "document.querySelectorAll('[data-vp-tiles] .vp-tile').length === 3", seconds=60)
    assert not pg.is_checked("[data-vp-curve]")                                            # remembered
    pg.keyboard.press("n")
    assert not pg.errors, pg.errors


def test_views_b_and_c(browser, stack):
    pg = page(browser, stack, "/var?scope=BANK&pm=1&pv=b")
    wait(pg, "document.querySelectorAll(\"[data-vp-pane='b'] .vp-tail-table tbody tr\").length > 0", seconds=60)
    pg.click("[data-vp-view='c']")
    wait(pg, "!!document.querySelector(\"[data-vp-pane='c'] .vp-heat\")", seconds=60)
    total = pg.inner_text("[data-vp-pane='c'] .vp-total")
    head = pg.inner_text("[data-vp-pane='c'] h3")
    assert "exactly" in head
    assert "pv=c" in pg.url
    assert total.split()[1].replace(",", "") in head.replace(",", "")                   # Σ components = the parametric VaR
    assert not pg.errors, pg.errors


def test_var_stays_historical_with_the_link(browser, stack):
    pg = page(browser, stack, "/go?q=VAR%20BANK%20%3CGO%3E")
    wait(pg, "document.querySelectorAll('.vx-tile').length >= 6", seconds=60)
    assert pg.locator("[data-vp-tiles]").count() == 0
    href = pg.get_attribute("[data-vx-pvar]", "href")
    assert "PVAR%20BANK" in href or "PVAR+BANK" in href


def test_the_phone_sheet(browser, stack):
    pg = page(browser, stack, "/var?scope=BANK&pm=1", width=390)
    wait(pg, "document.querySelectorAll('[data-vp-tiles] .vp-tile').length === 3", seconds=60)
    pg.click("[data-vp-tile='pgap'] .vp-q")
    assert "sheet" in pg.get_attribute(".vp-pop", "class")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    assert not pg.errors, pg.errors
