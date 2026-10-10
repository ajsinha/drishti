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

"""Admin → Packs in a real browser: an archive is uploaded, its checks and its preview (breaking changes marked) are read, the deploy is
confirmed; a pack's data source is a read-only view of its connectors, with links to Admin → Connectors. Keyboard and phone width are
covered. Runs Chromium through Playwright against a console with a stand-in server; skipped when Playwright or its Chromium is missing."""
import json
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


class PackBackend(FakeBackend):
    """A stand-in server that keeps what Admin → Packs changes: the pack's version, the history, the data-source override."""

    def __init__(self):
        super().__init__()
        self.version = "1.0.0"
        self.history = []
        self.sent = {"upload": None, "confirm": None, "save": None, "tests": []}

    def datasource(self):
        return {"pack": "lakepack", "directory": "/srv/drishti/config/connectors", "missing": ["thing-stream"], "connectors": [
            {"name": "item-store", "defined": True, "origin": "file", "state": "RUNNING", "health": "UP", "plugin": "file", "kinds": ["item"], "problems": [],
             "editUrl": "/admin/connectors?name=item-store", "createUrl": "/admin/connectors?new=item-store", "hasTemplate": True},
            {"name": "thing-stream", "defined": False, "state": "NOT_CONFIGURED", "problems": ["connector thing-stream is not configured"], "kinds": ["thing"],
             "editUrl": "/admin/connectors?name=thing-stream", "createUrl": "/admin/connectors?new=thing-stream", "hasTemplate": False}]}

    async def admin_upload(self, path, ident, data, headers, timeout=120.0):
        self.sent["upload"] = {"size": len(data), "headers": headers}
        return {"ok": True, "uploadId": "up1", "file": headers.get("X-Drishti-Filename"), "size": len(data), "sha256": "ab" * 32, "pack": "lakepack", "version": "2.0.0",
                "checks": [{"name": "archive", "ok": True, "detail": "9 bytes", "warn": False}, {"name": "manifest", "ok": True, "detail": "3 files listed; every checksum matches", "warn": False},
                           {"name": "sutra lint", "ok": True, "detail": "1 Sutra file(s) passed", "warn": False},
                           {"name": "signature", "ok": True, "detail": "not signed", "warn": True}],
                "preview": {"pack": "lakepack", "newVersion": "2.0.0", "state": "upgrade", "versionOrder": "newer",
                            "running": {"version": self.version, "loaded": True, "where": "loaded"},
                            "counts": {"breaking": 1, "selection": 0, "layout": 0, "change": 1},
                            "findings": [{"level": "breaking", "what": "kind removed", "name": "thing", "detail": "monitors, workspaces and alerts that name it stop working"},
                                         {"level": "change", "what": "version", "name": "lakepack", "detail": f"{self.version} -> 2.0.0"}]}}

    async def admin(self, method, path, ident, body=None, timeout=None, **params):
        if path == "/packs":
            return [{"name": "lakepack", "title": "Lake pack", "description": "Items", "version": self.version, "loaded": True, "added": False, "enabled": True,
                     "extends": [], "requiredBy": [], "kinds": ["item", "thing"], "connectors": ["item-store", "thing-stream"], "mnemonics": ["ITM"]}]
        if path == "/registry":
            return {"url": "", "configured": False, "packs": []}
        if path == "/packs/history":
            kept = [{"version": "1.0.0", "keptAt": "2026-10-05T09:00:00Z"}] if self.history else []
            return {"history": list(reversed(self.history)), "kept": {"lakepack": kept}}
        if method == "POST" and path == "/packs/deploy/up1":
            self.sent["confirm"] = params
            self.history.append({"at": "2026-10-05T10:00:00Z", "action": "deploy", "pack": "lakepack", "version": "2.0.0", "previous": self.version, "by": "ann", "detail": "from x"})
            self.version = "2.0.0"
            return {"deployed": "2.0.0", "previous": "1.0.0", "restarting": False, "note": "Saved; it takes effect when the server next starts."}
        if method == "DELETE" and path == "/packs/deploy/up1":
            return {"discarded": True}
        if path == "/packs/lakepack/datasource" and method == "GET":
            return self.datasource()
        return await super().admin(method, path, ident, body, **params)


@pytest.fixture(scope="module")
def packs_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = PackBackend()
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


def test_an_archive_is_checked_previewed_and_deployed(browser, packs_console):
    url, backend = packs_console
    page = browser.new_page()
    page.on("dialog", lambda d: d.accept())
    page.goto(url + "/admin/packs")
    assert page.locator("[data-upload]").is_disabled()                                           # nothing chosen yet
    page.set_input_files("[data-file]", {"name": "lakepack-2.0.0.tar.gz", "mimeType": "application/gzip", "buffer": b"NOT-REALLY-A-TAR"})
    page.click("[data-upload]")
    page.locator("[data-result]:not([hidden])").wait_for()
    result = page.locator("[data-result]").inner_text()
    for text in ("OK archive", "OK manifest", "NOTE signature", "kind removed", "thing", "1 breaking", "Breaking: monitors, workspaces, alerts or saved links may stop working",
                 "1.0.0 (loaded) becomes 2.0.0"):
        assert text in result, text
    assert backend.sent["upload"]["headers"]["X-Drishti-Filename"] == "lakepack-2.0.0.tar.gz" and backend.sent["upload"]["size"] == 16   # the archive's own bytes
    assert page.locator(".dep-lv-breaking[role=alert]").is_visible()
    go = page.locator("[data-deploy-go]")
    assert go.is_disabled()                                                                      # a breaking change needs an explicit yes
    page.check("[data-ack]")
    assert go.is_enabled()
    go.click()
    page.wait_for_function("() => document.querySelector('[data-history]').innerText.includes('2.0.0')", timeout=15000)   # the page reloads with the new history
    assert backend.sent["confirm"] == {"acceptBreaking": "true"} and backend.version == "2.0.0"
    table = page.locator("[data-history]").inner_text()
    assert "deploy" in table and "lakepack" in table and "1.0.0" in table and "ann" in table
    assert "Roll back to this" in page.locator("[data-kept]").inner_text()
    page.close()


def test_the_data_source_lists_the_packs_connectors_and_links_to_edit_or_create_them(browser, packs_console):
    url, backend = packs_console
    page = browser.new_page()
    page.goto(url + "/admin/packs")
    page.locator("[data-datasource]").click()
    dialog = page.locator("[data-ds-dialog]")
    dialog.locator("[data-connector]").first.wait_for()
    text = dialog.inner_text()
    for t in ("Connectors of lakepack", "item-store", "running", "thing-stream", "not configured", "nothing defines it", "/srv/drishti/config/connectors", "Admin"):
        assert t in text, t
    assert dialog.locator("[data-connector='item-store'] a").get_attribute("href") == "/admin/connectors?name=item-store"
    assert dialog.locator("[data-connector='thing-stream'] a").get_attribute("href") == "/admin/connectors?new=thing-stream"      # create, pre-filled from the pack's suggestion
    assert dialog.locator("[data-ds-msg]").inner_text().startswith("1 connector is not configured")
    assert dialog.locator("input, textarea").count() == 0                                          # a view: nothing to edit here
    page.close()


def test_the_data_source_panel_works_from_the_keyboard(browser, packs_console):
    url, backend = packs_console
    page = browser.new_page()
    page.goto(url + "/admin/packs")
    page.locator("[data-datasource]").focus()
    page.keyboard.press("Enter")
    dialog = page.locator("[data-ds-dialog]")
    dialog.locator("[data-connector]").first.wait_for()
    page.wait_for_function("() => document.activeElement && document.activeElement.tagName === 'A'", timeout=3000)   # focus lands on the first link
    assert dialog.get_attribute("aria-labelledby") == "dsTitle"
    page.keyboard.press("Escape")
    page.wait_for_function("() => !document.querySelector('[data-ds-dialog]').open")
    assert page.evaluate("() => document.activeElement.hasAttribute('data-datasource')")                     # and returns to what opened it
    page.close()


def test_the_pack_pages_fit_a_phone_and_the_dark_theme(browser, packs_console):
    url, backend = packs_console
    ctx = browser.new_context(viewport={"width": 390, "height": 800}, color_scheme="dark")
    page = ctx.new_page()
    page.goto(url + "/admin/packs")
    assert page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 1")              # no sideways page scroll
    page.locator("[data-datasource]").click()
    page.locator("[data-ds-dialog] [data-connector]").first.wait_for()
    box = page.locator("[data-ds-dialog]").bounding_box()
    assert box["x"] >= 0 and box["x"] + box["width"] <= 391
    assert page.locator("[data-ds-close]").is_visible()
    colour = page.evaluate("() => getComputedStyle(document.querySelector('[data-ds-dialog]')).color")
    background = page.evaluate("() => getComputedStyle(document.querySelector('[data-ds-dialog]')).backgroundColor")
    assert colour != background                                                                               # readable in the dark theme too
    ctx.close()
