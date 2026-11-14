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
"""A module's console part in Chromium (KERNEL_AND_MODULES.md sections 4.7, 9, 12; mock-up Module removed A), on a test console over a
stand-in of the server's module registry (tests/module_parts_fake.py: the SDK archetype as the module ``greeter``):

- installed: its page draws in the kernel shell, its menu entry and Home card are there, Help has its section, Admin -> AI lists its tool;
- removed: the page is the Module removed A page (410), the menu, card, Help section and tool are gone, an open page of it is frozen under
  the notice, and the saved Sutra that uses it says "needs module greeter";
- reinstalled: all of it is back;
- the economy module's World page (the World lens's Home) is drawn from the module's own folder, its lists on the Elements grid.

Skipped when Playwright or its Chromium is missing."""
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import FakeBackend  # noqa: E402
from module_parts_fake import Registry  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


@pytest.fixture(scope="module")
def console(tmp_path_factory):
    import uvicorn

    from conftest import CONSOLE
    from core.app import create_app
    from core.config import load_settings
    from core.module_parts import ModuleParts

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = FakeBackend()
    reg = Registry()
    reg.install(app.state.backend, None)
    old = app.state.module_parts
    app.state.module_parts = app.state.editions.modules.parts = ModuleParts(tmp_path_factory.mktemp("parts"), old.web, old.shipped, 0.0)
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)   # wait-ok: the console thread starting
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}", reg, app
    server.should_exit = True
    thread.join(10)


def _menu(page) -> list[str]:
    return page.eval_on_selector_all("a.mega-item", "els => els.map(e => e.getAttribute('href'))")


def test_install_remove_and_reinstall_in_the_browser(browser, console):
    url, reg, _ = console
    page = browser.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    try:
        reg.state = "active"
        r = page.goto(url + "/m/greeter/hello/asha")
        assert r.status == 200
        assert page.locator("meta[name=drishti-module-page][content=greeter]").count() == 1
        assert page.locator(".drishti-card").inner_text().startswith("Hello, asha")
        assert "/m/greeter/hello/world" in _menu(page)                                   # its menu entry
        page.goto(url + "/")
        assert page.locator('[data-home-card="GREETER world"]').count() == 1              # its Home card
        page.goto(url + "/help")
        assert page.locator('a[href="/help/section/module-greeter"]').count() >= 1      # its Help
        page.goto(url + "/admin/ai")
        assert page.locator('[data-ai-module-tool="greeter_greet"]').count() == 1         # its AI tool

        page.goto(url + "/m/greeter/hello/asha")                                         # a page of it stays open while it goes
        reg.state = "removed"
        page.evaluate("Drishti.moduleGoneCheck()")
        page.locator('[data-module-gone="greeter"]').wait_for()
        assert "no longer installed" in page.locator('[data-module-gone="greeter"]').inner_text()

        r = page.goto(url + "/m/greeter/hello/asha")                                     # Module removed A
        assert r.status == 410
        notice = page.locator(".mod-notice").inner_text()
        assert "greeter is no longer installed on this system" in notice and "needs module greeter" in notice
        assert page.get_by_role("link", name="Go to Home").count() == 1
        assert "/m/greeter/hello/world" not in _menu(page)
        page.goto(url + "/")
        assert page.locator('[data-home-card="GREETER world"]').count() == 0
        page.goto(url + "/help")
        assert page.locator('a[href="/help/section/module-greeter"]').count() == 0
        page.goto(url + "/admin/ai")
        assert page.locator('[data-ai-module-tool="greeter_greet"]').count() == 0
        page.goto(url + "/build?type=sutras")                                             # the saved Sutra: kept and marked
        row = page.locator('tr[data-item="sutras:greetings-board"]')
        assert "needs module greeter" in row.inner_text() and row.locator("a").count() == 0

        reg.state = "active"                                                              # reinstalled: restored
        page.goto(url + "/build?type=sutras")
        assert page.locator('tr[data-item="sutras:greetings-board"] [data-needs-module]').count() == 0
        assert page.goto(url + "/m/greeter/hello/asha").status == 200
        assert "/m/greeter/hello/world" in _menu(page)
        assert not errors, errors
    finally:
        page.close()


def test_the_world_page_comes_from_the_economy_module(browser, console):
    url, _, app = console
    from test_edition import FULL_ABOUT
    app.state.editions.learn(FULL_ABOUT)                                                    # the World lens of the full product: its Home is the module's page
    store = {app.state.auth.default.user: {"lens": "world"}}                                 # the person works in the World lens

    async def settings(ident=None):
        return dict(store.get(ident.user if ident else "", {}))

    app.state.backend.settings = settings
    page = browser.new_page()
    try:
        page.goto(url + "/")
        assert page.locator("[data-world-landing]").count() == 1
        assert page.locator('script[src^="/m/economy/static/"]').count() == 1
        assert page.locator("[data-grid=moved][data-elements=grid]").count() == 1        # the Elements grid, no table of its own
        assert page.locator(".wl table").count() == 0
    finally:
        page.close()
