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

"""Parametric VaR in a real browser (docs/architecture/PARAMETRIC_VAR.md section 15; VAR_GUIDE.md section 3.12), over the market-risk
pack's committed VaR data built into a scratch lake:

- ``PVAR BANK`` (an alias) opens the explorer with the Parametric toggle on: the three tiles (parametric VaR, parametric ES, the fat-tail
  gap), the tree's Parametric and Gap columns, the normal curve on the P&L path's histogram with the parametric marker and the gap bracket;
- every ? shows the server's lines: the tile's popover carries the answer's own numbers (its VaR in the formula line), no number formed in
  the page; Escape closes it;
- N hides the curve and the choice is remembered in the link (nc=0) and on the next visit;
- VAR BANK is historical with the toggle off; ticking it turns the parametric figures on, unticking turns them off;
- the VaR Playground's Parametric tab shows the worked figures (the components adding up to the parametric VaR);
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
    pg.click("[data-vp-tile='pvar'] .vp-q")                                       # the ? is its Explain: the drawer, the server's lines
    wait(pg, "(() => { const d = document.querySelector('[data-explain-drawer]'); return d && !d.hidden && d.dataset.state === 'ready' && d.dataset.tab === 'formula'; })()", seconds=120)
    pop = pg.inner_text("[data-explain-drawer]")
    assert "not the official VaR" in pop and f"{pvar:,}".replace("-", "−") in pop          # the server's number, as the server wrote it
    assert "never in the browser" in pop
    pg.keyboard.press("Escape")
    assert pg.is_hidden("[data-explain-drawer]")
    wait(pg, "document.querySelector('[data-vx-tree]').classList.contains('vp-cols')")
    assert "Parametric" in pg.inner_text("[data-vx-tree] .vx-row.hdr") and "Gap" in pg.inner_text("[data-vx-tree] .vx-row.hdr")
    # with the two parametric columns the scope names stay readable (they were once squeezed to "…") and a row stays one line high
    sizes = pg.evaluate("""[...document.querySelectorAll('[data-vx-tree] .vx-row:not(.hdr)')].slice(0, 6).map(r => ({
        name: r.firstElementChild.getBoundingClientRect().width, high: r.getBoundingClientRect().height}))""")
    assert sizes and all(s["name"] >= 120 for s in sizes), sizes
    assert all(s["high"] < 34 for s in sizes), sizes
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


def test_the_playground_parametric_tab(browser, stack):
    pg = page(browser, stack, "/play?scope=BANK")
    pg.click("[data-pg-tab-parametric]")
    wait(pg, "document.querySelectorAll('[data-pg-param-positions] tbody tr').length > 0", seconds=90)
    assert "measure=parametric" in pg.url
    assert "Parametric VaR = z·σ − μ" in pg.inner_text("[data-pg-param-figures]")
    assert pg.is_hidden(".pg-body")                                                     # the tab, in place of the grid
    assert not pg.errors, pg.errors


def test_var_is_historical_until_the_toggle_is_ticked(browser, stack):
    pg = page(browser, stack, "/go?q=VAR%20BANK%20%3CGO%3E")
    wait(pg, "document.querySelectorAll('.vx-tile').length >= 6", seconds=60)
    assert pg.locator("[data-vp-tiles]").count() == 0 and not pg.is_checked("[data-vp-on]")
    pg.check("[data-vp-on]")
    wait(pg, "document.querySelectorAll('[data-vp-tiles] .vp-tile').length === 3", seconds=60)
    assert "pm=1" in pg.url
    pg.uncheck("[data-vp-on]")
    wait(pg, "!document.querySelector('[data-vp-tiles]') && document.querySelectorAll('.vx-tile').length >= 6", seconds=60)
    assert "pm=1" not in pg.url
    assert not pg.errors, pg.errors


def test_the_phone_sheet(browser, stack):
    pg = page(browser, stack, "/var?scope=BANK&pm=1", width=390)
    wait(pg, "document.querySelectorAll('[data-vp-tiles] .vp-tile').length === 3", seconds=60)
    pg.click("[data-vp-tile='pgap'] .vp-q")
    wait(pg, "(() => { const d = document.querySelector('[data-explain-drawer]'); return d && !d.hidden && d.dataset.state === 'ready' && d.dataset.tab === 'formula'; })()", seconds=120)
    box = pg.locator("[data-explain-drawer]").bounding_box()                     # a sheet from the bottom
    assert box["width"] >= 389 and box["y"] > 100
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    assert not pg.errors, pg.errors
