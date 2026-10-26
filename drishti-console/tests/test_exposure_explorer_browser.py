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

"""The exposure explorer in a real browser (docs/guides/EXPOSURE_GUIDE.md section 3), over the committed exposure run (the run
XR-2026-09-30-01's eight netting sets, 627 trades, 200 paths, 24 grid dates: lake demo --exposure committed, the quick one) with the netting sets, CSAs, collateral balances,
credit limits, counterparties, groups and legal entities beside it, built into a scratch lake (lake demo --only those kinds, as the
guide's pictures do in tools/docs/shots/_exp.py, with the committed run) and served by the Delta connector, the demo connector OFF, compute ON (the exact pre-deal
method runs in the Python exposure engine):

- EXP BANK <GO> opens the explorer on the bank: the scope tree, the counterparty board; a click on a counterparty moves every panel in
  place (the breadcrumb, the tiles, its Netting sets tab); a netting set's Collateral tab shows its CSA and the signed "Effect of
  collateral"; its Trades tab opens a trade "In its netting set" with its netting benefit; Back returns to the set;
- By tenor; the q 95/99 and collateralised switches change the figures; the pre-deal what-if (on a netting set, and on a counterparty
  into the netting set picked, its largest first) by the proxy (addon) and by the exact
  method (paths, the Python engine);
- a netting set the run does not cover says so (no figures); on a phone the explorer does not scroll sideways; F1 opens the guide.

Takes no pictures. Skipped when Playwright, Chromium, the built server jar or a JDK is missing, or the lake cannot be built."""
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
#: the kinds the exposure engine reads (as tools/docs/shots/_exp.py builds them)
EXP_DATA = ",".join(["counterparty-risk/" + k for k in ("simulation-run", "exposure-path", "netting-set", "csa", "collateral-balance", "credit-limit")]
                    + ["core/" + k for k in ("counterparty", "counterparty-group", "legal-entity")])
CPTY = "CPTY:CP-ALDERSHOT"
SET = "NSET:NS-ALDERSHOT-LDN"                 # 194 trades under a CSA (thresholds 10m each way, MPOR 10 business days)
PAINT_S = 120                                 # a first answer on a loaded machine (the date loads from the lake, the trades' paths too)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    work = tmp_path_factory.mktemp("exp-lake")
    lake = work / "delta"
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(lake), "--data-dir", str(work / "data"),
                        "--connectors-dir", str(work / "connectors"), "--as-of", "data", "--only", EXP_DATA, "--no-signal", "-q",
                        "--engine", "auto", "--exposure", "committed"], capture_output=True, text=True, cwd=ROOT, timeout=900)
    if r.returncode != 0 or not (lake / "risk" / "exposure-path").is_dir():
        pytest.skip("could not build the exposure lake: " + (r.stderr or r.stdout)[-400:])
    for st in _stack(tmp_path_factory, {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true",
                                        "DRISHTI_COMPUTE_ENABLED": "true", "DRISHTI_PYTHON": sys.executable}):
        _engine_ready(str(st))
        yield st


def _engine_ready(base, seconds=300):
    """Waits until the engine answers the bank: the Delta connector comes up after the server and the date loads from the lake on the
    first question; until then a page would draw an error, not the figures the tests look for."""
    deadline = time.monotonic() + seconds
    while True:
        try:
            with urllib.request.urlopen(base + "/api/exposure/scope?scope=BANK", timeout=60) as r:
                if r.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            pass
        if time.monotonic() > deadline:
            pytest.fail(f"the exposure engine did not answer within {seconds} s")
        time.sleep(1)


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=120) as r:
        return json.loads(r.read())


def open_exp(browser, base, path, size=None):
    pg = browser.new_page(viewport=size or {"width": 1600, "height": 1000})
    pg.goto(base + path)
    return pg


def painted(pg, scope):
    """The page's own signals: the headline painted for the scope, then its tab."""
    wait(pg, "document.querySelector('[data-exposure]') && document.querySelector('[data-exposure]').dataset.painted === %r" % scope, PAINT_S)
    wait(pg, "document.querySelector('[data-exposure]').dataset.tabPainted !== undefined", PAINT_S)


def tab(pg, name):
    pg.locator(f'.xx-tab[data-tab="{name}"]').click()
    wait(pg, "document.querySelector('[data-exposure]').dataset.tabPainted === %r" % name, PAINT_S)


def head(pg, field):
    return pg.evaluate("window.ExposureExplorer.state.head[%r]" % field)


def test_from_the_bank_to_a_trade_in_place_and_back(stack, browser):
    pg = open_exp(browser, str(stack), "/go?q=" + "EXP BANK <GO>".replace(" ", "+"))
    assert "/exposure" in pg.url
    painted(pg, "BANK")
    pg.evaluate("window.__marker = 1")                                       # survives only while the page is not reloaded
    # the tree: the bank, its legal entities, their counterparties
    wait(pg, "document.querySelectorAll('.xx-tree .xx-row[data-scope]').length > 3", PAINT_S)
    assert pg.locator('.xx-tree .xx-row[data-scope="BANK"]').count() == 1
    assert pg.locator('.xx-tree .xx-row[data-scope^="LE:"]').count() >= 1
    # the counterparty board, sorted by peak PFE
    assert pg.get_attribute("[data-exposure]", "data-tab-painted") == "board"
    wait(pg, "document.querySelectorAll('[data-xx-pane] tbody tr[data-scope^=\"CPTY:\"]').length > 1")
    assert pg.locator(".xx-tile").count() == 5
    bank_peak = head(pg, "peakPfe")

    pg.locator(f'[data-xx-pane] tbody tr[data-scope="{CPTY}"]').click()
    painted(pg, CPTY)
    crumb = pg.locator("[data-xx-crumb]").text_content()
    assert "Bank" in crumb and "Back" in crumb
    assert pg.locator("[data-xx-crumb] .cur").inner_text().strip() != ""
    assert pg.locator(".xx-tile").count() == 5 and head(pg, "peakPfe") != bank_peak
    assert pg.locator('.xx-tile[data-tile="0"] .k').text_content().startswith("Peak PFE 95%")

    # Netting sets: their contributions add up to the counterparty's peak; a click opens the set's workbench
    assert pg.get_attribute("[data-exposure]", "data-tab-painted") == "sets"
    rows = pg.locator('[data-xx-pane] tbody tr[data-scope^="NSET:"]')
    assert rows.count() >= 2
    total = pg.evaluate("Number(document.querySelector('[data-xx-pane] tr.xx-total td:nth-child(6)').textContent.replace(/[^0-9.\\-]/g, ''))")
    assert total > 0
    pg.locator(f'[data-xx-pane] tbody tr[data-scope="{SET}"]').click()
    painted(pg, SET)
    assert pg.get_attribute("[data-exposure]", "data-tab-painted") == "coll"
    pane = pg.locator("[data-xx-pane]").text_content()
    assert "CSA" in pane and "none (uncollateralised)" not in pane
    effect = pg.evaluate("(function () { var dts = document.querySelectorAll('[data-xx-pane] dt');"
                         " for (var i = 0; i < dts.length; i++) if (dts[i].textContent === 'Effect of collateral') return dts[i].nextSibling.textContent;"
                         " return null; })()")
    assert effect and re.fullmatch(r"[−+]\d+%", effect), effect
    assert pg.evaluate("window.__marker") == 1

    # Trades: all the set's trades by EE contribution (read from the lake); a row opens the trade in its netting set
    tab(pg, "trades")
    wait(pg, "document.querySelectorAll('[data-xx-pane] tbody tr[data-scope^=\"TRD:\"]').length > 0")
    first = pg.locator('[data-xx-pane] tbody tr[data-scope^="TRD:"]').first
    trd = first.get_attribute("data-scope")
    first.locator("td").nth(1).click()                                       # not the id (it links to the trade's page)
    painted(pg, trd)
    assert pg.get_attribute("[data-exposure]", "data-tab-painted") == "inset"
    assert pg.locator('.xx-tab[data-tab="inset"]').text_content() == "In its netting set"
    assert pg.locator('.xx-tile[data-tile="2"] .k').text_content() == "Netting benefit"
    wait(pg, "document.querySelector('.xx-tile[data-tile=\"2\"] .v').textContent !== '…'", PAINT_S)
    assert "EE contribution" in pg.locator("[data-xx-pane]").text_content()
    assert pg.locator("[data-xx-crumb] .cur").text_content().startswith("TRD ")

    # Back returns to the netting set, in place
    pg.locator("[data-xx-back]").click()
    painted(pg, SET)
    assert pg.evaluate("window.__marker") == 1
    pg.close()


def test_by_tenor_and_the_switches_change_the_figures(stack, browser):
    pg = open_exp(browser, str(stack), "/exposure?scope=" + SET)
    painted(pg, SET)
    tab(pg, "tenor")
    tenors = len(head(pg, "profile"))
    assert tenors >= 10
    assert pg.locator("[data-xx-pane] thead th").count() == tenors + 1
    assert pg.locator("[data-xx-pane] tbody tr").count() == 6               # EE, ENE, EEE, PFE q, PFE 99%, EE uncollateralised
    p95 = head(pg, "peakPfe")

    pg.locator('[data-xx-q="0.99"]').click()
    wait(pg, "window.ExposureExplorer.state.head && window.ExposureExplorer.state.head.confidence === 0.99"
             " && document.querySelector('[data-exposure]').dataset.painted === %r" % SET, PAINT_S)
    assert head(pg, "peakPfe") > p95
    assert pg.locator('.xx-tile[data-tile="0"] .k').text_content().startswith("Peak PFE 99%")
    assert "q=0.99" in pg.url
    pg.locator('[data-xx-q="0.95"]').click()
    wait(pg, "window.ExposureExplorer.state.head.confidence === 0.95 && document.querySelector('[data-exposure]').dataset.painted === %r" % SET, PAINT_S)
    epe = head(pg, "epe")

    pg.locator('[data-xx-coll="none"]').click()
    wait(pg, "window.ExposureExplorer.state.collateral === 'none' && document.querySelector('[data-exposure]').dataset.painted === %r"
             " && window.ExposureExplorer.state.head.collateralModel !== 'lagged'" % SET, PAINT_S)
    assert head(pg, "epe") != epe
    assert pg.get_attribute('[data-xx-coll="none"]', "aria-pressed") == "true"
    pg.locator('[data-xx-coll="lagged"]').click()
    wait(pg, "window.ExposureExplorer.state.collateral === 'lagged' && document.querySelector('[data-exposure]').dataset.painted === %r"
             " && window.ExposureExplorer.state.head.epe === %r" % (SET, epe), PAINT_S)
    pg.close()


def test_the_pre_deal_what_if_by_the_proxy_and_by_the_paths(stack, browser):
    pg = open_exp(browser, str(stack), "/exposure?scope=" + SET)
    painted(pg, SET)
    tab(pg, "predeal")
    wait(pg, "document.querySelectorAll('[data-xx-candidate] option').length > 0")
    pg.locator("[data-xx-method]").select_option("addon")
    pg.locator("[data-xx-whatif]").click()
    wait(pg, "document.querySelector('[data-exposure]').dataset.whatif === 'addon'", PAINT_S)
    out = pg.locator("[data-xx-whatif-out]").text_content()
    assert "Peak PFE after" in out and "proxy" in out
    assert pg.locator("[data-xx-whatif-out] tbody tr").count() == len(head(pg, "profile"))

    # the exact method: the candidate's paths netted and collateralised with the set by the Python exposure engine (compute is on)
    pg.evaluate("delete document.querySelector('[data-exposure]').dataset.whatif")
    pg.locator("[data-xx-method]").select_option("paths")
    pg.locator("[data-xx-factor]").fill("-1")                                # the unwind of a booked trade
    pg.locator("[data-xx-whatif]").click()
    wait(pg, "document.querySelector('[data-exposure]').dataset.whatif === 'paths'"
             " || /failed/.test(document.querySelector('[data-xx-whatif-out]').parentNode.parentNode.innerText)", 300)
    assert pg.get_attribute("[data-exposure]", "data-whatif") == "paths", pg.locator("[data-xx-whatif-out]").inner_text()[:300]
    out = pg.locator("[data-xx-whatif-out]").text_content()
    assert "Peak PFE after" in out and "exact" in out
    pg.close()


def test_the_pre_deal_what_if_on_a_counterparty(stack, browser):
    """The counterparty's Pre-deal tab: the netting set the new trade goes into (its largest first), the candidate from that set, the
    counterparty's figures; another set lists its own trades; the same result layout as a netting set's."""
    pg = open_exp(browser, str(stack), "/exposure?scope=" + CPTY)
    painted(pg, CPTY)
    tab(pg, "predeal")
    wait(pg, "document.querySelectorAll('[data-xx-predeal-set] option').length > 1 && document.querySelectorAll('[data-xx-candidate] option').length > 0")
    sets = pg.evaluate("[...document.querySelectorAll('[data-xx-predeal-set] option')].map(o => o.value)")
    peaks = {r["nettingSet"]: r["peakPfe"] for r in head(pg, "byNettingSet")}
    assert sets[0] == "NSET:" + max(peaks, key=lambda k: peaks[k] or 0)          # the largest by peak PFE, picked by default
    assert pg.locator("[data-xx-whatif-scope]").input_value() == CPTY            # the counterparty's figures by default
    first = pg.evaluate("[...document.querySelectorAll('[data-xx-candidate] option')].map(o => o.value).join(',')")
    pg.locator("[data-xx-predeal-set]").select_option(sets[1])
    wait(pg, "!document.querySelector('[data-xx-candidate]').disabled && [...document.querySelectorAll('[data-xx-candidate] option')]"
             ".map(o => o.value).join(',') !== %r" % first, PAINT_S)
    assert "pre-deal what-if" in pg.locator("[data-xx-pane]").inner_text().lower()
    pg.locator("[data-xx-method]").select_option("addon")
    pg.locator("[data-xx-whatif]").click()
    wait(pg, "document.querySelector('[data-exposure]').dataset.whatif === 'addon'", PAINT_S)
    assert pg.get_attribute("[data-exposure]", "data-whatif-scope") == CPTY
    out = pg.locator("[data-xx-whatif-out]").text_content()
    assert "Peak PFE after" in out and "proxy" in out
    pg.evaluate("delete document.querySelector('[data-exposure]').dataset.whatif")
    pg.locator("[data-xx-method]").select_option("paths")
    pg.locator("[data-xx-factor]").fill("-1")
    pg.locator("[data-xx-whatif]").click()
    wait(pg, "document.querySelector('[data-exposure]').dataset.whatif === 'paths'"
             " || /failed/.test(document.querySelector('[data-xx-whatif-out]').parentNode.parentNode.innerText)", 300)
    assert pg.get_attribute("[data-exposure]", "data-whatif") == "paths", pg.locator("[data-xx-whatif-out]").inner_text()[:300]
    assert "exact" in pg.locator("[data-xx-whatif-out]").text_content()
    pg.close()


def test_a_netting_set_without_paths_says_so(stack, browser):
    tree = get(str(stack), "/api/exposure/tree")
    missing = [s["scope"] for g in tree.get("groups") or [] for cp in g.get("children") or [] for s in cp.get("children") or [] if s.get("noPaths")]
    assert missing, "the demo run leaves two counterparties without paths"
    pg = open_exp(browser, str(stack), "/exposure?scope=" + missing[0])
    wait(pg, "document.querySelector('[data-exposure]') && document.querySelector('[data-exposure]').dataset.noPaths === %r" % missing[0], PAINT_S)
    box = pg.locator("[data-xx-nopaths]")
    assert box.is_visible() and "never an old figure" in box.text_content()
    assert pg.locator("[data-xx-tiles]").is_hidden()
    pg.close()


def test_on_a_phone_no_sideways_scroll_and_f1_opens_the_guide(stack, browser):
    pg = open_exp(browser, str(stack), "/exposure?scope=" + CPTY, {"width": 390, "height": 844})
    painted(pg, CPTY)
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    pg.close()
    pg = open_exp(browser, str(stack), "/exposure?scope=BANK")
    painted(pg, "BANK")
    pg.locator("body").click(position={"x": 5, "y": 5})
    pg.keyboard.press("F1")
    pg.wait_for_url("**/help/**", timeout=PAINT_S * 1000)
    assert "exposure" in pg.url.lower()
    assert "Exposure" in pg.title() or "Exposure in Drishti" in pg.content()
    pg.close()
