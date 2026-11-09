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
"""Admin → Data loads → Upload in a browser: a file picked from the inbox folder, the form filling from its name, the exact .ready, a refusal
blocking Push, the one-click fix, the tick a replace needs, Push and the confirmation; a tar's read-only tables; two people's forms apart."""
import re
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from playwright.sync_api import expect  # noqa: E402

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_dataload_inbox import QUIET  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

FILE = "trade_endur.2026-11-06.jsonl.gz"


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


CHOICES = {"on": True, "owner": True, "node": "drishti-main", "nextCycleText": "14:50", "maxGb": 10, "sampleRows": 10000, "freeBytes": 1 << 40,
           "accepted": [".jsonl", ".gz", ".zip", ".tar", ".tar.gz"], "newTables": False, "defaultLake": "risklake", "lakes": ["demo", "risklake"],
           "tables": [{"table": "trade_endur", "kind": "trade", "pack": "trading", "connector": "trade-endur", "key": "TRADE_ID", "label": "trade_endur · trade · trading"},
                      {"table": "scenario-pv", "kind": "scenario-pv", "pack": "market-risk", "connector": None, "key": "SCENARIO_PV_ID", "label": "scenario-pv · scenario-pv · market-risk"}],
           "packs": [{"pack": "trading", "kinds": ["trade"]}],
           "waiting": [{"file": FILE, "size": 2048, "unchangedSeconds": 700, "stable": True},
                       {"file": "eod.tar.gz", "size": 4096, "unchangedSeconds": 900, "stable": True}]}


class ReadyBackend(FakeBackend):
    """The server's Upload endpoints, standing in: the refusal and the replace warning follow the form's mode."""

    def __init__(self):
        super().__init__()
        self.pushed = []
        self.items = {}

    async def admin(self, method, path, ident, body=None, timeout=None, **params):
        if path == "/dataload" and method == "GET":
            return QUIET
        if path == "/packs":
            return []
        if path == "/dataload/choices":
            return CHOICES
        if path == "/dataload/pick":
            tar = body["file"].endswith(".tar.gz")
            self.items["item-" + body["file"]] = out = {"id": "item-" + body["file"], "file": body["file"], "source": "inbox", "state": "ready", "size": 2048, "sha256": "ab" * 32, "rows": 42, "tar": tar,
                    "suggest": {} if tar else {"table": "trade_endur", "date": "2026-11-06"},
                    "folders": [{"folder": "trade", "dates": ["2026-11-05", "2026-11-06"], "rows": 42}] if tar else None}
            return out
        if path.startswith("/dataload/item/"):
            return self.items[path.rsplit("/", 1)[1]]
        if path == "/dataload/ready/preview":
            return {"text": ("table: " + str(body.get("table")) + "\n" if body.get("table") else "") + "mode: " + body["mode"] + "\nsha256: " + "ab" * 32 + "\nrows: 42\n", "errors": {}, "derived": {}}
        if path == "/dataload/ready/check":
            if body.get("mode") == "replace":
                return {"checks": [{"status": "warning", "text": "The table already holds 9 rows for 2026-11-06 (CALYPSO 9). Replace removes every one of them.",
                                    "fix": "Load them together in one tar, or use append.", "field": "mode", "value": "append", "tick": True}], "blocked": False, "tick": True, "text": "", "errors": {}}
            if body.get("key") == "BAD":
                return {"checks": [{"status": "refused", "text": "DRS-5210: this date would be refused.", "fix": "One system sends one grain per date.", "field": "mode", "value": "replace", "tick": False}],
                        "blocked": True, "tick": False, "text": "", "errors": {}}
            return {"checks": [{"status": "pass", "text": "Every required field is there.", "fix": None, "field": None, "value": None, "tick": False}], "blocked": False, "tick": False, "text": "", "errors": {}}
        if path == "/dataload/ready/push":
            self.pushed.append(body)
            return {"file": FILE, "marker": FILE + ".ready", "sha256": "ab" * 32, "rows": 42, "text": "x", "hold": False, "node": "drishti-main", "nextCycleText": "14:50"}
        return await super().admin(method, path, ident, body, **params)


@pytest.fixture(scope="module")
def ready_console():
    import uvicorn
    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    fake = ReadyBackend()
    app.state.backend = fake
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    for _ in range(100):
        if server.started:
            break
        time.sleep(0.05)
    yield f"http://127.0.0.1:{port}", fake
    server.should_exit = True
    thread.join(timeout=5)


def _open(browser, base):  # noqa: F811
    ctx = browser.new_context(viewport={"width": 1600, "height": 960})
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(base + "/admin/loads/upload")
    page.wait_for_selector("[data-ready-page]")
    page.wait_for_selector("[data-ready-drop] .di-drop-zone")
    return page, errors, ctx


def _ready(page) -> str:
    return page.locator("[data-ready-text]").input_value()


def test_the_page_fills_from_the_name_and_a_refusal_blocks_push(browser, ready_console):  # noqa: F811
    base, fake = ready_console
    page, errors, ctx = _open(browser, base)
    page.click('[data-src="inbox"]')
    page.click(f'[data-ready-pick="{FILE}"]')
    expect(page.locator("[data-ready-text]")).to_have_value(re.compile("table: trade_endur"))
    assert "mode: append" in _ready(page), "never replace by default"
    assert "trade" in page.locator("[data-ready-derived-kind]").inner_text()
    assert "trading" in page.locator("[data-ready-derived-pack]").inner_text()
    page.wait_for_selector('[data-check="pass"]')
    assert not page.locator("[data-ready-push]").is_disabled()

    page.locator("[data-ready-key] input, input[data-ready-key]").first.fill("BAD")
    page.wait_for_selector('[data-check="refused"]')
    assert page.locator("[data-ready-push]").is_disabled()
    assert "fix the refusal" in page.locator("[data-ready-why]").inner_text()

    page.locator("[data-ready-key] input, input[data-ready-key]").first.fill("TRADE_ID")
    page.wait_for_selector('[data-check="pass"]')
    assert errors == [], errors
    ctx.close()


def test_a_replace_of_existing_rows_needs_its_tick_then_pushes(browser, ready_console):  # noqa: F811
    base, fake = ready_console
    page, errors, ctx = _open(browser, base)
    page.click('[data-src="inbox"]')
    page.click(f'[data-ready-pick="{FILE}"]')
    page.wait_for_selector('[data-check="pass"]')
    page.locator('[data-ready-mode-value="replace"]').check()
    page.wait_for_selector('[data-check="warning"]')
    assert "CALYPSO 9" in page.locator('[data-check="warning"]').inner_text()
    assert page.locator("[data-ready-push]").is_disabled()
    page.locator('[data-check="warning"] input[type=checkbox]').check()
    expect(page.locator("[data-ready-push]")).to_be_enabled()
    page.click("[data-ready-push]")
    page.wait_for_selector("[data-ready-done]")
    assert page.locator("[data-ready-board]").get_attribute("href") == "/admin/loads/inbox"
    assert fake.pushed[-1]["ackReplace"] is True and fake.pushed[-1]["mode"] == "replace"
    assert errors == [], errors
    ctx.close()


def test_a_tar_lists_its_tables_and_takes_no_table_or_date(browser, ready_console):  # noqa: F811
    base, _ = ready_console
    page, errors, ctx = _open(browser, base)
    page.click('[data-src="inbox"]')
    page.click('[data-ready-pick="eod.tar.gz"]')
    page.wait_for_selector(".ready-folders >> text=2026-11-05, 2026-11-06")
    assert page.locator("[data-ready-table]").count() == 0
    assert "names its own tables" in page.locator("[data-ready-form]").inner_text()
    assert errors == [], errors
    ctx.close()


def test_two_people_keep_their_own_forms(browser, ready_console):  # noqa: F811
    base, _ = ready_console
    one, _, c1 = _open(browser, base)
    two, _, c2 = _open(browser, base)
    one.click('[data-src="inbox"]')
    one.click(f'[data-ready-pick="{FILE}"]')
    expect(one.locator("[data-ready-text]")).to_have_value(re.compile("trade_endur"))
    assert _ready(two) == ""
    c1.close()
    c2.close()
