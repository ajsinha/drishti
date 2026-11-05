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

"""Why it moved (2.9.10, docs/guides/VAR_GUIDE.md section 3.11) in a real browser, over the market-risk pack's committed VaR data built
into a scratch lake (its three business dates, moved to end today: the tab compares the newest with the date before it), the demo
connector off:

- ``VAR EXPLAIN BANK`` opens the VaR page on the tab; the waterfall, the steps, the drivers, the states and the order-free view are drawn by
  the renderer with the standard panel kinds; the steps add up to the change; a step's row opens its drivers;
- the view switch (Waterfall, Matrix, Sentences) redraws from the same answer and is kept in the address; the export link names the scope;
- at 1280 px nothing scrolls sideways; at 390 px (a phone) the answer and the steps as bars are drawn, nothing scrolls sideways;
- F1 on the tab opens the guide's section.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import json
import urllib.request
from pathlib import Path

import pytest

from test_var_explorer_browser import open_var, shot
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from var_stack import VAR_TRADES, var_data, var_lake, var_server

@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    import os
    keep = os.environ.get("DRISHTI_TEST_EXPLAIN_WORK")           # a lake built before, reused (debugging: the build takes minutes)
    if keep and (Path(keep) / "delta" / "risk" / "pnl-vector").is_dir():
        lake = Path(keep) / "delta"
    else:
        lake = var_lake(tmp_path_factory, var_data(*VAR_TRADES))   # the committed three dates, ending today (the last weekday)
    yield from var_server(tmp_path_factory, lake)


def explained(pg, view: str):
    pane = "document.querySelector('[data-pane=\"why\"]')"
    wait(pg, "%s.dataset.painted === %r || %s.dataset.state === 'failed'" % (pane, view, pane), 180)
    state = pg.evaluate("[%s.dataset.state, %s.dataset.painted, %s.querySelector('[data-vw-msg]').textContent]" % (pane, pane, pane))
    assert state[1] == view, "the tab was not painted: %r" % (state,)


def shadow(pg, selector: str) -> str:
    """JS reaching into the renderer's shadow root."""
    return "(document.querySelector('[data-vw-host]').shadowRoot || document.querySelector('[data-vw-host]')).querySelector(%r)" % selector


def no_sideways_scroll(pg):
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), "the page scrolls sideways"


def test_the_tab_explains_the_change_in_steps_that_add_up(stack, browser):
    pg = open_var(browser, str(stack), "/go?q=" + "VAR EXPLAIN BANK <GO>".replace(" ", "+"), {"width": 1280, "height": 900})
    assert "tab=why" in pg.url
    explained(pg, "waterfall")
    assert pg.get_attribute('.vx-tab[data-tab="why"]', "aria-selected") == "true"
    a = json.loads(urllib.request.urlopen(str(stack) + "/api/var/scope?scope=BANK").read())       # the page's own engine answers
    assert a.get("var")
    got = json.loads(pg.evaluate("fetch('/api/var/explain?scope=BANK').then(r => r.text())"))
    assert got["addsUp"] and abs(got["sumOfSteps"] - got["change"]) < 0.005
    assert abs(got["varTo"] - a["var"]) < 0.5                     # the headline is rounded to units
    assert pg.evaluate("!!" + shadow(pg, '[data-panel="waterfall"]')), "the waterfall panel"
    assert pg.evaluate("!!" + shadow(pg, '[data-panel="states"]')) and pg.evaluate("!!" + shadow(pg, '[data-panel="orderfree"]'))
    rows = pg.evaluate("(%s).querySelectorAll('tbody tr[data-ev]').length" % shadow(pg, '[data-panel="steps"]'))
    assert rows == len(got["lines"])
    # a step's row opens its drivers
    key = got["lines"][-1]["key"]
    pg.evaluate("(%s).querySelector('tbody tr[data-ev=\"%s\"]').click()" % (shadow(pg, '[data-panel="steps"]'), key))
    wait(pg, "!!" + shadow(pg, '[data-panel^="drivers-%s"], [data-panel="drivers"]' % key), 30)
    assert "xlsx?scope=BANK" in pg.get_attribute("[data-vw-xlsx]", "href")
    no_sideways_scroll(pg)
    shot(pg, "why-it-moved-waterfall")

    # the view switch: Matrix, then Sentences, kept in the address
    pg.click('[data-vw-view="matrix"]')
    explained(pg, "matrix")
    assert "view=matrix" in pg.url and pg.evaluate("!!" + shadow(pg, '[data-panel="matrix"]'))
    no_sideways_scroll(pg)
    shot(pg, "why-it-moved-matrix")
    pg.click('[data-vw-view="sentences"]')
    explained(pg, "sentences")
    assert "view=sentences" in pg.url
    assert "VaR of" in pg.evaluate("(%s).innerText" % shadow(pg, '[data-panel="answer"]'))
    shot(pg, "why-it-moved-sentences")

    # the console's active theme, not the library's own default (parchment on a light operating system), and a switch follows at once
    pg.emulate_media(color_scheme="light")
    page_theme = pg.evaluate("document.documentElement.getAttribute('data-theme')")
    assert pg.get_attribute("[data-vw-host]", "data-theme") == page_theme
    bg = "getComputedStyle(document.querySelector('[data-vw-host]')).backgroundColor"
    before = pg.evaluate(bg)
    other = "light" if page_theme != "light" else "terminal"
    pg.evaluate("document.querySelector('[data-theme-choice=\"%s\"]') ? document.querySelector('[data-theme-choice=\"%s\"]').click()"
                " : document.dispatchEvent(new CustomEvent('drishti:theme', {detail: '%s'}))" % (other, other, other))
    if pg.evaluate("document.documentElement.getAttribute('data-theme')") != other:   # no picker on the page: set it as the picker does
        pg.evaluate("document.documentElement.setAttribute('data-theme', '%s'); document.dispatchEvent(new CustomEvent('drishti:theme', {detail: '%s'}))" % (other, other))
    wait(pg, "document.querySelector('[data-vw-host]').getAttribute('data-theme') === '%s'" % other)
    assert pg.evaluate(bg) != before
    pg.emulate_media(color_scheme="no-preference")

    # F1 on the tab: the guide's section
    pg.keyboard.press("F1")
    pg.wait_for_url("**/help/**")
    assert "311-why-it-moved-the-var-change-explained" in pg.url
    pg.close()


def test_a_phone_draws_the_answer_and_the_steps_as_bars(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=BANK&tab=why", {"width": 390, "height": 844})
    explained(pg, "phone")                                      # whatever view the person chose last
    assert pg.evaluate("!!" + shadow(pg, '[data-panel="answer"]')) and pg.evaluate("!!" + shadow(pg, '[data-panel="bars"]'))
    assert not pg.evaluate("!!" + shadow(pg, '[data-panel="states"]')), "the states are for the desk"
    no_sideways_scroll(pg)
    shot(pg, "why-it-moved-phone")
    pg.close()
