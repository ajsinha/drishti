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

"""Start order does not matter (server-watch.js): the console starts with no server, the page shows the banner, the server
starts and the page recovers by itself (no manual reload), the server stops and the banner returns."""
from __future__ import annotations

import glob
import os
import subprocess
import sys
import time
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import sync_playwright  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bi_stack import FORBIDDEN, listening, wait  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
from scratch_ports import worker_ports  # noqa: E402
from java_launch import java_cmd

SERVER_PORT, CONSOLE_PORT = worker_ports(18963, 17963)
SERVER, CONSOLE_URL = f"http://127.0.0.1:{SERVER_PORT}", f"http://127.0.0.1:{CONSOLE_PORT}"


def find_jar() -> str | None:
    """The built server: in this checkout, else in the main checkout this worktree hangs off."""
    roots = [ROOT, *([ROOT.parents[2]] if ROOT.parent.name == "worktrees" else [])]
    for r in roots:
        jars = sorted(glob.glob(str(r / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)
        if jars:
            return jars[0]
    return None


def start_server(tmp: Path):
    java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
    jar = find_jar()
    if not jar or not Path(java).exists():
        pytest.skip("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
    work = tmp / "server"
    work.mkdir(exist_ok=True)
    env = dict(os.environ, DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_PACKS="trading", DRISHTI_SECURITY_ENABLED="false",
               DRISHTI_DATA_DIR=str(work / "data"))
    p = subprocess.Popen(java_cmd(java, ["-Xmx512m", "-jar", jar, f"--server.port={SERVER_PORT}"], tmp=tmp / "jvm-tmp"), cwd=work, env=env,
                         stdout=open(tmp / "server.log", "ab"), stderr=subprocess.STDOUT)
    return p


def marker(page):
    """The page's marker: 1 until the page reloads. The reload is the page's own, at any moment: an evaluation it interrupts ("execution
    context was destroyed") means the reload is under way (it failed so, two runs in three under load)."""
    try:
        return page.evaluate("window.__marker")
    except Exception as e:  # noqa: BLE001 - Playwright's Error
        if "context was destroyed" in str(e) or "navigat" in str(e):
            return None
        raise


def stop(p) -> None:
    p.terminate()
    try:
        p.wait(30)
    except subprocess.TimeoutExpired:
        p.kill()


def test_banner_appears_recovers_and_returns(tmp_path):
    for port in (SERVER_PORT, CONSOLE_PORT):
        assert port not in FORBIDDEN
        if listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    cenv = dict(os.environ, DRISHTI_AUTH_ENABLED="false", DRISHTI_CONSOLE_BACKEND_CHECK_SECONDS="1")
    console = subprocess.Popen([sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}", f"--backend.url={SERVER}"],
                               env=cenv, stdout=open(tmp_path / "console.log", "ab"), stderr=subprocess.STDOUT)
    server = None
    try:
        wait(CONSOLE_URL + "/healthz")
        with sync_playwright() as p:
            try:
                b = p.chromium.launch()
            except Exception as e:  # noqa: BLE001
                pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
            page = b.new_context(viewport={"width": 390, "height": 800}).new_page()
            page.goto(CONSOLE_URL + "/v/desk/NOPE")                       # no server yet: the page says it cannot reach it
            banner = page.locator("[data-server-watch]")
            banner.wait_for(state="visible", timeout=15000)
            assert "not reachable" in banner.inner_text()
            assert "unreachable" in page.content()
            assert page.locator(".sw-retry").bounding_box()["height"] >= 44
            assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
            page.evaluate("window.__marker = 1")
            server = start_server(tmp_path)                               # the server starts later
            wait(SERVER + "/actuator/health", 150)
            end = time.time() + 90
            while marker(page) == 1:                                      # until the page has reloaded by itself
                assert time.time() < end, "the page did not recover: " + page.locator("[data-server-watch]").inner_text() + " / " + (tmp_path / "console.log").read_text()[-600:]
                page.wait_for_timeout(1000)
            page.wait_for_load_state("load")
            assert "unreachable" not in page.content()
            assert page.locator("[data-server-watch]").count() == 0 or not page.locator("[data-server-watch]").is_visible()
            stop(server)                                                  # the server stops: the first failed call brings the banner back
            server = None
            page.wait_for_timeout(2500)                                   # the console's watch notices
            page.evaluate("fetch('/v/desk/NOPE/ask', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{\"question\":\"x\"}'})")
            page.locator("[data-server-watch]").wait_for(state="visible", timeout=15000)
            assert "not reachable" in page.locator("[data-server-watch]").inner_text()
            b.close()
    finally:
        if server is not None:
            stop(server)
        stop(console)
