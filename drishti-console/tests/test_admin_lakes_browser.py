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

"""Admin → Data loads → Lakes in a real browser: a synthetic export uploaded with progress, its fields scanned with the id, date and
classifier suggested, a new lake built with the CLI's engine (deltalake), the connector bound and each date announced, the summary with a
link to open a record; and the page at phone width without sideways scrolling. Chromium through Playwright against a console with a
stand-in server; skipped when Playwright, its Chromium or deltalake is missing."""
import importlib.util
import json
import socket
import threading
import time
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
if importlib.util.find_spec("deltalake") is None:
    pytest.skip("the build needs deltalake and pyarrow", allow_module_level=True)

from conftest import CONSOLE, FakeBackend  # noqa: E402
from core.lakes import Lakes, LakeSettings  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

REPO = Path(__file__).resolve().parents[2]


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class LakesBackend(FakeBackend):
    def __init__(self):
        super().__init__()
        self.puts, self.announced = [], []

    async def admin(self, method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/connectors" and method == "GET":
            return {"connectors": [{"name": "book-store", "plugin": "delta", "kinds": ["deal"], "state": "RUNNING", "origin": "pack",
                                    "usedBy": [{"pack": "synth-book", "kinds": ["deal"]}]}]}
        if path == "/connectors/book-store" and method == "GET":
            return {"text": None, "packDefault": "plugin: delta\nkinds: [deal]\nsettings:\n  root: x\n  domain: book\n", "etag": None}
        if path == "/connectors/book-store" and method == "PUT":
            self.puts.append(body)
            return {"ok": True}
        return await super().admin(method, path, ident, body, **params)

    async def announce_load(self, pack, body, ident):
        self.announced.append(body)
        return {"verified": "verified"}


@pytest.fixture(scope="module")
def lakes_console(tmp_path_factory):
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    root = tmp_path_factory.mktemp("lakes") / "lake"
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = LakesBackend()
    app.state.lakes = Lakes(LakeSettings(roots=[root], tools_dir=REPO / "tools", engine="deltalake"))
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}", app.state.backend, root
    server.should_exit = True
    thread.join(10)


def book(n=300):
    return [{"TRADE_ID": 900 + i, "TRADE_DATE": "2026-10-16", "PRODUCT_TYPE": ["ComSwap", "ComFut", "Cash"][i % 3], "PORTFOLIO": f"PF-{i % 4}",
             "QUANTITY": 100.0 * i, "LEGS": [{"LEG_ID": 1, "CASH_FLOWS": [{"AMOUNT": i}]}]} for i in range(n)]


def test_a_file_becomes_a_lake_bound_and_announced(browser, lakes_console, tmp_path):
    url, backend, root = lakes_console
    src = tmp_path / "book.json"
    src.write_text(json.dumps(book(), indent=2), encoding="utf-8")
    page = browser.new_page()
    page.goto(url + "/admin/lakes")
    page.locator("[data-file]").set_input_files(str(src))
    page.locator("[data-step=fields]").wait_for(state="visible", timeout=30000)
    assert page.locator("[data-key]").input_value() == "TRADE_ID"
    assert page.locator("[data-date]").input_value() == "TRADE_DATE"
    assert page.locator("[data-pick=classifier][value=PRODUCT_TYPE]").is_checked()
    assert "Read 300 documents" in page.locator("[data-scan-note]").inner_text()
    page.locator("[data-pick=promote][value=QUANTITY]").check()
    page.locator("[data-step=target]").wait_for(state="visible")
    page.locator("[data-domain]").fill("comswap")
    page.locator("[data-kind]").fill("deal")
    page.locator("[data-run]").click()
    page.locator("[data-result] .loads-sum").first.wait_for(timeout=120000)
    result = page.locator("[data-result]").inner_text()
    assert "Loaded 300 rows from 300 documents" in result and "engine deltalake" in result
    assert "Connector book-store created" in result and "Load of 2026-10-16 announced (verified)" in result
    assert page.locator("[data-result] table tbody tr").count() == 3                       # one row per classifier value
    link = page.locator("[data-result] a.btn-pill")
    assert link.get_attribute("href").startswith("/v/deal/9") and "asOf=2026-10-16" in link.get_attribute("href")
    assert (root / "comswap" / "deal" / "business_date=2026-10-16" / "PRODUCT_TYPE=ComSwap").is_dir()
    assert "root: " + str(root) in backend.puts[0]["text"] and [a["businessDate"] for a in backend.announced] == ["2026-10-16"]
    page.close()


def test_phone_width_has_no_sideways_scroll(browser, lakes_console):
    url, _, _ = lakes_console
    page = browser.new_page(viewport={"width": 390, "height": 844})
    page.goto(url + "/admin/lakes")
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    box = page.locator("[data-file]").bounding_box()
    assert box and box["height"] >= 40
    page.close()
