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

"""Admin → Data loads → Feeds in a real browser (docs/guides/WORLD_DATA_GUIDE.md section 6): the feeds and the latest runs as console tables
(drishti-grid: sortable headings), the failing badge on the Feeds tab, Run now answered started, then skipped while the feed's lock is
held, and a 390 px phone without sideways scroll. Chromium through Playwright against a console with a stand-in server; skipped when
Playwright or its Chromium is missing."""
import copy
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_admin_feeds import STATUS  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class FeedsBackend(FakeBackend):
    """The server's data feeds, standing in: gleif failed last night, world-economy off; the first Run now starts, the next is skipped."""

    def __init__(self):
        super().__init__()
        self.status = copy.deepcopy(STATUS)
        self.started = []

    async def admin(self, method, path, ident, body=None, timeout=None, **params):
        if path.startswith("/feeds?") and method == "GET":
            return self.status
        if path.startswith("/feeds/") and path.endswith("/run") and method == "POST":
            feed = path.split("/")[2]
            if feed in self.started:
                return {"feed": feed, "started": False, "skipped": f"a run of {feed} is in progress (data/datafeeds/{feed}/run.lock)", "by": "alice"}
            self.started.append(feed)
            return {"feed": feed, "started": True, "pid": 4242, "log": f"data/datafeeds/{feed}/admin-run.log", "by": "alice"}
        if path == "/dataload":
            from core.backend import BackendError
            raise BackendError(404, "DRS-5011", "no such endpoint")
        return await super().admin(method, path, ident, body, **params)


@pytest.fixture(scope="module")
def feeds_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = FeedsBackend()
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


def test_the_feeds_and_runs_are_console_tables_with_the_failing_badge(browser, feeds_console):
    url, _ = feeds_console
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    page.goto(url + "/admin/loads/feeds")
    rows = page.locator("[data-feeds-table] tbody tr[data-feed]")
    rows.first.wait_for()
    assert rows.count() == 2
    assert page.locator("tr[data-feed=gleif]").get_attribute("data-status") == "failed"
    assert "1 request(s) failed after retries" in page.locator("tr[data-feed=gleif] [data-feed-error]").inner_text()
    assert "internal only" in page.locator("tr[data-feed=world-economy]").inner_text()
    assert page.locator("[data-feeds-failing-badge]").inner_text().strip() == "1 failing"
    assert page.locator("[data-feeds-tab]").get_attribute("aria-current") == "page"
    assert page.locator("[data-feeds-runs] tbody tr").count() == 2
    page.locator("[data-feeds-table] thead th", has_text="Feed").first.click()        # drishti-grid's console table: a heading sorts
    page.wait_for_function("() => document.querySelector('[data-feeds-table] thead th[aria-sort]') !== null")
    page.close()


def test_run_now_starts_then_says_a_run_is_in_progress(browser, feeds_console):
    url, backend = feeds_console
    backend.started.clear()
    page = browser.new_page()
    page.goto(url + "/admin/loads/feeds")
    said = page.locator("[data-feeds-said]")
    page.locator("[data-feed-run=gleif]").click()
    said.filter(has_text="started").wait_for()
    assert "gleif started (process 4242)" in said.inner_text() and backend.started == ["gleif"]
    page.goto(url + "/admin/loads/feeds")                                            # before the page's own reload
    page.locator("[data-feed-run=gleif]").click()
    said.filter(has_text="in progress").wait_for()
    assert "a run of gleif is in progress" in said.inner_text()
    assert page.locator("[data-feed-run=gleif]").is_enabled()
    page.close()


def test_a_phone_has_no_sideways_scroll(browser, feeds_console):
    url, _ = feeds_console
    page = browser.new_page(viewport={"width": 390, "height": 844})
    page.goto(url + "/admin/loads/feeds")
    page.locator("[data-feeds-table]").wait_for()
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    page.close()
