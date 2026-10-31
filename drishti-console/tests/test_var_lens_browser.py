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

"""The VaR page's Horizon and Confidence (2.9.7, docs/guides/VAR_GUIDE.md section 18.6; the approved mock-up var-horizon-picker), in a
real browser over the market-risk pack's committed VaR data built into a scratch lake, the demo connector off:

- the header's Horizon (1-day, 10-day √10, 10-day sum), Confidence and ES beside the Reporting currency; each choice redraws every
  panel (the tiles, the method strip, the tree, the P&L path, the history, the contributions, the other currencies), names itself in the
  command and the link (h=10d&m=sum&q=0.95), and is kept in the person's settings (the next page without a lens opens with it);
- the bank's 10-day √10 VaR is its 1-day VaR × √10; the 10-day sum reads 500 overlapping windows;
- limit use only at the limit's own horizon and confidence (1-day 99%), else "limit is for 1-day 99%: not comparable"; the backtest only
  at 1 day;
- ``VAR BANK 10D SUM 97.5`` opens the page with that lens; the address round-trips;
- at phone width the controls are full width with no sideways scroll; the controls draw in every theme.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import math
import subprocess
import sys
import urllib.request
from pathlib import Path

import pytest

from test_var_explorer_browser import _engine_ready, open_var, painted, shot
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
VAR_DATA = ",".join([f"market-risk/{k}" for k in ("pnl-vector", "scenario-pv", "fx-scenario", "scenario-set", "risk-hierarchy", "portfolio",
                                                 "var-limit")] + ["trades/murex"])   # var-limit: the limits that limit use reads
THEMES = ["terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"]
TILE = "document.querySelector('.vx-tile[data-tile=\"%d\"] %s').innerText"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    work = tmp_path_factory.mktemp("var-lens-lake")
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--connectors-dir", str(work / "connectors"), "--as-of", "today", "--only", VAR_DATA, "--no-signal", "-q",
                        "--engine", "auto"], capture_output=True, text=True, cwd=ROOT, timeout=600)
    if r.returncode != 0 or not (lake / "risk" / "scenario-pv").is_dir():
        pytest.skip("could not build the VaR demo lake: " + (r.stderr or r.stdout)[-400:])
    for st in _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                                        "DRISHTI_VAR_HOT_DIR": str(work / "hot")}):
        _engine_ready(str(st))
        yield st


def money(text: str) -> float:
    """``112.04m`` / ``112,041,646`` / ``1.2bn`` -> the number (the tiles' compact money)."""
    t = text.strip().replace(",", "").replace("−", "-")
    for suffix, f in (("bn", 1e9), ("m", 1e6), ("k", 1e3)):
        if t.endswith(suffix):
            return float(t[:-len(suffix)]) * f
    return float(t)


def lens_painted(pg, lens: str, scope="BANK"):
    """Waits for the page painted with that lens (root data-lens: ``10D-SUM|0.99|0.975``)."""
    wait(pg, "document.querySelector('[data-var]').dataset.painted === %r && (document.querySelector('[data-var]').dataset.lens || '')"
             ".indexOf(%r) === 0" % (scope, lens), 90)


def test_each_horizon_and_confidence_redraws_every_panel_and_round_trips(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=BANK")
    painted(pg, "BANK")
    lens_painted(pg, "1D|0.99|0.975")
    wait(pg, "document.querySelector('[data-var]').dataset.lensReady === 'true'", 30)
    assert pg.eval_on_selector_all("[data-vx-horizon] option", "os => os.map(o => o.textContent)") == ["1-day", "10-day (√10)", "10-day (sum)"]
    confs = pg.eval_on_selector_all("[data-vx-conf] option", "os => os.map(o => o.textContent)")
    assert confs == ["95%", "97.5%", "99% · default", "99.5%"], confs
    assert "97.5% · default" in pg.eval_on_selector_all("[data-vx-esconf] option", "os => os.map(o => o.textContent)")
    one = money(pg.evaluate(TILE % (0, ".v")))
    assert "limit" in pg.evaluate(TILE % (4, ".s")) and pg.evaluate(TILE % (4, ".v")) != "—"
    assert "zone" in pg.evaluate(TILE % (5, ".s"))
    strip = " ".join(pg.locator("[data-vx-method]").inner_text().split())
    assert strip.startswith("Horizon 1-day") and "VaR 99%, ES 97.5%" in strip and "509 scenarios" in strip, strip
    shot(pg, "lens-1d")

    # 10-day √10: the 1-day VaR × √10; the tree, the path and the method strip follow; limit use not comparable; no backtest
    pg.select_option("[data-vx-horizon]", "10d:sqrt10")
    lens_painted(pg, "10D-SQRT10|0.99|0.975")
    assert "h=10d" in pg.url and "m=sqrt10" in pg.url
    assert pg.locator("[data-vx-cmd]").inner_text() == "VAR BANK 10D SQRT"
    sq = money(pg.evaluate(TILE % (0, ".v")))
    assert abs(sq - one * math.sqrt(10)) <= 0.006 * sq, (one, sq)                     # the tiles round to 2 or 3 figures
    assert "1-day × √10" in pg.evaluate(TILE % (0, ".s")) and "10 days" in pg.evaluate(TILE % (0, ".l"))
    assert pg.evaluate(TILE % (4, ".s")) == "limit is for 1-day 99%: not comparable" and pg.evaluate(TILE % (4, ".v")) == "—"
    assert "backtesting is 1-day" in pg.evaluate(TILE % (5, ".s"))
    assert "√10" in pg.locator("[data-vx-method]").inner_text()
    wait(pg, "!!document.querySelector('.vx-tree .vx-row[data-scope=\"NODE:MGMT~BANK\"]')", 60)
    tree_bank = pg.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~BANK"] .num').first.inner_text()
    assert abs(money(tree_bank) - sq) <= 0.006 * sq, (tree_bank, sq)
    shot(pg, "lens-10d-sqrt")

    # 10-day sum: 500 overlapping windows; the path is drawn on them; the history says there is no 10-day backtest
    pg.select_option("[data-vx-horizon]", "10d:sum")
    lens_painted(pg, "10D-SUM|0.99|0.975")
    assert pg.locator("[data-vx-cmd]").inner_text() == "VAR BANK 10D SUM"
    assert "5th worst of 500 overlapping windows" in pg.evaluate(TILE % (0, ".s"))
    assert "(500 windows)" in pg.locator("[data-vx-n]").inner_text()
    wait(pg, "document.querySelectorAll('[data-vx-pathtable] tbody tr').length > 0", 60)
    first = pg.locator("[data-vx-pathtable] tbody tr").first.locator("td").nth(1).inner_text()
    assert " … " in first, first                                                     # a window: its first and last day
    assert "sum of days" in pg.locator("[data-vx-method]").inner_text()
    shot(pg, "lens-10d-sum")
    pg.locator('.vx-tab[data-tab="hist"]').click()
    wait(pg, "document.querySelector('[data-var]').dataset.tabPainted === 'hist'", 60)
    assert "backtesting is 1-day" in pg.locator("[data-vx-hist-note]").inner_text()
    pg.locator('.vx-tab[data-tab="contrib"]').click()
    wait(pg, "document.querySelector('[data-var]').dataset.tabPainted === 'contrib'", 60)
    assert pg.locator("[data-vx-ctable] tbody tr.vx-click").count() > 0
    pg.locator('.vx-tab[data-tab="ccy"]').click()
    wait(pg, "document.querySelectorAll('[data-vx-ccytable] tbody tr[data-ccy]').length >= 5", 60)
    pg.locator('.vx-tab[data-tab="path"]').click()

    # a confidence: another rank of the same vector; 95% at 10 days sum is the 25th of 500
    pg.select_option("[data-vx-conf]", "0.95")
    lens_painted(pg, "10D-SUM|0.95|0.975")
    assert "q=0.95" in pg.url and pg.locator("[data-vx-cmd]").inner_text() == "VAR BANK 10D SUM 95"
    assert "25th worst of 500" in pg.evaluate(TILE % (0, ".s")) and "VaR 95%" in pg.evaluate(TILE % (0, ".l"))
    assert "VaR 95%, ES 97.5%" in pg.locator("[data-vx-method]").inner_text()

    # the address round-trips: the same link opens the same lens
    link = pg.url
    pg2 = open_var(browser, str(stack), link.replace(str(stack), ""))
    lens_painted(pg2, "10D-SUM|0.95|0.975")
    assert pg2.input_value("[data-vx-horizon]") == "10d:sum"
    pg2.close()

    # 1-day 95%: the limit is 1-day 99%, so still not comparable; back at 99% it is shown again
    pg.select_option("[data-vx-horizon]", "1d")
    lens_painted(pg, "1D|0.95|0.975")
    assert "26th worst of 509" in pg.evaluate(TILE % (0, ".s"))
    assert "not comparable" in pg.evaluate(TILE % (4, ".s"))
    pg.select_option("[data-vx-conf]", "")
    lens_painted(pg, "1D|0.99|0.975")
    assert abs(money(pg.evaluate(TILE % (0, ".v"))) - one) < 1 and pg.evaluate(TILE % (4, ".v")) != "—"
    assert "h=" not in pg.url and "q=" not in pg.url
    pg.close()


def test_the_command_opens_the_lens_and_the_person_keeps_it(stack, browser):
    pg = open_var(browser, str(stack), "/go?q=" + "VAR BANK 10D SUM 97.5 <GO>".replace(" ", "+"))
    assert "h=10d" in pg.url and "m=sum" in pg.url and "q=0.975" in pg.url
    lens_painted(pg, "10D-SUM|0.975|0.975")
    assert "13th worst of 500" in pg.evaluate(TILE % (0, ".s"))
    # the person's settings keep the last choice: a VaR page without a lens opens with it
    pg.select_option("[data-vx-horizon]", "10d:sqrt10")
    lens_painted(pg, "10D-SQRT10|0.975|0.975")
    pg.wait_for_timeout(500)
    pg.goto(str(stack) + "/var?scope=DESK:DESK-RATES")
    lens_painted(pg, "10D-SQRT10|0.975|0.975", "DESK:DESK-RATES")
    assert "h=10d" in pg.url and "m=sqrt10" in pg.url
    # back to the default for the next tests
    pg.select_option("[data-vx-horizon]", "1d")
    lens_painted(pg, "1D|0.975|0.975", "DESK:DESK-RATES")
    pg.select_option("[data-vx-conf]", "")
    lens_painted(pg, "1D|0.99|0.975", "DESK:DESK-RATES")
    pg.wait_for_timeout(500)
    pg.close()


def test_on_a_phone_the_controls_are_full_width_and_draw_in_every_theme(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=BANK&h=10d&m=sum", {"width": 390, "height": 844})
    lens_painted(pg, "10D-SUM")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    for sel in ("[data-vx-horizon]", "[data-vx-conf]", "[data-vx-esconf]", "[data-vx-ccy]"):
        box = pg.locator(sel).bounding_box()
        assert box and box["height"] >= 36 and box["x"] + box["width"] <= 391, (sel, box)
        label = pg.locator(sel).locator("xpath=..").bounding_box()
        assert label["width"] >= 330, (sel, label)                              # the control's row is the screen's width
    shot(pg, "lens-phone")
    seen = set()
    for theme in THEMES:
        pg.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
        colours = pg.evaluate("""() => { const s = getComputedStyle(document.querySelector('[data-vx-horizon]'));
            const m = getComputedStyle(document.querySelector('[data-vx-method]'));
            return [s.backgroundColor, s.color, s.borderTopColor, m.backgroundColor, m.color]; }""")
        assert all(c and c != "rgba(0, 0, 0, 0)" for c in colours), (theme, colours)
        assert colours[0] != colours[1], (theme, colours)                       # the text stands out from its field
        assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), theme
        seen.add(colours[0])
    assert len(seen) > 1
    pg.close()


def test_the_engine_answers_through_the_lens(stack):
    with urllib.request.urlopen(str(stack) + "/api/var/scope?scope=BANK&horizon=10d&method=sum", timeout=60) as r:
        import json
        d = json.loads(r.read())
    assert d["horizon"] == "10D" and d["method"] == "SUM" and d["scenarios"] == 500 and d["k"] == 5
    assert d["limitUse"] is None and d["limitComparable"] is False
