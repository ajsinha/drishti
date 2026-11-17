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

"""Admin -> Modules (mock-up C) in Chromium, over the server's recorded answer (tests/answers/admin-modules.json, served by the stand-in
backend): the tiles, the nodes-disagree banner and its copy button, the differing cell in red, a module's detail on a click, the filter chips
on the grid's own filter box, the install steps; and on a phone (390 wide) no sideways page scroll, the matrix scrolling inside its card."""
import threading
import time

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _free_port  # noqa: E402


@pytest.fixture(scope="module")
def url():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = FakeBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)   # wait-ok: the console thread starting
    assert server.started
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed
            pytest.skip(f"Chromium is not installed: {e}")
        yield b
        b.close()


def _open(browser, url, width, height):
    ctx = browser.new_context(viewport={"width": width, "height": height}, is_mobile=width < 500, has_touch=width < 500)
    pg = ctx.new_page()
    errors = []
    pg.on("pageerror", lambda e: errors.append(str(e)))
    pg.goto(url + "/admin/modules")
    pg.wait_for_selector("[data-modules-table]")
    return ctx, pg, errors


def test_the_modules_page_on_a_desktop(url, browser):
    ctx, pg, errors = _open(browser, url, 1366, 900)
    try:
        tiles = {t.get_attribute("data-tile"): t.inner_text() for t in pg.query_selector_all("[data-tile]")}
        assert list(tiles) == ["modules", "nodes", "fingerprint", "problems", "stale", "notLoaded"]
        assert "10" in tiles["modules"] and "2 / 3" in tiles["nodes"] and "b1ac0a79" in tiles["fingerprint"] and "1" in tiles["stale"]
        banner = pg.inner_text("[data-modules-banner]")
        assert "Nodes disagree" in banner and "trading" in banner and "drishti-c" in banner
        assert pg.is_visible("[data-copy-fix]")
        diff = pg.query_selector_all('[data-module="trading"] [data-differs="true"] .mod-cell')
        assert len(diff) == 1 and "dcc296b5" in diff[0].inner_text()
        same = pg.query_selector('[data-module="trading"] [data-cell="drishti-main"] .mod-cell')
        red = lambda e: e.evaluate("e => getComputedStyle(e).color")           # noqa: E731
        assert red(diff[0]) != red(same)
        # the detail follows a click
        assert pg.is_visible('[data-module-detail="banking"]') and not pg.is_visible('[data-module-detail="trading"]')
        pg.click('[data-pick="trading"]')
        assert pg.is_visible('[data-module-detail="trading"]') and not pg.is_visible('[data-module-detail="banking"]')
        assert "0035e3be" in pg.inner_text('[data-module-detail="trading"]') and "differs" in pg.inner_text('[data-module-detail="trading"]')
        # the chips filter the grid
        pg.click('[data-filter="problem"]')
        pg.wait_for_function("() => document.querySelectorAll('[data-modules-table] tbody tr:not([hidden])').length === 3")
        shown = pg.eval_on_selector_all("[data-modules-table] tbody tr:not([hidden])", "rs => rs.map(r => r.getAttribute('data-module'))")
        assert sorted(shown) == ["capitalmarkets", "source-swapone", "trading"]
        pg.click('[data-filter=""]')
        pg.wait_for_function("() => document.querySelectorAll('[data-modules-table] tbody tr:not([hidden])').length === 10")
        assert "module verify" in pg.inner_text("[data-install-text]")
        assert not errors, errors
    finally:
        ctx.close()


def test_the_modules_page_fits_a_phone(url, browser):
    ctx, pg, errors = _open(browser, url, 390, 844)
    try:
        wide = pg.evaluate("document.documentElement.scrollWidth - window.innerWidth")
        assert wide <= 1, f"{wide} px of horizontal page scroll"
        m = pg.query_selector(".mod-matrix")
        assert m.evaluate("e => e.scrollWidth > e.clientWidth") is True                 # the matrix scrolls inside its card
        assert m.evaluate("e => e.getBoundingClientRect().right") <= 391
        assert pg.is_visible("[data-modules-banner]")
        assert not errors, errors
    finally:
        ctx.close()
