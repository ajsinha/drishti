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
"""Build -> New pack, the second mode ("a source for an existing kind"), in a browser against a REAL scratch server: a mocked SwapOne file
(tools/packgen/swapone_synth.py) is dropped and read; the person chooses "a source for an existing kind" (trade), the server proposes the field
map and the page shows it as a grid; a cell is edited (a native field dropped); the shared table is chosen; the module source-swapone is made
with its checks and its section of the trade page. Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import re
import shutil
import sys

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from test_pack_designer_browser import designer, sign_in  # noqa: E402,F401 - the scratch server of the designer's tests
from wb_live import BROWSER_WAIT_MS  # noqa: E402

pytestmark = pytest.mark.spread
ROOT = CONSOLE.parent
LONG = max(BROWSER_WAIT_MS, 240_000)
sys.path.insert(0, str(ROOT / "tools" / "packgen"))


def pick(page, host, label):
    """Chooses an option of a Drishti Select."""
    page.click(f"{host} .di-select-control")
    page.locator(".di-option", has_text=label).first.click()


def test_a_source_for_the_trade_kind_is_mapped_edited_placed_and_made(browser, designer, tmp_path):
    import swapone_synth
    base = str(designer)
    folder = ROOT / "data" / "pdui-test" / ("source-" + tmp_path.name)
    f = swapone_synth.write(folder / "swapone_trades.2026-11-13.jsonl", 400, "2026-11-13")
    try:
        ctx = browser.new_context(viewport={"width": 1500, "height": 950})
        page = ctx.new_page()
        page.set_default_timeout(30_000)
        sign_in(page, base)
        page.goto(base + "/build/pack/designer")
        page.evaluate("sessionStorage.clear()")
        page.goto(base + "/build/pack/designer")

        # what the files are: a source for an existing kind (the analysis offers the kinds that fit)
        page.set_input_files("[data-pane=data] .di-file-drop input[type=file]:not([webkitdirectory])", [str(f)])
        page.locator("[data-mode-box]:not([hidden])").wait_for(timeout=LONG)
        pick(page, "[data-sel-mode]", "A source for an existing kind")
        page.locator("[data-pane=map]:not([hidden])").wait_for()

        # the map: every record read, each canonical field with its native field and a confidence
        page.locator("[data-map-grid]").get_by_text("COUNTERPARTY", exact=True).first.wait_for(timeout=LONG)
        grid = page.locator("[data-map-grid]").inner_text()
        assert "cptyCode" in grid and "notionalAmt" in grid and "dealRef" in grid
        assert "required missing" in page.locator("[data-map-kpi]").inner_text()
        assert "brokerRef" in page.locator("[data-unmapped]").inner_text()           # the native fields with no canonical one

        # edit the map: drop a guess (the row's native field set to none)
        page.locator("[data-map-grid] .dg-row", has_text=re.compile(r"^COUNTERPARTY_UEN")).first.click()
        assert "COUNTERPARTY_UEN" in page.locator("[data-ev-title]").inner_text()           # the evidence of the row
        cell = page.locator("[data-map-grid] .dg-row", has_text=re.compile(r"^COUNTERPARTY_UEN")).locator(".dg-cell[data-c='2']")
        cell.click()
        page.keyboard.press("Enter")                                                 # the cell's choice of the native fields
        page.locator(".di-option", has_text="(none)").first.click()
        page.wait_for_function("() => Object.keys(window.Drishti.PackDesigner.S.mapEdits).includes('COUNTERPARTY_UEN')", timeout=30_000)

        # the layout: the shared table, read with where
        page.click("[data-go=layout]")
        pick(page, "[data-sel-layout]", "A shared table")
        yaml = page.locator("[data-layout-yaml]").inner_text()
        assert "lake-tables: trading.trades_shared" in yaml and "SOURCE_SYSTEM = 'SWAPONE'" in yaml

        # finalize: the module with its checks
        page.click("[data-go=finalize]")
        page.click("[data-finalize]")
        page.locator("[data-download]:not([hidden])").wait_for(timeout=LONG * 2)
        assert "checks passed" in page.locator("[data-sum=finalize]").inner_text()
        assert "source-swapone" in page.locator("[data-pane=finalize]").inner_text()
        ctx.close()
    finally:
        shutil.rmtree(folder, ignore_errors=True)
