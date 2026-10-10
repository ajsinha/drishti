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

"""RUPAKA PHASE 0 PROOF OF CONCEPT, in Chromium (docs/architecture/RUPAKA_POC.md): /bi/poc under the strict content security policy
loads Perspective (grid and pivot tick from the live feed), loads DuckDB-Wasm and re-slices a masked Arrow extract in the browser,
and shows the mask for a user without raw. No CSP violation is logged and the page is not cross-origin isolated (no SharedArrayBuffer).

Skipped when Playwright or its Chromium is missing, or DuckDB-Wasm is not installed (tools/fetch-duckdb-wasm.sh). Starts the console
in-process on a free port against the stand-in server (tests/poc_backend.py); no port is fixed, so it may run beside others."""
import socket
import threading
import time

import pytest

from conftest import CONSOLE
from poc_backend import PocBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def url():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings
    from routes.bi_poc_routes import duckdb_base

    if not duckdb_base(CONSOLE / "web" / "static"):
        pytest.skip("DuckDB-Wasm is not installed: run tools/fetch-duckdb-wasm.sh")
    settings = load_settings(CONSOLE / "config")
    settings.as_dict().setdefault("bi", {})["poc_enabled"] = True
    app = create_app(settings)
    app.state.backend = PocBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}/bi/poc"
    server.should_exit = True
    thread.join(10)


@pytest.fixture()
def browser():   # one Chromium per test: a page that held DuckDB-Wasm's 36 MB module left the next page in the same browser process short of memory (RUPAKA_POC.md)
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed: skip, do not fail
            pytest.skip(f"needs Playwright's Chromium (playwright install chromium): {str(e).splitlines()[0]}")
        yield b
        b.close()


def _until(page, expression: str, seconds: float = 30.0):
    """Polls with evaluate (Playwright's wait_for_function evaluates a string, which the page's policy rightly refuses)."""
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = page.evaluate(expression)
        if value:
            return value
        time.sleep(0.1)
    raise AssertionError(f"timed out: {expression}")


def test_the_page_runs_under_the_strict_policy(browser, url):
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    messages = []
    page.on("console", lambda m: messages.append(m.text))
    page.on("pageerror", lambda e: messages.append(f"pageerror: {e}"))
    page.goto(url)

    # a. Perspective: the grid and the pivot are fed by the live feed and keep changing
    _until(page, "document.body.dataset.pspReady === 'true'")
    _until(page, "window.drishtiPoc.stats.feedRows >= 12")
    first = page.evaluate("window.drishtiPoc.stats.applied")
    _until(page, f"window.drishtiPoc.stats.applied > {first + 24}")
    assert page.evaluate("window.drishtiPoc.size()") == 12
    cells = page.evaluate("""() => { const walk = (n, out) => { n.querySelectorAll('*').forEach((e) => { if (e.tagName === 'TD') out.push(e.textContent); if (e.shadowRoot) walk(e.shadowRoot, out); }); return out; };
                                    return walk(document.getElementById('pocGrid'), []); }""")
    assert any(c.startswith("POC-") for c in cells), "the grid drew rows"
    assert any(c == "•••" for c in cells), "the masked trader column reads the mask in the grid"
    assert int(page.inner_text("#stUps")) >= 0 and int(page.inner_text("#stFps")) >= 0

    # b. DuckDB-Wasm: a masked Arrow extract is loaded and re-sliced in the browser
    page.click("#btnFetch")
    _until(page, "document.body.dataset.sliced !== undefined", 90)
    t = page.evaluate("window.drishtiPoc.timings")
    assert t["extractRows"] == 960 and t["sliceRows"] == 6 and t["arrowBytes"] > 1000
    assert page.locator("#sliceTable tbody tr").count() == 6
    page.select_option("#selGroup", "currency")
    _until(page, "document.body.dataset.sliced === '8'")
    page.select_option("#selFilter", "USD")
    _until(page, "document.querySelectorAll('#sliceTable tbody tr').length === 1")
    assert page.locator("#sliceTable tbody tr td").first.inner_text() == "USD"

    # c. the mask, for a user without raw
    page.click("#btnMasks")
    _until(page, "document.body.dataset.masks !== undefined")
    assert page.evaluate("document.body.dataset.masks") == "•••"
    assert page.locator("#maskPreview tbody tr").count() == 1 and page.locator("#maskAsYou tbody tr").count() > 1
    assert "TRDR-" in page.locator("#maskAsYou tbody").inner_text() and "TRDR-" not in page.locator("#maskPreview tbody").inner_text()

    # the policy held, and nothing needed SharedArrayBuffer
    assert page.evaluate("window.crossOriginIsolated") is False
    assert page.evaluate("window.drishtiPoc.stats.errors") == []
    assert [m for m in messages if "violates" in m or "pageerror" in m] == []
    page.close()


def test_the_synthetic_publisher_drives_the_table(browser, url):
    page = browser.new_page()
    page.goto(url)
    _until(page, "document.body.dataset.pspReady === 'true'")
    page.select_option("#pubRate", "500")
    _until(page, "window.drishtiPoc.stats.synRows > 1000")
    assert page.evaluate("window.drishtiPoc.size()") > 100
    page.select_option("#pubRate", "0")
    page.close()
