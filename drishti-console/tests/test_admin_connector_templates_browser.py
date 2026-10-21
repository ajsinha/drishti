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

"""Admin → Connectors → Template updates in a real browser: Add missing kinds merges and the card goes; Remove obsolete kinds asks first; the page
fits a phone in the dark theme with both texts stacked. Chromium through Playwright against a console with a stand-in server; skipped without it."""
import copy
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

START = [{"name": "macro-store", "pack": "world-economy", "missingKinds": ["region-economy"], "newSettings": [], "conflicting": [],
          "obsoleteKinds": [], "additive": True, "etag": "m1", "text": "plugin: delta\nkinds: [macro-indicator]\n",
          "templateText": "plugin: delta\nkinds: [macro-indicator, region-economy]\n"},
         {"name": "risk-store", "pack": "market-risk", "missingKinds": [], "newSettings": [], "conflicting": [], "obsoleteKinds": ["price-basket"],
          "additive": False, "etag": "r1", "text": "plugin: delta\nkinds: [trade-valuation, price-basket]\n" + "settings:\n  root: /a/very/long/path/to/the/lake/that/must/not/widen/the/page\n" * 3,
          "templateText": "plugin: delta\nkinds: [trade-valuation]\n"}]


class Backend:
    def __init__(self):
        self.drifts = copy.deepcopy(START)
        self.calls = []

    async def admin(self, method, path, ident, body=None, timeout=None, headers=None, **params):
        self.calls.append((method, path, headers))
        if path == "/status":
            return {"connectorTemplateDrift": len(self.drifts)}
        if path == "/connectors/template-updates":
            return {"mode": "notify", "count": len(self.drifts), "additive": sum(1 for d in self.drifts if d["additive"]), "notices": [], "drifts": self.drifts}
        if method == "POST" and path.startswith("/connectors/template-updates/"):
            name = path.split("/")[3]
            self.drifts = [d for d in self.drifts if d["name"] != name]
            return {"name": name, "etag": "x", "changed": []}
        return {}


@pytest.fixture()
def tu_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = Backend()
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
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


def test_the_buttons_fix_a_drift_and_the_badge_counts_down(browser, tu_console):
    url, backend = tu_console
    page = browser.new_page()
    asked = []
    page.on("dialog", lambda d: (asked.append(d.message), d.accept()))
    page.goto(url + "/admin/connectors/template-updates")
    assert page.locator("[data-drift-badge]").first.inner_text().startswith("2")
    page.locator("[data-drift='macro-store'] [data-act='add-missing']").click()
    page.wait_for_selector("[data-drift='macro-store']", state="detached", timeout=10000)
    assert ("POST", "/connectors/template-updates/macro-store/add-missing", {"If-Match": "m1"}) in backend.calls
    assert not asked                                                                                   # adding never asks
    page.locator("[data-drift='risk-store'] [data-act='remove-obsolete']").click()
    page.wait_for_selector("[data-none]", timeout=10000)
    assert asked and "price-basket" not in asked[0] and "risk-store" in asked[0]
    assert page.locator("[data-drift-badge]").count() == 0
    page.close()


def test_the_page_fits_a_phone_in_the_dark_theme(browser, tu_console):
    url, _ = tu_console
    ctx = browser.new_context(viewport={"width": 390, "height": 800}, color_scheme="dark", has_touch=True)
    page = ctx.new_page()
    page.goto(url + "/admin/connectors/template-updates")
    assert page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 1")
    boxes = page.locator("[data-drift='risk-store'] .tu-diff figure").evaluate_all("els => els.map(e => e.getBoundingClientRect().top)")
    assert boxes[1] > boxes[0]                                                                         # stacked, not side by side
    colour = page.evaluate("() => getComputedStyle(document.querySelector('.tu-diff pre')).color")
    background = page.evaluate("() => getComputedStyle(document.body).backgroundColor")
    assert colour != background
    ctx.close()
