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

"""Sample packs in a real browser: a developer (author or admin) is shown a sample pack in the pack menu and on Admin → Packs; a business user
is not (the stand-in server filters as the real one does); "Hide samples from business users" is sent and the page shows the new mode.
Runs Chromium through Playwright; skipped when Playwright or its Chromium is missing."""
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class SampleBackend(FakeBackend):
    """A stand-in server with one sample pack that only developers are told about, and the mode an administrator saved."""

    def __init__(self):
        super().__init__()
        self.developer = True
        self.mode = "visible"
        self.saved = []

    def _visible(self):
        return self.developer or self.mode == "visible"

    async def packs(self, ident=None):
        rows = [{"name": "finance", "version": "1.0.0", "title": "Finance", "description": "", "console": {}, "assigned": True, "active": True},
                {"name": "trading", "version": "1.0.0", "title": "Trading", "description": "", "console": {}, "assigned": True, "active": True}]
        if self._visible() and self.mode != "hidden":
            rows.append({"name": "genomics", "version": "1.0.0", "title": "Genomics", "description": "", "console": {}, "sample": True, "assigned": True,
                         "active": True})
        return rows

    async def admin(self, method, path, ident, body=None, timeout=None, **params):
        if path == "/packs" and method == "GET":
            return [{"name": "finance", "title": "Finance", "description": "", "version": "1.0.0", "loaded": True, "added": False, "enabled": True, "sample": False,
                     "extends": [], "requiredBy": [], "kinds": ["trade"], "connectors": [], "mnemonics": ["TRD"]},
                    {"name": "genomics", "title": "Genomics", "description": "", "version": "1.0.0", "loaded": True, "added": True, "enabled": True, "sample": True,
                     "extends": [], "requiredBy": [], "kinds": ["gene"], "connectors": [], "mnemonics": ["GENE"]}]
        if path == "/packs/samples" and method == "GET":
            return {"mode": self.mode, "configured": "visible", "overridden": self.mode != "visible", "modes": ["visible", "developers", "hidden"],
                    "samples": ["genomics"]}
        if path == "/packs/samples" and method == "PUT":
            self.saved.append(body["mode"])
            self.mode = body["mode"]
            return {"mode": self.mode, "samples": ["genomics"]}
        if path == "/registry":
            return {"url": "", "configured": False, "packs": []}
        if path == "/packs/history":
            return {"history": [], "kept": {}}
        return await super().admin(method, path, ident, body, **params)


@pytest.fixture(scope="module")
def samples_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = SampleBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}", app
    server.should_exit = True
    thread.join(10)


def _pack_menu(page) -> str:
    page.locator("button[aria-label='Packs shown']").click()
    return page.locator("form[data-packs]").inner_text()


def test_an_author_sees_the_sample_pack_and_a_business_user_does_not(browser, samples_console):
    url, app = samples_console
    backend = app.state.backend
    backend.mode = "developers"
    backend.developer = True                                       # the server tells an author about the sample pack
    app.state.packs.forget_all()
    page = browser.new_page()
    page.goto(url + "/admin/packs")
    assert "Genomics" in _pack_menu(page)
    assert page.locator("[data-sample-badge]").count() == 1                                        # marked Sample on Admin -> Packs
    assert "developers only" in page.locator("[data-samples]").inner_text()
    page.close()

    backend.developer = False                                      # a business user: the server leaves it out, exactly as a pack switched off
    app.state.packs.forget_all()
    page = browser.new_page()
    page.goto(url + "/admin/packs")
    menu = _pack_menu(page)
    assert "Finance" in menu and "Trading" in menu and "Genomics" not in menu
    page.close()


def test_hide_samples_from_business_users_is_sent_and_shown(browser, samples_console):
    url, app = samples_console
    backend = app.state.backend
    backend.mode, backend.developer, backend.saved = "visible", True, []
    app.state.packs.forget_all()
    page = browser.new_page()
    page.goto(url + "/admin/packs")
    assert "visible to everyone" in page.locator("[data-samples]").inner_text()
    page.locator("button[data-samples-set='developers']").click()
    page.wait_for_function("() => document.querySelector('[data-samples]').innerText.includes('developers only')", timeout=15000)   # the page reloads
    assert backend.saved == ["developers"]
    assert page.locator("button[data-samples-set='developers']").count() == 0                       # the current mode has no button
    page.close()
