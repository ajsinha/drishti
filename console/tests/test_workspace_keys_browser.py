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

"""Workspace keys wherever the focus is (UX-07): Alt+1..4 moved to a pane only while the focus was in the workspace page
itself; once a pane (an embedded view, another document) had it, the keys went to the pane and did nothing, and a keyboard
user was stuck there. Panes now hand Alt+0..4 to the workspace (same-origin messages, origin and sender checked): Alt+N
moves to pane N from anywhere, Alt+0 back to the workspace's toolbar.

Runs Chromium through Playwright; skipped when Playwright or its Chromium is not installed (`pip install playwright` and
`playwright install chromium`). The console runs in-process on a free port against the stand-in server."""
import asyncio
import socket
import threading
import time

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")


class QuietBackend(FakeBackend):
    """The stand-in server, with live streams that stay open and quiet."""

    async def stream(self, kind, id_, ident=None, opened=None):
        yield "view", "{}"
        while True:
            await asyncio.sleep(3600)

    async def sse(self, path, ident=None, opened=None):
        yield "hello", "{}"
        while True:
            await asyncio.sleep(3600)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def console_url():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = QuietBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed: skip, do not fail
            pytest.skip(f"needs Playwright's Chromium (playwright install chromium): {str(e).splitlines()[0]}")
        yield b
        b.close()


# Where the workspace page's focus is: "pane N" (its iframe or something in pane N's header), "bar", or the tag.
WHERE = """() => {
  const a = document.activeElement;
  const frames = [...document.querySelectorAll('.ws-frame')];
  const i = frames.indexOf(a);
  if (i >= 0) { return 'pane ' + (i + 1); }
  const pane = a && a.closest && a.closest('.ws-pane');
  if (pane) { return 'pane ' + ([...document.querySelectorAll('.ws-grid > .ws-pane')].indexOf(pane) + 1); }
  if (a && a.closest && a.closest('.ws-bar')) { return 'bar'; }
  return a ? a.tagName : 'none';
}"""


def _focus_inside(page, n):
    """Puts the keyboard focus inside pane n's document (on its first link or button, else its body), as a click or Tab
    into the pane does; the keys then go to the pane's document, not the workspace page."""
    page.evaluate("(n) => document.querySelectorAll('.ws-frame')[n - 1].focus()", n)
    frame = [f for f in page.frames if f != page.main_frame][n - 1]
    frame.evaluate("""() => { const el = document.querySelector('main a[href], main button, a[href], button');
      if (el) { el.focus(); } }""")
    assert frame.evaluate("() => document.hasFocus()"), "the pane's document has the keyboard"


def test_alt_keys_move_between_panes_even_from_inside_a_pane(console_url, browser):
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    try:
        page.goto(console_url + "/w/Rates?template=Rates", wait_until="domcontentloaded")
        page.wait_for_selector(".ws-frame")
        page.wait_for_function("() => [...document.querySelectorAll('.ws-frame')].every(f => f.contentDocument && f.contentDocument.readyState === 'complete')")
        children = [f for f in page.frames if f != page.main_frame]
        assert len(children) == 2, "two panes: a view and a view the stand-in server does not hold (an error page)"

        page.locator(".ws-bar .tbar-link").first.focus()
        page.keyboard.press("Alt+2")
        assert page.evaluate(WHERE) == "pane 2"                     # from the workspace page itself, as before

        _focus_inside(page, 1)                                  # the focus is now INSIDE pane 1 (a view)
        assert page.evaluate(WHERE) == "pane 1"
        page.keyboard.press("Alt+2")
        page.wait_for_function("() => document.activeElement === document.querySelectorAll('.ws-frame')[1]", timeout=3000)

        _focus_inside(page, 2)                                  # inside pane 2 (an error page: no such entity)
        page.keyboard.press("Alt+1")
        page.wait_for_function("() => document.activeElement === document.querySelectorAll('.ws-frame')[0]", timeout=3000)

        _focus_inside(page, 1)                                  # and out of the panes altogether: no trap
        page.keyboard.press("Alt+0")
        page.wait_for_function("() => !!document.activeElement.closest('.ws-bar')", timeout=3000)
        assert page.evaluate(WHERE) == "bar"
    finally:
        page.close()


def test_a_message_from_elsewhere_moves_nothing(console_url, browser):
    """Only the workspace's own panes may move its focus: a message from the page itself (not a pane) is ignored."""
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    try:
        page.goto(console_url + "/w/Rates?template=Rates", wait_until="domcontentloaded")
        page.wait_for_selector(".ws-frame")
        page.locator(".ws-bar .tbar-link").first.focus()
        page.evaluate("() => window.postMessage({ type: 'drishti:key', n: 2 }, location.origin)")
        page.wait_for_timeout(300)
        assert page.evaluate(WHERE) == "bar"
    finally:
        page.close()


def test_a_blank_pane_takes_the_focus_on_its_command_input(console_url, browser):
    """A new workspace's panes are empty: Alt+N puts the focus where a command is typed."""
    page = browser.new_page(viewport={"width": 1400, "height": 900})
    try:
        page.goto(console_url + "/w/Keys?new=1", wait_until="domcontentloaded")
        page.wait_for_selector(".ws-pane")
        page.keyboard.press("Alt+2")
        assert page.evaluate("() => document.activeElement.getAttribute('aria-label')") == "Entity for pane 2"
    finally:
        page.close()
