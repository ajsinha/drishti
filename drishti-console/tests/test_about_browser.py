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
"""The About page at a phone (390 px): no sideways scroll and finger-sized (44 px) targets, in both themes. One Chromium run against a
console with the stand-in server (port 17958); skipped when Playwright or its Chromium is missing."""
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from test_page_widths_browser import AUDIT, TARGETS  # noqa: E402


@pytest.fixture(scope="module")
def about_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = FakeBackend()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=17958, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield "http://127.0.0.1:17958"
    server.should_exit = True
    thread.join(10)


def test_about_on_a_phone(browser, about_console):
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    page = ctx.new_page()
    try:
        page.goto(about_console + "/about", wait_until="load")
        page.wait_for_timeout(300)
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        assert not page.evaluate(AUDIT)
        small = [t for t in page.evaluate(TARGETS) if "toast" not in t]   # a live alert toast from the stand-in is not the page
        assert not small, small
        assert page.locator("#competitive a[href='/about/competitive']").is_visible()
        page.emulate_media(color_scheme="dark")
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    finally:
        ctx.close()
