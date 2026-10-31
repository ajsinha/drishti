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

"""Drishti*net end to end in a real browser (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, sections 11.1 and 17): three built servers and a console given server a alone
(tests/net_stack.py). The console learns b and c; a trade only c holds opens through a with the same page c would give; Explain on Admin → Net
names the route the request actually took; draining the node that answers a shared trade moves that traffic to the other holder; with a
stopped, the console moves to another server within 10 s, the open page reloads its data, its live view carries on and the next search works,
with no new sign-in. Needs the built server jar and Playwright's Chromium (skipped otherwise); one cluster at a time (a lock)."""
import json
import time
import urllib.request

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from net_stack import CONSOLE_URL, HOST, NO_HOST, NetStack, get, url  # noqa: E402
from test_live_tabs_browser import COUNT_TICKS  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


@pytest.fixture(scope="module")
def cluster(tmp_path_factory):
    if not HOST:
        pytest.skip(NO_HOST)
    try:
        stack = NetStack(tmp_path_factory.mktemp("net-stack")).start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    yield stack
    stack.close()


def traffic(name: str) -> dict:
    with urllib.request.urlopen(url(name) + "/api/v1/net/traffic", timeout=5) as r:
        return json.loads(r.read())


def test_the_console_learns_the_cluster_from_one_server(browser, cluster):
    page = browser.new_page()
    deadline = time.time() + 30
    while time.time() < deadline:
        page.goto(CONSOLE_URL + "/admin/net")
        if all(url(n) in page.locator("[data-console-servers]").inner_text() for n in ("a", "b", "c")):
            break
        time.sleep(1)
    text = page.locator("[data-console-servers]").inner_text()
    assert all(url(n) in text for n in ("a", "b", "c")), text
    assert "discovered" in text and "configured" in text
    page.close()


def test_a_trade_only_c_holds_opens_through_a_and_explain_names_that_route(browser, cluster):
    page = browser.new_page()
    page.goto(CONSOLE_URL + "/v/trade/MX-00003")
    assert "MX-00003" in page.content()
    assert "DRS-" not in page.locator("main").inner_text()
    status, direct = get(url("c"), "/api/v1/views/trade/MX-00003")
    assert status == 200
    assert any(c["peer"] == "drishti-c" and c["kind"] == "trade" and c["what"] == "data" for c in traffic("a")["recent"])
    page.goto(CONSOLE_URL + "/admin/net?tab=routing")
    page.locator("[data-ex-kind]").fill("trade")
    page.locator("[data-ex-id]").fill("MX-00003")
    page.locator("[data-explain-form] button[type=submit]").click()
    page.locator("[data-explained]").wait_for(state="visible")
    assert page.locator("[data-ex-route]").inner_text() == "answered here"
    assert "drishti-c" in page.locator("[data-ex-data]").inner_text()
    page.close()


def test_a_drain_moves_the_traffic_to_the_other_holder(browser, cluster):
    def holder_of_shared() -> str:
        get(url("b"), "/api/v1/views/trade/SHARED-1")
        last = [c for c in traffic("b")["recent"] if c["what"] == "data" and c["detail"] == "SHARED-1" and c["outcome"] == "ok"]
        return last[0]["peer"]
    first = holder_of_shared()
    assert first in ("drishti-a", "drishti-c")
    drain = urllib.request.Request(url(first[-1]) + "/api/v1/net/drain", method="POST")
    urllib.request.urlopen(drain, timeout=5).read()
    time.sleep(3)                                              # the drain spreads with gossip
    try:
        assert holder_of_shared() != first
    finally:
        urllib.request.urlopen(urllib.request.Request(url(first[-1]) + "/api/v1/net/undrain", method="POST"), timeout=5).read()


def test_stopping_a_moves_the_console_to_another_server_and_the_page_live_view_and_search_carry_on(browser, cluster):
    page = browser.new_page()
    page.goto(CONSOLE_URL + "/v/trade/IRS-48213")                # held on b, which ticks; the console is on a
    page.wait_for_timeout(1500)
    before = page.evaluate(COUNT_TICKS)
    deadline = time.time() + 20
    while page.evaluate(COUNT_TICKS) <= before and time.time() < deadline:
        page.wait_for_timeout(250)
    assert page.evaluate(COUNT_TICKS) > before, "the live view did not tick before the stop"
    t0 = time.time()
    stopping = cluster.stop("a", wait=False)                    # timed from the stop, not from the process's exit (see NetStack.stop)
    moved, status, body = None, 0, ""
    time.sleep(0.5)                                             # the stop signal reaches a; an answer a gave before it does not count
    while time.time() - t0 < 30:
        try:
            status, body = get(CONSOLE_URL, "/v/trade/CLY-00001", timeout=3)
        except OSError as e:                                     # a read timed out while the console was still on a
            status, body = 0, repr(e)
        if status == 200 and "CLY-00001" in body:
            moved = time.time() - t0
            break
        time.sleep(0.2)
    at_move = page.evaluate(COUNT_TICKS)
    deadline = time.time() + 45                                 # the old stream breaks when a's graceful shutdown ends (30 s at most); it reconnects
    while page.evaluate(COUNT_TICKS) <= at_move + 2 and time.time() < deadline:
        page.wait_for_timeout(250)
    ticking = page.evaluate(COUNT_TICKS) > at_move + 2
    cluster.reap(stopping)
    after_exit = page.evaluate(COUNT_TICKS)
    deadline = time.time() + 20
    while page.evaluate(COUNT_TICKS) <= after_exit + 2 and time.time() < deadline:
        page.wait_for_timeout(250)
    assert ticking and page.evaluate(COUNT_TICKS) > after_exit + 2, "the open page's live view stopped ticking after the move"
    assert moved is not None and moved < 10, f"the console took {moved} s to move; last answer {status}: {body[-600:]}"
    print(f"net: the console moved off a stopped server in {moved:.1f} s; the open page kept ticking without a reload")
    page.reload()
    assert "IRS-48213" in page.content()
    status, body = get(CONSOLE_URL, "/s?q=" + urllib.request.quote("TRD where BOOKING_SYSTEM = 'CALYPSO' limit 200"))
    assert status == 200 and "CLY-00001" in body and "MX-00003" not in body, body[-600:]           # the next search answers, from b's data
    snap = json.loads(get(CONSOLE_URL, "/admin/net/api/members")[1]).get("console", {})
    assert snap.get("active") != url("a")
    page.close()
