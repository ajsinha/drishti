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

"""The one keymap and the one search in a real browser (2.9.11.f; drishti_keys.js, drishti_search-box.js), over a console of its own on the
stand-in server:
- ? opens the keys of the screen (a dialog, focus inside, the global keys listed, no conflicts registered), Esc closes it and gives the
  focus back; the footer's Keys button opens it too; ? typed into the command line is a character, not the overlay;
- Alt+K (and the top bar's search button) opens the global search, also from inside the command line; typing asks GET /api/search?in=all
  and shows the answer grouped by kind; ↓ and Enter open the selected result; Esc closes the search and gives the focus back.
The static side (conflicts, stray handlers): test_keymap.py. Skipped when Playwright or its Chromium is missing."""
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE  # noqa: E402
from test_live_tabs_browser import TickingBackend, _free_port, browser  # noqa: E402,F401 - the shared browser fixture

pytestmark = pytest.mark.browser


@pytest.fixture(scope="module")
def console():
    """A full console (the acting person is an administrator) over the stand-in server."""
    import uvicorn

    from core import engines
    from core.app import create_app
    from core.config import load_settings

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


@pytest.fixture
def page(browser, console):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900})
    p = ctx.new_page()
    errors = []
    p.on("pageerror", lambda e: errors.append(str(e)))
    p.goto(console + "/t", wait_until="domcontentloaded")          # the terminal: the command line, its keys
    p.wait_for_function("() => window.Drishti && window.Drishti.keys && window.Drishti.find && window.Drishti.keys.list().some(k => k.id === 'command.focus')")
    yield p
    ctx.close()
    assert errors == [], errors


def _overlay_open(p):
    return p.evaluate("() => window.Drishti.keys.overlay.isOpen()")


def test_question_mark_lists_the_keys_of_the_screen_and_escape_closes_it(page):
    page.locator("body").click(position={"x": 5, "y": 300})
    page.keyboard.press("?")
    page.wait_for_selector("[data-keys-overlay]:not([hidden])")
    ov = page.locator("[data-keys-overlay]")
    assert ov.get_attribute("role") == "dialog" and ov.get_attribute("aria-modal") == "true"
    ids = page.eval_on_selector_all("[data-keys-overlay] [data-key-id]", "els => els.map(e => e.dataset.keyId)")
    assert {"keys.overlay", "find.open", "command.focus", "help.context"} <= set(ids)
    assert page.locator("[data-keys-overlay] [data-keys-scope='global']").count() == 1
    assert page.evaluate("() => document.activeElement.closest('[data-keys-overlay]') !== null")
    assert page.evaluate("() => window.Drishti.keys.conflicts()") == []
    page.keyboard.press("Escape")
    page.wait_for_function("() => !window.Drishti.keys.overlay.isOpen()")
    page.keyboard.press("?")                                   # ? again, and the same key closes it
    page.wait_for_function("() => window.Drishti.keys.overlay.isOpen()")
    page.keyboard.press("?")
    page.wait_for_function("() => !window.Drishti.keys.overlay.isOpen()")


def test_the_footers_keys_button_opens_the_list(page):
    page.locator("[data-keys-open]").first.click()
    page.wait_for_function("() => window.Drishti.keys.overlay.isOpen()")
    page.locator("[data-keys-overlay] [data-keys-close]").click()
    page.wait_for_function("() => !window.Drishti.keys.overlay.isOpen()")


def test_a_question_mark_typed_in_the_command_line_is_a_character(page):
    box = page.locator("#cmdInput")
    box.click()
    page.keyboard.type("what?")
    assert box.input_value() == "what?"
    assert not _overlay_open(page)


def test_alt_k_opens_the_global_search_and_shows_the_answer_grouped(page):
    page.keyboard.press("Alt+K")
    page.wait_for_selector("[data-find]:not([hidden])")
    assert page.evaluate("() => document.activeElement.id") == "drishtiFind"
    with page.expect_response(lambda r: "/api/search?in=all" in r.url and "q=trade" in r.url) as answer:
        page.keyboard.type("trade")
    assert answer.value.status == 200
    page.wait_for_function("() => document.querySelector('[data-find] [role=listbox]').getAttribute('data-searched') === 'trade'")
    groups = page.eval_on_selector_all("[data-find] [data-search-group]", "els => els.map(e => e.dataset.searchGroup)")
    assert groups and set(groups) <= {"entity", "command", "screen", "help", "build"} and len(groups) == len(set(groups))
    assert {"screen", "help"} <= set(groups)
    assert page.locator("#drishtiFind").get_attribute("aria-expanded") == "true"
    page.keyboard.press("ArrowDown")
    active = page.locator("#drishtiFind").get_attribute("aria-activedescendant")
    assert active and page.locator(f"#{active}").get_attribute("aria-selected") == "true"
    page.keyboard.press("Escape")                              # the first Esc closes the list, the second the search
    page.wait_for_function("() => document.querySelector('#drishtiFind').getAttribute('aria-expanded') === 'false'")
    page.keyboard.press("Escape")
    page.wait_for_function("() => !window.Drishti.find.isOpen()")


def test_enter_opens_the_selected_result(page, console):
    page.locator("[data-find-open]").first.click()
    page.wait_for_function("() => window.Drishti.find.isOpen()")
    page.keyboard.type("pick lists")
    page.wait_for_function("() => document.querySelector('[data-find] [role=listbox]').getAttribute('data-searched') === 'pick lists'")
    first = page.eval_on_selector("[data-find] [role=option][data-i='0']", "e => e.dataset.group")
    assert first == "screen"
    page.keyboard.press("ArrowDown")
    with page.expect_navigation():
        page.keyboard.press("Enter")
    assert page.url == console + "/s"


def test_alt_k_works_from_inside_the_command_line_and_esc_gives_the_focus_back(page):
    page.locator("#cmdInput").click()
    page.keyboard.press("Alt+K")
    page.wait_for_function("() => window.Drishti.find.isOpen() && document.activeElement.id === 'drishtiFind'")
    page.keyboard.press("Escape")
    page.wait_for_function("() => !window.Drishti.find.isOpen() && document.activeElement.id === 'cmdInput'")
