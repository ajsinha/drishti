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

"""Admin -> Connectors -> Index in a real browser: the Index section lists kinds by days; Reload on a day shows it rebuilding (progress), then fresh with a
new build time and new content; a second click while one runs says it is already running; an error is shown; on a phone the buttons are 44 px.
Chromium through Playwright against a console with a stand-in server; skipped when Playwright or its Chromium is missing."""
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from core.backend import BackendError  # noqa: E402
from test_admin_connectors_browser import ConnectorBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


class IndexBackend(ConnectorBackend):
    """The connector stand-in plus an index: three days of one kind; a reload keeps a day 'building' for a few reads, then swaps it."""

    def __init__(self):
        super().__init__()
        self.days = {d: {"ids": 100, "tier": "disk" if d != "2026-10-07" else "heap", "builtAt": "2026-10-07T08:00:00Z"} for d in ("2026-10-05", "2026-10-06", "2026-10-07")}
        self.building = {}
        self.reloads = []
        self.fail_next = False

    def view(self):
        for d in list(self.building):
            self.building[d] -= 1
            if self.building[d] < 0:
                del self.building[d]
                self.days[d].update(ids=250, builtAt="2026-10-12T09:30:00Z")
        running = bool(self.building)
        return {"enabled": True,
                "reload": {"running": running, "trigger": "manual", "total": 1, "done": 0, "current": "trade " + next(iter(self.building), ""), "finishedAt": None},
                "auto": {"enabled": True, "everySeconds": 600, "lastCheck": "2026-10-12T09:00:00Z", "nextCheck": "2026-10-12T09:10:00Z", "lastChange": None, "lastChangeWhat": None},
                "days": [{"kind": "trade", "date": d, "ids": v["ids"], "tier": v["tier"], "bytes": v["ids"] * 9, "builtAt": v["builtAt"], "building": d in self.building,
                          "lastError": None} for d, v in sorted(self.days.items(), reverse=True)]}

    async def admin(self, method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/connectors/item-store/index" and method == "GET":
            return self.view()
        if path == "/connectors/item-store/index/reload" and method == "POST":
            if self.building:
                raise BackendError(409, "DRS-5032", "a reload is already running (0 of 1 days)")
            self.reloads.append(body)
            self.building[body["date"]] = 4
            return {**self.view(), "queued": 1}
        return await super().admin(method, path, ident, body, timeout, headers, **params)


@pytest.fixture(scope="module")
def index_console():
    import uvicorn

    from conftest import CONSOLE
    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = IndexBackend()
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


def test_reload_a_day_shows_it_rebuilding_then_fresh_and_a_second_click_is_refused(browser, index_console):
    url, backend = index_console
    page = browser.new_page()
    page.goto(url + "/admin/connectors/item-store")
    panel = page.locator("[data-index-panel]")
    panel.wait_for(state="visible")
    assert page.locator("[data-index-table] tbody tr").count() == 3
    assert "Automatic check every 600 s" in panel.locator("[data-index-auto]").inner_text()
    row = page.locator("[data-index-table] tr[data-day='2026-10-05']")
    assert "100" in row.inner_text() and "disk" in row.inner_text()
    row.get_by_role("button", name="Reload trade 2026-10-05").click()
    page.locator("[data-index-table] tr[data-day='2026-10-05'] [data-state=building]").wait_for()       # rebuilding is shown
    assert "Rebuilding" in panel.locator("[data-index-msg]").inner_text()
    assert page.locator("[data-index-reload]").first.is_disabled()                                         # one at a time
    page.locator("[data-index-table] tr[data-day='2026-10-05'] [data-state=ok]").wait_for(timeout=15000)   # then fresh
    row = page.locator("[data-index-table] tr[data-day='2026-10-05']")
    assert "250" in row.inner_text()
    assert backend.reloads[-1] == {"kind": "trade", "date": "2026-10-05"}
    page.close()


def test_a_second_reload_while_one_runs_says_it_is_already_running(browser, index_console):
    url, backend = index_console
    page = browser.new_page()
    page.goto(url + "/admin/connectors/item-store")
    page.locator("[data-index-panel]").wait_for(state="visible")
    backend.building["2026-10-06"] = 3                                                                     # someone else started one
    result = page.evaluate("fetch('/admin/connectors/api/item-store/index/reload', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({date: '2026-10-07', kind: 'trade'})}).then(r => r.json().then(j => [r.status, j.code, j.detail]))")
    assert result[0] == 409 and result[1] == "DRS-5032" and "already running" in result[2]
    page.close()


def test_on_a_phone_every_index_button_is_44_px(browser, index_console):
    url, backend = index_console
    backend.building.clear()
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    page = ctx.new_page()
    page.goto(url + "/admin/connectors/item-store")
    page.locator("[data-index-panel]").wait_for(state="visible")
    buttons = page.locator("[data-index-panel] button")
    assert buttons.count() >= 5
    for i in range(buttons.count()):
        box = buttons.nth(i).bounding_box()
        assert box["height"] >= 44 and box["width"] >= 44, (i, box)
    ctx.close()
