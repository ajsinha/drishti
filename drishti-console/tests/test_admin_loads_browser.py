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

"""Admin → Packs → Data loads in a real browser: the history with each load's step results, the expectations with their state, the
filters, the settings dialog (saved and reset), the keyboard keys, phone width and the dark theme. Runs Chromium through Playwright against a
console with a stand-in server; skipped when Playwright or its Chromium is missing."""
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_admin_loads import CONFIG, EXPECT, FAILED, LOAD  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class LoadsBackend(FakeBackend):
    def __init__(self):
        super().__init__()
        self.saved = None
        self.reset = 0

    async def admin(self, method, path, ident, body=None, timeout=None, **params):
        if path == "/loads/trading":
            rows = [LOAD, FAILED]
            if params.get("status"):
                rows = [r for r in rows if r["status"] == params["status"]]
            return {"pack": "trading", "kinds": ["trade", "curve"], "loads": rows}
        if path == "/loads/trading/expectations":
            return EXPECT
        if path == "/loads/trading/config" and method == "GET":
            return CONFIG
        if path == "/loads/trading/config" and method == "PUT":
            self.saved = body
            return CONFIG
        if path == "/loads/trading/config" and method == "DELETE":
            self.reset += 1
            return CONFIG
        return await super().admin(method, path, ident, body, **params)


@pytest.fixture(scope="module")
def loads_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = LoadsBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}", app.state.backend
    server.should_exit = True
    thread.join(10)


def test_history_steps_and_expectations_are_readable_and_filterable(browser, loads_console):
    url, _ = loads_console
    page = browser.new_page()
    page.goto(url + "/admin/packs/trading/loads")
    table = page.locator("[data-loads-table]")
    assert table.locator("tbody tr").count() == 2
    first = table.locator("tr[data-load='k1-1']")
    assert "48,213" in first.inner_text() and "verified" in first.inner_text() and "eod-1" in first.inner_text()
    assert not first.locator(".loads-steps ol").is_visible()                                       # step results are folded away until asked for
    first.locator(".loads-steps summary").click()
    steps = first.locator(".loads-steps ol").inner_text()
    assert "16 trade entities on 2026-10-06" in steps and "verify" in steps and "skipped" in steps
    states = page.locator("[data-expectations] tbody tr")
    assert states.count() == 2 and "late" in states.nth(0).inner_text() and "on-time" in states.nth(1).inner_text()
    page.select_option("select[name=status]", "failed")
    page.click(".loads-filter button[type=submit]")
    page.wait_for_url("**status=failed**")
    assert page.locator("[data-loads-table] tbody tr").count() == 1
    page.locator("[data-loads-table] .loads-steps summary").click()
    assert "FAILED: disk full" in page.locator("[data-loads-table]").inner_text()
    page.close()


def test_settings_are_edited_saved_and_reset_from_the_keyboard(browser, loads_console):
    url, backend = loads_console
    page = browser.new_page()
    page.goto(url + "/admin/packs/trading/loads")
    page.keyboard.press("/")
    assert page.evaluate("document.activeElement.hasAttribute('data-filter-kind')")                # "/" goes to the filter
    page.evaluate("document.activeElement.blur()")
    page.keyboard.press("e")
    dialog = page.locator("[data-config-dialog]")
    dialog.wait_for()
    assert page.evaluate("document.activeElement.name") == "roles"                                 # focus lands in the dialog
    dialog.locator("[name=roles]").fill("admin, risk-ops")
    dialog.locator("[data-expect-add]").click()
    new = dialog.locator("[data-expect-rows] tbody tr").nth(1)
    new.locator("[name=x-kind]").select_option("curve")
    new.locator("[name=x-by]").fill("08:30")
    new.locator("[name=x-zone]").fill("Europe/London")
    dialog.locator("[name=email]").check()
    dialog.locator("[data-config-save]").click()
    page.wait_for_load_state("load")
    deadline = time.monotonic() + 10
    while backend.saved is None and time.monotonic() < deadline:
        time.sleep(0.05)
    assert backend.saved == {"notify": {"roles": ["admin", "risk-ops"], "users": [], "email": True}, "smoke": 0,
                             "expect": {"trade": {"by": "19:00", "zone": "America/New_York", "calendar": "USNY"}, "curve": {"by": "08:30", "zone": "Europe/London"}}}
    page.goto(url + "/admin/packs/trading/loads")                                                   # the page reloads itself after a save
    page.keyboard.press("e")
    page.locator("[data-config-dialog]").wait_for()
    page.locator("[data-config-reset]").click()
    deadline = time.monotonic() + 10
    while backend.reset == 0 and time.monotonic() < deadline:
        time.sleep(0.05)
    assert backend.reset == 1
    page.close()


@pytest.mark.parametrize("width,theme", [(390, "light"), (1280, "dark")])
def test_the_page_fits_a_phone_and_both_themes(browser, loads_console, width, theme):
    url, _ = loads_console
    page = browser.new_page(viewport={"width": width, "height": 800}, color_scheme=theme)
    page.goto(url + "/admin/packs/trading/loads")
    page.locator("[data-loads-table]").wait_for()
    assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")      # no sideways page scroll
    for bad in page.locator("[data-loads-table] .st-bad").all():
        assert bad.inner_text().strip()                                                                # a state always has its word
    page.close()
