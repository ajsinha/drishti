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

"""Maps in Chromium over the real server and console (ports 18941 and 17941, no sign-in): the politics-society and economics packs' real data and the sample
report World economics on its own dataset. The jurisdiction page of Australia draws the world with Australia highlighted and a click on New Zealand opens
JUR-NZL; a country with no entity says so on hover and does not open; the economy page shades the countries with a legend; the report's map drills from the world
to Australia and its regions and back, with the cross-filter on the rest of the page; and on a phone (390 px) nothing scrolls sideways and the way in is a list of
44 px links. No request leaves the machine and the page raises no console error (the content security policy is strict).

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
from __future__ import annotations

import glob
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import sync_playwright  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
from scratch_ports import worker_ports  # noqa: E402

SERVER_PORT, CONSOLE_PORT = worker_ports(18941, 17941)
BASE = f"http://127.0.0.1:{CONSOLE_PORT}"
REPORT = BASE + "/bi/r/file:report:world-economics"
JAVA = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"


def listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def wait(url: str, seconds: int = 150) -> None:
    for _ in range(seconds * 2):
        try:
            with urllib.request.urlopen(url, timeout=2) as r:
                if r.status == 200:
                    return
        except OSError:
            pass
        time.sleep(0.5)
    raise TimeoutError(url)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)
    if not jars or not Path(JAVA).exists():
        pytest.skip("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
    for port in (SERVER_PORT, CONSOLE_PORT):
        if listening(port):
            pytest.skip(f"port {port} is in use")
    tmp = tmp_path_factory.mktemp("maps")
    work = tmp / "server"
    for kind in ("datasets", "reports"):
        (work / "config" / "bi" / kind).mkdir(parents=True)
    from bi_samples import copy_samples
    copy_samples(work / "config/bi", ["world-economics"])               # the model and the dataset it reads
    shutil.copy(ROOT / "config/bi/reports/world-economics.report.yaml", work / "config/bi/reports")
    env = dict(os.environ, DRISHTI_PACKS_DIR=str(ROOT / "config/packs"), DRISHTI_PACKS="politics-society,economics", DRISHTI_BI_DIR=str(work / "config/bi"),
               DRISHTI_DATA_DIR=str(work / "data"))
    procs = []
    try:
        procs.append(subprocess.Popen([JAVA, "-Xmx768m", "-jar", jars[0], f"--server.port={SERVER_PORT}", f"--drishti.bi.query.store-dir={tmp / 'store'}"], cwd=work, env=env,
                                      stdout=open(tmp / "server.log", "ab"), stderr=subprocess.STDOUT))
        wait(f"http://127.0.0.1:{SERVER_PORT}/actuator/health")
        procs.append(subprocess.Popen([sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}", f"--backend.url=http://127.0.0.1:{SERVER_PORT}"],
                                      stdout=open(tmp / "console.log", "ab"), stderr=subprocess.STDOUT))
        wait(BASE + "/healthz")
        yield BASE
    finally:
        for p in procs:
            p.terminate()
        for p in procs:
            try:
                p.wait(20)
            except subprocess.TimeoutExpired:
                p.kill()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:                                           # no Chromium installed
            pytest.skip(f"no Chromium: {e}")
        yield b
        b.close()


def new_page(browser, width=1280, height=900):
    if width < 700:                                                      # a phone: a coarse pointer, which is what makes controls finger-sized
        page = browser.new_context(viewport={"width": width, "height": height}, has_touch=True, is_mobile=True).new_page()
    else:
        page = browser.new_page(viewport={"width": width, "height": height})
    page.problems = []
    page.on("console", lambda m: page.problems.append(m.text) if m.type == "error" else None)
    page.on("pageerror", lambda e: page.problems.append(str(e)))
    page.on("request", lambda r: page.problems.append("OUTSIDE " + r.url) if not r.url.startswith(("http://127.0.0.1", "data:", "blob:")) else None)
    return page


def pixel(page, selector, lon, lat):
    """Where a longitude and latitude are on the map drawn in `selector`, in page coordinates."""
    page.locator(selector).first.scroll_into_view_if_needed()
    return page.evaluate("""([sel, lon, lat]) => { const el = document.querySelector(sel); const c = echarts.getInstanceByDom(el); const r = el.getBoundingClientRect();
        const q = c.convertToPixel({ geoIndex: 0 }, [lon, lat]); return [r.left + q[0], r.top + q[1]]; }""", [selector, lon, lat])


def regions(page, selector):
    return page.evaluate("""(sel) => (echarts.getInstanceByDom(document.querySelector(sel)).getOption().geo[0].regions || []).map((r) => [r.name, r.itemStyle && r.itemStyle.areaColor || null])""", selector)


def test_the_jurisdiction_page_highlights_australia_and_a_click_on_new_zealand_opens_its_page(browser):
    page = new_page(browser)
    page.goto(BASE + "/v/jurisdiction/JUR-AUS")
    page.wait_for_selector(".xmap svg", timeout=20000)
    page.wait_for_timeout(800)
    marked = dict(regions(page, ".xmap"))
    assert "AUS" in marked and marked["AUS"], marked                                    # Australia is in its own colour
    assert page.locator('.xmap-list a[href="/v/jurisdiction/JUR-NZL"]').count() == 1       # the keyboard's way to the same click
    assert page.locator(".xmap").get_attribute("aria-label").startswith("Where it is: world map, AUS highlighted")
    # a country with no entity says so on hover and does not open
    tip = page.evaluate("""() => echarts.getInstanceByDom(document.querySelector('.xmap')).getOption().tooltip[0].formatter({ seriesType: 'map', name: 'ATA' })""")
    assert "Antarctica" in tip and "no page for this country" in tip
    x, y = pixel(page, ".xmap", 0, -80)
    page.mouse.click(x, y)
    page.wait_for_timeout(600)
    assert page.url.endswith("/v/jurisdiction/JUR-AUS")
    x, y = pixel(page, ".xmap", 172.5, -41.5)                                           # New Zealand
    page.mouse.click(x, y)
    page.wait_for_url("**/v/jurisdiction/JUR-NZL", timeout=15000)
    assert not page.problems, page.problems


def test_the_economy_page_shades_the_countries_with_a_legend_and_says_by_what(browser):
    page = new_page(browser)
    page.goto(BASE + "/v/economy/ECON-AUS")
    page.wait_for_selector(".xmap svg", timeout=20000)
    page.wait_for_timeout(800)
    shaded = page.evaluate("""() => echarts.getInstanceByDom(document.querySelector('.xmap')).getOption().visualMap.length""")
    assert shaded == 1
    assert "Shaded by GDP per capita (USD)" in page.locator(".xmap-note").inner_text()
    assert page.locator(".xmap-list a").count() > 150
    assert not page.problems, page.problems


def test_the_report_map_drills_from_the_world_to_australia_and_its_regions_and_back(browser):
    page = new_page(browser)
    page.goto(REPORT)
    page.wait_for_selector(".bi-vis-map .bi-chart", timeout=60000)
    page.wait_for_timeout(1500)
    assert page.locator('[data-visual="economies"] .bi-card-value').first.inner_text().strip() not in ("", "1")      # the whole world
    x, y = pixel(page, ".bi-vis-map .bi-chart", 134, -25)
    page.mouse.click(x, y)
    page.locator(".bi-map-crumb [data-map-up]").wait_for(timeout=20000)                 # at the world the crumb is only "World": the button appears once a country is open
    assert "Australia" in page.locator(".bi-map-crumb").inner_text()
    page.wait_for_timeout(1200)
    names = [r[0] for r in regions(page, ".bi-vis-map .bi-chart")] or []
    assert page.evaluate("echarts.getMap('drishti-region-AUS') !== undefined")          # the regions of Australia are loaded and drawn
    assert page.locator('[data-visual="economies"] .bi-card-value').first.inner_text().strip() == "1"               # the cross-filter: the page is Australia
    assert page.locator('.bi-vis-map [data-map-noregions="AUS"]').count() == 1, names                               # the field holds countries: said, not faked
    page.locator("[data-map-up]").click()
    page.wait_for_timeout(1000)
    assert page.locator(".bi-map-crumb [data-map-up]").count() == 0 and page.locator(".bi-map-here").inner_text() == "World"
    assert not [p for p in page.problems if "OUTSIDE" in p or "Refused" in p or "Content Security" in p], page.problems


def test_on_a_phone_nothing_scrolls_sideways_and_the_way_in_is_a_list_of_big_links(browser):
    page = new_page(browser, 390, 844)
    page.goto(BASE + "/v/jurisdiction/JUR-AUS")
    page.wait_for_selector(".xmap svg", timeout=20000)
    page.wait_for_timeout(800)
    assert page.evaluate("document.documentElement.scrollWidth") <= 391
    page.locator(".xmap-places summary").click()
    link = page.locator('.xmap-list a[href="/v/jurisdiction/JUR-NZL"]')
    box = link.bounding_box()
    assert box and box["height"] >= 44 and box["width"] >= 44, box
    assert page.evaluate("document.documentElement.scrollWidth") <= 391
    page.goto(REPORT)
    page.wait_for_selector(".bi-vis-map .bi-chart", timeout=60000)
    page.wait_for_timeout(1500)
    assert page.evaluate("document.documentElement.scrollWidth") <= 391
    x, y = pixel(page, ".bi-vis-map .bi-chart", 134, -25)
    page.mouse.click(x, y)
    page.wait_for_selector(".bi-map-crumb [data-map-up]", timeout=20000)
    up = page.locator(".bi-map-crumb [data-map-up]").bounding_box()
    assert up["height"] >= 44 and up["width"] >= 44, up
    assert page.evaluate("document.documentElement.scrollWidth") <= 391
    assert not page.problems, page.problems
