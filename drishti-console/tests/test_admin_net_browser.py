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


class Pool:
    """Stands in for the console's own server list (core/cluster.py): on n-b, the console's current server."""

    def snapshot(self):
        return {"enabled": True, "active": "http://127.0.0.1:18784", "file": "/srv/console/net/servers.json", "failovers": [], "servers": [
            {"url": "http://127.0.0.1:18783", "origin": "configured", "nodeId": "n-a", "healthy": True, "latencyMs": 1.0, "fails": 0, "lastError": ""},
            {"url": "http://127.0.0.1:18784", "origin": "discovered", "nodeId": "n-b", "healthy": True, "latencyMs": 2.0, "fails": 0, "lastError": ""}]}


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
    app.state.cluster = Pool()                      # after start: only the pages read it (the console's servers, the ring's mark)
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


def test_the_hash_ring_shows_each_member_by_state_with_this_node_and_the_console_s_server(browser, net_console):
    url, _ = net_console
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    page.goto(url + "/admin/net")
    page.locator("[data-arc]").first.wait_for(timeout=10000)
    assert page.locator("[data-arc]").count() == 3
    states = {g.get_attribute("data-arc"): g.get_attribute("data-state") for g in page.locator("[data-arc]").all()}
    assert states == {"n-a": "alive", "n-b": "suspect", "n-c": "dead"}
    fills = page.evaluate("() => [...document.querySelectorAll('[data-arc] .net-arc-fill')].map(p => getComputedStyle(p).fill)")
    assert len(set(fills)) == 3, fills                                        # one colour per state, from the theme's tokens
    assert page.locator("[data-arc][data-self]").get_attribute("data-arc") == "n-a"
    assert page.locator("[data-arc][data-console]").get_attribute("data-arc") == "n-b"
    assert page.locator("[data-arc='n-b'] .net-arc-console").count() == 1
    assert page.locator("[data-ring-count]").text_content() == "1 / 3"
    assert "n-a" in page.locator("[data-arc='n-a'] .net-arc-label").text_content() and "core" in page.locator("[data-arc='n-a'] .net-arc-label").text_content()
    assert "holds data of: trade" in page.locator("[data-arc='n-a'] title").text_content()
    assert "sized by the kinds" in page.locator("[data-ring-caption]").inner_text()
    ring, table = page.locator("[data-ring]").bounding_box(), page.locator("[data-members]").bounding_box()
    assert ring["x"] > table["x"] + 100, (ring, table)                         # on the right of the table
    page.close()


def click_arc(page, node):
    """A real click in the middle of the node's arc (its data-at point, in the ring's units, mapped to the screen)."""
    x, y = page.evaluate("""id => { const g = document.querySelector(`[data-arc='${id}']`), [x, y] = g.getAttribute('data-at').split(',').map(Number);
        const p = g.ownerSVGElement.createSVGPoint(); p.x = x; p.y = y; const s = p.matrixTransform(g.getScreenCTM()); return [s.x, s.y]; }""", node)
    page.mouse.click(x, y)


def test_clicking_an_arc_selects_its_row_and_a_row_its_arc(browser, net_console):
    url, _ = net_console
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    page.goto(url + "/admin/net")
    page.locator("[data-arc='n-c']").wait_for(timeout=10000)
    click_arc(page, "n-c")
    assert "net-row-sel" in page.locator("tr[data-member='n-c']").get_attribute("class")
    assert page.locator("tr.net-row-sel").count() == 1
    assert page.locator("[data-arc='n-c']").get_attribute("aria-pressed") == "true"
    page.locator("tr[data-member='n-b'] th").click()
    assert page.locator("[data-arc='n-b']").get_attribute("aria-pressed") == "true"
    assert page.locator("[data-arc='n-c']").get_attribute("aria-pressed") == "false"
    page.locator("[data-arc='n-a']").focus()
    page.keyboard.press("Enter")
    assert page.locator("tr[data-member='n-a']").get_attribute("data-selected") == "true"
    page.close()


def test_a_member_that_joins_grows_in_and_one_that_goes_fades_out(browser, net_console):
    url, backend = net_console
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    page.goto(url + "/admin/net")
    page.locator("[data-arc='n-a']").wait_for(timeout=10000)
    joined = dict(backend.members["members"][0], nodeId="n-d", self=False, url="http://127.0.0.1:18786", state="alive")
    backend.members["members"].append(joined)
    try:
        page.locator("[data-arc='n-d'].net-arc-in").wait_for(timeout=12000)      # the page's own refresh (5 s) draws it, growing in
        assert page.locator("[data-ring-count]").text_content() == "2 / 4"
    finally:
        backend.members["members"].remove(joined)
    page.locator("[data-arc='n-d'].net-arc-out").wait_for(timeout=12000)          # it fades out where it was
    page.locator("[data-arc='n-d']").wait_for(state="detached", timeout=5000)
    assert page.locator("[data-arc]").count() == 3
    page.close()


def test_at_phone_width_the_ring_is_above_the_table_and_nothing_scrolls_sideways(browser, net_console):
    url, _ = net_console
    page = browser.new_page(viewport={"width": 390, "height": 844})
    page.goto(url + "/admin/net")
    page.locator("[data-arc]").first.wait_for(timeout=10000)
    ring, table = page.locator("[data-ring]").bounding_box(), page.locator("[data-members]").bounding_box()
    assert ring["y"] + ring["height"] <= table["y"] + 1, (ring, table)
    assert ring["width"] <= 390
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    page.close()
