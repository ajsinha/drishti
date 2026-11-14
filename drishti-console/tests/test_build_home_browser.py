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

"""The Build home, the sitemap's menus and Admin → Configuration in a real browser (2.9.11.e): every button clicked. The rail's kinds,
the task tiles (hide, show, open), + New (open, Escape), the search ("/" focuses it); the top bar's menus and the Admin tabs; the
configuration editor's tiers, search, Propose a change (the dialog, a missing reason, the proposal), Withdraw. One console on the
stand-in server (port 17938), whose server answers for tables, the catalogue, connectors and the proposals are kept below; skipped when
Playwright or its Chromium is missing."""
from __future__ import annotations

import re
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from core.backend import BackendError  # noqa: E402
from scratch_ports import worker_port  # noqa: E402
from test_build_home import CATALOGUE, CONNECTORS, OBJECTS  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

PORT = worker_port(17938)
SERVER = {"settings": [{"key": "drishti.var.confidence", "value": "0.975", "origin": "file", "source": "application.local.yaml", "secret": False}]}


class Stand(FakeBackend):
    """The stand-in server with what the Build home and the editor ask (the proposals kept as the server keeps them)."""

    def __init__(self):
        super().__init__()
        self.kept: list[dict] = []

    async def admin(self, method, path, ident, body=None, **params):
        if path == "/connectors" and method == "GET":
            return CONNECTORS
        if path == "/settings":
            return SERVER
        if path == "/settings/proposals":
            if method == "POST":
                self.kept.append({**body, "id": f"SP-{len(self.kept) + 1}", "proposedBy": ident.user, "state": "proposed", "applied": False})
                return self.kept[-1]
            return {"proposals": list(reversed(self.kept))}
        m = re.fullmatch(r"/settings/proposals/([\w-]+)/(\w+)", path)
        if m:
            p = next(x for x in self.kept if x["id"] == m.group(1))
            p["state"] = {"approve": "approved", "reject": "rejected", "withdraw": "withdrawn"}[m.group(2)]
            return dict(p)
        return await super().admin(method, path, ident, body, **params)

    async def _send(self, method, path, ident=None, **kw):
        if path == "/tables/editor/objects":
            return OBJECTS
        return await super()._send(method, path, ident, **kw)

    async def bi(self, method, path, ident, body=None, **params):
        if path == "/catalogue":
            return CATALOGUE
        if path == "/me":
            return {"enabled": True, "powers": ["bi", "bi-author"]}
        if path == "/home":
            return {}
        raise BackendError(404, "DRS-9001", "no BI in this stand-in")


@pytest.fixture(scope="module")
def site():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = Stand()
    app.state.bi_on = True
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=PORT, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{PORT}", app
    server.should_exit = True
    thread.join(10)


@pytest.fixture
def page(browser, site):  # noqa: F811 - the shared browser fixture
    ctx = browser.new_context(viewport={"width": 1400, "height": 900})
    ctx.add_cookies([{"name": "drishti-phone-all", "value": "1", "url": site[0]}])
    p = ctx.new_page()
    yield p
    ctx.close()


def test_every_kind_of_the_rail_opens_its_list(page, site):
    url = site[0]
    page.goto(url + "/build", wait_until="load")
    kinds = page.locator("[data-kind]").evaluate_all("els => els.map(e => e.dataset.kind)")
    assert kinds[:4] == ["connectors", "lakes", "tables", "python-tables"] and "drafts" in kinds
    for kind in kinds:
        page.locator(f'[data-kind="{kind}"]').click()
        page.wait_for_url(f"**/build?type={kind}")
        assert page.locator(f'[data-kind="{kind}"][aria-current="page"]').count() == 1, kind
    page.locator('[data-kind="tables"]').click()
    page.wait_for_url("**/build?type=tables")
    row = page.locator('tr[data-item="tables:risk-by-desk"]')
    assert "VaR by desk" in row.inner_text()                                            # used by: the report
    row.locator("a").first.click()
    page.wait_for_url("**/build/tables?kind=model&item=M1")


def test_the_task_tiles_hide_show_and_open(page, site):
    url = site[0]
    page.goto(url + "/build", wait_until="load")
    assert page.locator("[data-tiles]").is_visible()
    page.locator("[data-hide-tiles]").click()
    page.wait_for_url("**/build?tiles=0*")
    assert page.locator("[data-tiles]").count() == 0
    page.goto(url + "/build", wait_until="load")
    assert page.locator("[data-tiles]").count() == 0                                   # kept for this browser
    page.locator("[data-show-tiles]").click()
    page.wait_for_url("**/build?tiles=1")
    for task in ("bring", "shape", "show", "package"):
        page.locator(f'[data-task="{task}"]').click()
        page.wait_for_url("**/build?type=*")
        page.go_back(wait_until="load")


def test_new_opens_closes_and_starts(page, site):
    page.goto(site[0] + "/build", wait_until="load")
    menu = page.locator("[data-new]")
    menu.locator("summary").click()
    assert menu.locator('[data-new-kind="sutras"]').is_visible()
    page.keyboard.press("Escape")
    assert not menu.locator('[data-new-kind="sutras"]').is_visible()
    menu.locator("summary").click()
    menu.locator('[data-new-kind="sutras"]').click()
    page.wait_for_url("**/build/new")


def test_slash_focuses_the_one_search(page, site):
    page.goto(site[0] + "/build", wait_until="load")
    page.locator("h1").click()
    page.keyboard.press("/")
    assert page.evaluate("document.activeElement.matches('[data-search]')")
    page.keyboard.type("risk")
    page.keyboard.press("Enter")
    page.wait_for_url("**/build?q=risk")
    assert page.locator('tr[data-item="tables:risk-by-desk"]').count() == 1


def test_the_top_menus_and_the_admin_tabs_go_where_they_say(page, site):
    url = site[0]
    page.goto(url + "/t", wait_until="load")
    for menu, item, where in (("Create", "Build home", "/build"), ("Admin", "Configuration", "/admin/configuration"),
                              ("Help", "Help centre", "/help")):
        page.locator(".tbar-menu-btn", has_text=menu).first.click()
        page.locator(".mega-item", has_text=item).first.click()
        page.wait_for_url(f"**{where}")
        page.goto(url + "/t", wait_until="load")
    page.goto(url + "/admin/configuration", wait_until="load")
    hrefs = page.locator(".adm-tabs a").evaluate_all("els => els.map(e => e.getAttribute('href'))")
    assert hrefs[0] == "/admin/users" and "/admin/configuration" in hrefs and hrefs[-1] == "/account"
    page.locator('.adm-tabs a[data-tab="engines"]').click()
    page.wait_for_url("**/admin/engines")
    assert page.locator('.adm-tabs a[data-tab="engines"][aria-current="page"]').count() == 1


def test_the_configuration_editor_searches_and_proposes(page, site):
    url, app = site
    page.goto(url + "/admin/configuration", wait_until="load")
    assert page.locator('[data-tier="everyday"][aria-current="page"]').count() == 1
    for tier in ("admin", "expert", "all", "everyday"):
        page.locator(f'[data-tier="{tier}"]').click()
        page.wait_for_url(f"**tier={tier}*")
    page.locator("[data-cfe-q]").fill("confidence")
    page.locator("[data-cfe-search] button[type=submit]").click()
    page.wait_for_url("**q=confidence*")
    row = page.locator('tr[data-key="drishti.var.confidence"]')
    assert "0.975" in row.inner_text()
    row.locator("[data-propose]").click()
    dlg = page.locator("[data-propose-dialog]")
    assert dlg.is_visible() and dlg.locator("[data-dlg-value]").input_value() == "0.975"
    dlg.locator("[data-dlg-cancel]").click()
    assert not dlg.is_visible()
    row.locator("[data-propose]").click()
    dlg.locator("[data-dlg-value]").fill("0.99")
    dlg.locator("[data-dlg-reason]").fill(" ")
    dlg.locator("[data-dlg-send]").click()
    assert dlg.locator("[data-dlg-error]").is_visible()                                 # say why
    dlg.locator("[data-dlg-reason]").fill("the board asked")
    with page.expect_navigation():
        dlg.locator("[data-dlg-send]").click()
    item = page.locator("[data-waiting] [data-proposal]").first
    assert "drishti.var.confidence" in item.inner_text() and "0.99" in item.inner_text()
    with page.expect_navigation():
        item.locator('[data-decide="withdraw"]').click()                                 # one's own: withdraw only
    assert "Nothing is waiting" in page.locator("[data-waiting]").inner_text()
    assert app.state.backend.kept[-1]["state"] == "withdrawn"
