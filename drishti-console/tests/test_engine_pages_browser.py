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

"""Every page the Engines menu held opens from its home (the menu removed, owner 2026-11-09), in a real browser over a real server and
console on the market-risk pack's committed VaR data: each link of the Risk menu opens without a 404 or an error and lays out within the
window; an item to finish puts its words in the command line; Why it moved (VAR BANK EXPLAIN) draws its waterfall across the page.
Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import pytest

from test_explain_browser import stack  # noqa: F401 - the VaR server and console
from test_var_explorer_browser import open_var
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers

_LINKS = """() => { const b = [...document.querySelectorAll('.tbar-menu-btn')].find((x) => x.textContent.trim() === 'Risk');
  const m = b && b.closest('.tbar-menu');
  return m ? [...m.querySelectorAll('a.mega-item')].map((a) => ({ href: a.getAttribute('href'), label: a.textContent.trim().split('\\n')[0] })) : null; }"""
_ERRORS = """() => [...document.querySelectorAll('.alert-danger, .err, [role=alert]:not([hidden])')].filter((e) => e.offsetParent !== null)
  .map((e) => e.textContent.trim()).filter(Boolean)"""


def test_every_risk_page_opens_from_the_menu_without_an_error(stack, browser):
    pg = open_var(browser, str(stack), "/t", {"width": 1400, "height": 950})
    links = pg.evaluate(_LINKS)
    assert links, "no Risk menu in the bar"
    hrefs = [x["href"] for x in links]
    for want in ("/var", "/smat", "/go?q=VAR%20BANK%20EXPLAIN%20%3CGO%3E", "/go?q=PLAY%20VAR%20%3CGO%3E"):
        assert want in hrefs, want
    assert not [m for m in pg.evaluate("[...document.querySelectorAll('.tbar-menu-l')].map((e) => e.textContent)") if m == "Engines"]
    for link in links:
        href = link["href"]
        if href.startswith("/t?cmd="):                                     # an item to finish: its words in the command line
            continue
        resp = pg.goto(str(stack) + href, timeout=120000)
        pg.wait_for_load_state("load")
        assert resp is not None and resp.status < 400, (href, resp and resp.status)
        assert "404" not in pg.title() and "Not found" not in pg.title(), href
        assert pg.evaluate(_ERRORS) == [], (href, pg.evaluate(_ERRORS))
        assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), href   # nothing pushes the page sideways
    pg.close()


def test_why_it_moved_lays_its_parts_across_the_page(stack, browser):
    pg = open_var(browser, str(stack), "/go?q=" + "VAR BANK EXPLAIN <GO>".replace(" ", "+"), {"width": 1400, "height": 950})
    assert "/explain?" in pg.url and "tab=why" in pg.url
    wait(pg, "document.querySelector('[data-explain-page]').dataset.state === 'ready'", 180)
    wait(pg, "!!(document.querySelector('[data-dx-waterfall] drishti-chart') || {}).chart", 180)      # layout A: the waterfall first
    m = pg.evaluate("""() => { const g = document.querySelector('[data-dx-grid]'), r = g.getBoundingClientRect();
      const wf = document.querySelector('[data-dx-why-waterfall]').getBoundingClientRect(), side = document.querySelector('[data-dx-side]').getBoundingClientRect();
      return { grid: r.width, wf: wf.width, side: side.width, page: document.documentElement.clientWidth }; }""")
    assert m["grid"] >= 0.6 * m["page"] and m["wf"] >= 0.5 * m["grid"] and m["wf"] > m["side"], m   # the parts use the page, the waterfall widest
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    assert pg.evaluate(_ERRORS) == []
    pg.close()
