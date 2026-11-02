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

"""The MR cube in a real browser (docs/guides/MR_CUBE_GUIDE.md), over the market-risk pack's committed VaR data and sensitivities built
into a scratch lake (its three business dates, moved to end today), the demo connector off:

- ``MCUBE <GO>`` opens landing A: the six tiles, the pivot of desks by risk class with its totals re-aggregated (the bank's cell is the
  engine's VaR of BANK, the desks sum to more), the Σ cells − total line, sparklines over the open dates, limit bars;
- a cell selected fills the right rail (the tail histogram with V and E, the provenance); Enter drills to the desk's books; Backspace returns;
- a re-pivot by typing a view; tab B draws the components (adding up to the total), the waterfall, the histogram and the heat map; tab C the
  DV01 ladder and a what-if side by side;
- at 390 px (a phone) the headline and the pivot draw and nothing scrolls sideways.

Screenshots with DRISHTI_MCUBE_SHOTS=<folder>. Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import datetime
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

import pytest

from test_var_explorer_browser import _engine_ready, open_var
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
SHOTS = os.environ.get("DRISHTI_MCUBE_SHOTS")
CUBE_DATA = ",".join([f"market-risk/{k}" for k in ("pnl-vector", "scenario-pv", "fx-scenario", "scenario-set", "risk-hierarchy", "portfolio",
                                                  "var-limit", "position-sensitivity", "risk-factor")] + ["trades/murex", "market-data/fx-spot"])


def shot(pg, name):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        pg.screenshot(path=str(Path(SHOTS) / f"{name}.png"), full_page=True)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    keep = os.environ.get("DRISHTI_TEST_MCUBE_WORK")              # a lake built before, reused (debugging: the build takes minutes)
    work = Path(keep) if keep else tmp_path_factory.mktemp("mcube-lake")
    lake = work / "delta"
    today = datetime.date.today()
    while today.weekday() >= 5:
        today -= datetime.timedelta(days=1)
    for as_of in () if keep and (lake / "risk" / "pnl-vector").is_dir() else (today,):
        r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                            "--connectors-dir", str(work / "connectors"), "--as-of", as_of.isoformat(), "--only", CUBE_DATA, "--no-signal", "-q",
                            "--engine", "auto"], capture_output=True, text=True, cwd=ROOT, timeout=1500)
        if r.returncode != 0 or not (lake / "risk" / "pnl-vector").is_dir():
            pytest.skip("could not build the cube's demo lake: " + (r.stderr or r.stdout)[-400:])
    for st in _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                                        "DRISHTI_VAR_HOT_DIR": str(work / "hot")}):
        _engine_ready(str(st))
        yield st


def painted(pg):
    wait(pg, "document.querySelector('[data-mc-grid] tbody tr td.mc-cell') !== null || !document.querySelector('[data-mc-notice]').hidden", 240)
    notice = pg.evaluate("document.querySelector('[data-mc-notice]').hidden ? '' : document.querySelector('[data-mc-notice]').textContent")
    assert pg.locator("[data-mc-grid] td.mc-cell").count() > 0, "the pivot was not drawn: " + notice


def no_sideways_scroll(pg):
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), "the page scrolls sideways"


def test_the_landing_draws_the_pivot_with_totals_that_are_the_unions_own_var(stack, browser):
    pg = open_var(browser, str(stack), "/go?q=" + "MCUBE <GO>".replace(" ", "+"), {"width": 1600, "height": 1000})
    assert "/mcube" in pg.url
    painted(pg)
    assert pg.locator(".mc-tile").count() == 6
    a = json.loads(pg.evaluate("fetch('/api/mcube/query', {method: 'POST', headers: {'Content-Type': 'application/json'}, "
                               "body: JSON.stringify({scope: 'BANK', rows: ['MGMT.DESK'], columns: ['RISK_CLASS'], measures: ['VAR']})}).then(r => r.text())"))
    bank = json.loads(urllib.request.urlopen(str(stack) + "/api/var/scope?scope=BANK").read())
    assert round(a["total"]["values"]["VAR"]) == bank["var"], "the grand total is the engine's VaR of the bank"
    desks = sum(r["values"]["VAR"] for r in a["rows"])
    assert desks > a["total"]["values"]["VAR"], "the desks sum to more than the bank: the total is not a sum"
    assert a["diversification"]["VAR"]["totalAcrossRows"] > 0
    assert pg.locator(".mc-total").count() == 1 and pg.locator(".mc-div").count() == 1
    assert pg.locator("svg.mc-spark").count() > 0
    # a cell: the right rail's histogram and provenance
    pg.locator("td.mc-cell").first.click()
    wait(pg, "document.querySelector('[data-mc-tail] svg.mc-histo') !== null", 60)
    assert "business date" in pg.locator("[data-mc-prov]").inner_text()
    no_sideways_scroll(pg)
    shot(pg, "mcube-landing-A")
    # Enter drills to the desk's books, Backspace goes back
    first_row = pg.locator("th.rowh a").first.inner_text()
    pg.locator('td.mc-cell[data-c="-1"]').first.click()
    wait(pg, "document.querySelector('[data-mc-tail] svg.mc-histo') !== null", 60)
    pg.keyboard.press("Enter")
    wait(pg, "document.querySelector('[data-mc-text]').textContent.indexOf('TT_BOOK') >= 0", 120)
    painted(pg)
    assert pg.locator("th.rowh a").count() >= 1
    shot(pg, "mcube-drill-books")
    pg.keyboard.press("Backspace")
    wait(pg, "document.querySelector('[data-mc-text]').textContent.indexOf('TT_BOOK') < 0", 120)
    painted(pg)
    assert pg.locator("th.rowh a").first.inner_text() == first_row
    pg.close()


def test_a_typed_view_re_pivots_and_tabs_b_and_c_draw(stack, browser):
    pg = open_var(browser, str(stack), "/mcube?text=" + "MCUBE BANK BY DESK,CURRENCY MEASURE VAR".replace(" ", "+"), {"width": 1600, "height": 1100})
    painted(pg)
    assert pg.locator("[data-mc-grid] thead th").count() > 3
    pg.click('[data-mc-tab="tail"]')
    wait(pg, "document.querySelector('[data-mc-waterfall] svg.mc-water') !== null", 120)
    wait(pg, "document.querySelector('[data-mc-hist] svg.mc-histo') !== null", 60)
    wait(pg, "document.querySelector('[data-mc-heat] table, [data-mc-heat] p') !== null", 120)
    shot(pg, "mcube-tab-B")
    pg.click('[data-mc-tab="sensitivities"]')
    wait(pg, "document.querySelector('[data-mc-ladder] table, [data-mc-ladder] p') !== null", 120)
    trade = pg.evaluate("fetch('/api/var/scope/contributions?scope=DESK:DESK-RATES&by=trade').then(r => r.json()).then(a => a.rows[0].key)")
    pg.fill('[data-mc-tray] input[name="trade"]', trade)
    pg.click('[data-mc-tray] button[type="submit"]')
    wait(pg, "document.querySelector('[data-mc-whatif] table') !== null", 120)
    shot(pg, "mcube-tab-C")
    pg.close()


def test_a_phone_draws_the_headline_and_the_pivot(stack, browser):
    pg = open_var(browser, str(stack), "/mcube", {"width": 390, "height": 844})
    painted(pg)
    assert pg.locator(".mc-tile").count() == 6
    no_sideways_scroll(pg)
    shot(pg, "mcube-phone")
    pg.close()
