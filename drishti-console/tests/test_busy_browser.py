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

"""Busy protection in a real browser (docs/guides/OPERATIONS_GUIDE.md, "Busy protection"; mock-up A): a download the server asks to wait comes
back to the Download card as the calm message with a ring counting down the server's Retry-After and Try now, Cancel and Copy CLI line;
when the time is up it starts the download again by itself, and the server lets it in. Admin -> Health draws the Busy protection card with
its bars (widths set without inline style) and the operator override. Runs Chromium through Playwright; skipped when it is missing."""
import os
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from core.backend import BackendError  # noqa: E402
from test_busy import HEALTH_BUSY, _busy  # noqa: E402
from test_data_view import DATASETS, TVAL, _Upstream  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

SHOTS = os.environ.get("DRISHTI_BUSY_SHOTS", "")       # a folder: the two screens are saved there (for the docs), else not


class BusyBackend(FakeBackend):
    refusals = 0
    downloads = 0

    async def data_datasets(self, ident=None, read_timeout=None):
        return DATASETS

    async def data_dataset(self, kind, ident=None):
        return TVAL

    async def data_count(self, kind, ident, date="", columns="", where=None, read_timeout=None):
        return {"date": date, "columns": ["TRADE_ID", "DESK", "MTM"], "words": f"From trade-valuation on {date}: 3 columns.", "sql": "SELECT ...",
                "pushdown": True, "rows": 412, "total": 12076, "complete": True, "bytes": 1, "gzipBytes": 31000, "read": "x"}

    async def open_download(self, path, ident, read_timeout=None):
        if BusyBackend.refusals > 0:
            BusyBackend.refusals -= 1
            raise _busy(wait="2")
        BusyBackend.downloads += 1
        return _Upstream()

    async def admin(self, method, path, ident, body=None, **params):
        if path == "/connectors":
            return {"connectors": []}
        if path == "/health":
            return {"status": "OK", "summary": {"sources": 0, "sourcesDown": 0, "failedToStart": 0, "packs": 0, "packsWithProblems": 0},
                    "server": {"version": "dev", "uptimeSeconds": 60, "java": "21", "heapUsedMb": 300, "heapMaxMb": 4000, "threads": 60, "cpus": 8},
                    "sources": [], "packs": [], "live": {}, "tmp": {}, "busy": HEALTH_BUSY}
        raise BackendError(404, "DRS-1001", path)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def busy_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = BusyBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


def test_a_download_that_must_wait_counts_down_in_its_card_and_starts_by_itself(browser, busy_console):
    page = browser.new_page(viewport={"width": 1440, "height": 1000}, accept_downloads=True)
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(busy_console + "/data?kind=trade-valuation&date=2026-09-30")
    page.wait_for_function("() => document.querySelector('[data-rows]').textContent === '412'")
    BusyBackend.refusals, BusyBackend.downloads = 1, 0
    page.locator("[data-download]").click()
    card = page.locator("[data-busy-host] .busy-card")
    card.wait_for()
    assert page.locator("[data-busy-host] .busy-title").inner_text() == "The download did not start"
    text = page.locator("[data-busy-host] .busy-text").inner_text()
    assert text.startswith("The server is short of memory: 93% in use after clean-up") and "We will try again in" in text
    assert "your selection is kept" in text
    assert page.locator("[data-busy-host] .busy-ring span").inner_text() in ("0:02", "0:01")
    assert page.locator("[data-busy-try]").is_visible() and page.locator("[data-busy-cancel]").is_visible()
    assert page.locator("[data-busy-host] .busy-ref").inner_text() == "DRS-9503 · try again after 2 s"
    assert page.locator("[data-busy-host] button:has-text('Copy CLI line')").is_visible()
    assert "date=2026-09-30" in page.url and "busy=" not in page.url        # the address keeps the selection only: a reload is a plain page
    if SHOTS:
        page.locator(".dv-dl-card").screenshot(path=os.path.join(SHOTS, "busy-download.png"))
    with page.expect_download(timeout=10_000) as dl:                    # the countdown ends: the download starts again by itself
        pass
    assert dl.value.suggested_filename.endswith(".jsonl.gz")
    assert BusyBackend.downloads == 1
    page.wait_for_function("() => document.querySelector('[data-busy-host]').textContent.indexOf('has started') >= 0", timeout=8000)
    assert not errors, errors
    page.close()


def test_cancel_stops_the_countdown_and_the_card_goes(browser, busy_console):
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    BusyBackend.refusals = 1
    page.goto(busy_console + "/data?kind=trade-valuation&date=2026-09-30")
    page.wait_for_function("() => document.querySelector('[data-rows]').textContent === '412'")
    page.locator("[data-download]").click()
    page.locator("[data-busy-cancel]").click()
    assert page.locator("[data-busy-host] .busy-card").count() == 0
    time.sleep(2.5)
    assert BusyBackend.refusals == 0 and page.locator("[data-busy-host] .busy-card").count() == 0
    page.close()


def test_health_draws_the_busy_protection_card(browser, busy_console):
    page = browser.new_page(viewport={"width": 1280, "height": 1400})
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(busy_console + "/admin/health")
    card = page.locator("[data-health-busy]")
    card.wait_for()
    assert "new heavy work waits" in page.locator("[data-busy-badge]").inner_text()
    width = page.locator("[data-busy-gauge='memory'] .busy-bar i").evaluate("e => e.style.width")
    assert width == "93%"
    assert page.locator("[data-busy-refusals] tbody tr").count() == 1
    assert page.locator("[data-busy-override] input[value=hold]").count() == 1
    page.locator("[data-busy-override] input[value=hold]").check()
    page.locator("[data-busy-apply]").click()
    assert page.locator("[data-busy-said]").inner_text() == "A reason is required."
    if SHOTS:
        card.screenshot(path=os.path.join(SHOTS, "busy-health.png"))
        page.locator("[data-busy-override]").screenshot(path=os.path.join(SHOTS, "busy-override.png"))
    assert not errors, errors
    page.close()
