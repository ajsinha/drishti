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

"""Screen Builder shape extractor, in a real browser: upload by the file picker, a bad file reported without failing the
rest, the tree by keyboard, the filter, a download, and the set surviving a reload.

Runs Chromium through Playwright; skipped when Playwright or its Chromium is not installed (`pip install playwright`
and `playwright install chromium`). The console runs in-process on a free port against the stand-in server."""
import json
import socket
import threading
import time

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def console_url():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = FakeBackend()
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


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed: skip, do not fail
            pytest.skip(f"needs Playwright's Chromium (playwright install chromium): {str(e).splitlines()[0]}")
        yield b
        b.close()


def test_upload_tree_filter_download_and_reload(console_url, browser, tmp_path):
    good = tmp_path / "trade.json"
    good.write_text(json.dumps({"tradeId": "T-1", "book": "rates", "desk": "north"}))
    bad = tmp_path / "broken.json"
    bad.write_text("{oops")
    page = browser.new_page()
    page.goto(console_url + "/build/shape")
    page.set_input_files("[data-files]", [str(good), str(bad)])
    status = page.locator("[data-status]")
    status.get_by_text("Shaped 1 sample").wait_for()
    assert "1 file had problems" in status.inner_text()
    assert "Problem: not valid JSON" in page.locator("[data-file-list]").inner_text()

    items = page.locator("[data-tree] [role=treeitem]")
    assert items.count() == 3 and page.locator("[data-counts]").inner_text() == "3 paths"
    items.first.focus()
    page.keyboard.press("ArrowDown")
    assert "book" in page.evaluate("document.activeElement.textContent")
    page.keyboard.press("Enter")                                          # a leaf's reason, files and examples
    assert page.locator("[data-tree] .bs-detail:visible").count() == 1

    page.fill("[data-filter]", "tradeid")
    assert page.locator("[data-counts]").inner_text() == "1 of 3 paths match"
    assert page.locator("[data-tree] [role=treeitem]:visible").count() == 1

    with page.expect_download() as d:
        page.get_by_role("link", name="JSON Schema").click()
    plain = json.loads(open(d.value.path()).read())
    assert plain["properties"]["tradeId"] == {"type": "string"}

    page.reload()                                                          # the set survives
    page.locator("[data-status]").get_by_text("Restored 1 sample").wait_for()
    assert page.locator("[data-tree] [role=treeitem]").count() == 3
    page.close()


def test_a_drop_with_too_many_files_is_refused_in_the_page(console_url, browser, tmp_path):
    files = []
    for i in range(51):
        f = tmp_path / f"f{i}.json"
        f.write_text("{}")
        files.append(str(f))
    page = browser.new_page()
    page.request.delete(console_url + "/build/shape/last")               # the module's console keeps the previous test's set
    page.goto(console_url + "/build/shape")
    # start without a kept set: an earlier test's upload is restored on load, as it should be for a user
    page.evaluate("() => fetch('/build/shape/last', {method: 'DELETE'})")
    page.goto(console_url + "/build/shape")
    page.set_input_files("[data-files]", files)
    page.locator("[data-status]").get_by_text("at most 50").wait_for()
    assert page.locator("[data-result]").is_hidden()
    page.close()


def test_draft_a_screen_shows_sutra_preview_reasons_and_opens_in_studio(console_url, browser, tmp_path):
    f = tmp_path / "trade.json"
    f.write_text(json.dumps({"tradeId": "T-1", "book": "rates"}))
    page = browser.new_page()
    page.goto(console_url + "/build/shape")
    page.set_input_files("[data-files]", [str(f)])
    page.locator("[data-status]").get_by_text("Shaped 1 sample").wait_for()
    page.get_by_role("button", name="Draft a screen").click()
    page.locator("[data-draft]").wait_for(state="visible")
    assert "sutra: sample-auto" in page.locator("[data-draft-yaml]").inner_text()
    assert page.locator("[data-draft-preview] .studio-view").count() == 1
    assert "empty in 4 of 5 samples" in page.locator("[data-draft-pruned]").inner_text()
    assert "tradeId is the id" in page.locator("[data-draft-reasons]").inner_text()
    assert "area" in page.locator("[data-draft-reasons] .bs-alts").inner_text()
    page.locator("[data-draft-studio]").click()
    page.wait_for_url("**/studio?build=1&draft=1")
    assert "sutra: sample-auto" in page.content()
    page.close()
