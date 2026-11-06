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

"""Export data, EXPORT (DATA works too), in a real browser (docs/guides/USER_GUIDE.md "Export data"): a dataset opened from the address shows its calendar,
its columns all ticked, the count and the read-back the server gives; choosing columns, a date and filter lines rewrites the address, asks the
count with every line, and builds the Download link and the CLI line from the same selection; a selection matching nothing turns Download off.
Runs Chromium through Playwright; skipped when Playwright or its Chromium is missing."""
import socket
import threading
import time
from urllib.parse import parse_qs, urlparse

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_data_view import DATASETS, TVAL  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class DataBackend(FakeBackend):
    counts: list = []

    async def data_datasets(self, ident=None, read_timeout=None):
        return DATASETS

    async def data_dataset(self, kind, ident=None):
        return TVAL

    async def data_count(self, kind, ident, date="", columns="", where=None, read_timeout=None):
        DataBackend.counts.append((date, columns, list(where or [])))
        rows = 0 if any("NOTHING" in w for w in where or []) else 412
        return {"date": date, "columns": (columns or "TRADE_ID,DESK,MTM").split(","), "words": f"From trade-valuation on {date}: {len(where or [])} lines.",
                "sql": "SELECT ...", "pushdown": True, "rows": rows, "total": 12076, "complete": True, "bytes": 1, "gzipBytes": 31000, "read": "x"}


@pytest.fixture(scope="module")
def data_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = DataBackend()
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


def test_the_selection_drives_the_address_the_count_the_download_and_the_cli(browser, data_console):
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(data_console + "/data?kind=trade-valuation&date=2026-09-30")
    page.wait_for_function("() => document.querySelector('[data-rows]').textContent === '412'")
    assert page.locator("[data-colcount]").inner_text() == "3 of 3 · all"
    assert page.locator(".dv-day.on").inner_text() == "30"
    assert "of 12,076 on the date" in page.locator("[data-total]").inner_text() and "≈ 30 KB" in page.locator("[data-size]").inner_text()

    page.locator("label.dv-chip:has-text('DESK') input").uncheck()
    page.locator(".dv-day.has", has_text="29").click()
    page.locator("[data-add]").click()
    page.locator("[data-line='0'] [data-f]").select_option("DESK")
    page.locator("[data-line='0'] [data-v]").fill("DESK-COMM")
    page.locator("[data-add]").click()
    page.locator("[data-line='1'] [data-f]").select_option("MTM")
    page.locator("[data-line='1'] [data-o]").select_option("between")
    page.locator("[data-line='1'] [data-v]").fill("1")
    page.locator("[data-line='1'] [data-v2]").fill("5")
    page.wait_for_function("() => location.search.indexOf('between') > 0")
    q = parse_qs(urlparse(page.url).query)
    assert q == {"kind": ["trade-valuation"], "date": ["2026-09-29"], "columns": ["TRADE_ID,MTM"],
                 "where": ["DESK = DESK-COMM", "MTM between 1 and 5"]}
    page.wait_for_function("() => document.querySelector('[data-words]').textContent.indexOf('2 lines') > 0")
    assert DataBackend.counts[-1] == ("2026-09-29", "TRADE_ID,MTM", ["DESK = DESK-COMM", "MTM between 1 and 5"])
    href = page.locator("[data-download]").get_attribute("href")
    assert href.startswith("/data/trade-valuation/download?date=2026-09-29&columns=TRADE_ID%2CMTM&where=DESK+%3D+DESK-COMM")
    assert page.locator("[data-download]").inner_text() == "Download trade-valuation_2026-09-29.jsonl.gz"
    cli = page.locator("[data-cli]").inner_text()
    assert "data get trade-valuation --date 2026-09-29" in cli and '--where "MTM between 1 and 5"' in cli and "--columns TRADE_ID,MTM" in cli

    page.locator("[data-line='0'] [data-v]").fill("NOTHING")
    page.wait_for_function("() => document.querySelector('[data-download]').getAttribute('aria-disabled') === 'true'")
    assert "no row matches" in page.locator("[data-total]").inner_text()
    assert not errors, errors
    page.close()


def test_views_export_data_opens_the_page_and_both_words_reach_it(browser, data_console):
    """The menu item reads Export data and opens the page; EXPORT is the word and DATA, the older one, still works."""
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    page.goto(data_console + "/t")
    item = page.locator(".tbar-menus a[href='/data']").first
    if not item.is_visible():                                                     # inside its menu: open the menu that holds it
        page.locator(".tbar-menu", has=page.locator("a[href='/data']")).locator(".tbar-menu-btn").first.click()
    assert "Export data" in item.inner_text()
    item.click()
    page.wait_for_url("**/data**")
    assert page.locator("h1.ws-h").text_content().strip().startswith("Export data") and page.title().startswith("Export data")
    for word in ("EXPORT", "DATA"):
        page.goto(data_console + "/go?q=" + word + "%20%3CGO%3E")
        page.wait_for_url("**/data**")
        assert page.locator("h1.ws-h").text_content().strip().startswith("Export data"), word
    page.close()
