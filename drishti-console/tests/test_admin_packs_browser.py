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
confirmed; a pack's data source is edited, tested (dates and row counts), saved as an override and reset. Keyboard and phone width are
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
        self.override = {}
        self.sent = {"upload": None, "confirm": None, "save": None, "tests": []}

    def datasource(self):
        root = self.override.get("root", "/data/lake-a")
        return {"pack": "lakepack", "file": "/srv/drishti/data/packs/settings/lakepack.yaml", "overridden": bool(self.override), "plugins": ["delta", "file"],
                "connectors": [{"name": "item-store", "plugin": "file", "kinds": ["item", "thing"],
                                "enabled": {"pack": "true", "override": None, "site": None, "effective": "true", "on": True, "source": "pack"},
                                "settings": [
                                    {"key": "root", "pack": "/data/lake-a", "override": self.override.get("root"), "site": None, "effective": root, "resolved": root,
                                     "resolvable": True, "secret": False, "source": "override" if "root" in self.override else "pack", "overridden": "root" in self.override},
                                    {"key": "lookback-days", "pack": "10", "override": None, "site": "7", "effective": "7", "resolved": "7", "resolvable": True,
                                     "secret": False, "source": "site", "overridden": False},
                                    {"key": "password", "pack": None, "override": None, "site": None, "effective": "", "resolved": None, "resolvable": True,
                                     "secret": True, "source": "pack", "overridden": False}]}]}

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
                     "extends": [], "requiredBy": [], "kinds": ["item", "thing"], "connectors": ["item-store"], "mnemonics": ["ITM"],
                     "dataSourceOverridden": bool(self.override)}]
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
        if path == "/packs/lakepack/datasource" and method == "PUT":
            self.sent["save"] = body
            self.override = dict(body["connectors"].get("item-store", {}).get("settings", {}))
            return {"changed": ["item-store.root"], "overridden": bool(self.override), "restarting": False, "note": "Saved; it takes effect when the server next starts."}
        if path == "/packs/lakepack/datasource" and method == "DELETE":
            self.override = {}
            return {"overridden": False, "restarting": False, "note": "Saved; it takes effect when the server next starts."}
        if path == "/packs/lakepack/datasource/test":
            self.sent["tests"].append(body)
            root = (((body.get("connectors") or {}).get("item-store") or {}).get("settings") or {}).get("root", "/data/lake-a")
            rows = [("2026-10-05", 5), ("2026-10-02", 3)] if root.endswith("lake-b") else [("2026-10-05", 2)]
            return {"pack": "lakepack", "tested": "the edited settings, not yet saved", "ok": True, "connectors": [{
                "connector": "item-store", "plugin": "file", "ok": True, "health": "UP", "ms": 4, "error": None,
                "kinds": [{"kind": "item", "exact": True, "note": "", "dates": [{"date": d, "rows": n} for d, n in rows]},
                          {"kind": "thing", "exact": True, "note": "nothing found for this kind", "dates": []}]}]}
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


def test_the_data_source_is_edited_tested_saved_and_reset(browser, packs_console):
    url, backend = packs_console
    backend.override = {}
    page = browser.new_page()
    page.on("dialog", lambda d: d.accept())
    page.goto(url + "/admin/packs")
    opener = page.locator("[data-datasource]")
    opener.click()
    dialog = page.locator("[data-ds-dialog]")
    dialog.locator("[data-connector]").wait_for()
    text = dialog.inner_text()
    for t in ("Data source of lakepack", "item-store", "root", "pack default", "lookback-days", "set by the site: wins over this file", "password", "Precedence, highest first"):
        assert t in text, t
    site_row = dialog.locator("tr[data-key='lookback-days'] input")
    assert site_row.is_disabled() and site_row.input_value() == "7"                              # the site wins: not editable here
    root = dialog.locator("tr[data-key='root'] input")
    assert root.input_value() == "/data/lake-a"
    root.fill("/data/lake-b")
    row = dialog.locator("tr[data-key='root']").inner_text()
    assert "overridden here" in row and "pack default: /data/lake-a" in row                       # pack default against override, in words
    dialog.locator("[data-ds-test]").click()
    dialog.locator("[data-test-out] table").wait_for()
    out = dialog.locator("[data-test-out]").inner_text()
    assert "Reachable" in out and "2026-10-05" in out and "5" in out and "2026-10-02" in out and "3" in out and "nothing found for this kind" in out
    assert backend.sent["tests"][-1]["connectors"] == {"item-store": {"settings": {"root": "/data/lake-b"}}}   # only the difference from the pack is sent
    dialog.locator("[data-ds-save]").click()
    page.wait_for_function("() => document.querySelector('[data-pack] .st-warn') !== null", timeout=15000)       # reloaded: the pack is badged
    assert backend.sent["save"] == {"connectors": {"item-store": {"settings": {"root": "/data/lake-b"}}}}
    assert "data source overridden" in page.locator("tr[data-pack=lakepack]").first.inner_text()
    page.locator("[data-datasource]").click()
    page.locator("[data-ds-dialog] [data-connector]").wait_for()
    again = page.locator("[data-ds-dialog] tr[data-key='root']")
    assert again.locator("input").input_value() == "/data/lake-b"
    again.get_by_role("button", name="Use the pack default for item-store root").click()
    assert again.locator("input").input_value() == "/data/lake-a" and "pack default" in again.inner_text()
    page.locator("[data-ds-reset]").click()
    page.wait_for_function("() => document.querySelector('[data-pack] .st-warn') === null", timeout=15000)
    assert backend.override == {}
    page.close()


def test_the_data_source_panel_works_from_the_keyboard(browser, packs_console):
    url, backend = packs_console
    backend.override = {}
    page = browser.new_page()
    page.goto(url + "/admin/packs")
    page.locator("[data-datasource]").focus()
    page.keyboard.press("Enter")
    dialog = page.locator("[data-ds-dialog]")
    dialog.locator("[data-connector]").wait_for()
    page.wait_for_function("() => document.activeElement && document.activeElement.getAttribute('aria-label') === 'item-store root'", timeout=3000)
    assert page.evaluate("() => document.activeElement.getAttribute('aria-label')") == "item-store root"       # focus lands on the first editable setting
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
    page.locator("[data-ds-dialog] [data-connector]").wait_for()
    box = page.locator("[data-ds-dialog]").bounding_box()
    assert box["x"] >= 0 and box["x"] + box["width"] <= 391
    assert page.locator("[data-ds-save]").is_visible()
    colour = page.evaluate("() => getComputedStyle(document.querySelector('[data-ds-dialog]')).color")
    background = page.evaluate("() => getComputedStyle(document.querySelector('[data-ds-dialog]')).backgroundColor")
    assert colour != background                                                                               # readable in the dark theme too
    ctx.close()
