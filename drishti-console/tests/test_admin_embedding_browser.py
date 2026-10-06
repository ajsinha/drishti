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

"""Admin → Embedding in a real browser: register an application (the secret is shown once, and not again after a reload), rotate the secret
(shown once), disable and enable, delete with its confirm, the keys (n, /), config applications read-only, phone width with finger-sized
buttons, and both themes. Runs Chromium through Playwright against a console with a stand-in server that keeps the registry in memory;
skipped when Playwright or its Chromium is missing."""
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from core.backend import BackendError  # noqa: E402
from test_admin_embedding import APPS, USAGE  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class EmbedBackend(FakeBackend):
    """The server's registry in memory: create, rotate, disable, enable, delete; the one from configuration refuses every change."""

    def __init__(self):
        super().__init__()
        self.apps = {a["id"]: dict(a) for a in APPS}
        self.rotations = []

    async def admin(self, method, path, ident, body=None, timeout=None, **params):
        if not path.startswith("/embed/"):
            return await super().admin(method, path, ident, body, **params)
        parts = path.split("/")[3:]
        if path == "/embed/usage":
            return {**USAGE, "apps": {k: USAGE["apps"].get(k, {**USAGE["apps"]["gitops"]}) for k in [*self.apps, "(unknown)"]}}
        if path == "/embed/apps" and method == "GET":
            return sorted(self.apps.values(), key=lambda a: a["id"])
        if path == "/embed/apps" and method == "POST":
            app = {**APPS[0], **{k: body[k] for k in ("id", "name", "origins", "kinds", "scopes") if k in body}, "hasSecret": body.get("secret", True),
                   "jwks": body.get("jwks"), "enabled": True}
            self.apps[app["id"]] = app
            return {"app": app, "secret": "first-secret-123" if body.get("secret", True) else None}
        app_id = parts[0]
        app = self.apps.get(app_id)
        if app is None:
            raise BackendError(404, "DRS-1001", f"no host application '{app_id}'")
        if app["fromConfig"]:
            raise BackendError(409, "DRS-2006", f"'{app_id}' is declared in configuration")
        if method == "DELETE":
            del self.apps[app_id]
            return None
        if parts[1] == "rotate-secret":
            self.rotations.append(body)
            return {"app": app, "secret": "second-secret-456"}
        app["enabled"] = parts[1] == "enable"
        return app


@pytest.fixture(scope="module")
def embed_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = EmbedBackend()
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


def _reloaded(page, click):
    with page.expect_navigation():
        click()


def test_register_secret_once_rotate_disable_enable_delete(browser, embed_console):
    url, backend = embed_console
    page = browser.new_page()
    page.goto(url + "/admin/embedding")
    page.locator("[data-apps]").wait_for()
    page.keyboard.press("n")                                                                  # the key opens the dialog, focus on the name
    dialog = page.locator("[data-new-dialog]")
    dialog.wait_for()
    assert page.evaluate("document.activeElement.name") == "name"
    dialog.locator("[name=name]").fill("Risk Portal")
    assert dialog.locator("[name=id]").input_value() == "risk-portal"                          # the id follows the name until it is edited
    dialog.locator("[name=origins]").fill("https://risk.bank.example\nhttps://risk2.bank.example")
    dialog.locator("[name=kinds]").fill("trade, counterparty")
    dialog.locator("[value=key]").check()
    assert dialog.locator("[data-jwks-field]").is_visible()
    dialog.locator("[value=secret]").check()
    assert not dialog.locator("[data-jwks-field]").is_visible()
    dialog.locator("[data-create]").click()
    secret = page.locator("[data-secret-dialog]")
    secret.wait_for()
    assert secret.locator("[data-secret-value]").input_value() == "first-secret-123"
    assert "only time" in secret.inner_text()
    assert backend.apps["risk-portal"]["origins"] == ["https://risk.bank.example", "https://risk2.bank.example"]
    assert backend.apps["risk-portal"]["kinds"] == ["trade", "counterparty"]
    _reloaded(page, lambda: secret.locator("[data-secret-done]").click())
    assert "first-secret-123" not in page.content()                                           # once: gone after the reload
    row = page.locator("tr[data-app=risk-portal]")
    assert "Risk Portal" in row.inner_text() and "enabled" in row.inner_text()

    row.locator("[data-rotate]").click()                                                      # rotate: a grace period, then a new secret shown once
    rot = page.locator("[data-rotate-dialog]")
    rot.wait_for()
    rot.locator("[data-rotate-grace]").fill("120")
    rot.locator("[data-rotate-go]").click()
    secret.wait_for()
    assert secret.locator("[data-secret-value]").input_value() == "second-secret-456"
    assert backend.rotations == [{"graceSeconds": 120}]
    _reloaded(page, lambda: page.keyboard.press("Escape"))                                    # Esc ends it the same way: forgotten
    assert "second-secret-456" not in page.content()

    row = page.locator("tr[data-app=risk-portal]")
    _reloaded(page, lambda: row.locator("[data-switch=disable]").click())                      # disable keeps the registration
    row = page.locator("tr[data-app=risk-portal]")
    assert "disabled" in row.inner_text() and not backend.apps["risk-portal"]["enabled"]
    _reloaded(page, lambda: row.locator("[data-switch=enable]").click())
    assert backend.apps["risk-portal"]["enabled"]

    page.locator("tr[data-app=risk-portal] [data-delete]").click()                             # delete asks first; the safe button has the focus
    confirm = page.locator("[data-delete-dialog]")
    confirm.wait_for()
    assert "Risk Portal" in confirm.inner_text()
    assert page.evaluate("document.activeElement.hasAttribute('data-close')")
    page.keyboard.press("Escape")
    assert "risk-portal" in backend.apps                                                       # cancelled: still there
    page.locator("tr[data-app=risk-portal] [data-delete]").click()
    _reloaded(page, lambda: confirm.locator("[data-delete-go]").click())
    assert "risk-portal" not in backend.apps and page.locator("tr[data-app=risk-portal]").count() == 0
    page.close()


def test_config_apps_are_read_only_and_the_filter_works(browser, embed_console):
    url, _ = embed_console
    page = browser.new_page()
    page.goto(url + "/admin/embedding")
    cfg = page.locator("tr[data-app=gitops]")
    assert "from config" in cfg.inner_text() and cfg.locator("button").count() == 0
    page.keyboard.press("/")
    assert page.evaluate("document.activeElement.hasAttribute('data-filter')")
    page.keyboard.type("gitops")
    assert page.locator("tr[data-app=client-crm]").is_hidden() and cfg.is_visible()
    page.fill("[data-filter]", "zzz")
    assert page.locator("[data-filter-none]").is_visible()
    page.close()


@pytest.mark.parametrize("width,theme", [(390, "light"), (1280, "dark")])
def test_the_page_fits_a_phone_and_both_themes(browser, embed_console, width, theme):
    url, _ = embed_console
    ctx = browser.new_context(viewport={"width": width, "height": 800}, color_scheme=theme, has_touch=width < 700, is_mobile=width < 700)
    page = ctx.new_page()
    page.goto(url + "/admin/embedding")
    page.locator("[data-apps]").wait_for()
    assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")      # no sideways page scroll
    for st in page.locator("[data-apps] .st").all():
        assert st.inner_text().strip()                                                           # a state always has its word
    if width < 700:
        small = page.evaluate("""() => [...document.querySelectorAll('main button, .adm button, .adm-bar input, .emb-help')]
            .filter(e => e.getBoundingClientRect().width && (e.getBoundingClientRect().width < 43.5 || e.getBoundingClientRect().height < 43.5))
            .map(e => e.className + ':' + e.textContent.trim().slice(0, 20))""")
        assert not small, small
        page.locator("[data-new]").click()
        dlg = page.locator("[data-new-dialog]")
        dlg.wait_for()
        box = dlg.bounding_box()
        assert box["x"] >= 0 and box["x"] + box["width"] <= width + 1
        small = page.evaluate("""() => [...document.querySelectorAll('[data-new-dialog] button, [data-new-dialog] input:not([type=radio]):not([type=checkbox])')]
            .filter(e => e.getBoundingClientRect().width && e.getBoundingClientRect().height < 43.5).map(e => e.name || e.textContent.trim())""")
        assert not small, small
    ctx.close()
