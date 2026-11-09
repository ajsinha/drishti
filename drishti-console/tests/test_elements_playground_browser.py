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
"""The Playground page (docs/lenses/mockups/bi-depth): an element drawn from its editable code in a sandbox with no way out, an error that
names its line and keeps the last good drawing, a reset; and the decomposition tree's own figures and split line."""
import asyncio
import pytest

from conftest import CONSOLE
from test_elements_gallery_browser import ORIGIN, run

pytestmark = pytest.mark.browser
DRAWN = "() => window.__gallery.pageState && window.__gallery.pageState().good && !document.querySelector('[data-page-error]:not([hidden])')"


def test_the_page_draws_in_a_sandbox_and_an_error_names_its_line_and_keeps_the_last_drawing(browsers):
    async def body(pg):
        await pg.wait_for_function(DRAWN, timeout=60000)
        assert await pg.get_attribute("iframe.gx-page-frame", "sandbox") == "allow-scripts"
        inner = await pg.frames[-1].evaluate("() => { try { return String(document.cookie); } catch (e) { return 'denied'; } }")
        assert inner in ("", "denied")
        await pg.evaluate("() => window.__gallery.pageEditors.js.cm.replaceRange('const a = 1;\\nthrow new Error(\\'boom\\');\\n', {line: 0, ch: 0})")
        await pg.wait_for_selector("[data-page-error]:not([hidden])", timeout=15000)
        text = await pg.inner_text("[data-page-error-text]")
        assert "boom" in text and "Line 2" in text, text
        assert await pg.locator("iframe.gx-page-frame.gx-page-stale").count() == 1
        pg.errors.clear()                                          # 'boom' is the sandbox's own
        await pg.click("[data-action=page-reset]")
        await pg.wait_for_function(DRAWN, timeout=30000)
        assert await pg.locator("iframe.gx-page-stale").count() == 0
    run(browsers, body, "#mode=docs&c=decomposition&tab=page")


def test_every_new_visual_has_a_playground_page(browsers):
    for cid in ("calendar", "sunburst", "icicle"):
        async def body(pg):
            await pg.wait_for_function(DRAWN, timeout=60000)
        run(browsers, body, "#mode=docs&c=" + cid + "&tab=page")


def test_the_decomposition_tree_says_the_diversification_and_opens_a_split(browsers):
    async def body(pg):
        await pg.wait_for_selector(".dt-box-root", timeout=30000)
        await pg.select_option(".dt-col[data-depth='0'] .dt-split", "desk")
        await pg.wait_for_selector(".dt-box[data-index='4']")
        line = await pg.inner_text(".dt-line")
        assert "diversification" in line and "=" in line and "$29.6m" in line, line
        await pg.click(".dt-box[data-depth='0'][data-index='0']")
        await pg.wait_for_selector(".dt-col[data-depth='1'] .dt-box")
        assert await pg.locator(".dt-box-on").count() >= 1 and await pg.locator(".dt-link").count() >= 1
    run(browsers, body, "#mode=playground&c=decomposition")
