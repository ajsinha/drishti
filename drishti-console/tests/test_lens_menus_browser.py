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

"""The lens menus in a real browser (docs/lenses/README.md, mock-up menus/A): with an edition's subject menus the top bar's controls
never overlap at desktop widths and the page never scrolls sideways; an item to finish (Engines -> Exposure -> A counterparty) puts its words in
the command line, the menu closed, instead of opening a page. Skipped when Playwright or Chromium is missing."""
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_live_tabs_browser import TickingBackend, _free_port, browser  # noqa: E402,F401 - the shared browser fixture
from test_topbar_widths_browser import BOXES, _clash  # noqa: E402

EDITIONS = ("risk", "trading", "market", "world")


@pytest.fixture(scope="module")
def consoles():
    """One console per edition, each started here on a free port over the stand-in server."""
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    out, servers = {}, []
    for name in EDITIONS:
        app = create_app(load_settings(CONSOLE / "config"))
        app.state.backend = TickingBackend()
        app.state.editions.learn({"product": f"Drishti {name.title()}", "edition": {"name": name, "title": f"Drishti {name.title()}"}})
        port = _free_port()
        server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
        threading.Thread(target=server.run, daemon=True).start()
        deadline = time.monotonic() + 15
        while not server.started and time.monotonic() < deadline:
            time.sleep(0.05)
        assert server.started, f"the {name} console did not start"
        servers.append(server)
        out[name] = f"http://127.0.0.1:{port}"
    yield out
    for s in servers:
        s.should_exit = True


@pytest.mark.parametrize("width", (1024, 1280, 1600, 1920))
def test_the_subject_menus_fit_the_bar_at_desktop_widths(browser, consoles, width):
    ctx = browser.new_context(viewport={"width": width, "height": 800})
    page = ctx.new_page()
    problems = []
    try:
        for name, url in consoles.items():
            page.goto(url + "/t", wait_until="load")
            page.wait_for_timeout(300)
            s = page.evaluate(BOXES)
            for i, a in enumerate(s["boxes"]):
                for b in s["boxes"][i + 1:]:
                    if _clash(a, b):
                        problems.append(f"{name} @ {width}: '{a[0]}' overlaps '{b[0]}'")
            if s["sw"] > s["vw"] + 1:
                problems.append(f"{name} @ {width}: page scrolls sideways ({s['sw']} in {s['vw']})")
    finally:
        ctx.close()
    assert not problems, "\n".join(problems)


def test_an_item_to_finish_fills_the_command_line(browser, consoles):
    page = browser.new_page(viewport={"width": 1600, "height": 900})
    try:
        page.goto(consoles["risk"] + "/t", wait_until="load")
        page.locator(".tbar-menu-btn", has_text="Engines").click()
        item = page.locator("a.mega-item[data-fill='EXP CPTY']")
        item.wait_for(state="visible")
        assert item.locator(".mega-cmd").inner_text().startswith("EXP CPTY")
        item.click()
        page.wait_for_timeout(200)
        assert page.input_value("#cmdInput") == "EXP CPTY "
        assert page.evaluate("document.activeElement.id") == "cmdInput"
        assert not page.locator("a.mega-item[data-fill='EXP CPTY']").is_visible()      # the menu closed
        assert page.url.endswith("/t")                                                  # no page opened
        page.goto(consoles["risk"] + "/t?cmd=EXP%20NSET", wait_until="load")            # the same, by its link (a new tab)
        page.wait_for_timeout(200)
        assert page.input_value("#cmdInput") == "EXP NSET "
    finally:
        page.close()


def test_the_trading_markets_menu_opens_inside_the_screen_and_runs_its_command(browser, consoles):
    """Product panel A19: Drishti Trading's one Markets menu (rates, vols, FX, credit, equities and commodities) opens inside the screen
    at a desktop width, three columns to a row, and a click on an item runs its command (FXF, unhidden in the lens) as typed. BI and
    Admin are not in the bar (by command only)."""
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    try:
        page.goto(consoles["trading"] + "/t", wait_until="load")
        buttons = page.locator(".tbar-menus .tbar-menu-btn")
        assert buttons.filter(has_text="BI").count() == 0 and buttons.filter(has_text="Admin").count() == 0
        buttons.filter(has_text="Markets").click()
        item = page.locator("a.mega-item[href='/go?q=FXF%20%3CGO%3E']")
        item.wait_for(state="visible")
        box = page.evaluate("""() => { const p = document.querySelector('.mega-panel.show'), r = p.getBoundingClientRect();
          return {left: r.left, right: r.right, cols: getComputedStyle(p).gridTemplateColumns.split(' ').length,
                  vw: document.documentElement.clientWidth, sw: document.documentElement.scrollWidth}; }""")
        assert box["left"] >= 0 and box["right"] <= box["vw"] + 1 and box["sw"] <= box["vw"] + 1 and box["cols"] == 3, box
        with page.expect_request(lambda r: "/go?q=FXF" in r.url):
            item.click()
    finally:
        page.close()


def test_counterparties_ccr_cube_opens_the_pivot_preset_under_construction(browser, consoles):
    """The CCR cube's door: Counterparties → CCR cube, with the chip, opens the Pivot's preset ccr, which says it is under construction."""
    page = browser.new_page(viewport={"width": 1600, "height": 900})
    try:
        page.goto(consoles["trading"] + "/t", wait_until="load")                    # Drishti Trading draws Counterparties in its bar
        page.locator(".tbar-menu-btn", has_text="Counterparties").click()
        item = page.locator("a.mega-item[href='/pivot?preset=ccr']")
        item.wait_for(state="visible")
        assert "Counterparty-risk cube" in item.inner_text() and item.locator(".drishti_uc-chip").count() == 1
        item.click()
        page.wait_for_url("**/pivot?preset=ccr**")
        assert page.locator("[data-pv-title]").text_content().strip() == "CCR cube"
        assert page.locator("[data-cube-building] .drishti_uc-chip").is_visible()
        page.locator("[data-cube-building] a[href='/exposure']").wait_for(state="visible")
    finally:
        page.close()
