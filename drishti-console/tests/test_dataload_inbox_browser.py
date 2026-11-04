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

"""Admin → Data loads → Inbox in a real browser (DATALOAD_INBOX.md section 10, mock-up A): the three columns (Waiting, Loading table by
table, Last cycles), Load now while a run loads (answered skipped, in words), Retry of a failed file (its reason shown, then gone from the
board after the redraw), the board redrawn from the server, a 390 px phone (columns stacked, 44 px controls, no sideways scroll) and both
themes. Runs Chromium through Playwright against a console with a stand-in server; skipped when Playwright or its Chromium is missing."""
import copy
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_dataload_inbox import BUSY, QUIET, RUN, SKIPPED  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

FAILED_FILE = "scenario-pv-2026-11-02-eod.jsonl.gz"


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class InboxBackend(FakeBackend):
    """The server's data inbox, standing in: the busy afternoon of mock-up A until a test changes it."""

    def __init__(self):
        super().__init__()
        self.status = copy.deepcopy(BUSY)
        self.runs = 0
        self.retried = []

    async def admin(self, method, path, ident, body=None, timeout=None, **params):
        if path == "/dataload" and method == "GET":
            return self.status
        if path == "/dataload/run" and method == "POST":
            self.runs += 1
            if self.status.get("running"):
                return {"started": False, "skipped": SKIPPED, "by": "alice"}
            return {"started": True, "by": "alice"}
        if path == "/dataload/retry" and method == "POST":
            name = (body or {}).get("file")
            self.retried.append(name)
            board = self.status["board"]
            board["failures"] = [f for f in board["failures"] if f["file"] != name]
            board["failedTotal"] = len(board["failures"])
            board["failedText"] = "No failed files in the last 30 days (keep-outbox-days)."
            return {"file": name, "from": "failed/2026-11-03/20261103T145000Z-drishti-main/" + name, "to": "inbox/" + name,
                    "next": "the next cycle loads it (or Load now)"}
        if path == "/packs":
            return []
        return await super().admin(method, path, ident, body, **params)


@pytest.fixture(scope="module")
def inbox_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = InboxBackend()
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


@pytest.fixture()
def busy(inbox_console):
    url, backend = inbox_console
    backend.status = copy.deepcopy(BUSY)
    backend.runs, backend.retried = 0, []
    return url, backend


def test_the_three_columns_follow_the_folders(browser, busy):
    url, _ = busy
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    page.goto(url + "/admin/loads/inbox")
    cols = page.locator("details[data-col]")
    assert [cols.nth(i).get_attribute("data-col") for i in range(cols.count())] == ["waiting", "loading", "cycles"]
    tops = [cols.nth(i).bounding_box()["y"] for i in range(3)]
    assert max(tops) - min(tops) < 2                                                   # side by side on a wide screen
    waiting = page.locator("[data-col=waiting]")
    assert waiting.locator("[data-waiting]").count() == 3 and waiting.locator("[data-ready-all]").count() == 1
    assert "not yet stable" in waiting.locator("[data-code=not-stable]").inner_text()
    assert "no rule matches this name" in waiting.locator("[data-code=no-marker] [data-rule]").inner_text()
    loading = page.locator("[data-col=loading]")
    assert RUN in loading.inner_text() and "table 2 of 2" in loading.locator("summary").inner_text()
    assert loading.locator("[data-table][data-state=committed]").count() == 1 and loading.locator("[data-table][data-state=loading]").count() == 1
    assert "2026-11-03 → v418 · 96,410 rows · 0 rejects" in loading.inner_text()
    cycles = page.locator("[data-col=cycles]")
    assert cycles.locator("[data-cycle=skipped]").count() == 2 and "2 skipped" in cycles.locator("summary").inner_text()
    assert page.locator("[data-inbox-failed-badge]").inner_text().strip() == "1 failed"
    page.close()


def test_load_now_while_a_run_loads_is_answered_skipped_in_words(browser, busy):
    url, backend = busy
    page = browser.new_page()
    page.goto(url + "/admin/loads/inbox")
    page.locator("[data-inbox-run]").click()
    said = page.locator("[data-inbox-said]")
    said.filter(has_text="skipped").wait_for()
    text = said.inner_text()
    assert f"run {RUN} still loading (23 min)" in text and "Nothing was started" in text
    assert backend.runs == 1
    backend.status = copy.deepcopy(QUIET)                                              # the run ended: Load now starts one
    page.reload()
    page.locator("[data-loading-empty]").wait_for()
    page.locator("[data-inbox-run]").click()
    said.filter(has_text="started").wait_for()
    assert "Load now: started" in said.inner_text()
    page.close()


def test_a_failed_file_shows_its_reason_and_retry_puts_it_back(browser, busy):
    url, backend = busy
    page = browser.new_page()
    page.goto(url + "/admin/loads/inbox")
    card = page.locator(f"[data-failed='{FAILED_FILE}']")
    assert "3,212 of 229,400 rows (1.4 %) rejected" in card.locator("[data-reason]").inner_text()
    assert card.locator("[data-report]").get_attribute("href").startswith("/admin/api/dataload/report?path=failed/2026-11-03/")
    card.locator(f"[data-retry='{FAILED_FILE}']").click()
    said = page.locator("[data-inbox-said]")
    said.filter(has_text="back in the inbox").wait_for()
    assert f"{FAILED_FILE}: back in the inbox" in said.inner_text() and "the next cycle loads it" in said.inner_text()
    assert backend.retried == [FAILED_FILE]
    page.locator(f"[data-failed='{FAILED_FILE}']").wait_for(state="detached")          # the board redrawn from the server
    assert "No failed files" in page.locator("[data-failures]").inner_text()
    page.close()


def test_the_board_redraws_itself_while_a_run_loads(browser, busy):
    url, backend = busy
    page = browser.new_page()
    page.goto(url + "/admin/loads/inbox")
    assert page.locator("[data-inbox-board]").get_attribute("data-running") == "1"
    backend.status["running"]["tables"][1].update(state="committed", position="committed 2 of 2", detail="2026-11-03 → v91 · 618,169 rows · 0 rejects")
    page.locator("[data-table='2'][data-state=committed]").wait_for(timeout=12000)     # 5 s while a run loads
    assert "618,169 rows" in page.locator("[data-col=loading]").inner_text()
    page.close()


@pytest.mark.parametrize("theme", ["light", "dark"])
def test_a_phone_stacks_the_columns_with_touch_sized_controls(browser, busy, theme):
    url, _ = busy
    page = browser.new_page(viewport={"width": 390, "height": 844}, color_scheme=theme)
    page.goto(url + "/admin/loads/inbox")
    page.locator("[data-inbox-board]").wait_for()
    assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")      # no sideways page scroll
    cols = page.locator("details[data-col]")
    boxes = [cols.nth(i).bounding_box() for i in range(3)]
    assert boxes[0]["y"] < boxes[1]["y"] < boxes[2]["y"]                                   # stacked
    assert page.locator("[data-col=loading]").get_attribute("open") is not None           # the run open, the others closed to rows
    assert page.locator("[data-col=waiting]").get_attribute("open") is None
    for sel in ("[data-inbox-run]", "details[data-col] > summary", f"[data-retry='{FAILED_FILE}']"):
        for el in page.locator(sel).all():
            if el.is_visible():
                assert el.bounding_box()["height"] >= 44, sel
    page.locator("[data-col=waiting] > summary").click()
    assert page.locator("[data-col=waiting] [data-waiting]").first.is_visible()
    for st in page.locator("[data-inbox-board] .st").all():
        if st.is_visible():
            assert st.inner_text().strip()                                                # a state always has its word
    page.close()


@pytest.mark.parametrize("theme", ["light", "dark"])
def test_both_themes_use_the_theme_tokens(browser, busy, theme):
    url, _ = busy
    page = browser.new_page(viewport={"width": 1280, "height": 900}, color_scheme=theme)
    page.goto(url + "/admin/loads/inbox")
    col = page.locator("[data-col=loading]")
    bg = col.evaluate("e => getComputedStyle(e).backgroundColor")
    ink = col.locator(".ib-h").evaluate("e => getComputedStyle(e).color")
    bad = page.locator("[data-failures] .st-bad").first.evaluate("e => getComputedStyle(e).color")
    assert bg != ink and bad != bg
    page.close()
