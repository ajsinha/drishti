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

"""QA2-16: Admin → Health on a phone (375 px wide) had 154 px of horizontal scroll: the external libraries' folder (a long path with no
space) and its jar list did not wrap. In Chromium, the console in-process against a stand-in server whose health names a long folder
and many jars; the page must not be wider than the screen, in the light and terminal themes."""
import threading
import time

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _free_port  # noqa: E402

LONG = "/opt/drishti-installations/production-cluster-node-07/releases/2.6.5/external_lib/java"
HEALTH = {"status": "OK", "summary": {"sources": 0, "sourcesDown": 0, "failedToStart": 0, "packs": 0, "packsWithProblems": 0},
          "server": {"version": "2.6.5", "uptimeSeconds": 60, "java": "21", "heapUsedMb": 300, "heapMaxMb": 4000, "threads": 60, "cpus": 8,
                     "directUsedBytes": 230 * 1048576, "directBuffers": 1234, "directMaxBytes": 256 * 1048576, "directPercent": 89,
                     "directWarning": True, "mappedUsedBytes": 812 * 1048576, "mappedBuffers": 96, "openFiles": 41234, "maxFiles": 1048576,
                     "deletedOpenFiles": 117},
          "sources": [], "packs": [], "live": {},
          "externalLib": {"java": LONG, "jars": [{"name": f"vendor-connector-driver-{i}.0.0-shaded-with-all-dependencies.jar"} for i in range(6)]},
          "tmp": {"base": "/var/tmp/drishti-installations/production-cluster-node-07", "path": "/var/tmp/drishti-installations/production-cluster-node-07/drishti-4242",
                  "bytes": 5242880, "files": 7}}


class HealthBackend(FakeBackend):
    async def admin(self, method, path, ident=None, body=None, **params):
        if path == "/health":
            return HEALTH
        if path == "/connectors":
            return {"connectors": []}
        return await super().admin(method, path, ident, body, **params)


@pytest.fixture(scope="module")
def url():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = HealthBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed
            pytest.skip(f"Chromium is not installed: {e}")
        yield b
        b.close()


@pytest.mark.parametrize("theme", ["light", "terminal"])
def test_health_fits_a_phone(url, browser, theme):
    ctx = browser.new_context(viewport={"width": 375, "height": 812}, is_mobile=True, has_touch=True)
    pg = ctx.new_page()
    errors = []
    pg.on("pageerror", lambda e: errors.append(str(e)))
    pg.add_init_script(f"document.addEventListener('DOMContentLoaded', () => document.documentElement.setAttribute('data-theme', '{theme}'))")
    try:
        pg.goto(url + "/admin/health")
        pg.wait_for_selector("[data-external-lib] [data-ext-java]")
        assert LONG in pg.inner_text("[data-ext-java]")
        assert "89%" in pg.inner_text("[data-direct]") and pg.is_visible("[data-direct-warning]")    # the memory row, in amber over 80%
        wide = pg.evaluate("document.documentElement.scrollWidth - window.innerWidth")
        assert wide <= 1, f"{wide} px of horizontal scroll"
        right = pg.evaluate("Math.max(...[...document.querySelectorAll('.hl-sum > span')].map(e => e.getBoundingClientRect().right))")
        assert right <= 376, right
        assert not errors, errors
    finally:
        ctx.close()
