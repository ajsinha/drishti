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

"""The Admin screens' forms on drishti-inputs' Form from the server's schema (core/admin_forms.py, docs/architecture/ELEMENTS.md section
12.3): Roles, Users and the searches post what they always posted (an unticked power or Enabled box is false, never left out), the
fields keep their names and hooks. Runs Chromium through Playwright against a console with a stand-in server; skipped when Playwright or
its Chromium is missing."""
import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def console():
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
    yield f"http://127.0.0.1:{port}", app.state.backend
    server.should_exit = True
    thread.join(10)


def _no_raw_controls(page, scope="main"):
    """Every input, select and text area on the page is drawn by drishti-inputs (inside a .di root) or is hidden."""
    stray = page.evaluate("""(scope) => Array.from(document.querySelectorAll(scope + ' input, ' + scope + ' select, ' + scope + ' textarea'))
        .filter((e) => e.type !== 'hidden' && String(e.className).indexOf('tbl-pg') < 0 && !e.closest('.di, .di-form-el, [data-di-form], .ag, .dg, .di-code, nav, header'))
        .map((e) => e.outerHTML.slice(0, 120))""", scope)
    assert stray == [], stray


def _calls(backend, method, path):
    return [c for c in backend.calls if c[0] == "admin" and c[1] == method and c[2] == path]


def test_a_role_is_saved_with_every_power_said_and_the_kinds_follow_opens_every_kind(browser, console):
    url, backend = console
    page = browser.new_page()
    page.on("dialog", lambda d: d.accept())
    page.goto(url + "/admin/roles")
    _no_raw_controls(page, ".adm")
    page.click("[data-new]")
    dlg = page.locator("[data-dialog]")
    dlg.locator("[name=name]").fill("Bad Name")
    dlg.locator("button[type=submit]").click()
    assert "must look like" in dlg.inner_text()                                     # the pattern in words; nothing sent
    assert not [c for c in backend.calls if c[2].startswith("/role-definitions/") and c[1] == "PUT"]
    dlg.locator("[name=name]").fill("desk-reader")
    dlg.locator("[name=description]").fill("Reads the desk")
    dlg.locator("[name=kinds]").fill("counterparty")
    btn = dlg.locator("[data-pack-kinds]").first
    want = ["counterparty"] + [k for k in (btn.get_attribute("data-pack-kinds") or "").split() if k != "counterparty"]
    btn.click()                                                                       # a pack's kinds added to the list
    assert dlg.locator("[name=kinds]").input_value().split() == want
    dlg.locator("[name=raw]").check()
    dlg.locator("[name=all]").check()
    assert dlg.locator("[name=kinds]").is_hidden() and dlg.locator("fieldset[data-kinds]").is_hidden()
    dlg.locator("button[type=submit]").click()
    page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Created')")
    put = [c for c in backend.calls if c[1] == "PUT" and c[2] == "/role-definitions/desk-reader"][-1][3]
    assert put["kinds"] == ["*"] and put["raw"] is True and put["layout"] is True
    for k in ("author", "approve", "admin", "calc", "edit"):
        assert put[k] is False, k                                                    # unticked: false, never missing
    page.close()


def test_editing_a_role_shows_its_values_and_unticking_layouts_saves_false(browser, console):
    url, backend = console
    page = browser.new_page()
    page.goto(url + "/admin/roles")
    page.click("tr[data-role='credit-analyst'] [data-edit]")
    dlg = page.locator("[data-dialog]")
    assert dlg.locator("[name=name]").input_value() == "credit-analyst"
    assert dlg.locator("[name=name]").get_attribute("readonly") is not None
    assert dlg.locator("[name=kinds]").input_value() == "counterparty\ncredit-curve"
    assert dlg.locator("[name=layout]").is_checked() and not dlg.locator("[name=raw]").is_checked()
    dlg.locator("[name=layout]").uncheck()
    dlg.locator("button[type=submit]").click()
    page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Saved')")
    put = [c for c in backend.calls if c[1] == "PUT" and c[2] == "/role-definitions/credit-analyst"][-1][3]
    assert put["layout"] is False and put["kinds"] == ["counterparty", "credit-curve"]
    page.close()


def test_a_new_user_needs_a_password_and_unticked_enabled_is_false(browser, console):
    url, backend = console
    page = browser.new_page()
    page.goto(url + "/admin/users")
    _no_raw_controls(page, ".adm")
    page.click("[data-new]")
    dlg = page.locator("[data-dialog]")
    dlg.locator("[name=username]").fill("ada.l")
    dlg.locator("button[type=submit]").click()
    assert "Initial password is required" in dlg.inner_text()
    dlg.locator("[name=password]").fill("correct-horse-42")
    dlg.locator("[name=roles][value=risk]").check()
    dlg.locator("[name=enabled]").uncheck()
    dlg.locator("button[type=submit]").click()
    page.wait_for_function("() => document.querySelector('[data-msg]').textContent.includes('Created')")
    body = _calls(backend, "POST", "/users")[-1][3]
    assert body["username"] == "ada.l" and body["password"] == "correct-horse-42" and body["roles"] == ["risk"]
    assert body["enabled"] is False and body["mustChangePassword"] is False
    page.close()


def test_the_searches_submit_their_old_names(browser, console):
    url, _ = console
    page = browser.new_page()
    page.goto(url + "/admin/users")
    page.locator("[data-search-fields] input[name=q]").fill("admin")
    page.locator("[data-search-fields] input[name=q]").press("Enter")
    page.wait_for_url("**/admin/users?q=admin")
    assert page.locator("[data-search-fields] input[name=q]").input_value() == "admin"
    page.goto(url + "/admin/audit")
    _no_raw_controls(page, ".adm")
    page.locator("input[name=subject]").fill("system")
    page.locator("input[name=subject]").press("Enter")
    page.wait_for_url("**/admin/audit?subject=system")
    page.close()
