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

"""Admin → Lakes → Maintain in a real browser on a tiny lake: a table another system published is listed with its checkbox disabled and the
reason in view, a dry run shows files and size before → after, Maintain now merges the small files and tells the server, the history lists
both, a schedule is created with the day-and-time picker and paused; and the page at phone width without sideways scrolling.
Chromium through Playwright against a console with a stand-in server; skipped when Playwright, its Chromium or deltalake is missing.

DRISHTI_DOC_SHOTS=<folder> also saves the screenshots of docs/admin/OPERATIONS.md there."""
import importlib.util
import os
import threading
import time
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
if importlib.util.find_spec("deltalake") is None:
    pytest.skip("the lake needs deltalake and pyarrow", allow_module_level=True)

from conftest import CONSOLE, FakeBackend  # noqa: E402
from core.lakes import Lakes, LakeSettings  # noqa: E402
from test_admin_lake_maintain import make_lake  # noqa: E402
from test_admin_lakes_browser import _free_port  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

REPO = Path(__file__).resolve().parents[2]
SHOTS = os.environ.get("DRISHTI_DOC_SHOTS")


class SignalBackend(FakeBackend):
    def __init__(self):
        super().__init__()
        self.announced = []

    async def announce_load(self, pack, body, ident):
        self.announced.append((pack, body))
        return {"verified": "verified"}


@pytest.fixture(scope="module")
def maint_console(tmp_path_factory):
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    work = tmp_path_factory.mktemp("maint")
    root = work / "lake"
    make_lake(root)
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = SignalBackend()
    app.state.lakes = Lakes(LakeSettings(roots=[root], tools_dir=REPO / "tools", engine="deltalake", data_dir=work / "data", schedules=False))
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}", app.state.backend, root, work
    server.should_exit = True
    thread.join(10)


def shot(page, name, locator=None):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        (locator or page).screenshot(path=str(Path(SHOTS) / name))


def test_dry_run_maintain_foreign_disabled_and_a_schedule(browser, maint_console):
    url, backend, root, work = maint_console
    page = browser.new_page(viewport={"width": 1400, "height": 1000})
    page.goto(url + "/admin/lakes")
    foreign = page.locator('[data-lake-table="risk/published"]')
    assert foreign.locator("input[type=checkbox]").is_disabled()
    assert "Maintained by its publisher" in foreign.inner_text()
    page.locator('[data-mt-pick][value="trading/trade"]').check()
    assert page.locator("[data-mt-count]").inner_text() == "1 table selected"
    page.wait_for_function("() => document.querySelector('[data-mt-cli]').textContent.includes('--tables trading/trade')")
    shot(page, "lake-maintain-tables.png", page.locator("[data-maint]"))

    page.locator("[data-mt-dry]").click()
    page.wait_for_function("() => document.querySelector('[data-mt-run-text]').textContent.startsWith('Dry run finished')", timeout=60000)
    row = page.locator("[data-mt-result] tbody tr").first.inner_text()
    assert "6 → 3 (estimate)" in row and "keeps every date" in row
    shot(page, "lake-maintain-dry-run.png", page.locator("[data-mt-runbox]"))

    page.locator("[data-mt-run]").click()
    page.wait_for_function("() => document.querySelector('[data-mt-run-text]').textContent.startsWith('Finished')", timeout=60000)
    row = page.locator("[data-mt-result] tbody tr").first.inner_text()
    assert "6 → 3" in row and "readers told (2026-10-16)" in row, row
    assert [b["source"] for _, b in backend.announced] == ["lake maintain"]
    page.wait_for_function("() => document.querySelectorAll('[data-mt-history] tbody tr').length === 2")
    assert "dry run" in page.locator("[data-mt-history] tbody tr").nth(1).inner_text()
    assert page.locator('[data-lake-table="trading/trade"] [data-mt-files]').inner_text() == "3"

    page.locator("[data-mt-sched-panel] summary").click()
    page.locator("[data-mt-day][value=sat]").uncheck()
    assert page.locator("[data-mt-cron]").input_value() == "30 2 * * 2,3,4,5"
    assert page.locator("[data-mt-friendly]").inner_text() == "Tue–Fri 02:30"
    page.locator("[data-mt-day][value=sat]").check()
    page.locator("[data-mt-sname]").fill("nightly")
    page.locator("[data-mt-add]").click()
    sched = page.locator('[data-mt-schedule="nightly"]')
    sched.wait_for()
    assert "Tue–Sat 02:30" in sched.inner_text() and "trading/trade" in sched.inner_text()
    shot(page, "lake-maintain-schedules.png", page.locator("[data-mt-sched-panel]"))
    sched.locator("[data-mt-pause]").click()
    page.wait_for_function("() => document.querySelector('[data-mt-schedule=\"nightly\"]').textContent.includes('paused')")
    assert page.locator('[data-mt-schedule="nightly"] [data-mt-pause]').inner_text() == "Resume"
    assert "paused: true" in (work / "data" / "lakes" / "lake-maintenance.yaml").read_text()
    page.locator("[data-mt-settings-panel] summary").click()
    page.locator('[data-mt-set="vacuum-hours"]').fill("24")
    assert page.locator("[data-mt-vacuum-warn]").is_visible()
    shot(page, "lake-maintain-settings.png", page.locator("[data-mt-settings-panel]"))
    shot(page, "lake-maintain-history.png", page.locator("[data-mt-history]"))
    page.close()


def test_phone_width_has_no_sideways_scroll(browser, maint_console):
    url, _, _, _ = maint_console
    page = browser.new_page(viewport={"width": 390, "height": 844})
    page.goto(url + "/admin/lakes")
    page.locator("[data-mt-sched-panel] summary").click()
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    for sel in ("[data-mt-dry]", "[data-mt-run]", '[data-mt-pick][value="trading/trade"]'):
        box = page.locator(sel).locator("xpath=ancestor-or-self::*[self::button or self::label][1]").bounding_box()
        assert box and box["height"] >= 40, sel
    page.close()
