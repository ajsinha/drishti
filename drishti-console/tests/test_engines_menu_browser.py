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

"""The Engines menu in a real browser (2.9.11.a, mock-up engines-menu/A; config/menus.yaml, terminal/_topbar.html, static/js/engines-menu.js),
dark and light, over a console of its own whose menus.yaml shows the search box from three engines (search-over: 2):
- on a desktop the menu sits after Views, opens a panel with a column per running engine (VaR, Exposure, FRTB-SA, SIMM), each with its
  command word, inside the screen, with Admin -> Engines; nothing scrolls sideways;
- its search box filters as you type (an engine with no match folds away, the word marked), Escape clears it, Enter opens the first page;
- an item to finish (Exposure -> A counterparty) fills the command line and closes the menu;
- on a phone (390 px) the columns fold: the first engine open, a tap on another opens it and closes the first; every row at least 44 px,
  the panel inside its 16 px gutters, no sideways scroll; F1 still opens the screen's help;
- a role that opens no engine (a BI analyst) has no Engines menu, is offered no VAR command in the type-ahead and, typing VAR BANK,
  reads "VaR is not opened for your role; ask an administrator.", on a desktop and a phone; the VaR guide still opens for it.
DRISHTI_ENGINES_SHOTS=<folder> saves screenshots. Skipped when Playwright or its Chromium is missing."""
import os
import threading
import time
from pathlib import Path

import pytest
import yaml

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_live_tabs_browser import TickingBackend, _free_port, browser  # noqa: E402,F401 - the shared browser fixture

pytestmark = pytest.mark.browser
THEMES = ("terminal", "light")
SHOTS = os.environ.get("DRISHTI_ENGINES_SHOTS")
GUTTER = 16

PANEL = r"""() => {
  const p = document.querySelector('[data-engines-panel]'), d = document.documentElement, r = p.getBoundingClientRect();
  const cols = [...p.querySelectorAll('.eng-col')].filter(c => !c.hidden && c.getBoundingClientRect().width);
  const rows = [...p.querySelectorAll('.eng-col.eng-open a.mega-item, [data-engine-head]')].filter(e => e.getBoundingClientRect().width);
  return {shown: p.classList.contains('show'), left: r.left, right: r.right, vw: d.clientWidth, sw: d.scrollWidth,
          cols: cols.map(c => c.querySelector('.eng-name').textContent.trim()),
          words: cols.map(c => (c.querySelector('.eng-word') || {}).textContent),
          tops: cols.map(c => Math.round(c.getBoundingClientRect().top)),
          open: [...p.querySelectorAll('.eng-col.eng-open')].map(c => c.dataset.engine),
          small: rows.map(e => [(e.textContent || '').trim().slice(0, 24), e.getBoundingClientRect().height]).filter(x => x[1] < 43.5)};
}"""


@pytest.fixture(scope="module")
def console():
    """A full console (the acting person is an administrator) over the stand-in server, its menus.yaml with search-over: 2."""
    import tempfile

    import uvicorn

    from core import engines, menus
    from core.app import create_app
    from core.config import load_settings

    spec = yaml.safe_load((CONSOLE / "config" / "menus.yaml").read_text(encoding="utf-8"))
    next(m for m in spec["menus"] if m.get("engines"))["search-over"] = 2
    with tempfile.TemporaryDirectory(prefix="engines-menu-") as tmp, pytest.MonkeyPatch.context() as mp:
        (Path(tmp) / "config").mkdir()
        (Path(tmp) / "config" / "menus.yaml").write_text(yaml.safe_dump(spec, allow_unicode=True), encoding="utf-8")
        mp.setattr(menus, "_console_dir", lambda request: Path(tmp))
        engines.reset()
        app = create_app(load_settings(CONSOLE / "config"))
        app.state.backend = TickingBackend()
        port = _free_port()
        server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
        threading.Thread(target=server.run, daemon=True).start()
        deadline = time.monotonic() + 15
        while not server.started and time.monotonic() < deadline:
            time.sleep(0.05)
        assert server.started, "the console did not start"
        yield f"http://127.0.0.1:{port}"
        server.should_exit = True


@pytest.fixture(scope="module")
def analyst_console():
    """A console whose acting person is a BI analyst: a role that opens no engine (the Engines menu's opens:)."""
    import uvicorn

    from core import engines
    from core.app import create_app
    from core.auth import Identity
    from core.config import load_settings

    engines.reset()
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = TickingBackend()

    async def analyst(cookie, _backend):
        return Identity(user="bea", display="Bea", desk="BI", roles=("bi-analyst",))
    app.state.auth.current = analyst
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    threading.Thread(target=server.run, daemon=True).start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the analyst's console did not start"
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True


def _context(browser, width, theme):
    phone = width < 640
    ctx = browser.new_context(viewport={"width": width, "height": 844 if phone else 900}, is_mobile=phone, has_touch=phone)
    ctx.add_init_script(f"try {{ localStorage.setItem('drishti.theme', '{theme}'); }} catch (e) {{}}")
    return ctx


def _shot(page, name):
    if SHOTS:
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(Path(SHOTS) / f"{name}.png"), full_page=False)


def _open(page, phone=False):
    btn = page.locator("[data-engines-menu] > .tbar-menu-btn")
    btn.tap() if phone else btn.click()
    page.locator("[data-engines-panel].show").wait_for(state="visible")
    page.wait_for_timeout(250)                                                   # the panel fades in


@pytest.mark.parametrize("theme", THEMES)
def test_the_engines_menu_on_a_desktop(browser, console, theme):
    ctx = _context(browser, 1280, theme)
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    try:
        page.goto(console + "/t", wait_until="load")
        labels = [t.strip() for t in page.locator(".tbar-menus .tbar-menu-l").all_text_contents()]
        assert labels[:2] == ["Views", "Engines"] and "VaR" not in labels and "Exposure" not in labels
        _open(page)
        s = page.evaluate(PANEL)
        assert s["cols"] == ["VaR", "Exposure", "FRTB-SA", "SIMM"] and s["words"] == ["VAR", "EXP", "FRSA", "SIMM"], s
        assert len(set(s["tops"])) == 1, s["tops"]                                # side by side, one row
        assert s["left"] >= 0 and s["right"] <= s["vw"] and s["sw"] <= s["vw"] + 1, s
        assert page.locator("[data-engines-admin]").is_visible()
        assert page.locator("[data-engines-q]").is_visible()                     # four engines, search-over: 2
        assert page.evaluate("document.activeElement && document.activeElement.hasAttribute('data-engines-q')")   # the box takes the focus
        _shot(page, f"01-engines-desktop-{theme}")
        assert not errors, errors
    finally:
        ctx.close()


def test_the_search_box_filters_marks_clears_and_opens_the_first(browser, console):
    ctx = _context(browser, 1280, "terminal")
    page = ctx.new_page()
    try:
        page.goto(console + "/t", wait_until="load")
        _open(page)
        page.locator("[data-engines-q]").fill("play")
        page.wait_for_timeout(150)
        s = page.evaluate(PANEL)
        assert s["cols"] == ["VaR", "FRTB-SA", "SIMM"], s                         # Exposure has no playground: folded away
        shown = [t.strip() for t in page.locator("[data-engines-panel] a.mega-item:visible .eng-l").all_text_contents()]
        assert shown and all("Playground" in t for t in shown), shown
        assert page.locator("[data-engines-panel] mark.eng-mark").count() == len(shown)
        assert page.locator("[data-engines-count]").inner_text().startswith(f"{len(shown)} of ")
        _shot(page, "02-engines-search")
        page.locator("[data-engines-q]").fill("no such page at all")
        page.wait_for_timeout(100)
        assert page.locator("[data-engines-none]").is_visible()
        page.locator("[data-engines-q]").press("Escape")
        page.wait_for_timeout(100)
        assert page.locator("[data-engines-q]").input_value() == ""
        assert page.evaluate(PANEL)["cols"] == ["VaR", "Exposure", "FRTB-SA", "SIMM"]
        page.locator("[data-engines-q]").fill("scenario matrix")
        page.locator("[data-engines-q]").press("Enter")
        page.wait_for_url("**/smat**", timeout=10000)
    finally:
        ctx.close()


def test_an_item_to_finish_fills_the_command_line_and_closes_the_menu(browser, console):
    ctx = _context(browser, 1280, "terminal")
    page = ctx.new_page()
    try:
        page.goto(console + "/t", wait_until="load")
        _open(page)
        page.locator("[data-engines-panel] a.mega-item[data-fill='EXP CPTY']").click()
        page.wait_for_timeout(200)
        assert page.input_value("#cmdInput") == "EXP CPTY "
        assert not page.locator("[data-engines-panel]").is_visible()
        assert page.url.endswith("/t")
    finally:
        ctx.close()


@pytest.mark.parametrize("theme", THEMES)
def test_the_engines_menu_folds_on_a_phone(browser, console, theme):
    ctx = _context(browser, 390, theme)
    page = ctx.new_page()
    try:
        page.goto(console + "/t", wait_until="load")
        page.wait_for_timeout(300)
        _open(page, phone=True)
        s = page.evaluate(PANEL)
        assert s["shown"] and s["open"] == ["var"], s
        assert s["left"] >= GUTTER - 1 and s["right"] <= s["vw"] - GUTTER + 1, s
        assert s["sw"] <= s["vw"] + 1, s
        assert not s["small"], s["small"]                                        # every row a finger tall
        assert page.locator("[data-engine='var'] a.mega-item").first.is_visible()
        assert not page.locator("[data-engine='exposure'] a.mega-item").first.is_visible()
        assert not page.locator("[data-engines-off]").is_visible()                # engines not running: left out on a phone
        _shot(page, f"03-engines-phone-{theme}")
        page.locator("[data-engine='exposure'] [data-engine-head]").tap()
        page.wait_for_timeout(150)
        s = page.evaluate(PANEL)
        assert s["open"] == ["exposure"], s                                      # one engine open at a time
        assert page.locator("[data-engine='exposure'] [data-engine-head]").get_attribute("aria-expanded") == "true"
        assert page.locator("[data-engines-panel]").is_visible()                 # a head opens its pages; the menu stays open
        page.locator("[data-engines-panel] a.mega-item[data-fill='EXP NSET']").tap()
        page.wait_for_timeout(200)
        assert page.input_value("#cmdInput") == "EXP NSET "
    finally:
        ctx.close()


@pytest.mark.parametrize("width", (1280, 390))
def test_a_role_that_opens_no_engine_has_no_menu_no_suggestion_and_a_refusal_in_words(browser, analyst_console, width):
    ctx = _context(browser, width, "terminal")
    page = ctx.new_page()
    try:
        page.goto(analyst_console + "/t", wait_until="load")
        labels = [t.strip() for t in page.locator(".tbar-menus .tbar-menu-l").all_text_contents()]
        assert "Engines" not in labels and page.locator("[data-engines-menu]").count() == 0
        page.locator("#cmdInput").fill("VAR B")
        page.wait_for_timeout(600)                                                # the type-ahead asks the console
        offered = [t.upper() for t in page.locator("#cmdList li").all_text_contents()]
        assert not any(t.startswith("VAR") for t in offered), offered
        page.locator("#cmdInput").fill("VAR BANK")
        page.locator("#cmdInput").press("Enter")
        page.wait_for_load_state("load")
        page.get_by_text("VaR is not opened for your role; ask an administrator.").first.wait_for(state="visible", timeout=10000)
        assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")
        _shot(page, f"04-engine-refused-{width}")
        page.goto(analyst_console + "/help/docs-guides-var-guide", wait_until="load")   # Help is documentation: open to the role
        assert page.locator(".help-article").is_visible()
    finally:
        ctx.close()


def test_f1_opens_the_screens_help_with_the_engines_menu_in_the_bar(browser, console):
    ctx = _context(browser, 1280, "terminal")
    page = ctx.new_page()
    try:
        page.goto(console + "/t", wait_until="load")
        page.locator("body").click(position={"x": 5, "y": 300})
        page.keyboard.press("F1")
        page.wait_for_url("**/help/**", timeout=10000)
        assert "/help/user-guide" in page.url
        assert page.locator("[data-engines-menu]").count() == 1                  # the same bar on the help pages
    finally:
        ctx.close()
