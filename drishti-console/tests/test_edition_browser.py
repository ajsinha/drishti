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

"""Drishti Risk's three Home landings in a browser (EDITIONS.md section 7): the console with the stand-in server answering the engines'
VaR and exposure calls as the engines shape them (port 17962). Each landing fills its panels from those answers, fits a phone without
sideways scrolling, and draws in every theme; skipped when Playwright or its Chromium is missing."""
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from test_page_widths_browser import AUDIT  # noqa: E402
from scratch_ports import worker_port  # noqa: E402

PORT = worker_port(17962)
THEMES = ["terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"]
TREE = {"hierarchy": "MGMT", "title": "Management", "hierarchies": ["MGMT", "LEGAL", "LL"], "businessDate": "2026-09-30", "currency": "USD",
        "nodes": [{"id": "MGMT~BANK", "scope": "NODE:MGMT~BANK", "name": "BANK", "level": "bank", "depth": 0, "var": 112_040_000, "limitUse": 0.78},
                  {"id": "MGMT~DIV-MARKETS", "scope": "NODE:MGMT~DIV-MARKETS", "name": "DIV-MARKETS", "level": "division", "depth": 1, "var": 101_600_000, "limitUse": 0.81},
                  {"id": "MGMT~DESK-COMM", "scope": "NODE:MGMT~DESK-COMM", "name": "DESK-COMM", "level": "desk", "depth": 2, "var": 41_200_000, "limitUse": 1.08},
                  {"id": "MGMT~DESK-RATES", "scope": "NODE:MGMT~DESK-RATES", "name": "DESK-RATES", "level": "desk", "depth": 2, "var": 33_800_000, "limitUse": 0.86},
                  {"id": "MGMT~DESK-FX", "scope": "NODE:MGMT~DESK-FX", "name": "DESK-FX", "level": "desk", "depth": 2, "var": 19_500_000, "limitUse": 0.61}]}
EXP_TREE = {"groups": [{"id": "(no group)", "children": [
    {"scope": "CPTY:CP-ALDERSHOT", "id": "CP-ALDERSHOT", "label": "Aldershot", "epe": 88_600_000, "limit": {"use": 1.04}, "children": [{}, {}]},
    {"scope": "CPTY:CP-SUMMIT", "id": "CP-SUMMIT", "label": "Summit", "epe": 40_700_000, "limit": {"use": 0.44}, "children": [{}]}]}]}


class RiskBackend(FakeBackend):
    async def var(self, method, path, ident, body=None, raw=False, **params):
        return {"/scope": {"var": 112_040_000, "es": 131_000_000, "confidence": 0.99, "businessDate": "2026-09-30", "currency": "USD"},
                "/scope/history": {"backtest": {"days": 250, "exceptions": 5, "light": "amber", "kupiecPValue": 0.21}},
                "/tree": TREE, "/intraday/streams": {"streams": [{"name": "pv-topic", "applied": 2, "lastApplied": "2026-09-30T11:00:00Z"}]},
                "/official": {"dates": [{"date": "2026-09-30"}]}, "/baskets": [{"owner": "alice", "name": "hedge-check"}]}.get(path, {})

    async def exposure(self, method, path, ident, body=None, raw=False, **params):
        return {"/scope": {"epe": 945_580_000, "peakPfe": 1_200_000_000}, "/tree": EXP_TREE,
                "/official": {"dates": [{"date": "2026-09-30"}]}}.get(path, {})


@pytest.fixture(scope="module")
def risk_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = RiskBackend()
    app.state.editions.learn({"product": "Drishti Risk", "edition": {"name": "risk", "title": "Drishti Risk"}})
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=PORT, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{PORT}"
    server.should_exit = True
    thread.join(10)


@pytest.mark.parametrize("landing", ["desk", "command", "map"])
@pytest.mark.parametrize("width", [390, 1280])
def test_each_landing_fills_from_the_engines_and_fits(browser, risk_console, landing, width):
    ctx = browser.new_context(viewport={"width": width, "height": 900}, is_mobile=width < 600, has_touch=width < 600)
    page = ctx.new_page()
    try:
        page.goto(f"{risk_console}/?landing={landing}", wait_until="load")
        root = page.locator(f"[data-risk-landing='{landing}']")
        root.wait_for()
        page.wait_for_function("() => !document.querySelector('[data-rl-date]') || document.querySelector('[data-rl-date]').textContent !== '…'")
        if landing == "desk":
            page.wait_for_function("() => document.querySelectorAll('[data-rl-desks] tr').length >= 3")
            assert page.locator("[data-rl-var]").first.inner_text() == "112.04m"
            assert page.locator("[data-rl-epe]").first.inner_text() == "945.58m"
            assert page.locator("[data-rl-backtest]").first.inner_text() == "amber · 5"
            page.wait_for_function("() => document.querySelector('[data-rl-breaches]').textContent === '2'")   # one VaR, one credit
            assert "DESK-COMM" in page.locator("[data-rl-desks] tr").first.inner_text()
            assert "Aldershot" in page.locator("[data-rl-cptys] tr").first.inner_text()
        elif landing == "command":
            assert page.locator("#rlCmd").is_visible()
            assert page.locator(".rl-chip", has_text="VAR BANK").count() == 1
            page.wait_for_function("() => document.querySelector('[data-rl-baskets] a')")
        else:
            page.wait_for_function("() => document.querySelectorAll('[data-rl-heat] .rl-cell').length >= 4")
            assert page.locator("[data-rl-hierarchies] button").count() == 3
            assert page.locator(".rl-cell.r").count() == 1
            page.wait_for_function("() => document.querySelectorAll('[data-rl-feed] li').length >= 2")
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        assert not page.evaluate(AUDIT)
    finally:
        ctx.close()


def test_the_desk_landing_draws_in_every_theme(browser, risk_console):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    page = ctx.new_page()
    try:
        page.goto(f"{risk_console}/?landing=desk", wait_until="load")
        page.wait_for_function("() => document.querySelectorAll('[data-rl-desks] tr').length >= 3")
        seen = set()
        for theme in THEMES:
            page.evaluate(f"document.documentElement.setAttribute('data-theme', '{theme}')")
            colours = page.evaluate("""() => { const t = getComputedStyle(document.querySelector('.rl-tile'));
                const f = getComputedStyle(document.querySelector('.rl-fill.r'));
                return [t.backgroundColor, t.color, f.backgroundColor]; }""")
            assert all(c and c != "rgba(0, 0, 0, 0)" for c in colours), (theme, colours)
            seen.add(colours[0])
        assert len(seen) > 1                                                     # the tiles follow the theme
    finally:
        ctx.close()
