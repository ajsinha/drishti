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
"""The CCR cube in a real browser (docs/guides/EXPOSURE_GUIDE.md section 30; CCR_CUBE_LENS.md section 6.7, mock-ups A, B and C as views of
one screen, A the default), over the committed exposure run with its netting sets, CSAs, collateral, credit limits, counterparties and
groups built into a scratch lake (as test_exposure_explorer_browser.py builds it), the demo connector off:

- ``CCUBE <GO>`` opens the pivot screen on source ccr with the CCR lens on view A: the headline strip, the lens controls, the limit board
  by tenor bucket, the fan and the treemap; no second grid, no script error;
- the board's grand total is the exposure engine's peak PFE of the bank (``/api/exposure/scope?scope=BANK``), and a counterparty's cell its
  own (``CPTY:``): every total is the quantile of its own summed paths, so the counterparties' peaks add up to more than the bank's;
- a row clicked draws its fan with the limit step line; the q 99% button asks again and the bank's figure becomes the 99% one;
- ``CCUBE NSET:...`` opens view B on the netting set, its tiles the explorer's figures; view C's pre-deal copy answers with the headroom;
- at 390 px the page has no sideways scroll and the headline is on screen; the lens draws in the dark and the light theme.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing, or the lake cannot be built."""
import subprocess
import sys
import urllib.parse
from pathlib import Path

import pytest

from test_exposure_explorer_browser import EXP_DATA, SET, _engine_ready
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
PAINT_S = 180


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    work = tmp_path_factory.mktemp("ccr-cube-lake")
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--connectors-dir", str(work / "connectors"), "--as-of", "data", "--only", EXP_DATA, "--no-signal", "-q",
                        "--engine", "deltalake", "--exposure", "committed"], capture_output=True, text=True, cwd=ROOT, timeout=900)
    if r.returncode != 0 or not (lake / "risk" / "exposure-path").is_dir():
        pytest.skip("could not build the exposure lake: " + (r.stderr or r.stdout)[-400:])
    for st in _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                                        "DRISHTI_CCR_CUBE_ENABLED": "true"}):
        _engine_ready(str(st))
        yield st


def fetch(pg, method, path, body=None):
    """The console's own same-origin API, from the page, as the signed-in person."""
    return pg.evaluate("""async ([m, p, b]) => { const o = {method: m, credentials: 'same-origin', headers: {'Accept': 'application/json'}};
        if (b !== null) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(b); }
        const r = await fetch(p, o); const t = await r.text(); return {status: r.status, body: t ? JSON.parse(t) : null}; }""",
                       [method, path, body])


def opened(pg):
    wait(pg, "document.querySelectorAll('[data-pv-grid] td[data-cell]').length > 0", PAINT_S)
    wait(pg, "!!document.querySelector('[data-ccr-tiles] .mr-tile .mr-tv')", PAINT_S)


def open_cube(browser, base, typed="CCUBE <GO>", size=None):
    pg = browser.new_page(viewport=size or {"width": 1600, "height": 1000})
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(base + "/go?q=" + urllib.parse.quote(typed))
    assert "/ccube" in pg.url
    opened(pg)
    return pg


def grand(answer, measure):
    row = [r for r in answer["rows"] if r["kind"] == "grand"][0]
    col = [c for c in answer["columns"] if c["kind"] == "grand"][0]
    return answer["cells"][row["key"] + "|" + col["key"]]["values"][measure]


def test_ccube_opens_view_a_and_every_total_is_the_engines(stack, browser):
    pg = open_cube(browser, str(stack))
    assert pg.locator('[data-pivot-screen][data-lens="ccr"][data-source="ccr"]').count() == 1
    assert pg.locator("[data-ccr-tab]").count() == 3 and pg.locator('[data-ccr-tab="A"][aria-selected="true"]').count() == 1
    v = pg.evaluate("window.PivotScreen.view()")
    assert v["source"] == "ccr" and v["columns"] == ["TENOR_BUCKET"]
    flat = dict(v, rows=["COUNTERPARTY"], columns=[], values=[{"measure": "pfe()", "as": "PFE"}], expanded=[])
    a = fetch(pg, "POST", "/api/pivot/query", {"view": flat})["body"]
    bank = fetch(pg, "GET", "/api/exposure/scope?scope=BANK&q=" + v["options"]["q"])["body"]
    assert abs(grand(a, "PFE") - bank["peakPfe"]) <= 0.5
    gc = [c for c in a["columns"] if c["kind"] == "grand"][0]["key"]
    peaks = [a["cells"][r["key"] + "|" + gc]["values"]["PFE"] for r in a["rows"] if r["kind"] == "node"]
    assert sum(p for p in peaks if p) > grand(a, "PFE"), "the counterparties' peaks add up to more than the bank's: a total is never a sum"
    first = [r for r in a["rows"] if r["kind"] == "node"][0]
    cp = fetch(pg, "GET", "/api/exposure/scope?scope=" + urllib.parse.quote("CPTY:" + first["value"]) + "&q=" + v["options"]["q"])["body"]
    assert abs(a["cells"][first["key"] + "|" + gc]["values"]["PFE"] - cp["peakPfe"]) <= 0.5
    assert not pg.errors, pg.errors


def test_a_row_draws_its_fan_and_the_lens_asks_again(stack, browser):
    pg = open_cube(browser, str(stack))
    pg.locator("[data-pv-grid] td[data-cell]").first.click()
    wait(pg, "!!document.querySelector('[data-ccr-fan] svg.ccr-fan')", PAINT_S)
    before = pg.locator("[data-ccr-tiles] .mr-tile .mr-tv").first.inner_text()
    pg.locator('[data-ccr-seg="q"] button[data-v="0.99"]').click()
    wait(pg, "window.PivotScreen.view().options.q === '0.99'", 30)
    wait(pg, f"document.querySelector('[data-ccr-tiles] .mr-tile .mr-tv') && document.querySelector('[data-ccr-tiles] .mr-tile .mr-tv').innerText !== {before!r}",
         PAINT_S)
    assert not pg.errors, pg.errors


def test_view_b_on_a_netting_set_and_view_c_pre_deal(stack, browser):
    pg = open_cube(browser, str(stack), "CCUBE " + SET + " <GO>")
    assert pg.locator('[data-ccr-tab="B"][aria-selected="true"]').count() == 1
    wait(pg, "document.querySelectorAll('[data-ccr-b-tiles] .mr-tile').length >= 3", PAINT_S)
    x = fetch(pg, "GET", "/api/exposure/scope?scope=" + urllib.parse.quote(SET))["body"]
    assert f"{x['peakPfe']:,}" in pg.locator("[data-ccr-b-tiles]").inner_text()
    wait(pg, "document.querySelectorAll('[data-ccr-b-trades] tr').length > 1", PAINT_S)
    trade = fetch(pg, "GET", "/api/exposure/scope/contributions?by=trade&scope=" + urllib.parse.quote(SET))["body"]["rows"][0]["key"]
    pg.locator('[data-ccr-tab="C"]').click()
    f = pg.locator("[data-ccr-tray]")
    f.locator("input[name=scope]").fill("CPTY:CP-ALDERSHOT")
    f.locator("input[name=trade]").fill(trade)
    f.locator("input[name=nset]").fill(SET.split(":", 1)[1])
    f.locator("button[type=submit]").click()
    wait(pg, "!!document.querySelector('[data-ccr-c-overlay] svg.ccr-fan')", PAINT_S)
    assert "Headroom after" in pg.locator("[data-ccr-c-tiles]").inner_text()
    assert not pg.errors, pg.errors


@pytest.mark.parametrize("theme", ["dark", "light"])
def test_the_phone_has_no_sideways_scroll_in_both_themes(stack, browser, theme):
    pg = open_cube(browser, str(stack), size={"width": 390, "height": 844})
    pg.evaluate(f"document.documentElement.setAttribute('data-theme', {('terminal' if theme == 'dark' else 'light')!r})")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), "the page scrolls sideways at 390 px"
    assert pg.locator("[data-ccr-tiles] .mr-tile").first.is_visible()
    colour = pg.evaluate("getComputedStyle(document.querySelector('[data-ccr-tiles] .mr-tile')).color")
    assert colour and colour != "rgba(0, 0, 0, 0)"
    assert not pg.errors, pg.errors
