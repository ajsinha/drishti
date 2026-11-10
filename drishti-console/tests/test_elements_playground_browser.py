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


DRAWS = "() => window.__gallery.pageState().draws"
MORE = "(n) => window.__gallery.pageState().draws > n"
APPEND = "(c) => { const cm = window.__gallery.pageEditors.js.cm; cm.replaceRange(c, {line: cm.lastLine() + 1, ch: 0}); }"
SPY = ("\nconst out = [];\n"
       "try { out.push('cookie=' + document.cookie); } catch (e) { out.push('cookie:denied'); }\n"
       "try { out.push('storage=' + localStorage.length); } catch (e) { out.push('storage:denied'); }\n"
       "try { out.push('parent=' + parent.document.title); } catch (e) { out.push('parent:denied'); }\n"
       "fetch('/api/v1/session').then((r) => 'api=' + r.status, () => 'api:denied')\n"
       "  .then((a) => { out.push(a); document.body.dataset.spy = out.join(' '); });\n")


async def frame_of(pg):
    return await (await pg.query_selector("iframe.gx-page-frame:not(.gx-page-stale)")).content_frame()


def test_edit_the_data_then_the_code_redraws_a_break_keeps_the_last_drawing_and_reset_restores(browsers):
    async def body(pg):
        await pg.wait_for_function(DRAWN, timeout=60000)
        n = await pg.evaluate(DRAWS)
        col = await pg.evaluate("() => { const g = window.__gallery.pageGrid; return g.model.shownColumns().findIndex((c) => typeof window.__gallery.pageRows()[0][c.field] === 'number'); }")
        assert col >= 0
        await pg.evaluate("(c) => window.__gallery.pageGrid.focusCell(0, c)", col)
        await pg.keyboard.type("4242")
        await pg.keyboard.press("Enter")
        await pg.wait_for_function("() => window.__gallery.pageEditors.js.getValue().includes('4242')", timeout=15000)
        await pg.wait_for_function(MORE, arg=n, timeout=30000)
        n = await pg.evaluate(DRAWS)
        await pg.evaluate(APPEND, "\ndocument.body.dataset.edited = 'yes';\n")
        await pg.wait_for_function(MORE, arg=n, timeout=30000)
        assert await (await frame_of(pg)).evaluate("() => document.body.dataset.edited") == "yes"
        await pg.evaluate("() => window.__gallery.pageEditors.js.cm.replaceRange('undefinedThing();\\n', {line: 0, ch: 0})")
        await pg.wait_for_selector("[data-page-error]:not([hidden])", timeout=15000)
        assert "Line 1" in await pg.inner_text("[data-page-error-text]")
        assert await pg.locator("iframe.gx-page-frame").count() == 1                 # the last good drawing stays, greyed
        assert await pg.locator("iframe.gx-page-frame.gx-page-stale").count() == 1
        pg.errors.clear()
        await pg.click("[data-action=page-reset]")
        await pg.wait_for_function(DRAWN, timeout=30000)
        assert "4242" not in await pg.evaluate("() => window.__gallery.pageEditors.js.getValue()")
        assert await (await frame_of(pg)).evaluate("() => document.body.dataset.edited || ''") == ""
    run(browsers, body, "#mode=docs&c=chart&tab=page")


def test_the_sandbox_reaches_neither_the_cookie_nor_the_storage_nor_the_page_nor_the_api(browsers):
    fetched = []

    async def body(pg):
        await pg.wait_for_function(DRAWN, timeout=60000)
        n = await pg.evaluate(DRAWS)
        await pg.evaluate(APPEND, SPY)
        await pg.wait_for_function(MORE, arg=n, timeout=30000)
        f = await frame_of(pg)
        await f.wait_for_function("() => document.body.dataset.spy", timeout=15000)
        spy = await f.evaluate("() => document.body.dataset.spy")
        assert spy == "cookie:denied storage:denied parent:denied api:denied", spy
        pg.errors.clear()                                          # the policy's own refusal of the fetch
    run(browsers, body, "#mode=docs&c=chart&tab=page", fetched=fetched)
    assert not [u for u in fetched if u.startswith("/api")], fetched


def test_every_visual_has_a_playground_page_that_draws(browsers):
    async def body(pg):
        ids = await pg.evaluate("() => window.__gallery.demos.filter((d) => d.kind === 'element' || d.page).map((d) => d.id)")
        assert {"calendar", "sunburst", "icicle", "decomposition", "render", "chart", "grid", "graph"} <= set(ids)
        for cid in ids:
            await pg.click(f".gx-nav-item[data-c='{cid}']")
            await pg.wait_for_selector(f"[data-page-live='{cid}']", timeout=15000)
            try:
                await pg.wait_for_function(DRAWN, timeout=30000)
            except Exception as e:
                raise AssertionError((cid, await pg.evaluate("() => (document.querySelector(\"[data-page-error-text]\") || {}).textContent"))) from e
    run(browsers, body, "#mode=docs&c=chart&tab=page")


def test_the_decomposition_tree_says_the_diversification_and_opens_a_split(browsers):
    async def body(pg):
        await pg.wait_for_selector(".dt-box-root", timeout=30000)
        await pg.click(".dt-col[data-depth='0'] .dt-split-opt[data-field=desk]")
        await pg.wait_for_selector(".dt-box[data-index='4']")
        line = await pg.inner_text(".dt-line")
        assert "diversification" in line and "=" in line and "$29.6m" in line, line
        await pg.click(".dt-box[data-depth='0'][data-index='0']")
        await pg.wait_for_selector(".dt-col[data-depth='1'] .dt-box")
        assert await pg.locator(".dt-box-on").count() >= 1 and await pg.locator(".dt-link").count() >= 1
    run(browsers, body, "#mode=playground&c=decomposition")
