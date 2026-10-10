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

"""Build -> New pack against a REAL scratch server (the repackaged jar on a free port, sign-in on): three schemas and a small JSON Lines folder are
brought, every Sutra is drafted by the real auto-designer, the bundle is downloaded and verified with the command line's `pack verify`, then handed to
Admin -> Packs -> Deploy archive, whose checks and preview are read, the deploy confirmed, a second version deployed, and the first rolled back.
Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
import json
import pathlib
import subprocess
import sys

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from wb_live import BROWSER_WAIT_MS, TOKEN_SECRET, _stack  # noqa: E402

FIX = pathlib.Path(__file__).resolve().parents[2] / "docs" / "guides" / "examples" / "schemas"
ROOT = CONSOLE.parent


@pytest.fixture(scope="module")
def deploy_console(tmp_path_factory):
    yield from _stack(tmp_path_factory, {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET}, auth=True)


def sign_in(page, base):
    page.goto(base + "/login")
    page.fill("input[name=user]", "drishti-dev-admin")
    page.fill("input[name=password]", "drishti-dev-admin123")
    page.click("form button[type=submit], form input[type=submit]")
    page.wait_for_url(lambda u: "/login" not in u)


PLANS = []


def make_pack(page, base, name, version, data, download_to=None):
    """Runs the five steps for the three schemas and the folder, with this pack name and version; returns the output step's text."""
    page.on("response", lambda r: PLANS.append((r.status, r.text()[:300])) if r.url.endswith("/build/pack/api/plan") and r.status != 200 else None)
    page.goto(base + "/build/pack/new")
    page.set_input_files("[data-files]", [str(FIX / n) for n in ("trade.schema.json", "counterparty.schema.json", "book.schema.json")])
    page.locator("[data-total]:has-text('3 schemas')").wait_for()
    page.set_input_files("[data-folder]", str(data))
    page.locator("[data-total]:has-text('2 data files')").wait_for()
    page.click("[data-next]")
    page.locator(".pk-card").first.wait_for(timeout=BROWSER_WAIT_MS)
    # the scratch server already runs the finance pack, which defines trade and counterparty: this pack must not (two unrelated packs may not
    # define the same kind) unless it is a new version of that pack. The page says so on the cards and refuses to leave step 3; the kinds are
    # renamed on their cards (a second version of sg-deploy owns its renamed kinds, so it goes straight through).
    if name == "sg-deploy" and version == "1.0.0":
        assert "already defines the kind" in page.locator(".pk-card").first.inner_text()
        page.click("[data-next]")
        page.fill("#pkp-name", name)
        page.locator("#pkp-name").dispatch_event("change")
        page.locator("[data-pack-form] .pk-grid").first.wait_for()
        page.click("[data-next]")
        assert "already defines the kind" in page.locator("[data-msg]").inner_text() and page.locator("[data-step='3']").is_visible()
        page.click("[data-go='2']")
    for old in ("trade", "counterparty", "book"):
        page.fill(f"input[data-focus=name-{old}]", "sg" + old)
        page.locator(f"input[data-focus=name-{old}]").dispatch_event("change")
        page.locator(f"input[data-focus=name-{old}][value=sg{old}]").wait_for()
        page.wait_for_function("() => !document.querySelector('[data-next]').disabled")           # the plan is redone; the next rename waits for it
    page.wait_for_function("() => !document.querySelector('[data-msg]').innerText.includes('Planning')")
    page.click("[data-next]")
    assert page.locator("#pkp-name").count() == 1, (page.locator("[data-msg]").inner_text(), PLANS[-1:])
    page.fill("#pkp-name", name)
    page.fill("#pkp-version", version)
    page.locator("#pkp-version").dispatch_event("change")
    page.locator("#pkp-name").dispatch_event("change")
    page.click("[data-next]")
    page.locator(".pk-item").first.wait_for(timeout=BROWSER_WAIT_MS)
    page.click("[data-next]")
    page.locator("[data-download]").wait_for(timeout=BROWSER_WAIT_MS)
    return page.locator("[data-output]").inner_text()


def test_schemas_and_a_folder_to_a_verified_bundle_a_deploy_and_a_rollback(browser, deploy_console, tmp_path):
    base = str(deploy_console)
    data = tmp_path / "jsonl"
    data.mkdir()
    with open(data / "trades.jsonl", "w") as f:
        for i in range(1, 25):
            f.write(json.dumps({"tradeId": f"TRD-{i:04d}", "counterpartyId": f"CPT-{i % 3 + 1:04d}", "bookId": "BK-0001", "status": ["LIVE", "PENDING"][i % 2],
                                "productType": ["IRS", "FXS", "FUT"][i % 3], "notional": 1000.0 * i, "currency": "USD", "tradeDate": f"2026-03-0{1 + i % 2}", "mtm": 5.5 * i}) + "\n")
    with open(data / "counterparties.jsonl", "w") as f:
        for i in range(1, 4):
            f.write(json.dumps({"id": f"CPT-{i:04d}", "name": f"C{i}", "rating": "AA"}) + "\n")
    ctx = browser.new_context(accept_downloads=True)
    page = ctx.new_page()
    page.set_default_timeout(BROWSER_WAIT_MS)
    page.on("dialog", lambda d: d.accept())                         # Deploy and Roll back ask "are you sure"
    sign_in(page, base)

    out = make_pack(page, base, "sg-deploy", "1.0.0", data)
    assert "sg-deploy-1.0.0.tar.gz" in out and "Check and deploy in Admin" in out
    with page.expect_download() as dl:
        page.click("[data-download]")
    archive = tmp_path / dl.value.suggested_filename
    dl.value.save_as(archive)
    # the command line verifies what the page produced: checksums, schema, server version, and the real sutra lint and test
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "pack", "verify", str(archive)], capture_output=True, text=True, cwd=ROOT)
    assert r.returncode == 0, r.stdout + r.stderr
    assert r.stdout.rstrip().endswith("verified") and "sutra     ok" in r.stdout

    # hand it to Admin -> Packs -> Deploy archive: checked and previewed there, deployed only when confirmed
    page.click("[data-deploy-link]")
    page.wait_for_url(lambda u: "/admin/packs" in u)
    page.locator("[data-result]:not([hidden])").wait_for()
    result = page.locator("[data-result]").inner_text()
    assert "sutra lint" in result and "sg-deploy 1.0.0" in result and "new pack" in result.lower()
    assert "FAIL" not in result
    page.click("[data-deploy-go]")
    page.wait_for_function("() => document.querySelector('[data-history]').innerText.includes('sg-deploy')")
    assert "deploy" in page.locator("[data-history]").inner_text()

    # a second version, the same way, then back to the first
    make_pack(page, base, "sg-deploy", "1.0.1", data)
    page.click("[data-deploy-link]")
    page.wait_for_url(lambda u: "/admin/packs" in u)
    page.locator("[data-result]:not([hidden])").wait_for()
    assert "1.0.0" in page.locator("[data-result]").inner_text()                       # what it replaces
    page.click("[data-deploy-go]")
    page.wait_for_function("() => document.querySelector('[data-history]').innerText.includes('1.0.1')")
    page.locator("[data-kept] button, [data-kept] [data-rollback]").first.click()
    page.wait_for_function("() => document.querySelector('[data-history]').innerText.toLowerCase().includes('rollback')")
    ctx.close()
