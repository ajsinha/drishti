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
into a scratch lake twice, on the newest business date and the one before (the demo data holds one date; each build moves it), the demo
connector off:

- ``VAR EXPLAIN BANK`` opens the VaR page on the tab; the waterfall, the steps, the drivers, the states and the order-free view are drawn by
  the renderer with the standard panel kinds; the steps add up to the change; a step's row opens its drivers;
- the view switch (Waterfall, Matrix, Sentences) redraws from the same answer and is kept in the address; the export link names the scope;
- at 1280 px nothing scrolls sideways; at 390 px (a phone) the answer and the steps as bars are drawn, nothing scrolls sideways;
- F1 on the tab opens the guide's section.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import datetime
import json
import subprocess
import sys
import urllib.request
from pathlib import Path

import pytest

from test_var_explorer_browser import _engine_ready, open_var, shot
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
VAR_DATA = ",".join([f"market-risk/{k}" for k in ("pnl-vector", "scenario-pv", "fx-scenario", "scenario-set", "risk-hierarchy", "portfolio",
                                                 "var-limit")] + ["trades/murex"])


def previous_business_day(d: datetime.date) -> datetime.date:
    d -= datetime.timedelta(days=1)
    while d.weekday() >= 5:
        d -= datetime.timedelta(days=1)
    return d


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    work = tmp_path_factory.mktemp("var-explain-lake")
    lake = work / "delta"
    today = datetime.date.today()
    while today.weekday() >= 5:
        today -= datetime.timedelta(days=1)
    for as_of in (previous_business_day(today), today):                # two business dates: the earlier first
        r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                            "--connectors-dir", str(work / "connectors"), "--as-of", as_of.isoformat(), "--only", VAR_DATA, "--no-signal", "-q",
                            "--engine", "auto"], capture_output=True, text=True, cwd=ROOT, timeout=1500)
        if r.returncode != 0 or not (lake / "risk" / "pnl-vector").is_dir():
            pytest.skip("could not build the VaR demo lake: " + (r.stderr or r.stdout)[-400:])
    for st in _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                                        "DRISHTI_VAR_HOT_DIR": str(work / "hot")}):
        _engine_ready(str(st))
        yield st


def explained(pg, view: str):
    wait(pg, "document.querySelector('[data-pane=\"why\"]').dataset.painted === %r" % view, 180)


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
    assert abs(got["varTo"] - a["var"]) < 0.005
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

    # F1 on the tab: the guide's section
    pg.keyboard.press("F1")
    pg.wait_for_url("**/help/**")
    assert "311-why-it-moved-the-var-change-explained" in pg.url
    pg.close()


def test_a_phone_draws_the_answer_and_the_steps_as_bars(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=BANK&tab=why", {"width": 390, "height": 844})
    explained(pg, "waterfall-phone")
    assert pg.evaluate("!!" + shadow(pg, '[data-panel="answer"]')) and pg.evaluate("!!" + shadow(pg, '[data-panel="bars"]'))
    assert not pg.evaluate("!!" + shadow(pg, '[data-panel="states"]')), "the states are for the desk"
    no_sideways_scroll(pg)
    shot(pg, "why-it-moved-phone")
    pg.close()
