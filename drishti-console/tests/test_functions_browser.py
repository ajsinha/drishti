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

"""Functions, FUNC, in a real browser (docs/admin/COMMANDS.md). As a user: only the commands that are on, no switches, Find and the pack
picker, a command opening with its example. As an administrator: the matrix, a change batched and undone, Review with what turning a command
off blocks and a reason, Apply sending one batch, the history in Cluster; and the "not active" page of a command that is off. The stand-in
server keeps the switches as the real one does. Runs Chromium through Playwright; skipped when Playwright or its Chromium is missing. The
pictures of docs/admin/COMMANDS.md are written to docs/ only with DRISHTI_SHOTS set (tests/docshots.py)."""
import copy
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.auth import Identity  # noqa: E402
from docshots import docs_img  # noqa: E402
from test_functions import LISTING  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _shot(page, name, locator=None):
    path = docs_img("admin/img/" + name)
    path.parent.mkdir(parents=True, exist_ok=True)
    (locator or page).screenshot(path=str(path))


class SwitchBackend(FakeBackend):
    """A stand-in server holding the switches: a batch changes them, the listing and the history follow."""

    def __init__(self):
        super().__init__()
        self.listing = copy.deepcopy(LISTING)
        self.batches = []

    async def admin(self, method, path, ident, body=None, timeout=None, **params):
        if path == "/commands" and method == "GET":
            return self.listing
        if path == "/commands" and method == "POST":
            self.batches.append(body)
            for c in body["changes"]:
                for p in self.listing["packs"]:
                    for m in p["commands"]:
                        if c["scope"] == "command" and m["code"] == c["name"]:
                            m["on"] = c["on"]
            self.listing["off"] = sum(1 for p in self.listing["packs"] for m in p["commands"] if not m["on"])
            return self.listing
        if path == "/commands/history":
            return {"items": [{"scope": "command", "name": b["changes"][0]["name"], "on": b["changes"][0]["on"], "reason": b["reason"], "actor": "ash",
                               "at": "2026-10-25T10:00:00Z", "node": "n-a"} for b in self.batches]}
        if path.endswith("/impact"):
            return {"code": "VAR", "kind": "var", "page": "/v/var/…", "uses": [{"label": "BI datasets", "count": 1, "items": [{"name": "market-risk-capital"}]}]}
        if path == "/status":
            return {}
        return await super().admin(method, path, ident, body, **params)

    async def functions(self, ident=None):
        """What a user reads: only the commands that are on, nothing of the switches (the real server's /api/v1/functions)."""
        packs = [{"name": p["name"], "code": p["code"], "title": p["title"],
                  "commands": [{k: m[k] for k in ("code", "kind", "label", "description", "example")} for m in p["commands"] if m["on"]]}
                 for p in self.listing["packs"] if p["on"]]
        packs = [p for p in packs if p["commands"]]
        return {"total": sum(len(p["commands"]) for p in packs), "packs": packs}

    async def view(self, kind, id_, user, controls=None, panels=None, **kw):
        if kind == "frtb-sensitivity":
            e = BackendError(403, "DRS-1016", "DRS-1016 FRTB is not active on this server")
            e.extra = {"code": "DRS-1016", "command": "FRTB", "kind": kind, "since": "2026-10-24"}
            raise e
        return await super().view(kind, id_, user, controls, panels)


@pytest.fixture(scope="module")
def commands_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = SwitchBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}", app
    server.should_exit = True
    thread.join(10)


def test_a_change_is_batched_undone_reviewed_and_applied_with_its_reason(browser, commands_console):
    url, app = commands_console
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    page.goto(url + "/admin/commands")                                       # redirects to Functions
    assert page.url.endswith("/func")
    assert page.locator("[data-matrix] tr[data-command]").count() == 3
    _shot(page, "functions-admin.png")
    assert "FRTB SA sensitivities." in page.locator("tr[data-command='FRTB']").inner_text()

    var = page.locator("tr[data-command='VAR'] input[data-switch]")
    var.uncheck()
    assert page.locator("[data-pending]").inner_text() == "1 change not saved"
    assert "turning off" in page.locator("tr[data-command='VAR']").inner_text()
    page.locator("[data-undo]").click()
    assert var.is_checked() and page.locator("[data-pending]").is_hidden()
    assert page.locator("[data-review]").is_disabled()

    var.uncheck()
    page.locator("[data-review]").click()
    side = page.locator("[data-side]")
    assert side.is_visible()
    assert page.locator("[data-side-title]").inner_text() == "Turn VAR off"
    blocks = page.locator("[data-side-blocks]")
    assert "/v/var/…" in blocks.inner_text() and "Search and type-ahead suggestions" in blocks.inner_text()
    page.wait_for_function("() => document.querySelector('[data-side-blocks]').innerText.includes('market-risk-capital')", timeout=10000)
    page.locator("[data-reason-input]").fill("quarter-end freeze")
    _shot(page, "admin-commands-review.png")
    page.locator("[data-apply]").click()
    page.wait_for_function("() => document.querySelector('tr[data-command=VAR]') && document.querySelector('tr[data-command=VAR]').dataset.on === 'false'",
                           timeout=15000)                                      # the page reloads with what the server holds
    assert app.state.backend.batches == [{"reason": "quarter-end freeze", "changes": [{"scope": "command", "name": "VAR", "on": False}]}]
    page.locator("[data-cluster] > summary").click()
    page.wait_for_function("() => document.querySelector('[data-history]').innerText.includes('quarter-end freeze')", timeout=10000)
    app.state.backend.listing = copy.deepcopy(LISTING)
    page.close()


def test_find_narrows_the_matrix(browser, commands_console):
    url, _ = commands_console
    page = browser.new_page()
    page.goto(url + "/func")
    page.locator("[data-find]").fill("frtb")
    assert page.locator("tr[data-command]:visible").count() == 1
    page.locator("[data-find]").fill("")
    page.locator("[data-show]").select_option("off")
    assert all(r.get_attribute("data-on") == "false" for r in page.locator("tr[data-command]:visible").all())
    page.close()


def test_a_user_sees_only_the_commands_that_are_on_and_opens_one(browser, commands_console):
    url, app = commands_console
    mp = pytest.MonkeyPatch()
    mp.setattr(Identity, "is_admin", property(lambda self: False))
    try:
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        page.goto(url + "/t")
        page.locator("#cmdInput").fill("FUNC")
        page.keyboard.press("Enter")
        page.wait_for_url("**/func**")
        assert page.locator("tr[data-command]").count() == 2, "FRTB is off: a user never sees it"
        assert page.locator("[data-switch], [data-review], [data-cluster]").count() == 0
        assert page.locator("[data-counts]").inner_text() == "2 commands you can use"
        _shot(page, "functions-user.png")
        page.locator("[data-find]").fill("value")
        assert page.locator("tr[data-command]:visible").count() == 1
        assert page.locator("[data-counts]").inner_text() == "1 of 2 commands you can use"
        assert "q=value" in page.url
        page.locator("[data-find]").fill("")
        page.locator("[data-pack-filter]").select_option("trading")
        assert [r.get_attribute("data-command") for r in page.locator("tr[data-command]:visible").all()] == ["TRD"]
        assert page.locator("tr[data-command='TRD'] a").get_attribute("href") == "/go?q=TRD%201000015"
        assert page.locator("tbody[data-pack='trading'] a.cmd-pack-name").get_attribute("href") == "/form/trading"
        page.goto(url + "/go?q=" + "FUNC%20trading")
        assert page.url.endswith("/func?q=trading")
        assert [r.get_attribute("data-command") for r in page.locator("tr[data-command]:visible").all()] == ["TRD"]
        page.close()
    finally:
        mp.undo()


def test_the_page_of_a_command_turned_off_is_the_not_active_page(browser, commands_console):
    url, _ = commands_console
    page = browser.new_page(viewport={"width": 1280, "height": 720})
    r = page.goto(url + "/v/frtb-sensitivity/FRTB-RATES-1")
    assert r.status == 403
    assert page.locator("[data-command-off='FRTB'] h1").inner_text() == "FRTB is not active on this server"
    assert "Off since 2026-10-24" in page.locator("[data-command-off]").inner_text()
    _shot(page, "command-not-active.png")
    page.close()
