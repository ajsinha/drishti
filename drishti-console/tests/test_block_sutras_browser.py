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

"""Trading Sutras written with shared blocks ({ use: legs@1, with: {...} }) open in the Build designer drawn, not as "Add your first
panel": from a MUREX and an ENDUR deal page's "How this view was built" link (which carries the page's record as kind and id) the
preview holds the blocks' legs and cash-flow panels and the YAML check has no false problem for use:/with:/when:; opened with no record
(a pack Sutra's own link), the Design gets the first matching record of the pack's samples and says so in Samples. Against a REAL
scratch server with the shipped packs (wb_live.py); skipped when Playwright, Chromium, the jar or a JDK is missing."""
import urllib.parse

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from wb_live import BROWSER_WAIT_MS, live_console  # noqa: E402,F401

TRADES = [("MX-20000001", "MUREX"), ("1000001", "ENDUR")]      # an IRS fixed/float and a ComSwap: both Sutras are all blocks


def drawn_with_blocks(page):
    """The designer drew the Sutra: the legs block's and the flow-schedule block's panels are on the canvas."""
    page.wait_for_function("() => window.drishtiWorkbench && window.drishtiWorkbench.store.state.id", timeout=BROWSER_WAIT_MS)
    page.locator("[data-preview] .pnl[data-panel='legs']").first.wait_for(timeout=BROWSER_WAIT_MS)
    page.locator("[data-preview] .pnl[data-panel='flows']").first.wait_for(timeout=BROWSER_WAIT_MS)
    assert page.locator("[data-preview] [data-first-panel]").count() == 0 or page.locator("[data-preview] .wb-first").first.is_hidden()
    page.wait_for_timeout(1500)                                   # the YAML editor's light check runs after a pause
    false = page.locator("[data-problems] li.pr-yaml, [data-problems] li.pr-operation")
    assert false.count() == 0, page.locator("[data-problems]").text_content()


def test_block_sutras_draw_in_the_designer_from_the_deal_page_and_from_the_pack(browser, live_console):
    base = str(live_console)
    ctx = browser.new_context()
    page = ctx.new_page()
    page.set_default_timeout(BROWSER_WAIT_MS)
    for trade, system in TRADES:
        page.goto(f"{base}/v/trade/{trade}")
        link = page.locator("a[data-prov-sutra]").first
        link.wait_for()
        q = urllib.parse.parse_qs(urllib.parse.urlparse(link.get_attribute("href")).query)
        assert q.get("kind") == ["trade"] and q.get("id") == [trade], (system, q)       # the page's record goes with the link
        link.click()
        page.wait_for_url(lambda u: "/build/d/" in u, timeout=BROWSER_WAIT_MS)
        drawn_with_blocks(page)
        refs = [s.get("ref") for s in page.request.get(f"{base}/build/designs/" + page.evaluate("() => window.drishtiWorkbench.store.state.id")).json()["samples"]]
        assert {"kind": "trade", "id": trade} in refs, (system, refs)

    # a pack Sutra opened with no record: the first pack sample its match holds for is added, and Samples says so
    page.goto(f"{base}/studio?sutra=cms-swap%401")
    if "/build/d/" not in page.url:
        page.locator("[data-start-go]").click()
    page.wait_for_url(lambda u: "/build/d/" in u, timeout=BROWSER_WAIT_MS)
    drawn_with_blocks(page)
    note = page.locator("[data-pack-sample]").first
    assert note.text_content().startswith("Sample picked from the pack: MX-"), note.text_content()
    ctx.close()
