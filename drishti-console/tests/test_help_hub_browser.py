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

"""The help centre's front door and a section's page in a real browser (2.9.11.a, mock-up help-hub/A; help/index.html, help/section.html,
static/js/drishti_help.js), dark and light:
- the front door: the search box, the most used row and nine tiles (an administrator: Internal too), with no script error;
- a tile opens its section; the section's rows filter as you type (a group with none left folds away), Enter searches that section only;
- an old address /help#cat-<id> opens the section that took it over;
- F1 on the help pages and on the terminal opens the screen's guide;
- a visitor who is not signed in reads every running engine's guides (Help is documentation), but not Internal;
- on a phone (390 px): search first, the most used row scrolls sideways inside itself, the tiles stand in one column, every tile and
  row a finger tall, nothing scrolls sideways.
DRISHTI_HELP_SHOTS=<folder> saves screenshots. Skipped when Playwright or its Chromium is missing."""
import os
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import browser, console_url  # noqa: E402,F401 - the shared fixtures

pytestmark = pytest.mark.browser
THEMES = ("terminal", "light")
SHOTS = os.environ.get("DRISHTI_HELP_SHOTS")
TILES = ["Get started", "Risk measures", "Analytics and BI", "Data", "Build", "Operate", "Reference", "Knowledge", "Internal"]


@pytest.fixture(scope="module")
def public_console():
    """A console nobody is signed in to (the auth gate answers no one): Help as a signed-out visitor sees it."""
    import threading
    import time

    import uvicorn

    from conftest import CONSOLE
    from core import engines
    from core.app import create_app
    from core.config import load_settings
    from test_live_tabs_browser import TickingBackend, _free_port

    engines.reset()
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = TickingBackend()

    async def nobody(cookie, _backend):
        return None
    app.state.auth.current = nobody
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    threading.Thread(target=server.run, daemon=True).start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the public console did not start"
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True


@pytest.mark.parametrize("width", (1280, 390))
def test_a_signed_out_visitor_reads_every_running_engines_help(browser, public_console, width):
    """Help is documentation, not data (product owner, 2026-11-03): the running engines' guides and tutorials are open to a visitor who
    is not signed in; Internal is not."""
    ctx = _context(browser, width, "light")
    page = ctx.new_page()
    try:
        page.goto(public_console + "/help", wait_until="load")
        tiles = [t.strip() for t in page.locator("[data-help-tiles] .hub-tile-t").all_text_contents()]
        assert "Risk measures" in tiles and "Internal" not in tiles, tiles
        page.goto(public_console + "/help/section/risk", wait_until="load")
        for slug in ("docs-guides-var-guide", "docs-guides-exposure-guide", "docs-guides-frtb-sa-guide", "docs-guides-simm-guide"):
            assert page.locator(f"a[href='/help/{slug}']").count() == 1, slug
        page.locator("a[href='/help/docs-guides-exposure-guide']").click()
        page.wait_for_url("**/help/docs-guides-exposure-guide")
        assert page.locator(".help-article").is_visible()
        assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")
    finally:
        ctx.close()


def _context(browser, width, theme):
    phone = width < 640
    ctx = browser.new_context(viewport={"width": width, "height": 844 if phone else 900}, is_mobile=phone, has_touch=phone)
    ctx.add_init_script(f"try {{ localStorage.setItem('drishti.theme', '{theme}'); }} catch (e) {{}}")
    return ctx


def _shot(page, name):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(Path(SHOTS) / f"{name}.png"), full_page=False)


@pytest.mark.parametrize("theme", THEMES)
def test_the_front_door_and_a_section(browser, console_url, theme):
    ctx = _context(browser, 1280, theme)
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    try:
        page.goto(console_url + "/help", wait_until="load")
        assert page.locator("[data-help-q]").is_visible()
        most = [t.strip() for t in page.locator("[data-help-most] a").all_text_contents()]
        assert most == ["User guide", "VaR guide", "Tutorials", "What's new"], most
        tiles = [t.strip() for t in page.locator("[data-help-tiles] .hub-tile-t").all_text_contents()]
        assert tiles == TILES, tiles
        tops = page.evaluate("[...document.querySelectorAll('[data-help-tiles] .hub-tile')].map(t => Math.round(t.getBoundingClientRect().top))")
        assert len(set(tops)) < len(tops)                                       # several tiles to a row on a desktop
        _shot(page, f"01-help-front-door-{theme}")
        page.locator("[data-help-tiles] a[data-section='risk']").click()
        page.wait_for_url("**/help/section/risk")
        assert page.locator("[data-help-group]").count() == 3
        assert page.locator("[data-help-pair]").count() >= 4                    # each guide beside its tutorial
        _shot(page, f"02-help-section-{theme}")
        box = page.locator("[data-help-filter]")
        box.fill("simm")
        page.wait_for_timeout(100)
        rows = page.locator("[data-help-row]:visible")
        assert rows.count() == 1 and "SIMM guide" in rows.first.inner_text()
        assert page.locator("[data-help-group]:visible").count() == 1            # the other groups fold away
        box.fill("nothing matches this at all")
        page.wait_for_timeout(100)
        assert page.locator("[data-help-none]").is_visible()
        box.fill("backtesting")
        box.press("Enter")
        page.wait_for_url("**/help/search?**")
        assert "section=risk" in page.url and page.locator("[data-section-search]").is_visible()
        assert not errors, errors
    finally:
        ctx.close()


def test_an_old_section_address_opens_its_section(browser, console_url):
    ctx = _context(browser, 1280, "terminal")
    page = ctx.new_page()
    try:
        page.goto(console_url + "/help#cat-use", wait_until="load")
        page.wait_for_url("**/help/section/get-started", timeout=10000)
        page.goto(console_url + "/help#cat-knowledge", wait_until="load")
        page.wait_for_url("**/help/section/knowledge", timeout=10000)
        page.goto(console_url + "/help/docs-guides-var-guide", wait_until="load")   # an old page address: still the page
        assert page.locator(".help-article").is_visible()
        page.locator(".help-crumbs a[href='/help/section/risk']").click()
        page.wait_for_url("**/help/section/risk")
    finally:
        ctx.close()


def test_f1_opens_the_screens_guide(browser, console_url):
    ctx = _context(browser, 1280, "terminal")
    page = ctx.new_page()
    try:
        # each screen's own guide, as help.yaml's contextual: says (a pack may name its own: the stand-in's finance pack names
        # getting-started for the help pages and the terminal); the help hub and a section's page are the "help" screen
        for start, screen in (("/help", "help"), ("/help/section/risk", "help"), ("/t", "terminal")):
            target = page.request.get(console_url + f"/help/context/{screen}", max_redirects=0).headers["location"].split("#")[0]
            assert target.startswith("/help/") and target != "/help/", target
            page.goto(console_url + start, wait_until="load")
            page.locator("body").click(position={"x": 5, "y": 400})
            page.keyboard.press("F1")
            page.wait_for_url("**" + target + "**", timeout=10000)
    finally:
        ctx.close()


@pytest.mark.parametrize("theme", THEMES)
def test_the_help_centre_on_a_phone(browser, console_url, theme):
    ctx = _context(browser, 390, theme)
    page = ctx.new_page()
    try:
        page.goto(console_url + "/help", wait_until="load")
        page.wait_for_timeout(300)
        s = page.evaluate("""() => {
          const d = document.documentElement, q = document.querySelector('[data-help-q]').getBoundingClientRect();
          const most = document.querySelector('[data-help-most]'), tiles = [...document.querySelectorAll('[data-help-tiles] .hub-tile')];
          const first = tiles[0].getBoundingClientRect();
          return {sw: d.scrollWidth, vw: d.clientWidth, qTop: q.top, tileTop: first.top,
                  lefts: [...new Set(tiles.map(t => Math.round(t.getBoundingClientRect().left)))],
                  mostScrolls: most.scrollWidth > most.clientWidth, mostWrap: getComputedStyle(most).flexWrap,
                  small: [...document.querySelectorAll('.hub-chip, .hub-tile')].map(e => e.getBoundingClientRect().height).filter(h => h < 43.5).length};
        }""")
        assert s["sw"] <= s["vw"] + 1, s                                          # nothing scrolls sideways
        assert s["qTop"] < s["tileTop"], s                                        # search first
        assert len(s["lefts"]) == 1, s                                            # the tiles in one column
        assert s["mostWrap"] == "nowrap", s                                       # the most used row scrolls sideways inside itself
        assert s["small"] == 0, s
        _shot(page, f"03-help-phone-{theme}")
        page.locator("[data-help-tiles] a[data-section='data']").tap()
        page.wait_for_url("**/help/section/data")
        small = page.evaluate("[...document.querySelectorAll('.sec-link')].filter(e => e.getBoundingClientRect().height < 43.5).length")
        assert small == 0
        assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")
    finally:
        ctx.close()
