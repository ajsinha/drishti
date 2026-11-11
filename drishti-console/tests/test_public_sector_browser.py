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

"""The public-sector module's screens in Chromium over the real server and console (ports 18971 and 17971): the six feeds run on their
recorded fixtures (tools/feeds/tests/fixtures/publicsector, never the network) and load into a scratch worldlake and newslake through
drishti-feeds' python route, the demo source off, then each function's screen is drawn from the lakes: WDI (indicators, the world map),
CBANK (policy rate, series linked), NSTAT, OECD (the members' map), PRESS (the releases with their original links, internal only said)
and GDELT (categories, the map, the stories). PRESS is in the World lens too. Skipped when Playwright, Chromium, deltalake or the built
server jar is missing, or a scratch port is in use. Never run with pytest -n."""
from __future__ import annotations

import glob
import os
import subprocess
import sys
from datetime import date
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
pytest.importorskip("deltalake", reason="the python load route needs deltalake and pyarrow")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import news_stack as NS  # noqa: E402  (listening, wait)
from java_launch import java_cmd  # noqa: E402
from scratch_ports import worker_ports  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SERVER_PORT, CONSOLE_PORT = worker_ports(os.environ.get("DRISHTI_PUBLICSECTOR_SERVER_PORT", 18971),
                                         os.environ.get("DRISHTI_PUBLICSECTOR_CONSOLE_PORT", 17971))
SERVER, CONSOLE = f"http://127.0.0.1:{SERVER_PORT}", f"http://127.0.0.1:{CONSOLE_PORT}"
FEEDS = ["world-development", "central-banks", "national-statistics", "oecd", "official-news", "gdelt-events"]
PACKS = "core," + ",".join(FEEDS)
LOADER = r"""
import json, sys
from datetime import date
from pathlib import Path
import yaml
root = Path(sys.argv[1]); feeds = sys.argv[2].split(",")
sys.path[:0] = [str(root / "tools/feeds"), str(root / "tools/feeds/sources")]
import feedbase as FB, feedrunner as R, publicsector as PS
fix = root / "tools/feeds/tests/fixtures/publicsector"
sel = yaml.safe_load((fix / "selection.yaml").read_text())
for f in feeds:
    rc, entry = R.run_feed(FB.load_spec(f), R.RunOptions(business_date=date(2026, 11, 6), load="python", sets=sel.get(f) or {}),
                           opener=PS.Fixtures(fix / f), sleep=lambda s: None)
    print(f, rc, entry.get("status"), entry.get("rows"), entry.get("error") or "")
    if rc:
        sys.exit(rc)
"""


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (SERVER_PORT, CONSOLE_PORT):
        if NS.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    jars = sorted(glob.glob(str(ROOT / "kernel/drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
    if not jars or not Path(java).exists():
        pytest.skip("needs the built server: ./mvnw -o -q package -DskipTests -pl :drishti-app -am")
    tmp = tmp_path_factory.mktemp("publicsector")
    work, data = tmp / "server", tmp / "data"
    work.mkdir()
    env = dict(os.environ, DRISHTI_DATA_DIR=str(data), DRISHTI_FRED_API_KEY="recorded", DRISHTI_JAR=jars[0])
    loaded = subprocess.run([sys.executable, "-c", LOADER, str(ROOT), ",".join(FEEDS)], env=env, capture_output=True, text=True, timeout=900)
    assert loaded.returncode == 0, loaded.stdout[-3000:] + loaded.stderr[-3000:]
    senv = dict(os.environ, DRISHTI_PACKS=PACKS, DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_DEMO_ENABLED="false",
                DRISHTI_SECURITY_ENABLED="false", DRISHTI_DATA_DIR=str(data), DRISHTI_LAKES_DIR=str(ROOT / "config" / "lakes"),
                DRISHTI_OFFLINE="true", DRISHTI_LENSES="world,publicsector")
    procs = [subprocess.Popen(java_cmd(java, ["-Xmx1500m", "-jar", jars[0], f"--server.port={SERVER_PORT}"],
                                       tmp=tmp / "jvm-tmp"), cwd=work, env=senv, stdout=open(tmp / "server.log", "ab"), stderr=subprocess.STDOUT)]
    try:
        NS.wait(SERVER + "/actuator/health", proc=procs[0])
        procs.append(subprocess.Popen([sys.executable, str(ROOT / "drishti-console" / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}",
                                       f"--backend.url={SERVER}"], env=dict(os.environ), stdout=open(tmp / "console.log", "ab"),
                                      stderr=subprocess.STDOUT))
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


def _page(browser, path: str):
    page = browser.new_page(viewport={"width": 1500, "height": 1000})
    page.goto(CONSOLE + path)
    return page


def test_the_module_its_lens_and_its_sutras_are_loaded(stack):
    import json
    import urllib.request
    assert json.load(urllib.request.urlopen(SERVER + "/api/v1/sutras/problems", timeout=10)) == {}
    mods = json.load(urllib.request.urlopen(SERVER + "/api/v1/modules", timeout=10))
    assert "publicsector" in json.dumps(mods)


def test_a_country_development_profile_with_its_indicators_and_the_world_map(stack, browser):
    page = _page(browser, "/v/development-profile/WDI-CAN")
    expect(page.locator(".strip")).to_contain_text("Canada")
    expect(page.locator(".strip")).to_contain_text("High income")
    rows = page.locator("#p-indicators tbody tr")
    expect(rows).to_have_count(10)
    expect(rows.first.locator("a")).to_have_attribute("href", "/v/development-series/WDIS-CAN-SP_POP_TOTL")
    expect(page.locator("#p-world")).to_be_visible()
    expect(page.locator("#p-world")).to_contain_text("GDP per head")
    page.screenshot(path=str(stack / "wdi-can.png"))
    page.goto(CONSOLE + "/v/development-series/WDIS-IND-NY_GDP_MKTP_KD_ZG")
    expect(page.locator(".strip")).to_contain_text("GDP growth")
    expect(page.locator("#p-history")).to_be_visible()
    page.close()


def test_a_central_bank_with_its_policy_rate_and_its_series(stack, browser):
    page = _page(browser, "/v/central-bank/CBANK-BOC")
    expect(page.locator(".strip")).to_contain_text("Bank of Canada")
    expect(page.locator("#p-policy")).to_be_visible()
    rows = page.locator("#p-series tbody tr")
    expect(rows).to_have_count(5)
    expect(rows.first.locator("a")).to_have_attribute("href", "/v/rate-series/CBSER-BOC-V39079")
    page.goto(CONSOLE + "/v/rate-series/CBSER-FRED-DGS10")
    expect(page.locator("main")).to_contain_text("10-year")
    expect(page.locator(".strip")).to_contain_text("On the year")
    page.close()


def test_a_national_statistic_and_an_oecd_member_on_the_map(stack, browser):
    page = _page(browser, "/v/statistics-series/NSTAT-V41690973")
    expect(page.locator(".strip")).to_contain_text("index 2002=100")
    expect(page.locator("#p-history")).to_be_visible()
    page.goto(CONSOLE + "/v/oecd-country/OECD-CAN")
    expect(page.locator(".strip")).to_contain_text("Inflation")
    expect(page.locator("#p-measures tbody tr")).to_have_count(4)
    expect(page.locator("#p-members")).to_contain_text("Inflation")
    page.close()


def test_official_press_releases_link_to_the_originals_and_say_internal_only(stack, browser):
    page = _page(browser, "/v/release-source/PRESS-FED")
    rows = page.locator("#p-latest tbody tr")
    expect(rows.first).to_be_visible()
    expect(rows.first.locator("a[href^='/v/press-release/PREL-FED-']")).to_have_count(1)
    expect(rows.first.locator("a[href^='https://www.federalreserve.gov/']")).to_have_count(1)
    expect(page.locator(".strip")).to_contain_text("no")
    page.screenshot(path=str(stack / "press-fed.png"))
    page.goto(CONSOLE + "/v/release-source/PRESS-BOE")
    expect(page.locator(".strip")).to_contain_text("yes: The Bank of England")
    page.close()


def test_a_country_day_in_the_world_news(stack, browser):
    page = _page(browser, "/v/event-country/GDELT-USA")
    expect(page.locator(".strip")).to_contain_text("2026-11-05")
    expect(page.locator("#p-categories")).to_be_visible()
    expect(page.locator("#p-world")).to_contain_text("tone")
    expect(page.locator("#p-stories tbody tr a[href^='http']").first).to_be_visible()
    page.close()
