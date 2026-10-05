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

"""Many tabs on live views in one browser (UX-01): browsers open at most six connections to a site over HTTP/1.1, and a
live connection per tab used them all up, so a seventh page never loaded. The browser now holds ONE live connection to
the console however many tabs and workspace panes are open (live-hub.js), so the next page loads at once and every tab
and pane keeps ticking.

Runs Chromium through Playwright; skipped when Playwright or its Chromium is not installed (`pip install playwright` and
`playwright install chromium`). By default it starts the console in-process on a free port, against a stand-in server
whose views tick every 200 ms. To run it against a real console and server instead (the QA reproduction), set
DRISHTI_LIVE_E2E_URL=http://127.0.0.1:17974 and DRISHTI_LIVE_E2E_VIEWS=trade/MX-20000001,trade/MX-20000002,... (live
entities of that server)."""
import asyncio
import json
import os
import socket
import threading
import time

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

TABS = 8                          # more than the six connections a browser allows a site over HTTP/1.1
PANES = 4                         # a two-by-two workspace of live panes
SETTLE_S = 20.0                   # how long a live condition (an election, a first frame) may take on a loaded machine
PAGE_LOAD_S = 5.0                 # "promptly": a page load never waits for a live connection


class TickingBackend(FakeBackend):
    """The stand-in server, with views that tick: every frame changes the first strip cell."""

    async def stream(self, kind, id_, ident=None, opened=None):
        yield "view", json.dumps({"provenance": {"generation": 1}})
        n = 0
        while True:
            await asyncio.sleep(0.2)
            n += 1
            yield "frame", json.dumps({"seq": n, "generation": n + 1, "p99Ms": 1.0, "latencyMs": 0.5,
                                       "patches": [{"op": "strip", "index": 0, "cell": {"label": "Tick", "text": f"{id_} #{n}"}}]})

    async def sse(self, path, ident=None, opened=None):
        yield "hello", "{}"
        while True:                                   # the alerts bell's stream: open and quiet
            await asyncio.sleep(3600)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def console_url():
    """The console under test: a real one when DRISHTI_LIVE_E2E_URL is set, else one started here on a free port."""
    url = os.environ.get("DRISHTI_LIVE_E2E_URL")
    if url:
        yield url.rstrip("/")
        return
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = TickingBackend()
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


def _views() -> list[str]:
    listed = [v.strip() for v in os.environ.get("DRISHTI_LIVE_E2E_VIEWS", "").split(",") if v.strip()]
    return listed or ["trade/IRS-48213", "trade/FXS-20931", "netting-set/NS-NORTH-01"]


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed: skip, do not fail
            pytest.skip(f"needs Playwright's Chromium (playwright install chromium): {str(e).splitlines()[0]}")
        yield b
        b.close()


# Counts changes to a view's strip (each frame repaints a strip cell) in a page or a pane.
COUNT_TICKS = """() => {
  if (window.__ticks === undefined) {
    window.__ticks = 0;
    const strip = document.querySelector('.strip');
    if (strip) { new MutationObserver(() => { window.__ticks++; }).observe(strip, { subtree: true, childList: true, characterData: true }); }
  }
  return window.__ticks;
}"""


def _ticks(target) -> int:
    return target.evaluate(COUNT_TICKS)


def _goto(page, url: str) -> float:
    started = time.monotonic()
    try:
        page.goto(url, wait_until="domcontentloaded", timeout=PAGE_LOAD_S * 1000)
    except sync_api.TimeoutError:
        pytest.fail(f"{url} did not load within {PAGE_LOAD_S:.0f} s: the browser's connections to the console are used up")
    return time.monotonic() - started


def _open_channels(console_url) -> set | None:
    """The console's open channels, when it runs in this process (None against a real console)."""
    if os.environ.get("DRISHTI_LIVE_E2E_URL"):
        return None
    from routes import api_routes
    return set(api_routes.CHANNELS)


def _all_tick(targets, waiter) -> list[str]:
    """The tabs and panes that received no live update within a few seconds."""
    before = {name: _ticks(t) for name, t in targets}
    deadline = time.monotonic() + SETTLE_S
    stale = list(before)
    while stale and time.monotonic() < deadline:
        waiter.wait_for_timeout(250)
        stale = [name for name, t in targets if _ticks(t) <= before[name]]
    return stale


def _eventually(check, waiter, what: str):
    """Polls ``check()`` until it returns a truthy value (returned), for up to SETTLE_S: waits on the condition, never on a guess."""
    deadline = time.monotonic() + SETTLE_S
    got = check()
    while not got and time.monotonic() < deadline:
        waiter.wait_for_timeout(100)
        got = check()
    assert got, f"timed out after {SETTLE_S:.0f} s waiting for {what}"
    return got


# Chromium has a SharedWorker; without one (Chrome on Android, say) the tabs elect a leader that holds the connection.
NO_SHARED_WORKER = "Object.defineProperty(window, 'SharedWorker', {value: undefined, configurable: true});"


@pytest.mark.parametrize("hub", ["shared-worker", "leader"])
def test_many_live_tabs_and_a_live_workspace_leave_the_browser_free_and_all_keep_ticking(browser, console_url, hub):
    views = _views()
    channels_before = _open_channels(console_url)
    ctx = browser.new_context(viewport={"width": 1100, "height": 700})
    if hub == "leader":
        ctx.add_init_script(NO_SHARED_WORKER)
    try:
        tabs = []
        for i in range(TABS):
            page = ctx.new_page()
            _goto(page, f"{console_url}/v/{views[i % len(views)]}")
            tabs.append(page)

        # a workspace of four live panes, saved through the console as a person would
        ws = {"layout": "2x2", "panes": [{"ref": dict(zip(("kind", "id"), views[i % len(views)].split("/", 1))), "follows": None, "title": ""}
                                         for i in range(PANES)]}
        saved = tabs[0].evaluate("""ws => fetch('/w/api/live-tabs-test', {method: 'POST', headers: {'Content-Type': 'application/json'},
                                     body: JSON.stringify(ws)}).then(r => r.status)""", ws)
        assert saved == 200
        desk = ctx.new_page()
        _goto(desk, f"{console_url}/w/live-tabs-test")
        deadline = time.monotonic() + PAGE_LOAD_S
        while time.monotonic() < deadline and len([f for f in desk.frames if "/v/" in f.url]) < PANES:
            desk.wait_for_timeout(100)
        panes = [f for f in desk.frames if "/v/" in f.url]
        assert len(panes) == PANES
        for pane in panes:
            pane.wait_for_load_state("domcontentloaded", timeout=PAGE_LOAD_S * 1000)

        # the next page loads promptly
        extra = ctx.new_page()
        took = _goto(extra, f"{console_url}/t")
        assert took < PAGE_LOAD_S

        # and every tab and every pane still receives updates
        targets = [("tab %d" % (i + 1), t) for i, t in enumerate(tabs)] + [("pane %d" % (i + 1), f) for i, f in enumerate(panes)]
        stale = _all_tick(targets, extra)
        assert stale == [], f"no live updates reached: {stale}"

        # over one live connection for the whole browser
        every = tabs + [desk] + panes
        want = {"shared-worker"} if hub == "shared-worker" else {"leader", "follower"}
        kinds = _eventually(lambda: (k := {t.evaluate("() => window.DrishtiChannel.transport()") for t in every}) == want and k, extra,
                            f"the transports to settle as {sorted(want)}")
        assert kinds == want
        if channels_before is not None:
            _eventually(lambda: len(_open_channels(console_url) - channels_before) == 1, extra, "exactly one new live connection")

        if hub == "leader":
            # the tab holding the connection closes: another takes over, and the others keep ticking
            leader = next(t for t in tabs if t.evaluate("() => window.DrishtiChannel.transport()") == "leader")
            tabs.remove(leader)
            leader.close()
            targets = [(n, t) for n, t in targets if t is not leader]
            stale = _all_tick(targets, extra)
            assert stale == [], f"no live updates after the leader closed: {stale}"
            _eventually(lambda: "leader" in {t.evaluate("() => window.DrishtiChannel.transport()") for t in tabs + [desk, extra]}, extra,
                        "another tab to take over as leader")
        desk.evaluate("() => fetch('/w/api/live-tabs-test/delete', {method: 'POST'})")
    finally:
        ctx.close()
