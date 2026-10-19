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

"""Admin → Net in a real browser against a console with a stand-in server: the tabs (click and arrow keys, the URL keeps the tab), the member
table refreshing by itself, a drain sent and the page showing Undrain, Explain a request showing the route the server gave, a file saved with its
version and a stale save refused with the server's problem and its line, and every tab at phone width without sideways scrolling. Chromium
through Playwright; skipped when Playwright or its Chromium is missing. The same tabs against three real servers are in
test_admin_net_stack_browser.py."""
import copy
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from core.backend import BackendError  # noqa: E402
from scratch_ports import worker_port  # noqa: E402
from test_admin_net import ACCESS, CAPS, FILES, MEMBERS, SECURITY, TRAFFIC  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

CONSOLE_PORT = worker_port(17785)


class NetBackend(FakeBackend):
    """Stands in for a node: answers the /api/v1/net calls the page makes and remembers them."""

    def __init__(self):
        super().__init__()
        self.members = copy.deepcopy(MEMBERS)
        self.net_calls = []
        self.etag = "abc"

    async def admin(self, method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/status":
            return {}
        return await super().admin(method, path, ident, body, **params)

    async def net(self, method, path, ident, body=None, headers=None, **params):
        self.net_calls.append((method, path, body, headers, params))
        if path == "/members":
            return self.members
        if path in ("/drain", "/undrain"):
            self.members["node"]["drained"] = path == "/drain"
            self.members["members"][0]["drained"] = path == "/drain"
            return {"drained": path == "/drain"}
        if path == "/files":
            return FILES
        if path == "/file" and method == "GET":
            return {"id": params.get("id"), "exists": True, "text": "interval: 1s\nfanout: 3\n", "etag": self.etag, "problems": [],
                    "history": [{"id": "20261019T100000000", "at": "2026-10-19T10:00:00Z", "bytes": 20}]}
        if path == "/file/revision":
            return {"id": params.get("id"), "rev": params.get("rev"), "text": "interval: 1s\nfanout: 2\n"}
        if path == "/file" and method == "PUT":
            if (headers or {}).get("If-Match") != self.etag:
                err = BackendError(409, "DRS-5055", "gossip.yaml was changed since it was read")
                err.extra = {"problems": [{"file": "gossip.yaml", "level": "error", "field": "", "line": 0, "message": "changed since it was read"}]}
                raise err
            if "lots" in (body or {}).get("text", ""):
                err = BackendError(409, "DRS-5055", "gossip.yaml was not written: gossip.yaml:2 fanout: 'lots' is not a whole number")
                err.extra = {"problems": [{"file": "gossip.yaml", "level": "error", "field": "fanout", "line": 2, "message": "'lots' is not a whole number"}]}
                raise err
            self.etag = "def"
            return {"id": params.get("id"), "etag": "def", "problems": []}
        if path == "/capabilities":
            return CAPS
        if path == "/access":
            return ACCESS
        if path == "/security":
            return SECURITY
        if path == "/traffic":
            return TRAFFIC
        if path == "/explain":
            return {"kind": params.get("kind"), "route": "view", "reason": "the pack finance is not on this node: forwarded to a node that renders trade",
                    "forwardTo": ["n-b", "n-c"], "dataPeers": [], "scatter": ["n-b"], "unreachable": ["n-x"], "down": []}
        return {"ok": True}


@pytest.fixture(scope="module")
def net_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = NetBackend()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=CONSOLE_PORT, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{CONSOLE_PORT}", app.state.backend
    server.should_exit = True
    thread.join(10)


def test_the_tabs_move_by_click_and_by_arrow_keys_and_the_url_keeps_the_tab(browser, net_console):
    url, _ = net_console
    page = browser.new_page()
    page.goto(url + "/admin/net")
    assert page.locator("#netPaneMembers").is_visible()
    page.locator("#netTabMap").click()
    assert page.locator("#netPaneMap").is_visible() and not page.locator("#netPaneMembers").is_visible()
    assert page.url.endswith("/admin/net?tab=map")
    page.locator("#netTabMap").focus()
    page.keyboard.press("ArrowRight")
    assert page.locator("#netPaneRouting").is_visible()
    page.keyboard.press("End")
    assert page.locator("#netPaneSecurity").is_visible()
    page.keyboard.press("Home")
    assert page.locator("#netPaneMembers").is_visible()
    page.goto(url + "/admin/net?tab=files")
    assert page.locator("#netPaneFiles").is_visible()
    page.close()


def test_a_drain_is_sent_and_the_page_offers_undrain(browser, net_console):
    url, backend = net_console
    page = browser.new_page()
    page.goto(url + "/admin/net")
    page.locator("[data-action=drain]").click()
    page.locator("[data-action=undrain]").wait_for(timeout=10000)
    assert ("POST", "/drain") in [(m, p) for m, p, *_ in backend.net_calls]
    assert "Drained" in page.locator("[data-summary]").inner_text()
    page.locator("[data-action=undrain]").click()
    page.locator("[data-action=drain]").wait_for(timeout=10000)
    page.close()


def test_explain_shows_the_route_the_server_gives(browser, net_console):
    url, backend = net_console
    page = browser.new_page()
    page.goto(url + "/admin/net?tab=routing")
    page.locator("[data-ex-kind]").fill("trade")
    page.locator("[data-ex-id]").fill("IRS-48213")
    page.locator("[data-ex-role]").select_option("trader")
    page.locator("[data-explain-form] button[type=submit]").click()
    page.locator("[data-explained]").wait_for(state="visible", timeout=10000)
    assert page.locator("[data-ex-route]").inner_text() == "forwarded"
    assert page.locator("[data-ex-forward]").inner_text() == "n-b, n-c"
    assert page.locator("[data-ex-unreachable]").inner_text() == "n-x"
    ex = [c for c in backend.net_calls if c[1] == "/explain"][-1]
    assert ex[4] == {"kind": "trade", "id": "IRS-48213", "role": "trader"}
    assert page.locator("[data-traffic='n-b'] rect").count() >= 2
    page.close()


def test_a_file_is_saved_with_its_version_and_a_bad_one_shows_its_line(browser, net_console):
    url, backend = net_console
    page = browser.new_page()
    page.goto(url + "/admin/net?tab=files")
    page.locator("[data-file='gossip.yaml']").click()
    page.locator("[data-editor]").wait_for(state="visible")
    assert "fanout: 3" in page.locator("[data-yaml]").input_value()
    page.locator("[data-rev]").first.click()
    page.locator("[data-diff]").wait_for(state="visible")
    assert "- fanout: 2" in page.locator("[data-diff]").inner_text() and "+ fanout: 3" in page.locator("[data-diff]").inner_text()
    page.locator("[data-yaml]").fill("interval: 1s\nfanout: lots\n")
    page.locator("[data-save]").click()
    page.locator("[data-problems] li").first.wait_for(timeout=10000)
    assert "line 2: fanout: 'lots' is not a whole number" in page.locator("[data-problems]").inner_text()
    page.locator("[data-yaml]").fill("interval: 2s\nfanout: 3\n")
    page.locator("[data-save]").click()
    page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Saved gossip.yaml')", timeout=10000)
    put = [c for c in backend.net_calls if c[0] == "PUT"]
    assert put[-1][3] == {"If-Match": "abc"}
    page.close()


@pytest.mark.parametrize("tab", ["members", "map", "routing", "access", "files", "security"])
def test_every_tab_fits_a_phone(browser, net_console, tab):
    url, _ = net_console
    page = browser.new_page(viewport={"width": 390, "height": 844})
    page.goto(url + "/admin/net?tab=" + tab)
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), tab
    page.close()
