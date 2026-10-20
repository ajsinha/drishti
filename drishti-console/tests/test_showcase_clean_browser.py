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

"""Every shipped example opens in the workbench as a copy with no problems: the Problems tab is empty and its count is blank.
Runs against ``wb_live.py``'s real server; skipped when Playwright, its Chromium, the server jar or JDK 25 is missing."""
import pytest

from conftest import CONSOLE
from wb_live import BROWSER_WAIT_MS, live_console, post  # noqa: F401 - the fixture

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
expect = sync_api.expect
EXAMPLES = sorted(p.name[: -len(".sutra.yaml")] for p in (CONSOLE.parent / "docs" / "guides" / "examples").glob("*.sutra.yaml"))


SHOWCASE = "all-panels-showcase"


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed: skip
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.mark.parametrize("example", EXAMPLES)
def test_an_example_opens_as_a_copy_with_no_problems(browser, live_console, example):
    page = browser.new_page(viewport={"width": 1500, "height": 950})
    try:
        page.goto(live_console + "/build/new")
        made = post(page, f"{live_console}/build/examples/{example}/open", {})
        assert made["http"] in (200, 201), made
        page.goto(live_console + made["url"])
        page.wait_for_function("() => window.drishtiWorkbench && window.drishtiWorkbench.store.state.id", timeout=max(BROWSER_WAIT_MS, 120_000))   # a cold server under load: as long as the preview may take
        page.locator("[data-preview] [data-panel]").first.wait_for(timeout=max(BROWSER_WAIT_MS, 120_000))   # the first example on a worker meets a cold server under the drill's load
        # the check runs on idle; run it now and wait for its answer (the matrix, or why it could not run) instead of a fixed time
        page.evaluate("() => window.drishtiWorkbench.tests.run()")
        expect(page.locator("[data-result]")).not_to_have_text("… checking", timeout=max(BROWSER_WAIT_MS, 60_000))
        items = page.evaluate("[...document.querySelectorAll('[data-problems] .wb-problem-b')].map(b => b.textContent)")
        if example != SHOWCASE:                              # the others match kinds of the banking packs and link to their entities: not on this plain server
            items = [i for i in items if "is not a valid kind" not in i and "not found" not in i]
        assert items == [], f"{example} opens with problems: {items}"
    finally:
        page.close()
