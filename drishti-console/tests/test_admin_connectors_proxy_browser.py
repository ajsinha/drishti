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

"""Admin → Connectors in a real browser, for the outbound proxy: the connector's page shows the effective proxy (the password masked) and where it
came from, the editor has a Proxy section that says the same, a literal proxy password is refused like any credential, and "Test connection" says that it
went through the proxy. Chromium through Playwright against a console with a stand-in server; skipped when Playwright or its Chromium is missing."""
import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_admin_connectors_browser import ConnectorBackend, _spec, _free_port, CONSOLE  # noqa: E402,F401
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

PROXY_SPECS = [_spec("proxy", description="Write 'proxy: none' to go direct", group="proxy"),
               _spec("proxy.url", description="Outbound HTTP proxy, http://host:port", group="proxy"),
               _spec("proxy.username", description="Proxy user", group="proxy"),
               _spec("proxy.password", secret=True, description="Proxy password", group="proxy")]
WEB = {"name": "rest", "tls": True, "proxy": True, "declared": True, "settings": [_spec("base-url", required=True, description="Base URL")] + PROXY_SPECS}
SITE = {"enabled": True, "source": "site", "sourceLabel": "site default", "url": "http://proxy.corp:3128", "username": "svc", "password": "***", "noProxy": [".corp.example"]}


class ProxyBackend(ConnectorBackend):
    def __init__(self):
        super().__init__()
        self.files["quotes"] = {"text": "plugin: rest\nkinds: [item]\nsettings:\n  base-url: https://api.example.com\n", "v": 1, "history": []}
        self.proxy = SITE

    async def admin(self, method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/connectors/plugins":
            return {"plugins": [WEB]}
        out = await super().admin(method, path, ident, body, timeout, headers, **params)
        if path == "/connectors/quotes" and method == "GET":
            return {**out, "proxy": self.proxy}
        if path == "/connectors/quotes/test":
            return {**out, "via": "through the site default proxy proxy.corp:3128", "proxy": {**SITE, "viaProxy": True}, "health": "UP via proxy proxy.corp:3128 (site default)"}
        return out


@pytest.fixture(scope="module")
def proxy_console():
    import threading
    import time

    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = ProxyBackend()
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


def test_the_page_and_the_editor_show_the_proxy_and_the_test_says_it_went_through_it(browser, proxy_console):
    url, backend = proxy_console
    page = browser.new_page()
    page.goto(url + "/admin/connectors/quotes")
    row = page.locator("[data-proxy]")
    text = row.inner_text()
    assert "http://proxy.corp:3128" in text and "site default" in text and "svc" in text and "***" in text and ".corp.example" in text
    assert "hunter2" not in page.content()

    page.click("button[data-act='edit']")
    dlg = page.locator("[data-edit-dialog]")
    dlg.locator("[data-f-name]").wait_for()
    assert dlg.locator("[data-group='proxy'] legend").inner_text() == "Proxy"
    now = dlg.locator("[data-proxy-now]").inner_text()
    assert "Effective proxy: http://proxy.corp:3128" in now and "site default" in now and "password ***" in now
    for s in ("proxy", "proxy.url", "proxy.username", "proxy.password"):
        assert dlg.locator(f"[data-setting='{s}']").count() == 1, s

    # a literal proxy password is refused like any credential, and nothing is sent
    sent = len(backend.calls)
    dlg.locator("[data-setting='proxy.password']").fill("hunter2")
    dlg.locator("[data-save]").click()
    err = dlg.locator("div.conn-field:has([data-setting='proxy.password']) [data-field-err]").inner_text()
    assert "never written into a connector file" in err
    assert not any(c[0] == "PUT" for c in backend.calls[sent:])
    dlg.locator("[data-setting='proxy.password']").fill("${PROXY_PASSWORD}")

    dlg.locator("[data-test]").click()
    dlg.locator("[data-test-via]").wait_for()
    assert "The test went through the site default proxy proxy.corp:3128" in dlg.locator("[data-test-via]").inner_text()
    assert "via proxy proxy.corp:3128" in dlg.locator("[data-test-out]").inner_text()
    page.close()


def test_a_connector_with_no_proxy_says_it_connects_direct(browser, proxy_console):
    url, backend = proxy_console
    backend.proxy = {"enabled": False, "source": "none", "sourceLabel": "none"}
    page = browser.new_page()
    page.goto(url + "/admin/connectors/quotes")
    assert "none: connects direct" in page.locator("[data-proxy]").inner_text()
    page.close()
