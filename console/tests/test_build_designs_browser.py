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

"""Build workbench shape extractor, in a real browser: upload by the file picker, a bad file reported without failing the
rest, the tree by keyboard, the filter, a download, and the set surviving a reload.

Runs Chromium through Playwright; skipped when Playwright or its Chromium is not installed (`pip install playwright`
and `playwright install chromium`). The console runs in-process on a free port against the stand-in server."""
import json
from pathlib import Path
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


def test_new_examples_showcase_opens_a_design_page_with_all_21_panels(console_url, browser):
    page = browser.new_page()
    page.goto(console_url + "/build/new#examples")
    page.locator('[data-example="all-panels-showcase"]').click()
    page.wait_for_url("**/build/d/*")
    page.locator("[data-preview] [data-panel]").first.wait_for()
    assert page.locator("[data-preview] [data-panel]").count() == 21
    assert "sutra: all-panels-showcase" in page.locator("[data-yaml]").inner_text()
    assert page.locator("[data-sample]").count() == 1 and "all-panels-showcase.json" in page.locator("[data-samples]").inner_text()
    assert "(copy)" in page.locator("h1").inner_text()
    page.close()


def test_new_folder_of_examples_then_auto_design(console_url, browser):
    folder = Path(__file__).resolve().parents[2] / "docs" / "guides" / "examples"
    page = browser.new_page()
    page.goto(console_url + "/build/new")
    page.set_input_files("[data-folder]", str(folder))
    page.locator("[data-status]").get_by_text("files ready").wait_for()
    assert "not .json or .jsonl left out" in page.locator("[data-status]").inner_text()      # the .md and .yaml files
    assert page.locator("[data-file-list] li").count() >= 10
    page.fill("[data-name]", "From the folder")
    page.get_by_role("button", name="Create design").click()                                  # auto-design is the default
    page.wait_for_url("**/build/d/*")
    assert page.locator("[data-sample]").count() >= 10
    assert "sutra: sample-auto" in page.locator("[data-yaml]").inner_text()
    page.locator("[data-tree] [role=treeitem]").first.wait_for()
    page.locator("[data-preview] .studio-view").wait_for()
    first = page.locator("[data-sample-switch] option").nth(1).get_attribute("value")
    page.select_option("[data-sample-switch]", first)
    page.wait_for_function("document.querySelector('[data-studio-link]').href.includes('sample=')")
    assert "From the folder" in page.locator("h1").inner_text()
    page.get_by_role("link", name="My designs").first.click()
    assert "From the folder" in page.locator("[data-designs]").inner_text()
    page.close()


def test_upload_tree_filter_download_and_reload(console_url, browser, tmp_path):
    good = tmp_path / "trade.json"
    good.write_text(json.dumps({"tradeId": "T-1", "book": "rates", "desk": "north"}))
    bad = tmp_path / "broken.json"
    bad.write_text("{oops")
    page = browser.new_page()
    page.goto(console_url + "/build/new")
    page.set_input_files("[data-files]", [str(good), str(bad)])
    page.locator("[data-status]").get_by_text("2 files ready").wait_for()
    page.locator("[name=start][value=empty]").check()
    page.get_by_role("button", name="Create design").click()
    page.wait_for_url("**/build/d/*")
    items = page.locator("[data-tree] [role=treeitem]")
    items.first.wait_for()
    assert items.count() == 3 and page.locator("[data-counts]").inner_text() == "3 paths"
    items.first.focus()
    page.keyboard.press("ArrowDown")
    assert "book" in page.evaluate("document.activeElement.textContent")
    page.keyboard.press("Enter")                                                              # a leaf's reason, files and examples
    assert page.locator("[data-tree] .bs-detail:visible").count() == 1
    page.fill("[data-filter]", "tradeid")
    assert page.locator("[data-counts]").inner_text() == "1 of 3 paths match"
    with page.expect_download() as d:
        page.get_by_role("link", name="JSON Schema").click()
    assert json.loads(open(d.value.path()).read())["properties"]["tradeId"] == {"type": "string"}
    url = page.url
    page.reload()                                                                             # kept on the server
    page.locator("[data-tree] [role=treeitem]").first.wait_for()
    assert page.url == url and page.locator("[data-tree] [role=treeitem]").count() == 3
    page.close()


def test_a_drop_with_too_many_files_is_refused_in_the_page(console_url, browser, tmp_path):
    files = []
    for i in range(51):
        f = tmp_path / f"f{i}.json"
        f.write_text("{}")
        files.append(str(f))
    page = browser.new_page()
    page.goto(console_url + "/build/new")
    page.set_input_files("[data-files]", files)
    page.locator("[data-status]").get_by_text("at most 50").wait_for()
    page.close()


def test_my_designs_renames_duplicates_and_deletes_in_the_page(console_url, browser):
    page = browser.new_page()
    page.goto(console_url + "/build/new")
    page.fill("[data-name]", "Browser design")
    page.locator("[name=start][value=empty]").check()
    page.get_by_role("button", name="Create design").click()
    page.wait_for_url("**/build/d/*")
    page.goto(console_url + "/build")
    row = page.locator("tr[data-name='Browser design']")
    row.get_by_role("button", name="Rename").click()
    page.get_by_label("New name").fill("Renamed in browser")
    page.get_by_role("button", name="Save").click()
    row = page.locator("tr[data-name='Renamed in browser']")
    row.wait_for()
    row.get_by_role("button", name="Duplicate").click()
    page.locator("tr[data-name='Renamed in browser copy']").wait_for()
    page.on("dialog", lambda dialog: dialog.accept())
    page.locator("tr[data-name='Renamed in browser copy']").get_by_role("button", name="Delete").click()
    page.locator("tr[data-name='Renamed in browser copy']").wait_for(state="detached")
    assert page.locator("tr[data-name='Renamed in browser']").count() == 1
    page.close()


def test_the_old_shape_page_lands_on_new(console_url, browser):
    page = browser.new_page()
    page.goto(console_url + "/build/shape")
    page.wait_for_url("**/build/new#files")
    assert page.locator("[data-new]").count() == 1
    page.close()
