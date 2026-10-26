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

"""The funds pack in Chromium over the real server and console (ports 18994 and 17994), read from a demo lake built from the committed
snapshot (`drishti.py lake demo --only us-equities,funds`) with the demo connector off: FUND QQQ is the fact sheet with its tiles and
top ten holdings, and its two tabs (Look-through holdings with every holding linked to its issuer; vs Nasdaq-100 with the computed
index weights); USEQ AAPL's Held by funds tab lists the funds holding Apple; FUND VTSAX (a share class) opens the fund VTI. Skipped
when Playwright, Chromium, deltalake or the built server jar is missing, or a scratch port is in use. Never run with pytest -n."""
from __future__ import annotations

import glob
import os
import subprocess
import sys
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
pytest.importorskip("deltalake", reason="lake demo needs deltalake and pyarrow")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import news_stack as NS  # noqa: E402  (listening, wait)
from java_launch import java_cmd  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SERVER_PORT = int(os.environ.get("DRISHTI_FUNDS_SERVER_PORT", 18994))
CONSOLE_PORT = int(os.environ.get("DRISHTI_FUNDS_CONSOLE_PORT", 17994))
SERVER, CONSOLE = f"http://127.0.0.1:{SERVER_PORT}", f"http://127.0.0.1:{CONSOLE_PORT}"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (SERVER_PORT, CONSOLE_PORT):
        if NS.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
    if not jars or not Path(java).exists():
        pytest.skip("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
    tmp = tmp_path_factory.mktemp("fundsstack")
    work, lake = tmp / "server", tmp / "delta"
    work.mkdir()
    built = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--only", "us-equities,funds", "--lake", str(lake),
                            "--as-of", "today", "--connectors-dir", str(work / "connectors"), "--no-signal", "-q"],
                           env=dict(os.environ, DRISHTI_DATA_DIR=str(tmp / "data")), capture_output=True, text=True, timeout=900)
    assert built.returncode == 0, built.stderr[-2000:]
    env = dict(os.environ, DRISHTI_PACKS="us-equities,funds", DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_DEMO_ENABLED="false",
               DRISHTI_SECURITY_ENABLED="false", DRISHTI_CONNECTORS_DIR=str(work / "connectors"), DRISHTI_DELTA_ROOT=str(lake),
               DRISHTI_DATA_DIR=str(work / "data"), DRISHTI_OFFLINE="true")
    procs = [subprocess.Popen(java_cmd(java, ["-Xmx1500m", "-jar", jars[0], f"--server.port={SERVER_PORT}"], tmp=tmp / "jvm-tmp"), cwd=work, env=env,
                              stdout=open(tmp / "server.log", "ab"), stderr=subprocess.STDOUT)]
    try:
        NS.wait(SERVER + "/actuator/health", 240)
        procs.append(subprocess.Popen([sys.executable, str(ROOT / "drishti-console" / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}",
                                       f"--backend.url={SERVER}"], env=dict(os.environ), stdout=open(tmp / "console.log", "ab"), stderr=subprocess.STDOUT))
        NS.wait(CONSOLE + "/healthz")
        yield tmp
    finally:
        for p in procs:
            p.terminate()
        for p in procs:
            try:
                p.wait(20)
            except subprocess.TimeoutExpired:
                p.kill()


@pytest.fixture(scope="module")
def browser():
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


def test_the_fund_page_is_the_fact_sheet_with_the_look_through_and_index_tabs(stack, browser):
    import json
    import urllib.request
    assert json.load(urllib.request.urlopen(SERVER + "/api/v1/sutras/problems", timeout=10)) == {}
    page = browser.new_page(viewport={"width": 1500, "height": 1000})
    page.goto(CONSOLE + "/v/fund/QQQ")
    expect(page.locator("#p-net-assets")).to_contain_text("bn")
    expect(page.locator("#p-expense-ratio")).to_contain_text("%")
    expect(page.locator("#p-top-holdings tbody tr")).to_have_count(10)
    expect(page.locator("#p-top-holdings tbody tr").first.locator("a")).to_have_attribute("href", "/v/us-company/NVDA")
    expect(page.locator("#p-the-fund")).to_contain_text("Nasdaq-100")
    tabs = page.locator("#p-views [role=tab]")
    expect(tabs).to_have_count(2)
    expect(tabs.nth(0)).to_contain_text("Look-through holdings")
    expect(page.locator("#p-views")).to_contain_text("Concentration")
    expect(page.locator("#p-views")).to_contain_text("Who else holds")
    tabs.nth(1).click()
    expect(tabs.nth(1)).to_have_attribute("aria-selected", "true")
    expect(page.locator("#p-views")).to_contain_text("market capitalisation computed from SEC data")
    expect(page.locator("#p-views")).to_contain_text("Tilt by SIC division")
    page.screenshot(path=str(stack / "fund-index-tab.png"))
    page.close()


def test_a_company_has_a_held_by_funds_tab_and_a_share_class_opens_its_fund(stack, browser):
    page = browser.new_page(viewport={"width": 1500, "height": 1000})
    page.goto(CONSOLE + "/v/us-company/AAPL")
    tab = page.get_by_role("tab", name="Held by funds (a fund opens its page)")
    expect(tab).to_be_visible()
    tab.click()
    expect(page.locator("#p-held-by-funds tbody tr").first).to_be_visible()
    expect(page.locator("#p-held-by-funds")).to_contain_text("Vanguard")
    page.goto(CONSOLE + "/v/fund-holder/AAPL")
    expect(page.locator("#p-holders tbody tr").first.locator("a").first).to_have_attribute("href", "/v/fund/VTI")
    page.goto(CONSOLE + "/go?q=FUND%20VTSAX")
    page.wait_for_url("**/v/fund/VTI")
    expect(page.locator(".strip")).to_contain_text("VTI")
    page.close()
