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

"""The multi-day type-ahead index in a real browser: the command line answers a type-ahead for a past business date at once (its dropdown shows
that day's ids, none of another day's), and a connector's page and the connectors list show the index's days, ids, memory and warm state. Runs
Chromium through Playwright against a console with a stand-in server; skipped when Playwright or its Chromium is missing."""
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_admin_connectors_browser import ConnectorBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

INDEX = "type-ahead index: 5 days (4 on disk, 0.1 MB), 1,200 ids, 0.3 MB, warm"


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class DaysBackend(ConnectorBackend):
    """Suggestions that depend on the business date the stand-in was told (one index segment per date), and a connector whose health names the index."""

    def __init__(self):
        super().__init__()
        self.day = "2026-09-29"
        self.asked = []

    def row(self, name):
        r = super().row(name)
        r["health"] = f"UP ({INDEX})"
        return r

    async def suggest(self, q, user, limit=10):
        self.asked.append(q)
        ids = [f"T-{self.day[-2:]}-{i}" for i in range(3)] + ["T-ALWAYS"]
        return [{"type": "entity", "mnemonic": "TRD", "kind": "trade", "id": i, "title": i, "subtitle": f"trade · desk · {self.day}",
                 "complete": f"TRD {i}"} for i in ids if i.lower().startswith(q.lower().replace("trd ", "").strip())]


@pytest.fixture(scope="module")
def days_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = DaysBackend()
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


def test_a_past_date_type_ahead_answers_at_once_and_health_shows_the_days(browser, days_console):
    url, backend = days_console
    page = browser.new_page()
    page.goto(url + "/admin/connectors")
    assert "type-ahead index: 5 days" in page.locator("[data-list]").inner_text()
    page.goto(url + "/admin/connectors/item-store")
    health = page.locator("[data-health]").inner_text()
    for part in ("5 days", "4 on disk", "1,200 ids", "0.3 MB", "warm"):
        assert part in health, health

    page.goto(url + "/admin/connectors")
    box = page.locator("[data-command] .cmd-input")
    box.click()
    t0 = time.monotonic()
    box.type("TRD T-29")
    page.locator("[data-command] .cmd-list [role=option]").first.wait_for(timeout=3000)
    took = time.monotonic() - t0
    shown = page.locator("[data-command] .cmd-list [role=option]").all_inner_texts()
    assert any("T-29-0" in s for s in shown), shown
    assert not any("T-30" in s for s in shown), shown                       # no other day's ids
    assert took < 2.0, took                                                  # typing plus the answer, not a load
    page.close()
