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

"""The MR cube in a real browser (docs/guides/MR_CUBE_GUIDE.md; LENSES.md section 6.6.7), over the market-risk pack's committed VaR data
built into a scratch lake and served by its Delta connector, the demo connector off:

- ``MCUBE <GO>`` opens the Pivot's preset mr, the pivot screen on source var with the MR lens: the landing view (desks by risk class), the headline strip, the
  slider and the tabs; no second grid, no script error;
- every cell's VaR shown is the VaR engine's for the cell's scope (``/api/var/scope``) to the cent, and a total is never a sum: the desks'
  VaRs add up to more than the bank's, and the grand total's diversification is the difference;
- a cell clicked fills the rail with its tail (the histogram, V and E) and its provenance names its scope; its trades are listed
  (drill-through) and a desk opens by its books, each the engine's VaR;
- tab B's components add up to the bank's VaR; the lens's 10-day button asks again and the bank's VaR becomes the 10-day figure;
- at 390 px the page has no sideways scroll and the headline is on screen; the lens draws in the dark and the light theme.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import json
import subprocess
import sys
import urllib.parse
from pathlib import Path

import pytest

from test_var_explorer_browser import _engine_ready, shot
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
VAR_DATA = ",".join([f"market-risk/{k}" for k in ("pnl-vector", "scenario-pv", "fx-scenario", "scenario-set", "risk-hierarchy", "portfolio",
                                                 "var-limit")] + ["trades/murex"])


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    work = tmp_path_factory.mktemp("mr-cube-lake")
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--connectors-dir", str(work / "connectors"), "--as-of", "today", "--only", VAR_DATA, "--no-signal", "-q",
                        "--engine", "auto"], capture_output=True, text=True, cwd=ROOT, timeout=1500)
    if r.returncode != 0 or not (lake / "risk" / "pnl-vector").is_dir():
        pytest.skip("could not build the VaR demo lake: " + (r.stderr or r.stdout)[-400:])
    for st in _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                                        "DRISHTI_COMPUTE_ENABLED": "false", "DRISHTI_MR_CUBE_ENABLED": "true",
                                        "DRISHTI_VAR_HOT_DIR": str(work / "hot")}):
        _engine_ready(str(st))
        yield st


def fetch(pg, method, path, body=None):
    """The console's own same-origin API, from the page, as the signed-in person."""
    return pg.evaluate("""async ([m, p, b]) => { const o = {method: m, credentials: 'same-origin', headers: {'Accept': 'application/json'}};
        if (b !== null) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(b); }
        const r = await fetch(p, o); const t = await r.text(); return {status: r.status, body: t ? JSON.parse(t) : null}; }""",
                       [method, path, body])


def scope_var(pg, scope):
    """The VaR explorer's figure for a scope: money in whole units (the cube's cells are to the cent, so they agree within half a unit)."""
    r = fetch(pg, "GET", "/api/var/scope?scope=" + urllib.parse.quote(scope))
    assert r["status"] == 200, (scope, r)
    return r["body"]["var"]


def opened(pg):
    """Waits for the MR cube's landing: the grid's cells and the headline's VaR drawn."""
    wait(pg, "document.querySelectorAll('[data-pv-grid] td[data-cell]').length > 0", 120)
    wait(pg, "!!document.querySelector('[data-mr-tiles] .mr-tile .mr-tv')", 120)


def view(pg):
    return pg.evaluate("window.Drishti.PivotScreen.view()")


def grand(answer):
    row = [r for r in answer["rows"] if r["kind"] == "grand"][0]
    col = answer["columns"][-1]
    return row, answer["cells"][row["key"] + "|" + col["key"]], col


def open_cube(browser, base, size=None):
    pg = browser.new_page(viewport=size or {"width": 1600, "height": 1000})
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(base + "/go?q=" + urllib.parse.quote("MCUBE <GO>"))
    assert "/pivot?preset=mr" in pg.url                                          # the MR cube is the Pivot's preset mr
    opened(pg)
    return pg


def test_mcube_opens_the_pivot_screen_with_the_mr_lens_and_every_cell_is_the_engines_var(stack, browser):
    pg = open_cube(browser, str(stack))
    assert pg.locator('[data-pivot-screen][data-lens="mr"][data-source="var"]').count() == 1
    assert pg.locator("[data-mr-tab]").count() == 3 and pg.locator("[data-mr-slider]").count() == 1
    v = view(pg)
    assert v["source"] == "var" and v["columns"] == ["RISK_CLASS"] and v["rows"][0].endswith(".DESK")
    a = fetch(pg, "POST", "/api/pivot/query", {"view": v})["body"]
    checked = 0
    for c in a["cells"].values():
        val = c["values"].get("VaR")
        if c.get("scope") and val is not None and checked < 10:
            assert abs(val - scope_var(pg, c["scope"])) <= 0.5, c["scope"]
            checked += 1
    assert checked >= 4
    row, total, col = grand(a)
    assert abs(total["values"]["VaR"] - scope_var(pg, "BANK")) <= 0.5
    desks = sum(a["cells"][r["key"] + "|" + col["key"]]["values"]["VaR"] for r in a["rows"] if r["kind"] == "node")
    assert desks > total["values"]["VaR"], "a total is the union's VaR, never the sum of its rows"
    assert abs(row["diversification"]["VaR"] - (desks - total["values"]["VaR"])) < 0.05
    headline = pg.locator("[data-mr-tiles] .mr-tile").first.locator(".mr-tv").text_content()
    assert headline.replace(",", "").strip() == f"{total['values']['VaR']:.0f}"
    shot(pg, "mr-cube-landing")
    assert not pg.errors, pg.errors
    pg.close()


def test_the_doors_open_the_same_preset_the_var_explorer_link_and_the_older_address(stack, browser):
    """One grid, several doors: the VaR explorer's MR cube link opens its scope in the preset, and /mcube (the older address) lands on it too."""
    base = str(stack)
    pg = browser.new_page(viewport={"width": 1600, "height": 1000})
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(base + "/var?scope=BANK")
    pg.locator("[data-vx-cube]").click()
    pg.wait_for_url("**/pivot?preset=mr**")
    opened(pg)
    v = view(pg)
    assert v["source"] == "var" and (v.get("options") or {}).get("scope") == "BANK"
    assert pg.locator("[data-pv-title]").text_content().strip().endswith("MR cube")             # the source's title, "VaR · MR cube"
    pg.goto(base + "/mcube")
    pg.wait_for_url("**/pivot?preset=mr**")
    opened(pg)
    assert not pg.errors, pg.errors
    pg.close()


def test_a_cell_fills_the_rail_lists_its_trades_and_a_desk_opens_by_its_books(stack, browser):
    pg = open_cube(browser, str(stack))
    first = pg.locator("[data-pv-grid] td[data-cell]").first
    key = first.get_attribute("data-cell")
    first.click()
    wait(pg, "!!document.querySelector('[data-mr-tail] svg.mr-histo')", 60)
    wait(pg, "!document.querySelector('[data-pv-pop]').hidden", 30)
    a = fetch(pg, "POST", "/api/pivot/query", {"view": view(pg)})["body"]
    cell = a["cells"][key]
    if cell.get("scope"):
        assert cell["scope"] in pg.locator("[data-pv-pop]").text_content()
    rows = fetch(pg, "POST", "/api/pivot/rows", {"view": view(pg), "coords": cell["coords"], "limit": 20})
    assert rows["status"] == 200 and rows["body"]["columns"][0] == "TRADE_ID" and rows["body"]["total"] == cell["members"]
    v = view(pg)
    desk_dim = v["rows"][0]
    desk = [r for r in a["rows"] if r["kind"] == "node"][0]
    opened_desk = fetch(pg, "POST", "/api/pivot/expand", {"view": v, "axis": "rows", "path": [[desk_dim, desk["value"]]], "by": v["rows"][1]})
    assert opened_desk["status"] == 200
    b = opened_desk["body"]
    books = [r for r in b["rows"] if r["depth"] == 2]
    assert books
    gcol = b["columns"][-1]["key"]
    for r in books[:4]:
        c = b["cells"][r["key"] + "|" + gcol]
        assert abs(c["values"]["VaR"] - scope_var(pg, c["scope"])) <= 0.5, c["scope"]
    shot(pg, "mr-cube-cell")
    assert not pg.errors, pg.errors
    pg.close()


def test_tab_b_components_add_up_and_the_10_day_lens_asks_again(stack, browser):
    pg = open_cube(browser, str(stack))
    pg.click('[data-mr-tab="tail"]')
    wait(pg, "!!document.querySelector('[data-mr-comp] table') && !!document.querySelector('[data-mr-waterfall] svg')", 120)
    assert pg.locator(".pv-main").is_hidden()
    v = view(pg)
    comp = dict(v, columns=[], rows=v["rows"][:1], values=[{"measure": "comp_var()", "as": "Comp"}, {"measure": "var()", "as": "VaR"}], expanded=[])
    a = fetch(pg, "POST", "/api/pivot/query", {"view": comp})["body"]
    row, total, col = grand(a)
    parts = sum(a["cells"][r["key"] + "|" + col["key"]]["values"]["Comp"] for r in a["rows"] if r["kind"] == "node")
    assert abs(parts - total["values"]["VaR"]) < 0.05, "component VaR adds up to the total"
    shot(pg, "mr-cube-tab-b")
    pg.click('[data-mr-tab="pivot"]')
    one_day = fetch(pg, "POST", "/api/pivot/mr/headline", {"view": view(pg)})["body"]["var"]      # the lens's own 1-day figure, to the cent
    pg.click('[data-mr-seg="horizon"] button[data-v="10d"]')
    wait(pg, "(window.Drishti.PivotScreen.view().options || {}).horizon === '10d'", 60)
    wait(pg, "!!document.querySelector('[data-mr-tiles] .mr-tile .mr-tv')", 60)
    ten = fetch(pg, "POST", "/api/pivot/mr/headline", {"view": view(pg)})["body"]
    assert abs(ten["var"] - one_day * 10 ** 0.5) <= 1e-9 * abs(ten["var"]), (ten["var"], one_day)          # √10 of the same 1-day figure
    assert not pg.errors, pg.errors
    pg.close()


def test_on_a_phone_and_in_the_dark_and_light_themes(stack, browser):
    pg = open_cube(browser, str(stack), {"width": 390, "height": 844})
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    box = pg.locator("[data-mr-tiles] .mr-tile").first.bounding_box()
    assert box and box["x"] >= 0 and box["x"] + box["width"] <= 391
    shot(pg, "mr-cube-phone")
    seen = []
    for theme in ("terminal", "light"):
        pg.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
        colours = pg.evaluate("""() => { const t = getComputedStyle(document.querySelector('[data-mr-tiles] .mr-tile'));
            const v = getComputedStyle(document.querySelector('[data-mr-tiles] .mr-tv'));
            return [t.backgroundColor, v.color, t.borderTopColor]; }""")
        assert all(c and c != "rgba(0, 0, 0, 0)" for c in colours), (theme, colours)
        assert colours[0] != colours[1], (theme, colours)
        seen.append(json.dumps(colours))
        assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), theme
    assert seen[0] != seen[1], "the lens follows the theme"
    assert not pg.errors, pg.errors
    pg.close()
